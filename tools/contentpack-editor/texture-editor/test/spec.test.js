// spec.test.js - spec 解析/校验 + 元素库校验单测（自包含微型库，无 jar 依赖）
const assert = require('assert');
const Library = require('../core/library');
const Spec = require('../core/spec');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

// 微型合法库：cornerSize=1，1×1 精灵
const elements = {
    schema: 1,
    roles: { transparent: null, border: '#000000', body: '#C6C6C6' },
    panel: {
        symbols: { '.': 'transparent', '#': 'border', 'B': 'body' },
        cornerSize: 1,
        corners: { TL: ['#'], TR: ['#'], BL: ['#'], BR: ['#'] },
        edges: { top: ['#'], bottom: ['#'], left: ['#'], right: ['#'] },
        center: 'B',
    },
    sprites: { dot: { file: 'x.png', size: [1, 1] } },
};
const sprites = { dot: { width: 1, height: 1, data: Buffer.from([255, 0, 0, 255]) } };
const lib = Library.createLibrary(elements, sprites);
const libs = { vanilla: lib };
const base = () => ({ schema: 1, canvas: [10, 10], refs: { vanilla: 'x' }, layers: [] });
const ok = (spec) => Spec.validateSpec(Spec.parseSpec(spec), libs);
const bad = (spec) => assert.throws(() => ok(spec));

console.log('=== 1. parseSpec 基本形状 ===');
test('合法对象通过', () => { assert.ok(Spec.parseSpec(base())); });
test('JSON 字符串可解析', () => { assert.ok(Spec.parseSpec(JSON.stringify(base()))); });
test('坏 JSON 抛错', () => { assert.throws(() => Spec.parseSpec('{bad')); });
test('schema!=1 抛错', () => { assert.throws(() => Spec.parseSpec({ schema: 2, canvas: [1, 1], layers: [] })); });
test('canvas 非正整数抛错', () => { assert.throws(() => Spec.parseSpec({ schema: 1, canvas: [0, 5], layers: [] })); });

console.log('=== 2. nine_slice ===');
test('合法九宫格通过', () => { ok(Object.assign(base(), { layers: [{ id: 'p', type: 'nine_slice', ref: 'vanilla/panel', rect: [0, 0, 10, 10] }] })); });
test('rect 越界抛错', () => { bad(Object.assign(base(), { layers: [{ id: 'p', type: 'nine_slice', ref: 'vanilla/panel', rect: [5, 5, 10, 10] }] })); });
test('rect 太小（C*2>=min）抛错', () => { bad(Object.assign(base(), { layers: [{ id: 'p', type: 'nine_slice', ref: 'vanilla/panel', rect: [0, 0, 2, 10] }] })); });
test('ref 非 panel 抛错', () => { bad(Object.assign(base(), { layers: [{ id: 'p', type: 'nine_slice', ref: 'vanilla/dot', rect: [0, 0, 10, 10] }] })); });
test('未知库抛错', () => { bad(Object.assign(base(), { layers: [{ id: 'p', type: 'nine_slice', ref: 'nope/panel', rect: [0, 0, 10, 10] }] })); });

console.log('=== 3. grid ===');
const grid = (o) => Object.assign(base(), { layers: [Object.assign({ id: 'g', type: 'grid', stamp: 'vanilla/dot', origin: [0, 0], pitch: 1, cols: 2, rows: 2 }, o)] });
test('合法格点通过', () => { ok(grid()); });
test('pitch<精灵尺寸抛错', () => { const e2 = JSON.parse(JSON.stringify(elements)); e2.sprites.dot.size = [2, 2]; const l2 = Library.createLibrary(e2, { dot: { width: 2, height: 2, data: Buffer.alloc(16) } }); assert.throws(() => Spec.validateSpec(grid(), { vanilla: l2 })); });
test('cols<1 抛错', () => { bad(grid({ cols: 0 })); });
test('rows<1 抛错', () => { bad(grid({ rows: 0 })); });
test('格点越界抛错', () => { bad(grid({ origin: [9, 9], cols: 2, rows: 2 })); });
test('未知精灵抛错', () => { bad(grid({ stamp: 'vanilla/missing' })); });

