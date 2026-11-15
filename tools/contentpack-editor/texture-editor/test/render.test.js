// render.test.js - 渲染器集成单测：零覆盖逐字节复刻原版 + 换色隔离 + 确定性 + rect/stamp
// 对照原件取自原版 jar（唯一真源）。jar 缺失时响亮跳过（本机开发环境恒有 jar）。
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const JarReader = require('../../src/core/jarReader.js');
const PNG = require('../core/png.js');
const Library = require('../core/library.js');
const Spec = require('../core/spec.js');
const Render = require('../core/render.js');
const io = require('../io.js');

const REPO = path.resolve(__dirname, '../../../..');
const JAR = path.join(REPO, '.gradle/repositories/ng_dummy_ng/net/minecraft/client/1.21.1/client-1.21.1-client-extra.jar');
const LIBDIR = path.join(__dirname, '../library/vanilla-1.21.1');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

if (!fs.existsSync(JAR)) {
    console.log('SKIPPED: 原版 jar 不存在，逐字节复刻未验证 → ' + JAR);
    process.exit(0);
}

const arc = JarReader.openArchive(JAR);
const GUI = 'assets/minecraft/textures/gui';
const decodeJar = (name) => PNG.decode(arc.read(name));
const px = (im, x, y) => { const i = (y * im.width + x) * 4; return [im.data[i], im.data[i + 1], im.data[i + 2], im.data[i + 3]]; };
function bbox(im) {
    let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
    for (let y = 0; y < im.height; y++) for (let x = 0; x < im.width; x++) if (px(im, x, y)[3] > 0) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    return [x0, y0, x1, y1];
}
// 取面板区域成 W×H RGBA
function region(im, W, H) {
    const r = bbox(im); const out = Buffer.alloc(W * H * 4);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        const s = ((r[1] + y) * im.width + (r[0] + x)) * 4, d = (y * W + x) * 4;
        out[d] = im.data[s]; out[d + 1] = im.data[s + 1]; out[d + 2] = im.data[s + 2]; out[d + 3] = im.data[s + 3];
    }
    return out;
}

const lib = io.loadLibraryDir(LIBDIR);
const libs = { vanilla: lib };
const preset = io.readJson(path.join(LIBDIR, 'presets/chest_54.json'));
const renderSpec = (spec) => { Spec.validateSpec(spec, libs); return Render.render(spec, libs); };

console.log('=== 1. 零覆盖逐字节复刻 generic_54 ===');
const g54 = decodeJar(`${GUI}/container/generic_54.png`);
const target = region(g54, 176, 222);
const zero = renderSpec(preset);
test('chest_54 零覆盖 == generic_54 面板区（176×222 逐字节）', () => { assert.ok(zero.equals(target), '存在像素差异'); });
test('输出可 PNG 编码往返', () => { const dec = PNG.decode(PNG.encode(176, 222, zero)); assert.ok(dec.data.equals(zero)); });

console.log('=== 2. 确定性 ===');
test('同 spec 两次渲染逐字节相同', () => { assert.ok(renderSpec(preset).equals(renderSpec(preset))); });

console.log('=== 3. 换色隔离（仅改 tokens.body） ===');
test('改 body 后：原 body 像素变色，其余像素不变', () => {
    const recolored = renderSpec(Object.assign({}, preset, { tokens: { body: '#FF0000' } }));
    const bodyOld = [198, 198, 198, 255], bodyNew = [255, 0, 0, 255];
    let changed = 0;
    for (let i = 0; i < zero.length; i += 4) {
        const o = [zero[i], zero[i + 1], zero[i + 2], zero[i + 3]];
        const n = [recolored[i], recolored[i + 1], recolored[i + 2], recolored[i + 3]];
        const isBody = o[0] === bodyOld[0] && o[1] === bodyOld[1] && o[2] === bodyOld[2] && o[3] === bodyOld[3];
        if (isBody) { assert.deepStrictEqual(n, bodyNew, 'body 像素未变色 @' + i); changed++; }
        else assert.deepStrictEqual(n, o, '非 body 像素被改动 @' + i);
    }
    assert.ok(changed > 0, '没有任何 body 像素（测试无效）');
});

console.log('=== 4. 九宫格尺寸无关（hopper 176×133 边框环） ===');
test('176×133 面板边框环 == hopper 原件', () => {
    const hop = decodeJar(`${GUI}/container/hopper.png`);
    const spec = { schema: 1, canvas: [176, 133], layers: [{ id: 'p', type: 'nine_slice', ref: 'vanilla/panel', rect: [0, 0, 176, 133] }] };
    const out = renderSpec(spec);
    const C = lib.panel.cornerSize, W = 176, H = 133;
    const hr = bbox(hop);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        if (Math.min(x, W - 1 - x, y, H - 1 - y) >= C) continue;
        const g = ((hr[1] + y) * hop.width + (hr[0] + x)) * 4, d = (y * W + x) * 4;
        assert.ok(out[d] === hop.data[g] && out[d + 1] === hop.data[g + 1] && out[d + 2] === hop.data[g + 2] && out[d + 3] === hop.data[g + 3], '边框失配 @' + x + ',' + y);
    }
});

console.log('=== 5. rect / stamp 原语 ===');
test('rect 实心填充指定角色色', () => {
    const spec = { schema: 1, canvas: [10, 10], layers: [{ id: 'r', type: 'rect', rect: [2, 3, 4, 2], color: 'bevelDark' }] };
    const out = renderSpec(spec);
    const c = Library.hexToRGBA(lib.roles.bevelDark);
    for (let y = 3; y < 5; y++) for (let x = 2; x < 6; x++) { const i = (y * 10 + x) * 4; assert.deepStrictEqual([out[i], out[i + 1], out[i + 2], out[i + 3]], c); }
    assert.strictEqual(out[0], 0); // 其余透明
});
test('stamp 单次戳精灵', () => {
    const spec = { schema: 1, canvas: [40, 40], layers: [{ id: 's', type: 'stamp', stamp: 'vanilla/slot', at: [5, 6] }] };
    const out = renderSpec(spec);
    const spr = lib.sprites.slot;
    for (let j = 0; j < spr.height; j++) for (let i = 0; i < spr.width; i++) {
        const s = (j * spr.width + i) * 4, d = ((6 + j) * 40 + (5 + i)) * 4;
        assert.ok(out[d] === spr.data[s] && out[d + 3] === spr.data[s + 3], 'stamp 失配 @' + i + ',' + j);
    }
});
test('层序：后写覆盖先写', () => {
    const spec = { schema: 1, canvas: [10, 10], layers: [
        { id: 'a', type: 'rect', rect: [0, 0, 10, 10], color: 'body' },
        { id: 'b', type: 'rect', rect: [0, 0, 10, 10], color: 'border' },
    ] };
    const out = renderSpec(spec);
    assert.deepStrictEqual([out[0], out[1], out[2], out[3]], [0, 0, 0, 255]);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);