// tick.test.js - 时间换算 / 舍入（design §6：0.0417↔tick、4 位小数边界、24fps 文件还原）
const assert = require('assert');
const T = require('../core/tick');

let passed = 0, failed = 0;
function test(name, fn) {
    try {
        fn();
        passed++;
        console.log(`  ✓ ${name}`);
    } catch (e) {
        failed++;
        console.log(`  ✗ ${name}\n    ${e.message}`);
    }
}

console.log('=== 1. tick 换算（20 tps） ===');
test('toTicks/toSec 整数 tick 往返', () => {
    assert.strictEqual(T.toTicks(0.05), 1);
    assert.strictEqual(T.toSec(1), 0.05);
    assert.strictEqual(T.toTicks(3), 60);
    assert.strictEqual(T.toSec(60), 3);
});
test('半 tick 四舍五入（0.875s = 17.5 tick → 18）', () => {
    assert.strictEqual(T.toTicks(0.875), 18);
    assert.strictEqual(T.toSec(T.toTicks(0.875)), 0.9);
});

console.log('=== 2. 4 位小数舍入 ===');
test('round4 基本值', () => {
    assert.strictEqual(T.round4(0.875), 0.875);
    assert.strictEqual(T.round4(3), 3);
    assert.strictEqual(T.round4(0.1 + 0.2), 0.3); // 浮点尾数归一
});
test('24fps 还原：1/24 → 0.0417，28/24 → 1.1667', () => {
    assert.strictEqual(T.round4(1 / 24), 0.0417);
    assert.strictEqual(T.round4(28 / 24), 1.1667);
});
test('formatTime 去尾零', () => {
    assert.strictEqual(T.formatTime(3), '3');
    assert.strictEqual(T.formatTime(0.875), '0.875');
    assert.strictEqual(T.formatTime(1 / 24), '0.0417');
    assert.strictEqual(T.formatTime(0.0417), '0.0417');
});
test('parseTime 合法/非法', () => {
    assert.strictEqual(T.parseTime('0.875'), 0.875);
    assert.strictEqual(T.parseTime(' 3 '), 3);
    assert.ok(Number.isNaN(T.parseTime('abc')));
    assert.ok(Number.isNaN(T.parseTime('')));
    assert.ok(Number.isNaN(T.parseTime('1.2.3')));
});

console.log('=== 3. 网格吸附 ===');
test('默认 1 tick 吸附', () => {
    assert.strictEqual(T.snapToGrid(0.049), 0.05);
    assert.strictEqual(T.snapToGrid(0.051), 0.05);
    assert.strictEqual(T.snapToGrid(0.076), 0.1); // 距 0.05=0.026 / 距 0.1=0.024
});
test('自定义网格（24fps 帧吸附：0.13s → 3 帧 = 0.1251）', () => {
    assert.strictEqual(T.snapToGrid(0.13, 0.0417), 0.1251);
});
test('grid<=0 只舍入不吸附', () => {
    assert.strictEqual(T.snapToGrid(0.041666, 0), T.round4(0.041666));
});

console.log('=== 4. 24fps 网格判定（还原诊断） ===');
test('24fps 网格上的值（0.875 = 21 帧、0.375 = 9 帧：20fps 值也可落在 24fps 网格）', () => {
    assert.strictEqual(T.isOn24fpsGrid(0.0417), true); // 1 帧
    assert.strictEqual(T.isOn24fpsGrid(1.1667), true); // 28 帧
    assert.strictEqual(T.isOn24fpsGrid(5.5), true);    // 132 帧
    assert.strictEqual(T.isOn24fpsGrid(0.875), true);  // 21 帧
    assert.strictEqual(T.isOn24fpsGrid(0.375), true);  // 9 帧
});
test('不在 24fps 网格上', () => {
    assert.strictEqual(T.isOn24fpsGrid(0.05), false);  // 1.2 帧
    assert.strictEqual(T.isOn24fpsGrid(2.2), false);   // 52.8 帧
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
