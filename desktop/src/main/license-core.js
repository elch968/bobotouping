'use strict';

/**
 * 授权码的纯逻辑部分：机器指纹、签发、校验。
 *
 * 这里刻意不 require('electron')，也不碰文件系统，好处是能用普通 node 直接跑测试
 * （见 desktop/test/license.test.js），发码工具也能复用同一套签发代码，
 * 避免「工具签出来的码客户端验不过」这种最难查的问题。
 *
 * 安全模型：签发用私钥（只在开发者本机），客户端只内置公钥。
 * 所以授权码可以被复制，但改不了里面的任何字段 —— 改了签名就对不上。
 */

const crypto = require('crypto');
const { execFile } = require('child_process');

/** 授权码前缀，用来快速识别「这一串是不是本软件的授权码」。 */
const CODE_PREFIX = 'BOL1';

/**
 * 签名时参与签名的字符串前缀。
 * 加上它，别的用途用同一把钥匙签出来的东西就不能拿来冒充授权码。
 */
const MESSAGE_PREFIX = 'bobotouping-license-v1';

/** Crockford Base32：去掉容易看错的 I、L、O、U。 */
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** 设备码取哈希前 7 字节 —— 56 位，编成 12 个字符，显示成 XXXX-XXXX-XXXX。 */
const DEVICE_CODE_BYTES = 7;

/** 主板/BIOS 里常见的「没填」占位值，不能拿来做指纹。 */
const INVALID_TRAITS = new Set([
  '',
  'none',
  'not applicable',
  'not specified',
  'default string',
  'to be filled by o.e.m.',
  'system serial number',
  'oem',
  '0',
  '00000000-0000-0000-0000-000000000000',
  'ffffffff-ffff-ffff-ffff-ffffffffffff',
  '03000200-0400-0500-0006-000700080009',
]);

