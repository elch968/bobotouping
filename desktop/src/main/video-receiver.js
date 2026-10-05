'use strict';

const dgram = require('dgram');
const { HEADER_SIZE, parseHeader, FLAG_KEYFRAME, FLAG_CONFIG } = require('./protocol');

const FRAME_TIMEOUT_MS = 300;
/** 乱序容忍窗口：只比一帧（60fps 约 17ms）略长，正常按序到达时不会引入额外延迟。 */
const REORDER_WINDOW_MS = 25;
/** 合并连续的补帧请求，丢包时不要把手机端刷爆。 */
const KEYFRAME_REQUEST_COOLDOWN_MS = 250;
/** UDP 默认接收缓冲只有 64KB，一个 I 帧的突发就能把它冲爆、整帧丢光。 */
const RECV_BUFFER_BYTES = 8 * 1024 * 1024;
/**
 * frameId 明显倒退这么多帧，就当成「手机端重开了一场投屏」。
 * UDP 乱序最多倒退几帧，不会倒退几十帧。
 */
const SESSION_RESTART_BACKWARD_GAP = 60;

const U32 = 0x100000000;
const HALF_U32 = 0x80000000;

/** frameId 是 u32 且会回绕，用模运算判断谁更新。 */
function forwardDistance(from, to) {
  return (to - from + U32) % U32;
}

/**
 * 收 UDP 视频包，按 frameId 重组成完整帧。
 *
 * 相比初版有两处关键改动，都是冲着花屏 / 马赛克块去的：
 *
 * 1. 按 frameId 顺序交给解码器。UDP 会乱序，先收齐的帧不一定是前面的帧，
 *    解码器吃到乱序帧后参考帧就对不上，画面出现斑块；而手机屏幕静止时不会
 *    再有新帧把它冲掉，看起来就是「这一个页面一直花，切走才恢复」。
 * 2. 丢包不再静默跳过。缺帧时给下一帧打上 gap 标记，渲染端据此停在当前画面，
 *    等到下一个 I 帧再继续，同时立刻请求补帧，不让解码器带着坏参考继续解。
 */
class VideoReceiver {
  constructor({ port, onFrame, onKeyframeNeeded, log = console }) {
    this.port = port;
    this.onFrame = onFrame;
    this.onKeyframeNeeded = onKeyframeNeeded || (() => {});
    this.log = log;

    this.frames = new Map();     // 还在收包的帧
    this.completed = new Map();  // 已经收齐、等待按序交付的帧
    this.nextFrameId = null;     // 下一个该交付的 frameId
    this.gapPending = false;     // 下一次交付的帧前面缺过帧
    this.flushTimer = null;
    this.lastKeyframeRequestAt = 0;

    this.socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.sweeper = null;

    this.stats = { packets: 0, frames: 0, droppedFrames: 0, gaps: 0, bytes: 0 };
  }

  start() {
    this.socket.on('message', (msg) => this.handlePacket(msg));
    this.socket.on('error', (err) => {
      this.log.warn('[video] socket 错误:', err.message);
    });
    try {
      this.socket.setRecvBufferSize(RECV_BUFFER_BYTES);
    } catch (err) {
      this.log.warn('[video] 设置接收缓冲失败:', err.message);
    }
    this.socket.bind(this.port, () => {
      this.log.info(`[video] 监听 0.0.0.0:${this.port}`);
    });
    this.sweeper = setInterval(() => this.sweep(), 200);
  }

