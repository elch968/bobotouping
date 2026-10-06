'use strict';

// 发码工具：把朋友的设备码变成一条授权码。
//
// 用法（两种都行）：
//   交互式：  node tools/license/issue.js
//   带参数：  node tools/license/issue.js 设备码 "张三" 365
//             第三个参数是天数，不填或填 0 表示永久有效。
//
// 生成好的授权码会直接复制到剪贴板，微信发给对方、对方在电脑端粘贴即可。

const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { spawn } = require('child_process');

const core = require('../../desktop/src/main/license-core');

const ROOT = path.resolve(__dirname, '..', '..');
const KEY_DIR = path.join(ROOT, '.tools', 'license');
const PRIVATE_PATH = path.join(KEY_DIR, 'private.pem');
const PUBLIC_PATH = path.join(ROOT, 'desktop', 'src', 'main', 'license.pub');
const LOG_PATH = path.join(KEY_DIR, 'issued.log');

function die(message) {
  console.error('');
  console.error(message);
  process.exit(1);
}

function ask(rl, question, fallback) {
  return new Promise((resolve) => {
    rl.question(question, (answer) => {
      const text = String(answer || '').trim();
      resolve(text || fallback || '');
    });
  });
}

// 复制到剪贴板（Windows 自带 clip）。失败也不影响主流程。
function copyToClipboard(text) {
  return new Promise((resolve) => {
    try {
      const child = spawn('clip', { stdio: ['pipe', 'ignore', 'ignore'] });
      child.on('error', () => resolve(false));
      child.on('close', (code) => resolve(code === 0));
      child.stdin.on('error', () => resolve(false));
      child.stdin.end(text);
    } catch (_) {
      resolve(false);
    }
  });
}

async function main() {
  if (!fs.existsSync(PRIVATE_PATH)) {
    die('找不到私钥：' + PRIVATE_PATH + '\n请先双击 tools/license/初始化密钥.cmd 生成密钥。');
  }

  const args = process.argv.slice(2);
  let device = args[0] || '';
  let name = args[1] || '';
  let days = args[2] || '';

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  try {
    if (!device) {
      console.log('把对方电脑上显示的设备码填进来（形如 XXXX-XXXX-XXXX）。');
      device = await ask(rl, '设备码：');
    }
    if (!name) name = await ask(rl, '授权给谁（可留空，会显示在对方界面上）：');
    if (!days) days = await ask(rl, '有效天数（留空或 0 = 永久有效）：', '0');
  } finally {
    rl.close();
  }

  const normalized = core.normalizeDeviceCode(device);
  if (normalized.length !== 12) {
    die('设备码看起来不对（应为 12 位，实际 ' + normalized.length + ' 位）：' + device);
  }

  const dayCount = Number(days) || 0;
  const expiry = dayCount > 0 ? Math.floor(Date.now() / 1000) + Math.round(dayCount * 86400) : 0;

  const privateKey = fs.readFileSync(PRIVATE_PATH, 'utf8');
  const publicKey = fs.readFileSync(PUBLIC_PATH, 'utf8');

  const code = core.issueLicense({ device: normalized, name, expiry, privateKey });

  // 自己先验一遍，确保这份码对方一定能用（免得发出去才发现问题）
  const check = core.verifyLicense(code, normalized, publicKey);
  if (!check.ok) die('自检失败，这次的码没发出去：' + check.reason);

  const copied = await copyToClipboard(code);
  const expiryText =
    expiry > 0
      ? new Date(expiry * 1000).toLocaleDateString('zh-CN') + ' 到期（' + dayCount + ' 天）'
      : '永久有效';

  console.log('');
  console.log('----------------------------------------');
  console.log('设备码 ：' + core.formatDeviceCode(normalized));
  console.log('授权给 ：' + (name || '（未填写）'));
  console.log('有效期 ：' + expiryText);
  console.log('----------------------------------------');
  console.log(
    '授权码' + (copied ? '（已复制到剪贴板，直接粘贴发给对方即可）' : '（复制下面这一整段发给对方）') + '：'
  );
  console.log('');
  console.log(code);
  console.log('');

  try {
    fs.mkdirSync(KEY_DIR, { recursive: true });
    fs.appendFileSync(
      LOG_PATH,
      new Date().toISOString() +
        '\t' +
        core.formatDeviceCode(normalized) +
        '\t' +
        (name || '-') +
        '\t' +
        expiryText +
        '\n',
      'utf8'
    );
    console.log('已记一笔到：' + LOG_PATH);
  } catch (_) {
    // 记不上就算了，不影响发码
  }
}

main().catch((err) => die('发码失败：' + (err && err.message ? err.message : err)));