function base32Encode(buffer) {
  let bits = 0;
  let value = 0;
  let out = '';

  for (const byte of buffer) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

/**
 * 用户手抄设备码时经常把 0 和 O、1 和 I 搞混，这里按 Crockford 的规则纠正回来，
 * 再统一大写、去掉分隔符。这样设备码比对只跟真实内容有关，跟怎么写无关。
 */
function normalizeDeviceCode(input) {
  return String(input || '')
    .toUpperCase()
    .replace(/[^0-9A-Z]/g, '')
    .replace(/[IL]/g, '1')
    .replace(/O/g, '0')
    .replace(/U/g, 'V');
}

function formatDeviceCode(input) {
  const raw = normalizeDeviceCode(input);
  return raw.replace(/(.{4})(?=.)/g, '$1-');
}

function normalizeTrait(value) {
  const text = String(value == null ? '' : value).trim();
  if (!text) return '';
  return INVALID_TRAITS.has(text.toLowerCase()) ? '' : text;
}

/**
 * 从硬件特征算出设备码。
 *
 * 只用「重装系统也不会变」的东西：主板/BIOS 的 UUID、主板序列号、CPU 标识。
 * 刻意不用注册表 MachineGuid 和网卡 MAC —— 重装系统、插拔网卡都会变，
 * 用户会莫名其妙被要求重新发码。
 */
function deviceCodeFromTraits(traits) {
  const uuid = normalizeTrait(traits && traits.uuid);
  const board = normalizeTrait(traits && traits.board);
  const bios = normalizeTrait(traits && traits.bios);
  const cpu = normalizeTrait(traits && traits.cpu);

  // UUID 最可靠；有些主板没写 UUID，就退回到「主板序列号 + BIOS 序列号」
  const primary = uuid ? `uuid:${uuid}` : board || bios ? `board:${board}:${bios}` : '';
  if (!primary) {
    throw new Error('读不到本机硬件标识，无法生成设备码');
  }

  const material = ['bobotouping-device-v1', primary, cpu].join('|');
  const digest = crypto.createHash('sha256').update(material, 'utf8').digest();
  return formatDeviceCode(base32Encode(digest.subarray(0, DEVICE_CODE_BYTES)));
}

/**
 * 参与签名的原文。
 *
 * 这里手工拼字符串而不是 JSON.stringify 对象，是为了保证签发端和校验端
 * 拼出来的字节完全一样 —— 依赖 JSON 的键顺序是会出人命的。
 */
function buildMessage(body) {
  return [
    MESSAGE_PREFIX,
    normalizeDeviceCode(body.d),
    body.n || '',
    String(Number(body.e) || 0),
  ].join('\n');
}

/**
 * 签发授权码（只有开发者本机需要调用）。
 *
 * @param {string} device  设备码，可以带分隔符、大小写随意
 * @param {string} name    授权给谁，可为空
 * @param {number} expiry  到期时间（秒级时间戳），0 表示永久
 * @param {string|object} privateKey  Ed25519 私钥
 */
function issueLicense({ device, name = '', expiry = 0, privateKey }) {
  const body = {
    v: 1,
    d: normalizeDeviceCode(device),
    n: String(name || ''),
    e: Math.max(0, Math.floor(Number(expiry) || 0)),
  };
  if (!body.d) throw new Error('设备码是空的');

  const signature = crypto.sign(null, Buffer.from(buildMessage(body), 'utf8'), privateKey);
  const envelope = { ...body, s: signature.toString('base64url') };
  return `${CODE_PREFIX}.${Buffer.from(JSON.stringify(envelope), 'utf8').toString('base64url')}`;
}

/**
 * 校验收到的授权码。
 *
 * 返回 { ok, reason, name, expiry }，失败时的 reason 可以直接显示给用户。
 */
function verifyLicense(code, deviceCode, publicKey, nowSeconds) {
  const now = Number.isFinite(nowSeconds) ? nowSeconds : Math.floor(Date.now() / 1000);

  // 微信/QQ 转发经常会带上换行和空格，先全部去掉再解析
  const raw = String(code || '').replace(/\s+/g, '');
  if (!raw) return { ok: false, reason: '请先粘贴授权码' };
  if (!raw.startsWith(`${CODE_PREFIX}.`)) {
    return { ok: false, reason: '这不是本软件的授权码（应以 BOL1. 开头）' };
  }

  let body;
  try {
    const json = Buffer.from(raw.slice(CODE_PREFIX.length + 1), 'base64url').toString('utf8');
    body = JSON.parse(json);
  } catch (_) {
    return { ok: false, reason: '授权码内容不完整，可能复制时少了一段' };
  }

  if (body.v !== 1) return { ok: false, reason: `授权码版本不支持（v${body.v}）` };

  let signatureOk = false;
  try {
    signatureOk = crypto.verify(
      null,
      Buffer.from(buildMessage(body), 'utf8'),
      publicKey,
      Buffer.from(String(body.s || ''), 'base64url')
    );
  } catch (_) {
    signatureOk = false;
  }
  if (!signatureOk) return { ok: false, reason: '授权码签名不对，可能被改过' };

  if (body.d !== normalizeDeviceCode(deviceCode)) {
    return { ok: false, reason: '这个授权码是给另一台电脑的' };
  }

  const expiry = Number(body.e) || 0;
  if (expiry > 0 && now > expiry) {
    const when = new Date(expiry * 1000).toISOString().slice(0, 10);
    return { ok: false, reason: `授权已于 ${when} 到期`, expired: true };
  }

  return { ok: true, reason: '', name: body.n || '', expiry };
}

/** 查询本机硬件特征用的 PowerShell 脚本，只输出 ASCII，避免编码问题。 */
const POWERSHELL_TRAITS_SCRIPT = [
  "$ErrorActionPreference = 'SilentlyContinue'",
  '$cs = Get-CimInstance -ClassName Win32_ComputerSystemProduct',
  '$bb = Get-CimInstance -ClassName Win32_BaseBoard',
  '$bios = Get-CimInstance -ClassName Win32_BIOS',
  '$cpu = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1',
  'Write-Output ("UUID=" + $cs.UUID)',
  'Write-Output ("BOARD=" + $bb.SerialNumber)',
  'Write-Output ("BIOS=" + $bios.SerialNumber)',
  'Write-Output ("CPU=" + $cpu.ProcessorId)',
].join('\n');

function parseTraitsOutput(text) {
  const traits = { uuid: '', board: '', bios: '', cpu: '' };
  for (const line of String(text || '').split(/\r?\n/)) {
    const index = line.indexOf('=');
    if (index <= 0) continue;
    const key = line.slice(0, index).trim().toLowerCase();
    if (key in traits) traits[key] = line.slice(index + 1).trim();
  }
  return traits;
}

function runPowerShell(script, timeoutMs = 20000) {
  return new Promise((resolve, reject) => {
    execFile(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script],
      { windowsHide: true, timeout: timeoutMs, maxBuffer: 1 << 20 },
      (err, stdout) => {
        if (err) reject(err);
        else resolve(String(stdout || ''));
      }
    );
  });
}

/** 读取本机硬件特征（Windows）。 */
async function detectTraits() {
  if (process.platform !== 'win32') {
    throw new Error(`暂不支持在 ${process.platform} 上取设备码`);
  }
  return parseTraitsOutput(await runPowerShell(POWERSHELL_TRAITS_SCRIPT));
}

/** 一步到位：算出本机设备码。 */
async function computeDeviceCode() {
  return deviceCodeFromTraits(await detectTraits());
}

module.exports = {
  CODE_PREFIX,
  MESSAGE_PREFIX,
  ALPHABET,
  normalizeDeviceCode,
  formatDeviceCode,
  deviceCodeFromTraits,
  buildMessage,
  issueLicense,
  verifyLicense,
  detectTraits,
  computeDeviceCode,
  parseTraitsOutput,
};
