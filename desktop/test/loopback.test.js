'use strict';

/**
 * 纯 Node 的本地测试，不需要 Electron、不需要真机。
 * 运行：node desktop/test/loopback.test.js
 *
 * 覆盖两件最容易写错的事：
 *  1. 包格式与手机端 UdpSender 是否一致（手工按字节构造，模拟手机发出的包）
 *  2. 丢包时的重组策略（关键帧不完整要请求补帧，非关键帧直接丢）
 */

const assert = require('assert');
const dgram = require('dgram');
const net = require('net');

const { AvccConverter } = require('../src/main/annexb');
const { VideoReceiver } = require('../src/main/video-receiver');
const { ControlServer } = require('../src/main/control-server');
const { HEADER_SIZE, MAX_PAYLOAD, MAGIC } = require('../src/main/protocol');

const TESTS = [];
const test = (name, fn) => TESTS.push({ name, fn });

const silent = { info() {}, warn() {}, error() {} };

/** 完全按 Android 端 UdpSender.writeHeader 的字节布局构造包。 */
function buildPacket(frameId, packetIndex, packetCount, flags, frameTsMs, sendTsMs, payload) {
  const buf = Buffer.alloc(HEADER_SIZE + payload.length);
  buf.writeUInt16BE(MAGIC, 0);
  buf.writeUInt8(1, 2);
  buf.writeUInt8(flags, 3);
  buf.writeUInt32BE(frameId >>> 0, 4);
  buf.writeUInt16BE(packetIndex, 8);
  buf.writeUInt16BE(packetCount, 10);
  buf.writeUInt32BE(frameTsMs >>> 0, 12);
  buf.writeUInt32BE(sendTsMs >>> 0, 16);
  payload.copy(buf, HEADER_SIZE);
  return buf;
}

function sendPackets(port, packets) {
  return new Promise((resolve) => {
    const socket = dgram.createSocket('udp4');
    let sent = 0;
    for (const packet of packets) {
      socket.send(packet, port, '127.0.0.1', () => {
        sent += 1;
        if (sent === packets.length) {
          setTimeout(() => {
            socket.close();
            resolve();
          }, 60);
        }
      });
    }
  });
}

test('AvccConverter: 从 Annex-B 提取 SPS/PPS 并生成 avcC', () => {
  const converter = new AvccConverter();
  const sps = Buffer.from([0x67, 0x64, 0x00, 0x1e, 0xac, 0xd9]);
  const pps = Buffer.from([0x68, 0xeb, 0xe3, 0xcb, 0x22, 0xc0]);
  const idr = Buffer.from([0x65, 0x88, 0x84, 0x00, 0x01]);

  const annexB = Buffer.concat([
    Buffer.from([0, 0, 0, 1]), sps,
    Buffer.from([0, 0, 1]), pps,
    Buffer.from([0, 0, 1]), idr,
  ]);

  const result = converter.convert(annexB);

  assert.strictEqual(result.keyframe, true, 'IDR 帧应被识别为关键帧');
  assert.strictEqual(result.codec, 'avc1.64001E');
  assert.strictEqual(result.description.length, 11 + sps.length + pps.length);
  assert.strictEqual(result.sample.readUInt32BE(0), idr.length);
  assert.deepStrictEqual(
    result.sample.subarray(4),
    idr,
    'SPS/PPS 应被剥离，只留图像数据'
  );
});

test('VideoReceiver: 多包重组出完整帧', async () => {
  const port = 18991;
  const payload = Buffer.alloc(MAX_PAYLOAD * 2 + 50);
  for (let i = 0; i < payload.length; i += 1) payload[i] = i & 0xff;

  const count = Math.ceil(payload.length / MAX_PAYLOAD);
  const packets = [];
  for (let i = 0; i < count; i += 1) {
    const flags = (i === 0 ? 0x01 | 0x02 : 0) | (i === count - 1 ? 0x04 : 0);
    packets.push(
      buildPacket(
        7, i, count, flags, 1234, 5678,
        payload.subarray(i * MAX_PAYLOAD, (i + 1) * MAX_PAYLOAD)
      )
    );
  }

  let receiver;
  const received = new Promise((resolve) => {
    receiver = new VideoReceiver({
      port,
      onFrame: (data, frame) => resolve({ data, frame }),
      onKeyframeNeeded: () => {},
      log: silent,
    });
    receiver.start();
  });

  await sendPackets(port, packets);
  const { data, frame } = await received;

  assert.strictEqual(data.length, payload.length, '重组后长度应与原始数据一致');
  assert.ok(data.equals(payload), '重组后内容应逐字节一致');
  assert.strictEqual(frame.keyframe, true);
  assert.strictEqual(frame.tsMs, 1234);

  receiver.stop();
});

