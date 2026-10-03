'use strict';

const dgram = require('dgram');
const { HEADER_SIZE, parseHeader, FLAG_KEYFRAME, FLAG_CONFIG } = require('./protocol');

const FRAME_TIMEOUT_MS = 300;

/**
 * 收 UDP 视频包，按 frameId 重组成完整帧。
 *
 * 丢包策略：
 * - 关键帧不完整 → 丢弃，并请手机立刻补一个 IDR
 * - 非关键帧不完整 → 直接丢，画面轻微跳一下但不卡
 */
class VideoReceiver {
  constructor({ port, onFrame, onKeyframeNeeded, log = console }) {
    this.port = port;
    this.onFrame = onFrame;
    this.onKeyframeNeeded = onKeyframeNeeded || (() => {});
    this.log = log;

    this.frames = new Map();
    this.socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.sweeper = null;

    this.stats = { packets: 0, frames: 0, droppedFrames: 0, bytes: 0 };
  }

  start() {
    this.socket.on('message', (msg) => this.handlePacket(msg));
    this.socket.on('error', (err) => {
      this.log.warn('[video] socket 错误:', err.message);
    });
    this.socket.bind(this.port, () => {
      this.log.info(`[video] 监听 0.0.0.0:${this.port}`);
    });
    this.sweeper = setInterval(() => this.sweep(), 200);
  }

  handlePacket(msg) {
    const header = parseHeader(msg);
    if (!header) return;

    this.stats.packets += 1;
    this.stats.bytes += msg.length;

    let frame = this.frames.get(header.frameId);
    if (!frame) {
      frame = {
        id: header.frameId,
        count: header.packetCount,
        parts: new Array(header.packetCount).fill(null),
        received: 0,
        keyframe: (header.flags & FLAG_KEYFRAME) !== 0,
        config: (header.flags & FLAG_CONFIG) !== 0,
        tsMs: header.frameTsMs,
        firstSeen: Date.now(),
      };
      this.frames.set(header.frameId, frame);
    }

    if (header.packetIndex >= frame.parts.length) return;
    if (frame.parts[header.packetIndex]) return;

    frame.parts[header.packetIndex] = Buffer.from(msg.subarray(HEADER_SIZE));
    frame.received += 1;

    if (frame.received === frame.count) {
      this.frames.delete(header.frameId);
      this.stats.frames += 1;
      this.onFrame(Buffer.concat(frame.parts), frame);
    }
  }

  sweep() {
    const now = Date.now();
    for (const [id, frame] of this.frames) {
      if (now - frame.firstSeen < FRAME_TIMEOUT_MS) continue;
      this.frames.delete(id);
      this.stats.droppedFrames += 1;
      if (frame.keyframe) this.onKeyframeNeeded();
    }
  }

  stop() {
    clearInterval(this.sweeper);
    this.sweeper = null;
    this.frames.clear();
    try {
      this.socket.close();
    } catch (_) {
      /* 忽略 */
    }
  }
}

module.exports = { VideoReceiver };
