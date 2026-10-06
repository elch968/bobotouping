'use strict';

// 授权模块的纯 Node 测试（不需要 Electron、不需要真机）。
// 运行：node desktop/test/license.test.js
//
// 重点覆盖两件「错了就很难查」的事：
//   1. 设备码必须稳定：同样的硬件每次算出来要一样，换硬件要不一样
//   2. 授权码必须验签：任何字段被改动都要验不过，绑错设备也要拒绝

const assert = require('assert');
const crypto = require('crypto');

const core = require('../src/main/license-core');

const TESTS = [];
const test = (name, fn) => TESTS.push({ name, fn });

const pair = crypto.generateKeyPairSync('ed25519');
const publicKey = pair.publicKey.export({ type: 'spki', format: 'pem' });
const privateKey = pair.privateKey.export({ type: 'pkcs8', format: 'pem' });

const TRAITS_A = {
  uuid: '4C4C4544-004D-3410-8053-CAC04F4D5332',
  board: 'BOARD-A',
  bios: 'BIOS-A',
  cpu: 'BFEBFBFF000806EC',
};
const TRAITS_B = { ...TRAITS_A, uuid: '11111111-2222-3333-4444-555555555555' };

const DEVICE_A = core.deviceCodeFromTraits(TRAITS_A);
const DEVICE_B = core.deviceCodeFromTraits(TRAITS_B);

/* ------------------------------ 设备码 ------------------------------ */

test('设备码：同样的硬件特征每次算出来都一样', () => {
  assert.strictEqual(core.deviceCodeFromTraits({ ...TRAITS_A }), DEVICE_A);
});

test('设备码：换一台机器就是另一个码', () => {
  assert.notStrictEqual(DEVICE_A, DEVICE_B);
});

