// png.test.js - PNG 编解码单测（自包含，无外部 fixture）
const assert = require('assert');
const PNG = require('../core/png');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

console.log('=== 1. CRC32 ===');
test('crc32("123456789") == 0xCBF43926', () => {
    assert.strictEqual(PNG.crc32(Buffer.from('123456789', 'ascii')), 0xCBF43926);
});

console.log('=== 2. encode→decode 往返逐字节 ===');
function rgbaBuf(w, h, seed) {
    const b = Buffer.alloc(w * h * 4);
    let s = seed >>> 0;
    for (let i = 0; i < b.length; i++) { s = (s * 1103515245 + 12345) >>> 0; b[i] = (s >>> 16) & 255; }
    return b;
}
for (const [w, h] of [[1, 1], [2, 3], [18, 18], [176, 222], [7, 5]]) {
    test(`往返 ${w}x${h} 逐字节相同`, () => {
        const src = rgbaBuf(w, h, w * 31 + h);
        const dec = PNG.decode(PNG.encode(w, h, src));
        assert.strictEqual(dec.width, w);
        assert.strictEqual(dec.height, h);
        assert.ok(dec.data.equals(src), 'RGBA 不一致');
    });
}
test('全透明往返', () => {
    const src = Buffer.alloc(4 * 4 * 4, 0);
    const dec = PNG.decode(PNG.encode(4, 4, src));
    assert.ok(dec.data.equals(src));
});

console.log('=== 3. 确定性 ===');
test('同输入两次 encode 逐字节相同', () => {
    const src = rgbaBuf(32, 16, 7);
    assert.ok(PNG.encode(32, 16, src).equals(PNG.encode(32, 16, src)));
});

console.log('=== 4. 错误路径 ===');
test('非法签名抛错', () => { assert.throws(() => PNG.decode(Buffer.from('not a png'))); });
test('RGBA 长度不符抛错', () => { assert.throws(() => PNG.encode(2, 2, Buffer.alloc(3))); });

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);