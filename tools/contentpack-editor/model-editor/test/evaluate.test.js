// evaluate.test.js - pose(t) 引擎语义（design/ANIMATION_TECH_STACK.md §1.2/1.3）：
// rotation 加法 / position 偏移 / scale 绝对值 / pre-post 控制点 / lerp_mode 属目标帧 /
// t<首帧=默认值 / MoLang 不应用 / loop 边界
const assert = require('assert');
const E = require('../core/evaluate');
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
const kf = (t, post, lerp = 'linear', pre = null) => pre == null
    ? { timeSec: t, post, lerpMode: lerp }
    : { timeSec: t, post, lerpMode: lerp, pre };
const near = (a, b, eps = 1e-9) => Math.abs(a - b) < eps;

const model = M.createGeometry({
    identifier: 't',
    bones: [
        M.createBone({ name: 'a', rotation: [10, 0, 0] }),          // 静态旋转 [10,0,0]（验加法）
        M.createBone({ name: 'b', pivot: [5, 5, 5], scale: [2, 2, 2] }), // 静态 pivot/scale（验偏移/绝对值）
        M.createBone({ name: 'shade', scale: [1, 1, 1] }),
        M.createBone({ name: 'static' }),                            // 无轨道骨
    ],
});

console.log('=== 1. 姿态合成语义（引擎真值）===');
test('rotation 加法：静态 [10,0,0] + 动画 [0,90,0] → [10,90,0]', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'a', rotation: [kf(0, [0, 90, 0])] }] };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.5).get('a').rotation, [10, 90, 0]);
});
test('position 偏移：静态 pivot [5,5,5] + 动画 [1,2,3] → [6,7,8]', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'b', position: [kf(0, [1, 2, 3])] }] };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.5).get('b').position, [6, 7, 8]);
});
test('scale 绝对值：静态 [2,2,2] + 动画 [1,1,1] → [1,1,1]（非叠加）', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'b', scale: [kf(0, [1, 1, 1])] }] };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.5).get('b').scale, [1, 1, 1]);
});
test('无轨道骨 = 静态 pose，但 scale = [1,1,1]（引擎：未被动画覆盖的骨 scale 压 1）', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'a', rotation: [kf(0, [0, 0, 0])] }] };
    const pose = E.evaluatePose(model, anim, 0);
    assert.deepStrictEqual(pose.get('b').scale, [1, 1, 1]);
    assert.deepStrictEqual(pose.get('static').rotation, [0, 0, 0]);
    assert.deepStrictEqual(pose.get('static').position, [0, 0, 0]);
});

console.log('=== 2. 通道采样边界（引擎 getPresentAnimValue）===');
test('t < 首关键帧 → 默认值（无贡献，非 pre-hold）', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 2, tracks: [{ bone: 'a', rotation: [kf(1, [0, 50, 0])] }] };
    // t=0 < 首帧 1 → 无贡献 → 静态 [10,0,0]
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0).get('a').rotation, [10, 0, 0]);
});
test('t >= 末关键帧 → 末帧 post（post-hold）', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'a', rotation: [kf(0, [0, 0, 0]), kf(1, [0, 100, 0])] }] };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 5).get('a').rotation, [10, 100, 0]);
});
test('单关键帧：t ≥ 0 恒为该值', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'a', rotation: [kf(0, [0, 90, 0])] }] };
    for (const t of [0, 0.5, 1, 5]) {
        assert.deepStrictEqual(E.evaluatePose(model, anim, t).get('a').rotation, [10, 90, 0]);
    }
});

console.log('=== 3. linear 与 pre（段终点 = pre_{i+1}）===');
test('两点 lerp 中点（pre 缺省 = post）', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'a', rotation: [kf(0, [0, 0, 0]), kf(1, [0, 90, 0])] }] };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.5).get('a').rotation, [10, 45, 0]);
});
test('pre ≠ post：段终点取 pre，关键帧处跳变（t→1⁻ = 50，t=1 = 100）', () => {
    const anim = {
        name: 'x', loop: 'once', durationSec: 1,
        tracks: [{ bone: 'a', rotation: [kf(0, [0, 0, 0]), kf(1, [0, 100, 0], 'linear', [0, 50, 0])] }],
    };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.5).get('a').rotation, [10, 25, 0]);
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.999999).get('a').rotation[1], 49.99995);
    assert.deepStrictEqual(E.evaluatePose(model, anim, 1).get('a').rotation, [10, 100, 0]);
});

