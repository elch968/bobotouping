'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('bobo', {
  onState: (callback) => ipcRenderer.on('state', (_event, payload) => callback(payload)),
  onVideoFrame: (callback) => ipcRenderer.on('video-frame', (_event, payload) => callback(payload)),
  onAudioFrame: (callback) => ipcRenderer.on('audio-frame', (_event, payload) => callback(payload)),
  sendInput: (message) => ipcRenderer.send('input', message),
  selectAddress: (address) => ipcRenderer.send('select-address', address),
  refreshQr: () => ipcRenderer.send('refresh-qr'),
  requestKeyframe: () => ipcRenderer.send('request-keyframe'),
});
