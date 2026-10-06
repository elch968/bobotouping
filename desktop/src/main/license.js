'use strict';

/**
 * 授权模块（Electron 侧）。
 *
 * 只做三件事：算出本机设备码、把授权码存到用户目录、每次启动重新校验一遍。
 *
 * 「每次启动重新校验」是这套方案的关键：授权码是绑设备的，如果直接把整个
 * 程序目录（甚至整个用户目录）拷到另一台电脑，重新算出来的设备码不一样，
 * 校验就过不去，那份授权等于废纸。
 */

const fs = require('fs');
const path = require('path');
const { app } = require('electron');

const core = require('./license-core');

/** 公钥跟客户端一起发布；私钥只在开发者本机，绝不出现在这个仓库里。 */
const PUBLIC_KEY_PATH = path.join(__dirname, 'license.pub');

let deviceCode = '';
let ready = false;
let blockedReason = '';
let current = null;

function storagePath() {
  return path.join(app.getPath('userData'), 'license.json');
}

function readPublicKey() {
  return fs.readFileSync(PUBLIC_KEY_PATH, 'utf8');
}

function readStoredCode() {
  try {
    const parsed = JSON.parse(fs.readFileSync(storagePath(), 'utf8'));
    return typeof parsed.code === 'string' ? parsed.code : '';
  } catch (_) {
    return '';
  }
}

function writeStoredCode(code) {
  const file = storagePath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify({ code, savedAt: Date.now() }, null, 2), 'utf8');
}

/**
 * 启动时调用一次：算设备码 + 校验本地已存的授权码。
 * 设备码每次启动都重新算，不缓存到磁盘 —— 缓存了就等于把设备码也一起被复制走了。
 */
async function init() {
  try {
    deviceCode = await core.computeDeviceCode();
  } catch (err) {
    ready = true;
    blockedReason = `读不到本机硬件信息，无法激活：${err.message}`;
    return getPublicState();
  }

  const stored = readStoredCode();
  if (stored) {
    let result;
    try {
      result = core.verifyLicense(stored, deviceCode, readPublicKey());
    } catch (err) {
      result = { ok: false, reason: `客户端缺少公钥文件：${err.message}` };
    }
    if (result.ok) {
      current = { code: stored, name: result.name, expiry: result.expiry };
      blockedReason = '';
    } else {
      current = null;
      blockedReason = result.reason;
    }
  }

  ready = true;
  return getPublicState();
}

/** 用一条新授权码尝试激活；成功即写入用户目录。 */
function activate(code) {
  if (!deviceCode) {
    return { ok: false, message: blockedReason || '正在读取本机信息，请稍后重试' };
  }

  let publicKey;
  try {
    publicKey = readPublicKey();
  } catch (err) {
    return { ok: false, message: `客户端缺少公钥文件 license.pub：${err.message}` };
  }

  const cleaned = String(code || '').replace(/\s+/g, '');
  const result = core.verifyLicense(cleaned, deviceCode, publicKey);
  if (!result.ok) return { ok: false, message: result.reason };

  try {
    writeStoredCode(cleaned);
  } catch (err) {
    return { ok: false, message: `授权码保存失败：${err.message}` };
  }

  current = { code: cleaned, name: result.name, expiry: result.expiry };
  blockedReason = '';
  return { ok: true, message: '激活成功' };
}

function isActivated() {
  return !!current;
}

/** 给界面用的状态，注意不要带出授权码原文。 */
function getPublicState() {
  return {
    ready,
    activated: !!current,
    deviceCode,
    licensedTo: current ? current.name : '',
    expiresAt: current ? current.expiry : 0,
    error: current || !ready ? '' : blockedReason,
  };
}

module.exports = {
  init,
  activate,
  isActivated,
  getPublicState,
};