test('VideoReceiver: 乱序到达也能正确重组', async () => {
  const port = 18992;
  const payload = Buffer.alloc(MAX_PAYLOAD * 2 + 10, 0xab);
  const count = Math.ceil(payload.length / MAX_PAYLOAD);

  const packets = [];
  for (let i = 0; i < count; i += 1) {
    packets.push(
      buildPacket(
        9, i, count, i === count - 1 ? 0x04 : 0, 100, 200,
        payload.subarray(i * MAX_PAYLOAD, (i + 1) * MAX_PAYLOAD)
      )
    );
  }
  packets.reverse();

  let receiver;
  const received = new Promise((resolve) => {
    receiver = new VideoReceiver({
      port,
      onFrame: (data) => resolve(data),
      onKeyframeNeeded: () => {},
      log: silent,
    });
    receiver.start();
  });

  await sendPackets(port, packets);
  const data = await received;

  assert.ok(data.equals(payload), '乱序包应被正确排序重组');
  receiver.stop();
});

test('VideoReceiver: 关键帧丢包会请求补帧', async () => {
  const port = 18993;
  let keyframeRequested = false;

  const receiver = new VideoReceiver({
    port,
    onFrame: () => {},
    onKeyframeNeeded: () => { keyframeRequested = true; },
    log: silent,
  });
  receiver.start();

  await sendPackets(port, [
    buildPacket(11, 0, 3, 0x01 | 0x02, 0, 0, Buffer.alloc(100, 1)),
    buildPacket(11, 1, 3, 0, 0, 0, Buffer.alloc(100, 2)),
  ]);

  await new Promise((resolve) => setTimeout(resolve, 500));
  assert.strictEqual(keyframeRequested, true, '关键帧不完整时应请求补帧');
  receiver.stop();
});

test('VideoReceiver: 非关键帧丢包不补帧，直接丢弃', async () => {
  const port = 18994;
  let keyframeRequested = false;
  let frameDelivered = false;

  const receiver = new VideoReceiver({
    port,
    onFrame: () => { frameDelivered = true; },
    onKeyframeNeeded: () => { keyframeRequested = true; },
    log: silent,
  });
  receiver.start();

  await sendPackets(port, [
    buildPacket(12, 0, 3, 0, 0, 0, Buffer.alloc(100, 1)),
  ]);

  await new Promise((resolve) => setTimeout(resolve, 500));
  assert.strictEqual(frameDelivered, false, '不完整的帧不应交付');
  assert.strictEqual(keyframeRequested, false, '非关键帧丢包不应触发补帧');
  receiver.stop();
});

test('ControlServer: 令牌不匹配时拒绝连接', async () => {
  const port = 18995;
  const server = new ControlServer({
    port,
    token: 'goodtoken',
    onHello: () => {},
    onMessage: () => {},
    onDisconnect: () => {},
    log: silent,
  });
  server.start();
  await new Promise((resolve) => setTimeout(resolve, 100));

  const rejected = await new Promise((resolve) => {
    const socket = net.connect(port, '127.0.0.1', () => {
      socket.write(`${JSON.stringify({ t: 'hello', token: 'wrongtoken' })}\n`);
    });
    socket.on('close', () => resolve(true));
    socket.on('error', () => resolve(true));
    setTimeout(() => resolve(false), 1500);
  });

  assert.strictEqual(rejected, true, '错误令牌的连接应被断开');
  assert.strictEqual(server.clientCount, 0);
  server.stop();
});

test('ControlServer: 正确令牌可以握手并收发消息', async () => {
  const port = 18996;
  const received = [];

  const server = new ControlServer({
    port,
    token: 'goodtoken',
    onHello: (client) => server.send(client, { t: 'hello_ack' }),
    onMessage: (_client, msg) => received.push(msg),
    onDisconnect: () => {},
    log: silent,
  });
  server.start();
  await new Promise((resolve) => setTimeout(resolve, 100));

  const replies = await new Promise((resolve) => {
    const lines = [];
    const socket = net.connect(port, '127.0.0.1', () => {
      socket.write(`${JSON.stringify({ t: 'hello', token: 'goodtoken', model: '测试机' })}\n`);
      setTimeout(() => {
        socket.write(`${JSON.stringify({ t: 'ping', ts: 42 })}\n`);
        socket.write(`${JSON.stringify({ t: 'stats', fps: 60 })}\n`);
      }, 150);
      setTimeout(() => {
        socket.end();
        resolve(lines);
      }, 500);
    });

    let buffer = '';
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      let index = buffer.indexOf('\n');
      while (index >= 0) {
        lines.push(JSON.parse(buffer.slice(0, index)));
        buffer = buffer.slice(index + 1);
        index = buffer.indexOf('\n');
      }
    });
  });

  assert.strictEqual(server.clientCount, 1);
  assert.ok(replies.some((m) => m.t === 'hello_ack'), '应收到 hello_ack');
  assert.ok(
    replies.some((m) => m.t === 'pong' && m.ts === 42),
    'ping 应被应答为 pong 且带回原时间戳'
  );
  assert.ok(received.some((m) => m.t === 'stats' && m.fps === 60), 'stats 应被转发');

  server.stop();
});

(async () => {
  let passed = 0;
  let failed = 0;

  for (const { name, fn } of TESTS) {
    try {
      await fn();
      console.log(`  通过  ${name}`);
      passed += 1;
    } catch (err) {
      console.error(`  失败  ${name}`);
      console.error(`        ${err.message}`);
      failed += 1;
    }
  }

  console.log(`\n${passed} 项通过，${failed} 项失败`);
  process.exit(failed === 0 ? 0 : 1);
})();
