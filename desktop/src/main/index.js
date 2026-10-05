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
  lastKeyframeRequestAt: 0,
  counters: { frames: 0, bytes: 0, dropped: 0 },
  rates: { fps: 0, kbps: 0 },
};

function send(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload);
  }
}

function pushState() {
  send('state', {
    lanAddresses: state.lanAddresses,
    selectedAddress: state.selectedAddress,
    qrDataUrl: state.qrDataUrl,
    connected: state.connected,
    device: state.device,
    rttMs: state.rttMs,
    fps: state.rates.fps,
    kbps: state.rates.kbps,
    dropped: state.counters.dropped,
  });
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
    onDisconnect: () => {
      state.connected = false;
      state.device = null;
      state.rttMs = 0;
      pushState();
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

    pushState();
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

app.whenReady().then(async () => {
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
