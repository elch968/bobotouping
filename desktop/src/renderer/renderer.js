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

const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });

let decoder = null;
let configuredCodec = null;
let receivedFirstFrame = false;
let decodedFrames = 0;

let audioDecoder = null;
let audioContext = null;
let nextPlayTime = 0;
let audioEnabled = false;

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

function ensureDecoder(frame) {
  // 没有 SPS/PPS 就没法配置解码器，等第一个关键帧
  if (!frame.description) return false;

  const codec = frame.codec || 'avc1.42E01E';
  if (decoder && configuredCodec === codec) return true;

  if (decoder) {
    try {
      decoder.close();
    } catch (_) {
      /* 忽略 */
    }
  }

  decoder = new VideoDecoder({
    output: onDecodedFrame,
    error: (err) => {
      console.error('[video] 解码器错误:', err);
      decoder = null;
      configuredCodec = null;
    },
  });

  decoder.configure({
    codec,
    description: new Uint8Array(frame.description),
    optimizeForLatency: true,
    hardwareAcceleration: 'prefer-hardware',
  });

  configuredCodec = codec;
  return true;
}

function handleVideoFrame(frame) {
  if (typeof VideoDecoder === 'undefined') return;
  if (!ensureDecoder(frame)) return;

  // 解码队列积压时丢掉非关键帧，避免延迟越滚越大
  if (decoder.decodeQueueSize > 8 && !frame.keyframe) return;

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
  if (!audioContext) {
    audioData.close();
    return;
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

function handleAudioFrame(frame) {
  if (!audioEnabled) return;
  if (typeof AudioDecoder === 'undefined') return;

  const info = parseAdts(frame.data);
  if (!info) return;

  if (!audioDecoder) {
    audioContext = audioContext || new AudioContext({ latencyHint: 'interactive' });
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

audioButton.addEventListener('click', () => {
  audioEnabled = !audioEnabled;

  if (audioEnabled) {
    audioContext = audioContext || new AudioContext({ latencyHint: 'interactive' });
    audioContext.resume();
    nextPlayTime = 0;
    audioButton.textContent = '关闭声音';
    audioButton.classList.remove('primary');
  } else {
    if (audioContext) audioContext.suspend();
    audioButton.textContent = '开启声音';
    audioButton.classList.add('primary');
  }

  audioLabel.textContent = audioEnabled ? '已开启' : '已关闭';
});

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

  if (!audioEnabled) {
    audioLabel.textContent = state.device && state.device.audio ? '待开启' : '—';
  }
});

setInterval(() => {
  renderFps = decodedFrames;
  decodedFrames = 0;
  refreshVideoLabel();
}, 1000);
