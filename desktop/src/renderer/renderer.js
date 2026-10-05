'use strict';

/* globals window, document, VideoDecoder, AudioDecoder, EncodedVideoChunk, EncodedAudioChunk */

const api = window.bobo;

const qrImage = document.getElementById('qr');
const addressSelect = document.getElementById('address');
const connectionLabel = document.getElementById('conn');
const deviceLabel = document.getElementById('device');
const videoLabel = document.getElementById('video');
const audioLabel = document.getElementById('audioInfo');
const rttLabel = document.getElementById('rtt');
const canvas = document.getElementById('screen');
const placeholder = document.getElementById('placeholder');
const audioButton = document.getElementById('btn-audio');

// 不要 desynchronized：它走的是低延迟呈现路径，硬件解码出来的 VideoFrame
// 偶尔会以「只画了一半」的样子上屏，看起来就是桌面预览里花一块。代价只有一帧。
const ctx = canvas.getContext('2d', { alpha: false });

let decoder = null;
let configuredCodec = null;
let configuredDescription = null;
let awaitingKeyframe = false;
let awaitingKeyframeSince = 0;
let lastKeyRequestAt = 0;
let receivedFirstFrame = false;
let decodedFrames = 0;

/** 丢掉帧之后，最多这么频繁地请求手机端补一个 I 帧。 */
const KEYFRAME_REQUEST_MIN_INTERVAL_MS = 300;
/** 等补帧最多等这么久：超时就先恢复解码，宁可花一下也别让画面卡住。 */
const KEYFRAME_WAIT_TIMEOUT_MS = 400;
/** 解码队列积压超过这么多帧，说明已经追不上了。 */
const MAX_DECODE_QUEUE_SIZE = 8;

let audioDecoder = null;
let audioContext = null;
let nextPlayTime = 0;
// 默认开启声音，手机一连上就能听到，不需要再手动点一下。
let audioEnabled = true;

let renderFps = 0;
let netKbps = 0;

const FREQ_TABLE = [
  96000, 88200, 64000, 48000, 44100, 32000, 24000,
  22050, 16000, 12000, 11025, 8000, 7350,
];

/* ------------------------------ 视频解码 ------------------------------ */

function onDecodedFrame(frame) {
  const width = frame.displayWidth;
  const height = frame.displayHeight;

  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }

  ctx.drawImage(frame, 0, 0, width, height);
  frame.close();

  decodedFrames += 1;
  if (!receivedFirstFrame) {
    receivedFirstFrame = true;
    placeholder.classList.add('hidden');
  }
}

function sameBytes(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i += 1) {
    if (a[i] !== b[i]) return false;
  }
  return true;
}

/** 画面已经不可信了：让手机端补一个 I 帧（合并请求，别刷爆手机）。 */
function requestKeyframe() {
  const now = performance.now();
  if (now - lastKeyRequestAt < KEYFRAME_REQUEST_MIN_INTERVAL_MS) return;
  lastKeyRequestAt = now;
  api.requestKeyframe();
}

/** 进入「等 I 帧」状态（已经在等的话保持原来的计时，不要一直被重置）。 */
function requireKeyframe() {
  if (!awaitingKeyframe) {
    awaitingKeyframe = true;
    awaitingKeyframeSince = performance.now();
  }
  requestKeyframe();
}

function closeDecoder() {
  if (!decoder) return;
  try {
    decoder.close();
  } catch (_) {
    /* 忽略 */
  }
  decoder = null;
  configuredCodec = null;
  configuredDescription = null;
}

function ensureDecoder(frame) {
  // 没有 SPS/PPS 就没法配置解码器，等第一个关键帧
  if (!frame.description) return false;

  const codec = frame.codec || 'avc1.42E01E';
  const description = new Uint8Array(frame.description);

  if (decoder && configuredCodec === codec && sameBytes(configuredDescription, description)) {
    return true;
  }

  // 需要（重）建解码器时只有关键帧可以：SPS/PPS 变了（比如手机旋转、重开会话）
  // 或者解码器刚报过错，拿 delta 帧去解只会得到花屏。
  if (!frame.keyframe) return false;

  closeDecoder();

  decoder = new VideoDecoder({
    output: onDecodedFrame,
    error: (err) => {
      console.error('[video] 解码器错误:', err);
      closeDecoder();
      requireKeyframe();
    },
  });

  decoder.configure({
    codec,
    description,
    optimizeForLatency: true,
    hardwareAcceleration: 'prefer-hardware',
  });

  configuredCodec = codec;
  configuredDescription = description;
  return true;
}