test('设备码：格式是 XXXX-XXXX-XXXX', () => {
  assert.match(DEVICE_A, /^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
});

test('设备码：全 F 的占位 UUID 会被当成没填，退回主板序列号', () => {
  const a = core.deviceCodeFromTraits({
    uuid: 'FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF',
    board: 'BOARD-A',
    bios: 'BIOS-A',
    cpu: 'CPU-A',
  });
  const b = core.deviceCodeFromTraits({
    uuid: '',
    board: 'BOARD-A',
    bios: 'BIOS-A',
    cpu: 'CPU-A',
  });
  assert.strictEqual(a, b);
});

test('设备码：完全读不到硬件信息时报错，而不是算出一个假码', () => {
  assert.throws(() => core.deviceCodeFromTraits({ uuid: '', board: '', bios: '', cpu: '' }));
});

test('设备码：手抄时把 O 写成 0、大小写、带不带横杠都能对上', () => {
  const raw = core.normalizeDeviceCode(DEVICE_A);
  assert.strictEqual(core.normalizeDeviceCode(DEVICE_A.toLowerCase()), raw);
  assert.strictEqual(core.normalizeDeviceCode(DEVICE_A.replace(/-/g, '')), raw);
  // UI 里显示的带横杠写法，也要能直接比对
  assert.strictEqual(core.normalizeDeviceCode(core.formatDeviceCode(DEVICE_A)), raw);
});

/* ------------------------------ 授权码 ------------------------------ */

function issue(overrides = {}) {
  return core.issueLicense({
    device: DEVICE_A,
    name: '张三',
    expiry: 0,
    privateKey,
    ...overrides,
  });
}

test('授权码：签发之后能验过', () => {
  const result = core.verifyLicense(issue(), DEVICE_A, publicKey);
  assert.strictEqual(result.ok, true, result.reason);
  assert.strictEqual(result.name, '张三');
  assert.strictEqual(result.expiry, 0);
});

test('授权码：带横杠、大写小写、夹着换行空格都能验过', () => {
  const code = issue();
  const messy = `  ${code.slice(0, 40)}\n${code.slice(40)}  `;
  assert.strictEqual(core.verifyLicense(messy, DEVICE_A, publicKey).ok, true);
});

test('授权码：复制到别人电脑上会被拒绝', () => {
  const result = core.verifyLicense(issue(), DEVICE_B, publicKey);
  assert.strictEqual(result.ok, false);
  assert.match(result.reason, /另一台电脑/);
});

test('授权码：改成别人的设备码会露馅（签名对不上）', () => {
  const code = issue();
  const body = JSON.parse(Buffer.from(code.slice(5), 'base64url').toString('utf8'));
  body.d = core.normalizeDeviceCode(DEVICE_B);
  const forged = `BOL1.${Buffer.from(JSON.stringify(body), 'utf8').toString('base64url')}`;
  const result = core.verifyLicense(forged, DEVICE_B, publicKey);
  assert.strictEqual(result.ok, false);
  assert.match(result.reason, /签名/);
});

test('授权码：过期之后会被拒绝，并说明到期日', () => {
  const past = Math.floor(Date.now() / 1000) - 86400;
  const result = core.verifyLicense(issue({ expiry: past }), DEVICE_A, publicKey);
  assert.strictEqual(result.ok, false);
  assert.strictEqual(result.expired, true);
  assert.match(result.reason, /到期/);
});

test('授权码：还没到期就能用', () => {
  const future = Math.floor(Date.now() / 1000) + 86400 * 30;
  assert.strictEqual(core.verifyLicense(issue({ expiry: future }), DEVICE_A, publicKey).ok, true);
});

test('授权码：换一把钥匙签的就验不过', () => {
  const other = crypto.generateKeyPairSync('ed25519');
  const otherPriv = other.privateKey.export({ type: 'pkcs8', format: 'pem' });
  const code = core.issueLicense({ device: DEVICE_A, name: '李四', expiry: 0, privateKey: otherPriv });
  assert.strictEqual(core.verifyLicense(code, DEVICE_A, publicKey).ok, false);
});

test('授权码：空串、乱码、别的前缀都有明确提示', () => {
  assert.match(core.verifyLicense('', DEVICE_A, publicKey).reason, /请先粘贴/);
  assert.match(core.verifyLicense('hello world', DEVICE_A, publicKey).reason, /BOL1/);
  assert.match(core.verifyLicense('BOL1.!!!!', DEVICE_A, publicKey).reason, /不完整/);
});

test('授权码：名字是空的也能正常激活', () => {
  const result = core.verifyLicense(issue({ name: '' }), DEVICE_A, publicKey);
  assert.strictEqual(result.ok, true, result.reason);
  assert.strictEqual(result.name, '');
});

test('授权码：中文名字能原样带出来', () => {
  const result = core.verifyLicense(issue({ name: '王小明（公司）' }), DEVICE_A, publicKey);
  assert.strictEqual(result.name, '王小明（公司）');
});

test('授权码：解析 PowerShell 输出', () => {
  const traits = core.parseTraitsOutput('UUID=ABC\r\nBOARD=B1\r\nBIOS=B2\r\nCPU=C1\r\n');
  assert.deepStrictEqual(traits, { uuid: 'ABC', board: 'B1', bios: 'B2', cpu: 'C1' });
});

/* ------------------------------ 本机 ------------------------------ */

test('本机：能读出设备码（Windows）', async function () {
  if (process.platform !== 'win32') return;
  const code = await core.computeDeviceCode();
  assert.match(code, /^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
  // 再算一次必须一模一样
  assert.strictEqual(await core.computeDeviceCode(), code);
});

(async () => {
  let failed = 0;
  for (const item of TESTS) {
    try {
      await item.fn();
      console.log('  ok   ' + item.name);
    } catch (err) {
      failed += 1;
      console.log('  FAIL ' + item.name);
      console.log('       ' + (err && err.message ? err.message : err));
    }
  }
  console.log('');
  console.log(failed === 0 ? `全部通过（${TESTS.length} 项）` : `${failed}/${TESTS.length} 项失败`);
  process.exit(failed === 0 ? 0 : 1);
})();
