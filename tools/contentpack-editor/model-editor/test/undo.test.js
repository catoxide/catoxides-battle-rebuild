// undo.test.js - 快照式 undo/redo（design D1）
const assert = require('assert');
const U = require('../core/undo');

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

const doc = (n) => ({ model: { identifier: 'x', bones: [] }, n });

console.log('=== 1. 基础 push/undo/redo ===');
test('初始不可撤销/重做', () => {
    const s = U.createUndoStack(doc(0));
    assert.strictEqual(U.canUndo(s), false);
    assert.strictEqual(U.canRedo(s), false);
    assert.strictEqual(U.undo(s), null);
});
test('push → undo 回退 → redo 前进', () => {
    const s = U.createUndoStack(doc(0));
    U.push(s, doc(1), 'edit 1');
    U.push(s, doc(2), 'edit 2');
    assert.strictEqual(s.present.n, 2);
    const back = U.undo(s);
    assert.strictEqual(back.n, 1);
    assert.strictEqual(s.present.n, 1);
    const fwd = U.redo(s);
    assert.strictEqual(fwd.n, 2);
    assert.strictEqual(U.canRedo(s), false);
});
test('新 push 清空 redo 栈', () => {
    const s = U.createUndoStack(doc(0));
    U.push(s, doc(1));
    U.undo(s);
    U.push(s, doc(9));
    assert.strictEqual(U.canRedo(s), false);
    assert.strictEqual(s.present.n, 9);
});

console.log('=== 2. 幂等 / 隔离 / 上限 ===');
test('相同状态 push 不生效（返回 false）', () => {
    const s = U.createUndoStack(doc(0));
    assert.strictEqual(U.push(s, doc(0)), false);
    assert.strictEqual(U.canUndo(s), false);
});
test('快照隔离：push 后改源对象不影响栈内快照', () => {
    const s = U.createUndoStack(doc(0));
    const v1 = doc(1);
    U.push(s, v1);
    v1.n = 999; // 改传入的源对象（push 存的是克隆）
    U.push(s, doc(2));
    const back = U.undo(s);
    assert.strictEqual(back.n, 1); // 栈里的 v1 快照仍是 1
});
test('push 克隆 present：后续改源不影响栈', () => {
    const s = U.createUndoStack(doc(0));
    const next = doc(1);
    U.push(s, next);
    next.n = 999; // 改源对象
    assert.strictEqual(s.present.n, 1);
});
test('快照上限：超出丢最旧', () => {
    const s = U.createUndoStack(doc(0), 3);
    U.push(s, doc(1));
    U.push(s, doc(2));
    U.push(s, doc(3));
    U.push(s, doc(4)); // past 满 3 个 → 丢 doc(0)
    assert.strictEqual(s.past.length, 3);
    U.undo(s); U.undo(s); U.undo(s);
    assert.strictEqual(s.present.n, 1); // 最旧可回到的是 1
    assert.strictEqual(U.canUndo(s), false);
});
test('clear 清空历史但保留 present', () => {
    const s = U.createUndoStack(doc(0));
    U.push(s, doc(1));
    U.clear(s);
    assert.strictEqual(U.canUndo(s), false);
    assert.strictEqual(s.present.n, 1);
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
