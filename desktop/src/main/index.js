'use strict';

const os = require('os');
const path = require('path');
const crypto = require('crypto');

const { app, BrowserWindow, ipcMain } = require('electron');
const QRCode = require('qrcode');

// 允许渲染进程在没有用户点击的情况下直接出声（否则 AudioContext 会一直是 suspended，
// 手机端声音送过来也是静音）。
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

const { PORTS, VERSION } = require('./protocol');
const { listLanAddresses, pickLanAddress } = require('./lan');
const { DiscoveryAdvertiser } = require('./discovery');
const { ControlServer } = require('./control-server');
const { VideoReceiver } = require('./video-receiver');
const { AudioReceiver } = require('./audio-receiver');
const { AvccConverter } = require('./annexb');

const KEYFRAME_REQUEST_MIN_INTERVAL_MS = 200;
/**
 * 手机端上报的真实屏幕宽高比与投屏画面宽高比，允许的偏差。
 * 画面尺寸是「按长边缩放 + 取偶数」算出来的，本身有零点几个百分点的误差，
 * 所以给 1% 的余量，避免正常情况误报。
 */
const ASPECT_TOLERANCE = 0.01;

/** 投屏中途旋转屏幕时画面会被系统缩进原画框（出现黑边），点击坐标不再线性对应。 */
const ORIENTATION_NOTICE =
  '手机屏幕方向/尺寸已变化：投屏画面已出现黑边，点击位置会整体偏移。' +
  '请在手机上停止投屏后重新开始。';

let mainWindow = null;
let controlServer = null;
let videoReceiver = null;
let audioReceiver = null;
let advertiser = null;
let statsTimer = null;

const converter = new AvccConverter();

const state = {
  lanAddresses: [],
  selectedAddress: null,
  token: '',
  qrDataUrl: '',
  connected: false,
  device: null,
  rttMs: 0,
  notice: '',
  inputNotice: '',
  orientationChanged: false,
  lastKeyframeRequestAt: 0,
  counters: { frames: 0, bytes: 0, dropped: 0 },
  rates: { fps: 0, kbps: 0 },
};

/**
 * 界面上只留一条提示条，三种来源按优先级拼出来：服务异常 > 反向控制异常 > 屏幕方向异常。
 */
function currentNotice() {
  if (state.notice) return state.notice;
  if (state.inputNotice) return state.inputNotice;
  if (state.orientationChanged) return ORIENTATION_NOTICE;
  return '';
}

/**
 * 手机端上报的真实屏幕尺寸，和投屏画面的宽高比是否还一致。
 *
 * 一致就说明「电脑上看到的画面」和「手机上那块屏幕」还是同一个矩形，
 * 归一化坐标可以线性换算；不一致就说明手机中途转过屏（或折叠屏展开过），
 * 而 VirtualDisplay 的尺寸还是老样子，画面被缩进了原画框里 —— 此时点哪里都偏。
 */
function screenMatchesVideo(info) {
  if (!state.device) return true;
  const videoW = Number(state.device.videoW);
  const videoH = Number(state.device.videoH);
  const w = Number(info.w);
  const h = Number(info.h);
  if (!(videoW > 0 && videoH > 0 && w > 0 && h > 0)) return true;
  const videoAspect = videoW / videoH;
  const screenAspect = w / h;
  const larger = Math.max(videoAspect, screenAspect);
  return Math.abs(videoAspect - screenAspect) / larger <= ASPECT_TOLERANCE;
}

// 同时开两个实例会抢同一批端口（8765/8766/8768），第二个实例的二维码永远连不上，
// 表现就是「扫码后没反应」。这里直接保证只有一个实例在跑。
let hasSingleInstanceLock = true;
if (!app.requestSingleInstanceLock()) {
  hasSingleInstanceLock = false;
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

function send(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload);
  }
}

function pushState() {
  send('state', {
    version: app.getVersion(),
    lanAddresses: state.lanAddresses,
    selectedAddress: state.selectedAddress,
    qrDataUrl: state.qrDataUrl,
    connected: state.connected,
    device: state.device,
    rttMs: state.rttMs,
    notice: currentNotice(),
    fps: state.rates.fps,
    kbps: state.rates.kbps,
    dropped: state.counters.dropped,
  });
}

