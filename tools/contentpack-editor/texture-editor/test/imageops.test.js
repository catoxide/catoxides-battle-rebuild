// imageops.test.js - nearest 放大 + diff 单测（自包含，无外部 fixture）
const assert = require('assert');
const ImageOps = require('../core/imageops');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}
const solid = (w, h, c) => { const b = Buffer.alloc(w * h * 4); for (let i = 0; i < b.length; i += 4) { b[i] = c[0]; b[i + 1] = c[1]; b[i + 2] = c[2]; b[i + 3] = c[3]; } return b; };

console.log('=== 1. upscaleNearest ===');
test('2× 放大尺寸与块复制', () => {
    const src = Buffer.from([1, 2, 3, 255, 4, 5, 6, 255]); // 2x1
    const up = ImageOps.upscaleNearest(src, 2, 1, 2);
    assert.strictEqual(up.width, 4); assert.strictEqual(up.height, 2);
    for (let y = 0; y < 2; y++) for (let x = 0; x < 4; x++) {
        const i = (y * 4 + x) * 4, s = (x < 2 ? 0 : 4);
        assert.deepStrictEqual([up.data[i], up.data[i + 1], up.data[i + 2], up.data[i + 3]], [src[s], src[s + 1], src[s + 2], src[s + 3]]);
    }
});
test('1× 恒等', () => { const src = solid(3, 3, [9, 9, 9, 255]); const up = ImageOps.upscaleNearest(src, 3, 3, 1); assert.ok(up.data.equals(src)); });
test('scale<1 抛错', () => { assert.throws(() => ImageOps.upscaleNearest(solid(1, 1, [0, 0, 0, 0]), 1, 1, 0)); });
test('长度不符抛错', () => { assert.throws(() => ImageOps.upscaleNearest(Buffer.alloc(3), 2, 2, 2)); });

console.log('=== 2. diff ===');
test('相同图 count=0', () => { const a = solid(4, 4, [1, 2, 3, 255]); const d = ImageOps.diff(a, a, 4, 4); assert.strictEqual(d.count, 0); assert.ok(d.highlight.equals(a)); });
test('单像素差异定位 + 品红高亮', () => {
    const a = solid(4, 4, [1, 2, 3, 255]); const b = Buffer.from(a); const i = (2 * 4 + 1) * 4; b[i] = 9;
    const d = ImageOps.diff(a, b, 4, 4);
    assert.strictEqual(d.count, 1); assert.deepStrictEqual(d.pixels, [[1, 2]]);
    assert.deepStrictEqual([d.highlight[i], d.highlight[i + 1], d.highlight[i + 2], d.highlight[i + 3]], [255, 0, 255, 255]);
});
test('alpha 差异也算', () => { const a = solid(2, 2, [0, 0, 0, 255]); const b = Buffer.from(a); b[3] = 0; assert.strictEqual(ImageOps.diff(a, b, 2, 2).count, 1); });
test('尺寸不符抛错', () => { assert.throws(() => ImageOps.diff(Buffer.alloc(16), Buffer.alloc(12), 2, 2)); });

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);