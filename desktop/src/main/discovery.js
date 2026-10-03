'use strict';

const dgram = require('dgram');
const { PORTS, VERSION } = require('./protocol');

/**
 * 周期性向局域网广播自己的存在，让手机端的设备列表能发现本机。
 * 这是扫码连接之外的兜底方式。
 */
class DiscoveryAdvertiser {
  constructor({ address, name, log = console }) {
    this.address = address;
    this.name = name;
    this.log = log;
    this.socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.timer = null;
  }

  start() {
    this.socket.bind(() => {
      this.timer = setInterval(() => this.announce(), 1000);
      this.announce();
    });
    this.socket.on('error', (err) => {
      this.log.warn('[discovery] socket 错误:', err.message);
    });
  }

  announce() {
    const payload = Buffer.from(
      JSON.stringify({
        t: 'adv',
        name: this.name,
        ip: this.address,
        ctrlPort: PORTS.control,
        videoPort: PORTS.video,
        audioPort: PORTS.audio,
        ver: VERSION,
        busy: false,
      }),
      'utf8'
    );

    try {
      this.socket.setBroadcast(true);
    } catch (err) {
      this.log.warn('[discovery] 无法开启广播:', err.message);
      return;
    }

    this.socket.send(payload, 0, payload.length, PORTS.discovery, '255.255.255.255', (err) => {
      if (err) this.log.warn('[discovery] 广播失败:', err.message);
    });
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
    try {
      this.socket.close();
    } catch (_) {
      /* 已关闭 */
    }
  }
}

module.exports = { DiscoveryAdvertiser };