/**
 * 每秒一次的心跳只推「会变的那几个数」。
 *
 * 之前每秒都推完整 state（含二维码 base64，十几 KB），渲染端还会顺手把
 * 「本机地址」下拉框整个重建一次 —— 用户正在选网卡时会被重置，纯属浪费。
 */
function pushTelemetry() {
  send('state', {
    connected: state.connected,
    device: state.device,
    rttMs: state.rttMs,
    // 提示条必须跟着心跳一起推：渲染端是按「这次推来的 notice 有没有值」来决定
    // 显示/隐藏的，心跳里不带它的话，任何提示条最多活 1 秒就被清掉。
    notice: currentNotice(),
    fps: state.rates.fps,
    kbps: state.rates.kbps,
    dropped: state.counters.dropped,
  });
}

function setNotice(text) {
  state.notice = text || '';
  pushState();
}

/** 反向控制的提示单独存一份，免得被「控制端口占用」之类的提示盖掉后互相冲掉。 */
function setInputNotice(text) {
  const next = text || '';
  if (state.inputNotice === next) return;
  state.inputNotice = next;
  pushState();
}

function buildQrPayload() {
  return JSON.stringify({
    v: VERSION,
    ip: state.selectedAddress,
    ctrl: PORTS.control,
    video: PORTS.video,
    audio: PORTS.audio,
    name: os.hostname(),
    token: state.token,
  });
}

async function refreshQrCode() {
  try {
    state.qrDataUrl = await QRCode.toDataURL(buildQrPayload(), {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 320,
      color: { dark: '#0b1220', light: '#ffffff' },
    });
  } catch (err) {
    console.error('[qr] 生成失败:', err.message);
    state.qrDataUrl = '';
  }
}

function requestKeyframe() {
  if (!controlServer) return;
  const now = Date.now();
  if (now - state.lastKeyframeRequestAt < KEYFRAME_REQUEST_MIN_INTERVAL_MS) return;
  state.lastKeyframeRequestAt = now;
  controlServer.broadcast({ t: 'request_keyframe' });
}

function startReceivers() {
  videoReceiver = new VideoReceiver({
    port: PORTS.video,
    onFrame: (annexB, frame) => {
      const converted = converter.convert(annexB);
      if (!converted) return;
      state.counters.frames += 1;
      send('video-frame', {
        sample: converted.sample,
        keyframe: converted.keyframe,
        description: converted.description,
        codec: converted.codec,
        tsMs: frame.tsMs,
        frameId: frame.frameId,
        gap: !!frame.gap,
      });
    },
    onKeyframeNeeded: requestKeyframe,
  });
  videoReceiver.start();

  audioReceiver = new AudioReceiver({
    port: PORTS.audio,
    onFrame: (adts, tsMs) => send('audio-frame', { data: adts, tsMs }),
  });
  audioReceiver.start();
}

function startControlServer() {
  controlServer = new ControlServer({
    port: PORTS.control,
    token: state.token,
    onHello: (client, info) => {
      state.connected = true;
      state.notice = '';
      state.inputNotice = '';
      state.orientationChanged = false;
      // 新一次投屏的 frameId 从 0 重新开始，先清掉上一轮的重排序状态
      if (videoReceiver) videoReceiver.reset();
      state.device = {
        model: info.model || '未知设备',
        android: info.android,
        audio: !!info.audio,
        touch: !!info.touch,
        videoW: info.videoW,
        videoH: info.videoH,
      };
      // 手机端能力先随 hello 上报，随后再下发一次配置
      controlServer.send(client, {
        t: 'hello_ack',
        videoPort: PORTS.video,
        audioPort: PORTS.audio,
      });
      controlServer.send(client, { t: 'start' });
      pushState();
    },
    // 手机端每秒上报一次真实状态：音频是否真的在录、无障碍是否开着。
    // hello 里报的是「打算开」，这里才是「真的开起来了」，界面按这个显示更准。
    onMessage: (_client, msg) => {
      if (msg.t === 'pong') {
        // 手机端会把 ping 里的 ts 原样带回来，用本地时间减一下就是网络往返。
        const rtt = Date.now() - Number(msg.ts);
        if (Number.isFinite(rtt) && rtt >= 0 && rtt < 10_000) state.rttMs = rtt;
        return;
      }
      // 手机端注入失败：以前是纯静默的，用户只看到「点了没反应」。
      if (msg.t === 'input_error') {
        setInputNotice(
          typeof msg.detail === 'string' && msg.detail
            ? msg.detail
            : '手机端没能注入触摸，请确认手机上已开启反向控制'
        );
        return;
      }
      if (msg.t === 'input_ok') {
        setInputNotice('');
        return;
      }
      if (msg.t !== 'stats' || !state.device) return;
      const audio = !!msg.audio;
      const touch = !!msg.touch;
      const orientationChanged = !screenMatchesVideo(msg);
      if (
        state.device.audio === audio &&
        state.device.touch === touch &&
        state.orientationChanged === orientationChanged
      ) {
        return;
      }
      state.device = { ...state.device, audio, touch };
      state.orientationChanged = orientationChanged;
      pushState();
    },
    onDisconnect: () => {
      state.connected = false;
      state.device = null;
      state.rttMs = 0;
      state.inputNotice = '';
      state.orientationChanged = false;
      pushState();
    },
    onError: (err) => {
      if (err && err.code === 'EADDRINUSE') {
        setNotice(
          `控制端口 ${PORTS.control} 已被占用，手机连不上。` +
            '请关掉其它「波波投屏」窗口或占用该端口的程序后重新打开。'
        );
      } else {
        setNotice(`控制服务异常：${err && err.message ? err.message : err}`);
      }
    },
  });
  controlServer.start();
}