function handleVideoFrame(frame) {
  if (typeof VideoDecoder === 'undefined') return;

  // 上游丢过帧，参考帧已经对不上：停在当前画面上，等下一个 I 帧再继续。
  if (frame.gap) requireKeyframe();

  if (awaitingKeyframe && !frame.keyframe) {
    // 补帧请求迟迟没人理会（比如手机端不响应），就先恢复解码：
    // 宁可短暂花一下，也不要让画面一直卡在那一帧不动。
    if (performance.now() - awaitingKeyframeSince < KEYFRAME_WAIT_TIMEOUT_MS) {
      requestKeyframe();
      return;
    }
    awaitingKeyframe = false;
  }

  if (!ensureDecoder(frame)) {
    requireKeyframe();
    return;
  }

  awaitingKeyframe = false;

  // 解码队列积压说明追不上了：跳到下一个 I 帧，别带着坏参考继续解。
  if (decoder.decodeQueueSize > MAX_DECODE_QUEUE_SIZE && !frame.keyframe) {
    requireKeyframe();
    return;
  }

  try {
    decoder.decode(
      new EncodedVideoChunk({
        type: frame.keyframe ? 'key' : 'delta',
        timestamp: frame.tsMs * 1000,
        data: new Uint8Array(frame.sample),
      })
    );
  } catch (err) {
    console.error('[video] 送解码失败:', err);
  }
}

/* ------------------------------ 音频解码 ------------------------------ */

/** 解析 ADTS 头，取出采样率、声道和 AudioSpecificConfig。 */
function parseAdts(buf) {
  if (!buf || buf.length < 7) return null;
  if (buf[0] !== 0xff || (buf[1] & 0xf0) !== 0xf0) return null;

  const profile = (buf[2] >> 6) & 0x03;
  const freqIndex = (buf[2] >> 2) & 0x0f;
  const channels = ((buf[2] & 0x01) << 2) | ((buf[3] >> 6) & 0x03);
  const headerLength = (buf[1] & 0x01) ? 7 : 9;

  const objectType = profile + 1;
  const asc = new Uint8Array(2);
  asc[0] = (objectType << 3) | (freqIndex >> 1);
  asc[1] = ((freqIndex & 0x01) << 7) | ((channels || 2) << 3);

  return {
    sampleRate: FREQ_TABLE[freqIndex] || 48000,
    channels: channels || 2,
    asc,
    raw: buf.subarray(headerLength),
  };
}

function playAudioData(audioData) {
  if (!audioEnabled) {
    audioData.close();
    return;
  }
  if (!audioContext) {
    ensureAudioContext();
  }
  if (!audioContext) {
    audioData.close();
    return;
  }
  if (audioContext.state === 'suspended') {
    audioContext.resume().catch(() => {});
  }

  const frames = audioData.numberOfFrames;
  const channels = audioData.numberOfChannels;
  const sampleRate = audioData.sampleRate;

  const buffer = audioContext.createBuffer(channels, frames, sampleRate);
  for (let ch = 0; ch < channels; ch += 1) {
    const plane = new Float32Array(frames);
    audioData.copyTo(plane, { planeIndex: ch, format: 'f32-planar' });
    buffer.copyToChannel(plane, ch);
  }

  const source = audioContext.createBufferSource();
  source.buffer = buffer;
  source.connect(audioContext.destination);

  const now = audioContext.currentTime;
  if (nextPlayTime < now + 0.03) {
    // 落后了就重新对齐，宁可多一点点延迟也不要断续
    nextPlayTime = now + 0.06;
  }
  source.start(nextPlayTime);
  nextPlayTime += frames / sampleRate;

  audioData.close();
}

/** 创建并唤醒音频输出（Chromium 在没有用户手势时会把 AudioContext 挂起）。 */
function ensureAudioContext() {
  if (!audioContext) {
    try {
      audioContext = new AudioContext({ latencyHint: 'interactive' });
    } catch (err) {
      console.error('[audio] 无法创建音频输出:', err);
      return null;
    }
  }
  if (audioContext.state === 'suspended') {
    audioContext.resume().catch(() => {});
  }
  return audioContext;
}

function handleAudioFrame(frame) {
  if (!audioEnabled) return;
  if (typeof AudioDecoder === 'undefined') return;

  const info = parseAdts(frame.data);
  if (!info) return;

  if (!audioDecoder) {
    ensureAudioContext();
    audioDecoder = new AudioDecoder({
      output: playAudioData,
      error: (err) => {
        console.error('[audio] 解码器错误:', err);
        audioDecoder = null;
      },
    });
    audioDecoder.configure({
      codec: 'mp4a.40.2',
      sampleRate: info.sampleRate,
      numberOfChannels: info.channels,
      description: info.asc,
    });
  }

  try {
    audioDecoder.decode(
      new EncodedAudioChunk({
        type: 'key',
        timestamp: frame.tsMs * 1000,
        data: new Uint8Array(info.raw),
      })
    );
  } catch (err) {
    console.error('[audio] 送解码失败:', err);
  }
}

