// chaining.test.js - 动画接续：帧搬运（A尾→B头 / B头→A尾）
const assert = require('assert');
const C = require('../core/chaining');
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

// 模型：arm 静态 rotation [10,0,0]、pivot [5,0,0]
const model = M.createGeometry({
    identifier: 't',
    bones: [M.createBone({ name: 'arm', rotation: [10, 0, 0], pivot: [5, 0, 0] })],
});

// 动画 A：0s→1s，rotation 增量 0 → [0,90,0]（绝对姿态 10° → 100°）
const animA = A.createAnimation('a', 'once', [
    { bone: 'arm', rotation: [kf(0, [0, 0, 0]), kf(1, [0, 90, 0])] },
    { bone: 'arm', position: [kf(0, [0, 0, 0]), kf(1, [3, 0, 0])] },
]);
// 动画 B：空
const animB = A.createAnimation('b', 'once', []);

console.log('=== 1. capturePoseAt ===');
test('末帧姿态（绝对值）：rotation = 静态+增量 = [10,90,0]；position = pivot+偏移 = [8,0,0]', () => {
    const pose = C.capturePoseAt(model, animA, 1);
    assert.deepStrictEqual(pose.arm.rotation, [10, 90, 0]);
    assert.deepStrictEqual(pose.arm.position, [8, 0, 0]);
});

console.log('=== 2. insertPoseAsKeyframesOf（增量还原）===');
test('A尾→B头：B 的 t=0 关键帧 = A 末帧增量（rotation [0,90,0] / position [3,0,0]）', () => {
    const { anim, written } = C.tailToHead(model, animA, model, animB);
    const track = A.getTrack(anim, 'arm');
    assert.deepStrictEqual(track.rotation[0].post, [0, 90, 0]);
    assert.deepStrictEqual(track.position[0].post, [3, 0, 0]);
    assert.deepStrictEqual(track.scale[0].post, [1, 1, 1]);
    assert.ok(written.includes('arm:rotation'));
});
test('B头→A尾：A 的 t=1 写入 B 首帧姿态（B 无轨道 → 静态姿态 → 增量全 0）', () => {
    const { anim, written } = C.headToTail(model, animA, model, animB);
    const k = A.getTrack(anim, 'arm').rotation.find((x) => x.timeSec === 1);
    assert.deepStrictEqual(k.post, [0, 0, 0]);
    assert.ok(written.length >= 3);
});
test('bones/channels 过滤 + 幽灵骨跳过', () => {
    const pose = { arm: { rotation: [0, 0, 0], position: [0, 0, 0], scale: [1, 1, 1] } };
    const r1 = C.insertPoseAsKeyframesOf(model, A.createAnimation('x', 'once', []), 0, pose, { channels: ['rotation'] });
    assert.deepStrictEqual(r1.written, ['arm:rotation']);
    const poseGhost = { ghost: { rotation: [1, 2, 3], position: [0, 0, 0], scale: [1, 1, 1] } };
    const r2 = C.insertPoseAsKeyframesOf(model, A.createAnimation('x', 'once', []), 0, poseGhost);
    assert.deepStrictEqual(r2.written, []);
});
test('t 处已有帧 → 覆盖 post（不新建）', () => {
    const target = A.createAnimation('x', 'once', [{ bone: 'arm', rotation: [kf(0, [0, 1, 1])] }]);
    const pose = { arm: { rotation: [20, 0, 0], position: [5, 0, 0], scale: [1, 1, 1] } };
    const { anim } = C.insertPoseAsKeyframesOf(model, target, 0, pose);
    assert.strictEqual(A.getTrack(anim, 'arm').rotation.length, 1);
    assert.deepStrictEqual(A.getTrack(anim, 'arm').rotation[0].post, [10, 0, 0]); // 20 - 静态 10
});

console.log('=== 3. 往返稳定性 ===');
test('搬运结果 normalize 后 duration 正确', () => {
    const { anim } = C.tailToHead(model, animA, model, animB);
    const norm = A.normalizeAnimation(anim);
    assert.strictEqual(norm.durationSec, 0); // B 只有 t=0 关键帧
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
