'use strict';

/**
 * Android 的 MediaCodec 输出的是 Annex-B（用 00 00 01 起始码分隔 NAL），
 * 而 WebCodecs 需要 AVCC（长度前缀）+ avcC 描述。这里做这个转换。
 *
 * 转换只在主进程做一次，渲染进程拿到的就是可以直接喂给 VideoDecoder 的数据。
 */

const NAL_SPS = 7;
const NAL_PPS = 8;
const NAL_AUD = 9;
const NAL_IDR = 5;

function findStartCodes(buf) {
  const codes = [];
  let i = 0;
  while (i + 3 < buf.length) {
    if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 0 && buf[i + 3] === 1) {
      codes.push({ offset: i, length: 4 });
      i += 4;
    } else if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) {
      codes.push({ offset: i, length: 3 });
      i += 3;
    } else {
      i += 1;
    }
  }
  return codes;
}

function splitNalUnits(buf) {
  const codes = findStartCodes(buf);
  const units = [];
  for (let i = 0; i < codes.length; i += 1) {
    const start = codes[i].offset + codes[i].length;
    const end = i + 1 < codes.length ? codes[i + 1].offset : buf.length;
    if (end > start) units.push(buf.subarray(start, end));
  }
  return units;
}

class AvccConverter {
  constructor() {
    this.sps = null;
    this.pps = null;
    this.description = null;
    this.codec = 'avc1.42E01E';
  }

  /**
   * @param {Buffer} annexB 一帧 Annex-B 数据
   * @returns {{sample: Buffer, keyframe: boolean, description: Buffer|null, codec: string}|null}
   */
  convert(annexB) {
    const units = splitNalUnits(annexB);
    if (units.length === 0) return null;

    const kept = [];
    let keyframe = false;

    for (const nal of units) {
      const type = nal[0] & 0x1f;
      if (type === NAL_SPS) {
        this.sps = Buffer.from(nal);
        this.description = null;
        this.updateCodecString();
        continue;
      }
      if (type === NAL_PPS) {
        this.pps = Buffer.from(nal);
        this.description = null;
        continue;
      }
      if (type === NAL_AUD) continue; // 访问单元分隔符，解码器不需要
      if (type === NAL_IDR) keyframe = true;
      kept.push(nal);
    }

    if (kept.length === 0) return null;

    let total = 0;
    for (const nal of kept) total += 4 + nal.length;

    const sample = Buffer.allocUnsafe(total);
    let offset = 0;
    for (const nal of kept) {
      sample.writeUInt32BE(nal.length, offset);
      offset += 4;
      nal.copy(sample, offset);
      offset += nal.length;
    }

    return {
      sample,
      keyframe,
      description: this.getDescription(),
      codec: this.codec,
    };
  }

  updateCodecString() {
    if (!this.sps || this.sps.length < 4) return;
    const profile = this.sps[1].toString(16).padStart(2, '0');
    const compat = this.sps[2].toString(16).padStart(2, '0');
    const level = this.sps[3].toString(16).padStart(2, '0');
    this.codec = `avc1.${(profile + compat + level).toUpperCase()}`;
  }

  /** 构造 avcC（AVCDecoderConfigurationRecord），VideoDecoder 的 description。 */
  getDescription() {
    if (this.description) return this.description;
    if (!this.sps || !this.pps) return null;

    const { sps, pps } = this;
    const buf = Buffer.allocUnsafe(11 + sps.length + pps.length);
    let o = 0;

    buf[o++] = 1; // configurationVersion
    buf[o++] = sps[1]; // AVCProfileIndication
    buf[o++] = sps[2]; // profile_compatibility
    buf[o++] = sps[3]; // AVCLevelIndication
    buf[o++] = 0xff; // lengthSizeMinusOne = 3（4 字节长度前缀）
    buf[o++] = 0xe1; // numOfSequenceParameterSets = 1
    buf.writeUInt16BE(sps.length, o);
    o += 2;
    sps.copy(buf, o);
    o += sps.length;
    buf[o++] = 1; // numOfPictureParameterSets = 1
    buf.writeUInt16BE(pps.length, o);
    o += 2;
    pps.copy(buf, o);

    this.description = buf;
    return buf;
  }
}

module.exports = { AvccConverter, splitNalUnits, findStartCodes };