/* ------------------------------ 反向控制 ------------------------------ */

let dragStart = null;

function toNormalized(event) {
  const rect = canvas.getBoundingClientRect();
  const x = (event.clientX - rect.left) / rect.width;
  const y = (event.clientY - rect.top) / rect.height;
  return { x: Math.min(Math.max(x, 0), 1), y: Math.min(Math.max(y, 0), 1) };
}

canvas.addEventListener('mousedown', (event) => {
  if (event.button !== 0) return;
  dragStart = toNormalized(event);
});

canvas.addEventListener('mouseup', (event) => {
  if (event.button !== 0 || !dragStart) return;
  const end = toNormalized(event);
  const distance = Math.hypot(end.x - dragStart.x, end.y - dragStart.y);

  if (distance < 0.01) {
    api.sendInput({ t: 'touch', action: 'tap', x: end.x, y: end.y });
  } else {
    api.sendInput({
      t: 'touch',
      action: 'swipe',
      x: dragStart.x,
      y: dragStart.y,
      x2: end.x,
      y2: end.y,
      duration: 180,
    });
  }

  dragStart = null;
});

canvas.addEventListener('contextmenu', (event) => event.preventDefault());

window.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    api.sendInput({ t: 'key', code: 'back' });
  } else if (event.key === 'Home') {
    api.sendInput({ t: 'key', code: 'home' });
  }
});

document.getElementById('btn-back').addEventListener('click', () => {
  api.sendInput({ t: 'key', code: 'back' });
});
document.getElementById('btn-home').addEventListener('click', () => {
  api.sendInput({ t: 'key', code: 'home' });
});
document.getElementById('btn-recents').addEventListener('click', () => {
  api.sendInput({ t: 'key', code: 'recents' });
});

function refreshVideoLabel() {
  if (!receivedFirstFrame) {
    videoLabel.textContent = '—';
    return;
  }
  videoLabel.textContent = `${renderFps} fps · ${netKbps} kbps`;
}

/* ------------------------------ 状态同步 ------------------------------ */

function setAudioEnabled(on) {
  audioEnabled = on;

  if (on) {
    ensureAudioContext();
    nextPlayTime = 0;
    audioButton.textContent = '关闭声音';
    audioButton.classList.remove('primary');
  } else {
    if (audioContext && audioContext.state === 'running') {
      audioContext.suspend().catch(() => {});
    }
    audioButton.textContent = '开启声音';
    audioButton.classList.add('primary');
  }

  audioLabel.textContent = on ? '已开启' : '已关闭';
}

audioButton.addEventListener('click', () => setAudioEnabled(!audioEnabled));

// 初始就打开声音，避免「投屏有画面但没声音」。
setAudioEnabled(true);

addressSelect.addEventListener('change', () => {
  api.selectAddress(addressSelect.value);
});

api.onVideoFrame(handleVideoFrame);
api.onAudioFrame(handleAudioFrame);

api.onState((state) => {
  if (state.qrDataUrl && qrImage.src !== state.qrDataUrl) {
    qrImage.src = state.qrDataUrl;
  }

  addressSelect.innerHTML = '';
  for (const item of state.lanAddresses || []) {
    const option = document.createElement('option');
    option.value = item.address;
    option.textContent = `${item.address}  (${item.iface}${item.virtual ? ' · 虚拟网卡' : ''})`;
    option.selected = item.address === state.selectedAddress;
    addressSelect.appendChild(option);
  }

  connectionLabel.textContent = state.connected ? '已连接' : '等待手机连接';
  connectionLabel.style.color = state.connected ? '#35d07f' : '';

  deviceLabel.textContent = state.device
    ? `${state.device.model} · Android ${state.device.android}`
    : '—';
  deviceLabel.title = state.device
    ? `反向控制:${state.device.touch ? '支持' : '未开启'} / 音频:${state.device.audio ? '支持' : '不支持'}`
    : '';

  netKbps = state.kbps || 0;
  refreshVideoLabel();
  rttLabel.textContent = state.rttMs ? `${state.rttMs} ms` : '—';

  if (!state.device) {
    audioLabel.textContent = audioEnabled ? '已开启' : '已关闭';
  } else if (!state.device.audio) {
    audioLabel.textContent = '手机未送来声音';
  } else {
    audioLabel.textContent = audioEnabled ? '已开启' : '待开启';
  }
});

setInterval(() => {
  renderFps = decodedFrames;
  decodedFrames = 0;
  refreshVideoLabel();
}, 1000);
