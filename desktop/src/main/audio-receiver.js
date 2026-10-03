'use strict';

const dgram = require('dgram');
const { HEADER_SIZE, parseHeader } = require('./protocol');

/**
 * 音频包不分片，一个包就是一帧 ADTS，收下即可交给解码器。
 */
class AudioReceiver {
  constructor({ port, onFrame, log = console }) {
    this.port = port;
    this.onFrame = onFrame;
    this.log = log;

    this.socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.stats = { packets: 0, bytes: 0 };
  }

  start() {
    this.socket.on('message', (msg) => {
      const header = parseHeader(msg);
      if (!header) return;
      this.stats.packets += 1;
      this.stats.bytes += msg.length;
      this.onFrame(Buffer.from(msg.subarray(HEADER_SIZE)), header.frameTsMs);
    });
    this.socket.on('error', (err) => {
      this.log.warn('[audio] socket 错误:', err.message);
    });
    this.socket.bind(this.port, () => {
      this.log.info(`[audio] 监听 0.0.0.0:${this.port}`);
    });
  }

  stop() {
    try {
      this.socket.close();
    } catch (_) {
      /* 忽略 */
    }
  }
}

module.exports = { AudioReceiver };
