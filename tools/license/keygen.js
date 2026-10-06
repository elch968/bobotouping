'use strict';

// 生成授权用的密钥对（只需跑一次）。
//
// 用法：node tools/license/keygen.js
//      双击 tools/license/初始化密钥.cmd 也行。
//
// 产物：
//   .tools/license/private.pem      私钥 —— 只留在本机，绝不能提交，丢了就再也发不出新码
//   .tools/license/public.pem       公钥备份
//   desktop/src/main/license.pub    公钥（随客户端发布，需要提交进仓库）
//
// 客户端只能用公钥验签，所以公钥被人看到没有关系；
// 反过来私钥一旦泄漏，别人就能自己发授权码，整套机制就废了。

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const KEY_DIR = path.join(ROOT, '.tools', 'license');
const PRIVATE_PATH = path.join(KEY_DIR, 'private.pem');
const PUBLIC_BACKUP_PATH = path.join(KEY_DIR, 'public.pem');
const CLIENT_PUBLIC_PATH = path.join(ROOT, 'desktop', 'src', 'main', 'license.pub');

const force = process.argv.includes('--force');

if (fs.existsSync(PRIVATE_PATH) && !force) {
  console.error('私钥已经存在，没有覆盖：');
  console.error('  ' + PRIVATE_PATH);
  console.error('');
  console.error('注意：重新生成密钥会让「所有已经发出去的授权码」全部失效。');
  console.error('确实要换的话，加参数再跑一次： node tools/license/keygen.js --force');
  process.exit(1);
}

const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
const privatePem = privateKey.export({ type: 'pkcs8', format: 'pem' });
const publicPem = publicKey.export({ type: 'spki', format: 'pem' });

fs.mkdirSync(KEY_DIR, { recursive: true });
fs.writeFileSync(PRIVATE_PATH, privatePem, { mode: 0o600 });
fs.writeFileSync(PUBLIC_BACKUP_PATH, publicPem);
fs.mkdirSync(path.dirname(CLIENT_PUBLIC_PATH), { recursive: true });
fs.writeFileSync(CLIENT_PUBLIC_PATH, publicPem);

console.log('密钥已生成：');
console.log('  私钥（保密，请立刻复制一份到 U 盘或密码管理器）：');
console.log('    ' + PRIVATE_PATH);
console.log('  公钥（已写入客户端，需要随代码一起提交）：');
console.log('    ' + CLIENT_PUBLIC_PATH);
console.log('');
console.log('公钥内容（可以公开）：');
console.log(publicPem.trim());
