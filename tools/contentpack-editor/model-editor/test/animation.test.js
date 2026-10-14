// animation.test.js - 动画数据模型 / 关键帧操作（design §6：排序/去重/空轨剔除/同时刻冲突）
const assert = require('assert');
const A = require('../core/animation');

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

console.log('=== 1. 构造器 ===');
test('createAnimation：durationSec = max 关键帧时间', () => {
    const anim = A.createAnimation('x', 'once', [
        { bone: 'a', rotation: [kf(0, [0, 0, 0]), kf(0.5, [0, 90, 0])], position: [kf(2, [1, 2, 3])], scale: [] },
    ]);
    assert.strictEqual(anim.durationSec, 2);
});
test('createKeyframe 拒绝负时间 / 坏 post / 非字符串 lerp', () => {
    assert.throws(() => A.createKeyframe(-1, [0, 0, 0]), /时间/);
    assert.throws(() => A.createKeyframe(0, [0, 0]), /post/);
    assert.throws(() => A.createKeyframe(0, [0, 0, 0], null), /lerpMode/);
});
test('W7：未知 lerp_mode 允许（透传保全；validate 警告，导出拦截）', () => {
    const k = A.createKeyframe(0, [0, 0, 0], 'smoothstep');
    assert.strictEqual(k.lerpMode, 'smoothstep');
});
test('W7：pre 合法值可建（vec3 / MoLang 字符串）；坏 pre 拒绝', () => {
    const k = A.createKeyframe(0, [0, 0, 0], 'linear', [1, 2, 3]);
    assert.deepStrictEqual(k.pre, [1, 2, 3]);
    const k2 = A.createKeyframe(0, [0, 0, 0], 'linear', 'query.anim_time()'); // MoLang 字符串 = 合法
    assert.strictEqual(k2.pre, 'query.anim_time()');
    assert.throws(() => A.createKeyframe(0, [0, 0, 0], 'linear', [1]), /pre/);
});
test('createAnimation 拒绝坏 loop / 无名', () => {
    assert.throws(() => A.createAnimation('x', 'loop'), /loop/);
    assert.throws(() => A.createAnimation(''), /名/);
});
test('MoLang 字符串 post 合法', () => {
    const k = A.createKeyframe(0, 'entity.pos_x()');
    assert.strictEqual(k.post, 'entity.pos_x()');
});

console.log('=== 2. normalize：排序 / 去重 / 空轨剔除 ===');
test('乱序关键帧 → 升序', () => {
    const anim = A.createAnimation('x', 'once', [
        { bone: 'a', rotation: [kf(1, [0, 0, 1]), kf(0, [0, 0, 0]), kf(0.5, [0, 0, 0.5])] },
    ]);
    const n = A.normalizeAnimation(anim);
    assert.deepStrictEqual(n.tracks[0].rotation.map((k) => k.timeSec), [0, 0.5, 1]);
});
test('同时刻去重 → 保留最后一个（后写覆盖）', () => {
    const anim = A.createAnimation('x', 'once', [
        { bone: 'a', rotation: [kf(0.5, [1, 1, 1]), kf(0.5, [2, 2, 2])] },
    ]);
    const n = A.normalizeAnimation(anim);
    assert.strictEqual(n.tracks[0].rotation.length, 1);
    assert.deepStrictEqual(n.tracks[0].rotation[0].post, [2, 2, 2]);
});
test('空通道 / 空轨剔除', () => {
    const anim = A.createAnimation('x', 'once', [
        { bone: 'a', rotation: [kf(0, [0, 0, 0])], position: [] },
        { bone: 'b' },
    ]);
    const n = A.normalizeAnimation(anim);
    assert.strictEqual(n.tracks.length, 1);
    assert.strictEqual(n.tracks[0].bone, 'a');
    assert.strictEqual(n.tracks[0].position, undefined);
});
test('durationSec 重算', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 99, tracks: [{ bone: 'a', rotation: [kf(1.25, [0, 0, 0])] }] };
    const n = A.normalizeAnimation(anim);
    assert.strictEqual(n.durationSec, 1.25);
});
test('normalize 不修改原对象', () => {
    const anim = A.createAnimation('x', 'once', [{ bone: 'a', rotation: [kf(1, [0, 0, 1]), kf(0, [0, 0, 0])] }]);
    A.normalizeAnimation(anim);
    assert.deepStrictEqual(anim.tracks[0].rotation.map((k) => k.timeSec), [1, 0]);
});

