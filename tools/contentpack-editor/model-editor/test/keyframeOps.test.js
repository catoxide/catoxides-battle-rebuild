// keyframeOps.test.js - 关键帧增删改（auto-key 基础）
const assert = require('assert');
const K = require('../core/keyframeOps');
const A = require('../core/animation');
const M = require('../core/model');

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
const kf = (t, post, lerp = 'linear') => ({ timeSec: t, post, lerpMode: lerp });
const baseAnim = () => A.createAnimation('walk', 'once', [
    { bone: 'arm', rotation: [kf(0, [0, 0, 0]), kf(1, [0, 90, 0])] },
]);

console.log('=== 1. ensureKeyframe（auto-key 建帧）===');
test('无关键帧 → 新建（fallback 值）', () => {
    const { anim, created } = K.ensureKeyframe(baseAnim(), 'leg', 'position', 0.5, [1, 2, 3]);
    assert.strictEqual(created, true);
    const track = A.getTrack(anim, 'leg');
    assert.ok(track && track.position.length === 1);
    assert.deepStrictEqual(track.position[0].post, [1, 2, 3]);
});
test('同帧已有 → 不新建（幂等）', () => {
    const { anim, created } = K.ensureKeyframe(baseAnim(), 'arm', 'rotation', 0, [0, 0, 0]);
    assert.strictEqual(created, false);
    assert.strictEqual(A.getTrack(anim, 'arm').rotation.length, 2);
});
test('插入后升序 + 原动画不被修改（不可变）', () => {
    const src = baseAnim();
    const { anim } = K.ensureKeyframe(src, 'arm', 'rotation', 0.5, [0, 45, 0]);
    assert.deepStrictEqual(src.tracks[0].rotation.map((k) => k.timeSec), [0, 1]);
    assert.deepStrictEqual(A.getTrack(anim, 'arm').rotation.map((k) => k.timeSec), [0, 0.5, 1]);
});
test('新建后 durationSec 重算', () => {
    const { anim } = K.ensureKeyframe(baseAnim(), 'arm', 'rotation', 2, [0, 0, 0]);
    assert.strictEqual(anim.durationSec, 2);
});

console.log('=== 2. setKeyframeValue ===');
test('已有帧 → 改 post（不动 pre）', () => {
    const anim0 = A.createAnimation('x', 'once', [{ bone: 'a', rotation: [{ timeSec: 0, post: [0, 0, 0], lerpMode: 'linear', pre: [0, 9, 9] }] }]);
    const { anim, changed } = K.setKeyframeValue(anim0, 'a', 'rotation', 0, [5, 5, 5]);
    assert.strictEqual(changed, true);
    const k = A.getTrack(anim, 'a').rotation[0];
    assert.deepStrictEqual(k.post, [5, 5, 5]);
    assert.deepStrictEqual(k.pre, [0, 9, 9]);
});
test('无帧 + create=true → 新建；create=false → 不动', () => {
    assert.strictEqual(K.setKeyframeValue(baseAnim(), 'leg', 'scale', 0.5, [2, 2, 2]).created !== false, true);
    const r1 = K.setKeyframeValue(baseAnim(), 'leg', 'scale', 0.5, [2, 2, 2]);
    assert.strictEqual(r1.changed, true);
    assert.ok(A.getTrack(r1.anim, 'leg'));
    const r2 = K.setKeyframeValue(baseAnim(), 'leg', 'scale', 0.5, [2, 2, 2], false);
    assert.strictEqual(r2.changed, false);
    assert.strictEqual(A.getTrack(r2.anim, 'leg'), null);
});

console.log('=== 3. removeKeyframe / moveKeyframeTime ===');
test('删帧 → 空通道/空轨剔除', () => {
    const { anim, removed } = K.removeKeyframe(baseAnim(), 'arm', 'rotation', 0);
    assert.strictEqual(removed, true);
    const track = A.getTrack(anim, 'arm');
    assert.ok(track && track.rotation.length === 1);
    const { anim: a2 } = K.removeKeyframe(anim, 'arm', 'rotation', 1);
    assert.strictEqual(A.getTrack(a2, 'arm'), null);
    assert.strictEqual(a2.durationSec, 0);
});
test('移动跨邻键 → 重排升序', () => {
    const { anim, moved } = K.moveKeyframeTime(baseAnim(), 'arm', 'rotation', 0, 0.6);
    assert.strictEqual(moved, true);
    assert.deepStrictEqual(A.getTrack(anim, 'arm').rotation.map((k) => k.timeSec), [0.5, 0.6, 1].sort((a, b) => a - b).filter((t) => t !== 0.5));
    assert.strictEqual(anim.durationSec, 1);
});

console.log('=== 4. currentValue（fallback 来源）===');
test('有值 → 当前采样；无轨道 → fallback', () => {
    const v = K.currentValue(baseAnim(), 'arm', 'rotation', 0.5, [9, 9, 9]);
    assert.deepStrictEqual(v, [0, 45, 0]);
    const v2 = K.currentValue(baseAnim(), 'leg', 'position', 0.5, [1, 1, 1]);
    assert.deepStrictEqual(v2, [1, 1, 1]);
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
