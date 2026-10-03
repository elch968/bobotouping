'use strict';

const os = require('os');

/**
 * 电脑上常见的一堆虚拟网卡。二维码里如果写错了网卡的 IP，
 * 手机会扫到一个根本连不上的地址，所以这里要能把它们排到后面。
 */
const VIRTUAL_HINT =
  /(vethernet|vmware|virtualbox|hyper-?v|loopback|tailscale|zerotier|wsl|docker|vpn|tap|tun|npcap|bluetooth|radmin|hamachi)/i;

function isPrivateIpv4(ip) {
  return (
    /^10\./.test(ip) ||
    /^192\.168\./.test(ip) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(ip)
  );
}

function score(addr) {
  let s = 0;
  if (addr.private) s += 10;
  if (!addr.virtual) s += 5;
  if (/^192\.168\./.test(addr.address)) s += 2;
  // 169.254.x.x 是拿不到 DHCP 时的自动地址，基本不可用
  if (/^169\.254\./.test(addr.address)) s -= 20;
  return s;
}

/** 列出所有可用的局域网 IPv4 地址，按「最可能是真实网卡」排序。 */
function listLanAddresses() {
  const result = [];
  const ifaces = os.networkInterfaces();

  for (const [name, addrs] of Object.entries(ifaces)) {
    for (const addr of addrs || []) {
      if (addr.family !== 'IPv4' || addr.internal) continue;
      result.push({
        iface: name,
        address: addr.address,
        private: isPrivateIpv4(addr.address),
        virtual: VIRTUAL_HINT.test(name),
      });
    }
  }

  return result.sort((a, b) => score(b) - score(a));
}

function pickLanAddress() {
  return listLanAddresses()[0] || null;
}

module.exports = { listLanAddresses, pickLanAddress, isPrivateIpv4 };