console.log('=== 3. validate ===');
test('合法动画零错误零警告', () => {
    const anim = A.createAnimation('x', 'hold_on_last_frame', [
        { bone: 'a', rotation: [kf(0, [0, 0, 0]), kf(1, [0, 90, 0])] },
    ]);
    const { errors, warnings } = A.validateAnimation(anim);
    assert.deepStrictEqual(errors, []);
    assert.deepStrictEqual(warnings, []);
});
test('时间非单调 → 错误', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 2, tracks: [{ bone: 'a', rotation: [kf(1, [0, 0, 0]), kf(0.5, [0, 0, 0])] }] };
    const { errors } = A.validateAnimation(anim);
    assert.ok(errors.some((e) => e.includes('非单调')));
});
test('同骨多条轨道 → 错误', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'a', rotation: [kf(0, [0, 0, 0])] }, { bone: 'a', position: [kf(0, [0, 0, 0])] }] };
    const { errors } = A.validateAnimation(anim);
    assert.ok(errors.some((e) => e.includes('多条轨道')));
});
test('坏 loop → 错误；W7：未知 lerp_mode → 警告（不再是错误）', () => {
    const bad = { name: 'x', loop: 'forever', durationSec: 0, tracks: [{ bone: 'a', rotation: [{ timeSec: 0, post: [0, 0, 0], lerpMode: 'stepped' }] }] };
    const { errors, warnings } = A.validateAnimation(bad);
    assert.ok(errors.some((e) => e.includes('loop')));
    assert.ok(!errors.some((e) => e.includes('lerp_mode')));
    assert.ok(warnings.some((w) => w.includes('stepped')));
});
test('W7：pre 非法 → 错误', () => {
    const bad = { name: 'x', loop: 'once', durationSec: 0, tracks: [{ bone: 'a', rotation: [{ timeSec: 0, post: [0, 0, 0], pre: [1], lerpMode: 'linear' }] }] };
    const { errors } = A.validateAnimation(bad);
    assert.ok(errors.some((e) => e.includes('pre')));
});
test('W7：timeline 归一（同时刻合并脚本 + 升序）', () => {
    const tl = A.normalizeTimeline([
        { timeSec: 1, scripts: ['a()'] },
        { timeSec: 0.5, scripts: ['b()'] },
        { timeSec: 1, scripts: ['a()'] },
        { timeSec: 1, scripts: 'c()' },
    ]);
    assert.deepStrictEqual(tl, [
        { timeSec: 0.5, scripts: ['b()'] },
        { timeSec: 1, scripts: ['a()', 'c()'] },
    ]);
});
test('W7：timeline 非法 → 错误', () => {
    const bad = { name: 'x', loop: 'once', durationSec: 0, tracks: [], timeline: [{ timeSec: -1, scripts: ['a()'] }] };
    const { errors } = A.validateAnimation(bad);
    assert.ok(errors.some((e) => e.includes('timeline')));
});
test('骨骼不在模型中 → 警告（Spark 容错）', () => {
    const anim = A.createAnimation('x', 'once', [{ bone: 'ghost', rotation: [kf(0, [0, 0, 0])] }]);
    const { errors, warnings } = A.validateAnimation(anim, ['a', 'b']);
    assert.deepStrictEqual(errors, []);
    assert.ok(warnings.some((w) => w.includes('ghost')));
});
test('durationSec 与 max 不一致 → 警告', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 5, tracks: [{ bone: 'a', rotation: [kf(1, [0, 0, 0])] }] };
    const { warnings } = A.validateAnimation(anim);
    assert.ok(warnings.some((w) => w.includes('durationSec')));
});
test('MoLang → 警告（v1 只读）', () => {
    const anim = A.createAnimation('x', 'once', [{ bone: 'a', rotation: [kf(0, '1+1')] }]);
    const { warnings } = A.validateAnimation(anim);
    assert.ok(warnings.some((w) => w.includes('MoLang')));
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
