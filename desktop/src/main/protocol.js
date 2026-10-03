'use strict';

/**
 * 与 Android 端 Protocol.kt 必须保持一致。
 */

const PORTS = {
  discovery: 8767,
  control: 8765,
  video: 8766,
  audio: 8768,
};

const MAGIC = 0x4d43; // 'M' << 8 | 'C'
const VERSION = 1;

const FLAG_KEYFRAME = 0x01;
const FLAG_CONFIG = 0x02;
const FLAG_LAST_PACKET = 0x04;

const HEADER_SIZE = 20;
const MAX_PAYLOAD = 1200;

function parseHeader(buf) {
  if (!buf || buf.length < HEADER_SIZE) return null;
  if (buf.readUInt16BE(0) !== MAGIC) return null;
  return {
    version: buf.readUInt8(2),
    flags: buf.readUInt8(3),
    frameId: buf.readUInt32BE(4),
    packetIndex: buf.readUInt16BE(8),
    packetCount: buf.readUInt16BE(10),
    frameTsMs: buf.readUInt32BE(12),
    sendTsMs: buf.readUInt32BE(16),
  };
}

module.exports = {
  PORTS,
  MAGIC,
  VERSION,
  FLAG_KEYFRAME,
  FLAG_CONFIG,
  FLAG_LAST_PACKET,
  HEADER_SIZE,
  MAX_PAYLOAD,
  parseHeader,
};