console.log('=== 4. rect / stamp ===');
test('合法 rect 通过', () => { ok(Object.assign(base(), { layers: [{ id: 'r', type: 'rect', rect: [1, 1, 3, 3], color: 'body' }] })); });
test('rect 未知角色抛错', () => { bad(Object.assign(base(), { layers: [{ id: 'r', type: 'rect', rect: [1, 1, 3, 3], color: 'nope' }] })); });
test('rect 越界抛错', () => { bad(Object.assign(base(), { layers: [{ id: 'r', type: 'rect', rect: [8, 8, 5, 5], color: 'body' }] })); });
test('合法 stamp 通过', () => { ok(Object.assign(base(), { layers: [{ id: 's', type: 'stamp', stamp: 'vanilla/dot', at: [5, 5] }] })); });
test('stamp 越界抛错', () => { bad(Object.assign(base(), { layers: [{ id: 's', type: 'stamp', stamp: 'vanilla/dot', at: [10, 10] }] })); });

console.log('=== 5. tokens / id / type ===');
test('合法 token 覆盖通过', () => { ok(Object.assign(base(), { tokens: { body: '#123456' }, layers: [{ id: 'p', type: 'nine_slice', ref: 'vanilla/panel', rect: [0, 0, 10, 10] }] })); });
test('未知 token 抛错', () => { bad(Object.assign(base(), { tokens: { nope: '#123456' } })); });
test('token 非法颜色抛错', () => { bad(Object.assign(base(), { tokens: { body: 'red' } })); });
test('未知 type 抛错', () => { bad(Object.assign(base(), { layers: [{ id: 'x', type: 'polygon' }] })); });
test('缺 id 抛错', () => { bad(Object.assign(base(), { layers: [{ type: 'rect', rect: [0, 0, 1, 1], color: 'body' }] })); });
test('id 重复抛错', () => { bad(Object.assign(base(), { layers: [{ id: 'a', type: 'rect', rect: [0, 0, 1, 1], color: 'body' }, { id: 'a', type: 'rect', rect: [1, 1, 1, 1], color: 'body' }] })); });

console.log('=== 6. library 校验 ===');
test('坏 schema 抛错', () => { assert.throws(() => Library.createLibrary({ schema: 9 }, {})); });
test('角行长度不符抛错', () => { const e2 = JSON.parse(JSON.stringify(elements)); e2.panel.corners.TL = ['##']; assert.throws(() => Library.createLibrary(e2, sprites)); });
test('未定义符号抛错', () => { const e2 = JSON.parse(JSON.stringify(elements)); e2.panel.center = 'Z'; assert.throws(() => Library.createLibrary(e2, sprites)); });
test('符号指向未知角色抛错', () => { const e2 = JSON.parse(JSON.stringify(elements)); e2.panel.symbols['#'] = 'ghost'; assert.throws(() => Library.createLibrary(e2, sprites)); });
test('effectiveRoles 覆盖生效', () => { const r = Library.effectiveRoles(lib, { body: '#ABCDEF' }); assert.strictEqual(r.body, '#ABCDEF'); assert.strictEqual(r.border, '#000000'); });
test('effectiveRoles 未知角色抛错', () => { assert.throws(() => Library.effectiveRoles(lib, { ghost: '#000000' })); });
test('symbolColorTable 解析颜色', () => { const t = Library.symbolColorTable(lib, {}); assert.deepStrictEqual(t['B'], [198, 198, 198, 255]); assert.deepStrictEqual(t['.'], [0, 0, 0, 0]); });
test('symbolColorTable 应用 tokens', () => { const t = Library.symbolColorTable(lib, { body: '#010203' }); assert.deepStrictEqual(t['B'], [1, 2, 3, 255]); });

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);