function startStatsLoop() {
  let lastFrames = 0;
  let lastBytes = 0;

  statsTimer = setInterval(() => {
    const videoBytes = videoReceiver ? videoReceiver.stats.bytes : 0;
    const audioBytes = audioReceiver ? audioReceiver.stats.bytes : 0;
    const bytes = videoBytes + audioBytes;
    const frames = videoReceiver ? videoReceiver.stats.frames : 0;

    state.rates.fps = frames - lastFrames;
    state.rates.kbps = Math.round(((bytes - lastBytes) * 8) / 1000);
    state.counters.dropped = videoReceiver ? videoReceiver.stats.droppedFrames : 0;

    lastFrames = frames;
    lastBytes = bytes;

    if (controlServer && controlServer.clientCount > 0) {
      controlServer.broadcast({ t: 'ping', ts: Date.now() });
    }

    pushTelemetry();
  }, 1000);
}

function switchLanAddress(address) {
  if (!address || address === state.selectedAddress) return;
  state.selectedAddress = address;

  if (advertiser) advertiser.stop();
  advertiser = new DiscoveryAdvertiser({ address, name: os.hostname() });
  advertiser.start();

  refreshQrCode().then(pushState);
}

async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 800,
    minWidth: 900,
    minHeight: 640,
    backgroundColor: '#0b1220',
    title: '波波投屏',
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false,
    },
  });

  mainWindow.setMenuBarVisibility(false);
  await mainWindow.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  pushState();
}

ipcMain.on('select-address', (_event, address) => {
  switchLanAddress(address);
});

ipcMain.on('input', (_event, message) => {
  if (!controlServer) return;
  controlServer.broadcast(message);
});

ipcMain.on('refresh-qr', () => {
  refreshQrCode().then(pushState);
});

// 渲染端遇到丢帧 / 解码器出错时会请求补一个 I 帧
ipcMain.on('request-keyframe', () => {
  requestKeyframe();
});

app.whenReady().then(async () => {
  if (!hasSingleInstanceLock) return;

  const addresses = listLanAddresses();
  const picked = pickLanAddress();

  state.lanAddresses = addresses;
  state.selectedAddress = picked ? picked.address : '127.0.0.1';
  state.token = crypto.randomBytes(4).toString('hex');

  await refreshQrCode();
  await createWindow();

  startControlServer();
  startReceivers();
  advertiser = new DiscoveryAdvertiser({
    address: state.selectedAddress,
    name: os.hostname(),
  });
  advertiser.start();
  startStatsLoop();

  console.log(`[app] 本机地址 ${state.selectedAddress}，令牌 ${state.token}`);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  clearInterval(statsTimer);
  if (advertiser) advertiser.stop();
  if (videoReceiver) videoReceiver.stop();
  if (audioReceiver) audioReceiver.stop();
  if (controlServer) controlServer.stop();
});