  /** 手机端重新开一次投屏（收到新的 hello）时调用：frameId 会从 0 重新开始。 */
  reset() {
    this.frames.clear();
    this.completed.clear();
    this.nextFrameId = null;
    this.gapPending = false;
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }
  }

  handlePacket(msg) {
    const header = parseHeader(msg);
    if (!header) return;

    // 手机端每重新开始一次投屏，frameId 都会从 0 重新数。如果排序游标还停在上一场的末尾，
    // 新帧会被判成「过期帧」全部丢掉，现象就是：第一次投屏正常，停止后再投永远黑屏。
    // 这里主动识别新会话并重置排序状态，不再依赖 hello 是否成功送达。
    if (this.nextFrameId !== null && this.isNewSession(header)) {
      this.reset();
    }

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
      this.completed.set(header.frameId, {
        data: Buffer.concat(frame.parts),
        frame,
        firstSeen: frame.firstSeen,
      });
      this.flush(false);
    }
  }

  /**
   * 判断这个包是不是「新一场投屏」的开头。
   *
   * 最可靠的信号是 config 包：编码器每次 start() 只会吐一次 SPS/PPS（带 FLAG_CONFIG 标志），
   * 同一场投屏里不会再出现。第二道保险是 frameId 明显倒退（UDP 乱序最多退几帧，不会退几十帧）。
   */
  isNewSession(header) {
    if ((header.flags & FLAG_CONFIG) !== 0) return true;
    return this.nextFrameId - header.frameId > SESSION_RESTART_BACKWARD_GAP;
  }

  /** 已收齐的帧里，按 u32 回绕比较离 nextFrameId 最近的那个。 */
  oldestPendingId() {
    let best = null;
    let bestDistance = 0;
    for (const id of this.completed.keys()) {
      if (this.nextFrameId === null) return id;
      const distance = forwardDistance(this.nextFrameId, id);
      if (distance === 0 || distance >= HALF_U32) continue; // 已经过去的帧
      if (best === null || distance < bestDistance) {
        best = id;
        bestDistance = distance;
      }
    }
    return best;
  }

  /** 按 frameId 顺序交付，缺帧最多等 REORDER_WINDOW_MS。 */
  flush(force) {
    if (this.completed.size === 0) return;

    if (this.nextFrameId === null) {
      this.nextFrameId = this.oldestPendingId();
      if (this.nextFrameId === null) return;
    }

    // 落在 nextFrameId 后面的都是过期帧（重连前的残留、或者已经被判丢的帧）。
    for (const id of [...this.completed.keys()]) {
      if (forwardDistance(this.nextFrameId, id) >= HALF_U32) this.completed.delete(id);
    }

    for (;;) {
      const entry = this.completed.get(this.nextFrameId);
      if (entry) {
        this.completed.delete(this.nextFrameId);

        const gap = this.gapPending;
        this.gapPending = false;

        this.stats.frames += 1;
        if (gap) {
          this.stats.gaps += 1;
          this.requestKeyframe();
        }

        this.onFrame(entry.data, {
          id: entry.frame.id,
          frameId: entry.frame.id,
          keyframe: entry.frame.keyframe,
          config: entry.frame.config,
          tsMs: entry.frame.tsMs,
          gap,
        });

        this.nextFrameId = (this.nextFrameId + 1) % U32;
        continue;
      }

      const next = this.oldestPendingId();
      if (next === null) return;

      const waited = Date.now() - this.completed.get(next).firstSeen;
      if (!force && waited < REORDER_WINDOW_MS) {
        this.scheduleFlush();
        return;
      }

      // 中间这帧（或几帧）丢了：打个缺口标记，从后面接着交付，别让画面干等。
      this.gapPending = true;
      this.nextFrameId = next;
    }
  }

  scheduleFlush() {
    if (this.flushTimer) return;
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null;
      this.flush(false);
    }, REORDER_WINDOW_MS);
  }

  requestKeyframe() {
    const now = Date.now();
    if (now - this.lastKeyframeRequestAt < KEYFRAME_REQUEST_COOLDOWN_MS) return;
    this.lastKeyframeRequestAt = now;
    this.onKeyframeNeeded();
  }

  sweep() {
    const now = Date.now();
    for (const [id, frame] of this.frames) {
      if (now - frame.firstSeen < FRAME_TIMEOUT_MS) continue;
      this.frames.delete(id);
      this.stats.droppedFrames += 1;
      if (frame.keyframe) this.requestKeyframe();
    }
    this.flush(true);
  }

  stop() {
    clearInterval(this.sweeper);
    this.sweeper = null;
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }
    this.reset();
    try {
      this.socket.close();
    } catch (_) {
      /* 忽略 */
    }
  }
}

module.exports = { VideoReceiver };