console.log('=== 4. catmullrom 与 lerp_mode 属目标帧 ===');
// 全 catmullrom（pre=post）：控制点与旧端点自复制一致（回归基线）
const crAnim = {
    name: 'x', loop: 'once', durationSec: 3,
    tracks: [{ bone: 'a', rotation: [kf(0, [0, 0, 0], 'catmullrom'), kf(1, [0, 10, 0], 'catmullrom'), kf(2, [0, 30, 0], 'catmullrom'), kf(3, [0, 60, 0], 'catmullrom')] }],
};
test('中点值（手算）：t=0.5→3.75, t=1.5→18.75, t=2.5→46.25', () => {
    const p = (t) => E.evaluatePose(model, crAnim, t).get('a').rotation[1];
    assert.ok(near(p(0.5), 3.75), `t=0.5: ${p(0.5)}`);
    assert.ok(near(p(1.5), 18.75), `t=1.5: ${p(1.5)}`);
    assert.ok(near(p(2.5), 46.25), `t=2.5: ${p(2.5)}`);
});
test('端点精确 + C0 连续', () => {
    const p = (t) => E.evaluatePose(model, crAnim, t).get('a').rotation[1];
    assert.ok(near(p(1), 10));
    assert.ok(near(p(0.999999), p(1.000001), 1e-3));
});
test('lerp_mode 属**目标**关键帧：段 0→1 用 kf[1] 的 catmullrom（=20.3125@0.25，linear 会 25）', () => {
    const anim = {
        name: 'x', loop: 'once', durationSec: 1,
        tracks: [{ bone: 'a', rotation: [kf(0, [0, 0, 0], 'linear'), kf(1, [0, 100, 0], 'catmullrom')] }],
    };
    const p = (t) => E.evaluatePose(model, anim, t).get('a').rotation[1];
    assert.ok(near(p(0.25), 20.3125), `t=0.25: ${p(0.25)}`);
});
test('左帧 lerp_mode 不影响段：kf[0]=catmullrom, kf[1]=linear → 段走 linear（=50@0.5）', () => {
    const anim = {
        name: 'x', loop: 'once', durationSec: 1,
        tracks: [{ bone: 'a', rotation: [kf(0, [0, 0, 0], 'catmullrom'), kf(1, [0, 100, 0], 'linear')] }],
    };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.5).get('a').rotation, [10, 50, 0]);
});
test('catmullrom 用 pre 作控制点：段 1→2 的 p0 = kf[0].pre', () => {
    // kf[0] pre=[0,-10,0]（≠post 0）；段 1→2：p0=kf[0].pre=-10, p1=10, p2=30, p3=60
    const anim = {
        name: 'x', loop: 'once', durationSec: 3,
        tracks: [{ bone: 'a', rotation: [
            kf(0, [0, 0, 0], 'catmullrom', [0, -10, 0]),
            kf(1, [0, 10, 0], 'catmullrom'),
            kf(2, [0, 30, 0], 'catmullrom'),
            kf(3, [0, 60, 0], 'catmullrom'),
        ] }],
    };
    // catmullRom(-10,10,30,60,0.5) = 0.5*(2*10 + (10+30)*.5 + (-20-50+120-60)*.25 + (10+30-90+60)*.125)
    //   = 0.5*(20 + 20 + (-10)*.25 + 10*.125) = 0.5*(20+20-2.5+1.25) = 19.375
    const p = E.evaluatePose(model, anim, 1.5).get('a').rotation[1];
    assert.ok(near(p, 19.375), `t=1.5: ${p}`);
});

console.log('=== 5. loop 语义 ===');
test("'true' 取模回绕：t=1.5, duration=1 → t'=0.5", () => {
    assert.strictEqual(E.resolveLoopTime({ loop: 'true', durationSec: 1 }, 1.5), 0.5);
    const anim = { name: 'x', loop: 'true', durationSec: 1, tracks: [{ bone: 'a', rotation: [kf(0, [0, 0, 0]), kf(1, [0, 100, 0])] }] };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 1.5).get('a').rotation, [10, 50, 0]);
});
test("'once' / 'hold_on_last_frame' 夹到末帧", () => {
    assert.strictEqual(E.resolveLoopTime({ loop: 'once', durationSec: 1 }, 5), 1);
    assert.strictEqual(E.resolveLoopTime({ loop: 'hold_on_last_frame', durationSec: 1 }, 5), 1);
    assert.strictEqual(E.resolveLoopTime({ loop: 'once', durationSec: 1 }, -2), 0);
});
test("duration=0 的 'true' 不回绕", () => {
    assert.strictEqual(E.resolveLoopTime({ loop: 'true', durationSec: 0 }, 3), 0);
});

console.log('=== 6. MoLang / 幽灵骨 / scale 0 ===');
test('MoLang 字符串：v1 不应用（保持静态姿态）', () => {
    const anim = {
        name: 'x', loop: 'once', durationSec: 2,
        tracks: [{ bone: 'a', rotation: [{ timeSec: 0, post: ['1+1', '0', '0'], lerpMode: 'linear' }] }],
    };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.5).get('a').rotation, [10, 0, 0]);
});
test('标量通道值 = 三分量复制（引擎常量语义）', () => {
    const anim = {
        name: 'x', loop: 'once', durationSec: 2,
        tracks: [{ bone: 'b', position: [{ timeSec: 0, post: 0.5, lerpMode: 'linear' }, { timeSec: 1, post: 1, lerpMode: 'linear' }] }],
    };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.5).get('b').position, [5.75, 5.75, 5.75]);
});
test('scale [0,1,1]（shade 隐藏）原样透出', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'shade', scale: [kf(0, [0, 1, 1])] }] };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0.9).get('shade').scale, [0, 1, 1]);
});
test('幽灵骨骼：轨道存在但模型无此骨 → 忽略不报错', () => {
    const anim = { name: 'x', loop: 'once', durationSec: 1, tracks: [{ bone: 'ghost', rotation: [kf(0, [0, 0, 0])] }] };
    const pose = E.evaluatePose(model, anim, 0);
    assert.ok(!pose.has('ghost'));
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
