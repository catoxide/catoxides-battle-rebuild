// browser.test.js - 证明 core 在无 Buffer/require/module 的浏览器式环境可跑（UI 依赖此路径）
// 用 node:vm 造一个只有 self 的沙箱加载 core，渲染结果与 Node 路径逐字节比对。
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const io = require('../io.js');
const NodeRender = require('../core/render.js');
const NodeImageOps = require('../core/imageops.js');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

const LIBDIR = path.join(__dirname, '../library/vanilla-1.21.1');
const elements = io.readJson(path.join(LIBDIR, 'elements.json'));
const preset = io.readJson(path.join(LIBDIR, 'presets/chest_54.json'));
const nodeLib = io.loadLibraryDir(LIBDIR);
const slotData = nodeLib.sprites.slot.data; // Node Buffer，传入沙箱作精灵源

// 沙箱：只有 self（无 Buffer / require / module）
const sandbox = { self: {}, console };
vm.createContext(sandbox);
for (const f of ['bytes.js', 'library.js', 'spec.js', 'render.js', 'imageops.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../core', f), 'utf8'), sandbox, { filename: f });
}
const S = sandbox.self;

console.log('=== 1. 沙箱 core 已挂载且无 Buffer ===');
test('self.Library/Spec/Render/ImageOps/Bytes 存在', () => {
    for (const k of ['Library', 'Spec', 'Render', 'ImageOps', 'Bytes']) assert.ok(S[k], '缺 ' + k);
});
test('沙箱内 Buffer 未定义', () => { assert.strictEqual(vm.runInContext('typeof Buffer', sandbox), 'undefined'); });

console.log('=== 2. 沙箱渲染 == Node 渲染（逐字节） ===');
const sprites = { slot: { width: 18, height: 18, data: slotData } };
const sandLib = S.Library.createLibrary(elements, sprites);
const libs = { vanilla: sandLib };
test('chest_54 沙箱渲染与 Node 逐字节一致', () => {
    S.Spec.validateSpec(preset, libs);
    const out = S.Render.render(preset, libs);
    const expect = NodeRender.render(preset, { vanilla: nodeLib });
    assert.strictEqual(out.constructor.name, 'Uint8Array', '沙箱输出应为 Uint8Array');
    assert.ok(!Buffer.isBuffer(out), '沙箱输出不应是 Node Buffer');
    assert.strictEqual(out.length, expect.length);
    for (let i = 0; i < out.length; i++) assert.strictEqual(out[i], expect[i], '像素差异 @' + i);
});
test('沙箱换色渲染与 Node 一致', () => {
    const rec = Object.assign({}, preset, { tokens: { body: '#FF0000' } });
    const out = S.Render.render(rec, libs);
    const expect = NodeRender.render(rec, { vanilla: nodeLib });
    for (let i = 0; i < out.length; i++) assert.strictEqual(out[i], expect[i]);
});

console.log('=== 3. 沙箱 imageops ===');
test('upscaleNearest 沙箱 == Node', () => {
    const src = S.Render.render(preset, libs);
    const a = S.ImageOps.upscaleNearest(src, 176, 222, 2);
    const b = NodeImageOps.upscaleNearest(NodeRender.render(preset, { vanilla: nodeLib }), 176, 222, 2);
    assert.strictEqual(a.width, 352); assert.strictEqual(a.height, 444);
    for (let i = 0; i < a.data.length; i++) assert.strictEqual(a.data[i], b.data[i]);
});
test('diff 沙箱 count 正确', () => {
    const a = S.Render.render(preset, libs);
    const b = S.Render.render(Object.assign({}, preset, { tokens: { body: '#FF0000' } }), libs);
    const d = S.ImageOps.diff(a, b, 176, 222);
    assert.ok(d.count > 0);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);