// evaluate.js - pose(t)：给定动画与时间 t，求每根骨骼的姿态
// 纯函数，node 可测。W4 预览播放的核心；语义以 spark_core 引擎源码为基准
// （design/ANIMATION_TECH_STACK.md §1.2/1.3，2026-10-12 源码定案）：
//
// 姿态合成（BonePose.getLocalTransformMatrix）：
//   rotation = 静态旋转 + 动画增量（逐轴加法，度）   ← 非"绝对替换"（静态为 0 时等价）
//   position = 静态 pivot + 动画偏移（编辑器 px 空间；引擎侧再 ÷16 转格 + x 镜像）
//   scale    = 动画值（绝对值；无轨道 = [1,1,1]，非静态 scale —— 引擎行为，预览对齐）
//
// 通道采样（OBoneAnimation.getPresentAnimValue + InterpolationType.lerp）：
//   - t < 首关键帧 → 通道默认值（无贡献：rot 增量 0 / pos 偏移 0 / scale 1）
//   - t >= 末关键帧 → 末关键帧 post
//   - 段 [i, i+1] 的插值模式 = **目标关键帧 kf[i+1] 的 lerp_mode**（引擎 kTarget.value.interpolation）
//   - LINEAR：post_i → **pre_{i+1}**（pre 缺省 = post）
//   - CATMULLROM：控制点 pre_{i-1}, post_i, pre_{i+1}, post_{i+2}（索引钳制，非端点复制）
//   - pre≠post = 关键帧处跳变（线性段在 i+1 处落到 pre_{i+1}，然后跳到 post_{i+1}）
//   - MoLang 值（整值字符串 / 含字符串分量）：不插值，返回 ≤t 的最近关键帧值（v1 不求值）
//   - scale [0,1,1] = 隐藏（shade 约定，evaluate 不特殊处理，渲染层负责）

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.Evaluate）
(function (globalScope) {
const Model = (typeof module === 'object' && module.exports) ? require('./model') : globalScope.Model;
const { vec3 } = Model;

/**
 * 按 loop 语义求有效时间。
 * @param {Object} anim { durationSec }
 * @param {number} tSec
 */
function resolveLoopTime(anim, tSec) {
    const dur = anim && anim.durationSec > 0 ? anim.durationSec : 0;
    if (!Number.isFinite(tSec) || tSec <= 0) return 0;
    const loop = anim && anim.loop;
    if (loop === 'true' && dur > 0) {
        return tSec % dur;
    }
    // once / hold_on_last_frame / 缺省：夹住
    return dur > 0 ? Math.min(tSec, dur) : 0;
}

/** 单轴 Catmull-Rom（均匀参数）。p0/p1/p2/p3 为四个控制点标量，u∈[0,1]。
 *  与引擎 Mth.catmullrom(progress, p0, p1, p2, p3) 同式。 */
function catmullRomAxis(p0, p1, p2, p3, u) {
    return 0.5 * (
        2 * p1 +
        (-p0 + p2) * u +
        (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u +
        (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u
    );
}

/** 值形态：vec3（全数值）| num | string | molangVec（含字符串分量的三元组）| null。 */
function valueKind(v) {
    if (Array.isArray(v) && v.length === 3) {
        const allNum = v.every((x) => typeof x === 'number' && Number.isFinite(x));
        const ok = v.every((x) => (typeof x === 'number' && Number.isFinite(x)) || typeof x === 'string');
        if (allNum) return 'vec3';
        if (ok) return 'molangVec';
        return null;
    }
    if (typeof v === 'string') return 'string';
    if (typeof v === 'number' && Number.isFinite(v)) return 'num';
    return null;
}

/** 可插值 = 双端点都是纯数值形态（vec3/num）且同型。 */
function interpolatable(a, b) {
    const ka = valueKind(a), kb = valueKind(b);
    return ka === kb && (ka === 'vec3' || ka === 'num');
}

const cloneVal = (v) => (Array.isArray(v) ? v.slice() : v);

/** 取关键帧的 pre 值（缺省 = post）。 */
function kfPre(k) {
    return k.pre != null ? k.pre : k.post;
}

/**
 * 采样一个通道（已按时间升序的关键帧数组）。
 * @returns {Vec3|string|number|null} 无贡献（无关键帧 / t 早于首关键帧）→ null
 *   （null = 引擎 defaultValue：rot 增量 0 / pos 偏移 0 / scale 1，由 evaluatePose 落位）
 */
function sampleChannel(keyframes, t) {
    if (!Array.isArray(keyframes) || keyframes.length === 0) return null;
    if (t < keyframes[0].timeSec) return null; // 引擎：pre-first = defaultValue（非 pre-hold）
    const last = keyframes[keyframes.length - 1];
    if (t >= last.timeSec) return cloneVal(last.post);

    // 找左端点 i：keyframes[i].time <= t < keyframes[i+1].time
    let i = 0;
    while (i + 1 < keyframes.length && keyframes[i + 1].timeSec <= t) i++;
    const a = keyframes[i];
    const b = keyframes[i + 1];
    const span = b.timeSec - a.timeSec;
    if (span <= 0) return cloneVal(a.post);

    // 引擎语义：模式取目标关键帧 b；MoLang/形态不一致 → 不插值，取左端点值
    if (!interpolatable(a.post, b.post)) return cloneVal(a.post);

    const u = Math.min(1, (t - a.timeSec) / span);
    if (b.lerpMode === 'catmullrom') {
        // 控制点（索引钳制，非端点复制）：pre_{i-1}, post_i, pre_{i+1}, post_{i+2}
        const kp = keyframes[Math.max(i - 1, 0)];
        const k3 = keyframes[Math.min(i + 2, keyframes.length - 1)];
        const p0 = kfPre(kp), p1 = a.post, p2 = kfPre(b), p3 = k3.post;
        if (valueKind(a.post) === 'vec3') {
            const out = new Array(3);
            for (let ax = 0; ax < 3; ax++) {
                out[ax] = catmullRomAxis(p0[ax], p1[ax], p2[ax], p3[ax], u);
            }
            return out;
        }
        // num：标量 catmullrom
        return catmullRomAxis(p0, p1, p2, p3, u);
    }
    // linear：post_i → pre_{i+1}（未知 lerpMode 按 linear 容错）
    const to = kfPre(b);
    if (valueKind(a.post) === 'vec3') {
        const out = new Array(3);
        for (let ax = 0; ax < 3; ax++) out[ax] = a.post[ax] + (to[ax] - a.post[ax]) * u;
        return out;
    }
    return a.post + (to - a.post) * u;
}

/**
 * 求 t 时刻的完整姿态。
 * @param {Geometry} model 模型（静态姿态来源：rotation/pivot/scale）
 * @param {Object} anim 动画（{name, loop, durationSec, tracks}）
 * @param {number} tSec
 * @returns {Map<string, {rotation: Vec3, position: Vec3, scale: Vec3}>} 每根骨骼一条（绝对值，可直接喂 viewport.setPose）
 */
function evaluatePose(model, anim, tSec) {
    const pose = new Map();
    if (model && Array.isArray(model.bones)) {
        for (const b of model.bones) {
            pose.set(b.name, {
                rotation: b.rotation ? b.rotation.slice() : vec3(),
                position: b.pivot ? b.pivot.slice() : vec3(),
                scale: vec3(1, 1, 1),
            });
        }
    }
    if (!anim || !Array.isArray(anim.tracks)) return pose;

    const t = resolveLoopTime(anim, tSec);
    for (const track of anim.tracks) {
        const entry = pose.get(track.bone);
        if (!entry) continue; // 幽灵骨骼：引擎只采样模型存在的骨 → 忽略
        let rot = sampleChannel(track.rotation, t);
        let pos = sampleChannel(track.position, t);
        let scl = sampleChannel(track.scale, t);
        // 标量值（引擎通道常量语义 = 三分量复制）
        if (typeof rot === 'number') rot = [rot, rot, rot];
        if (typeof pos === 'number') pos = [pos, pos, pos];
        if (typeof scl === 'number') scl = [scl, scl, scl];
        // MoLang 值（含字符串分量）：v1 不应用（保持静态/默认）——游戏内由引擎求值
        const applyNumArr = (v) => Array.isArray(v) && v.length === 3
            && v.every((x) => typeof x === 'number' && Number.isFinite(x));
        if (applyNumArr(rot)) {
            for (let ax = 0; ax < 3; ax++) entry.rotation[ax] += rot[ax]; // 加法（引擎 bone.rotation + 动画）
        }
        if (applyNumArr(pos)) {
            for (let ax = 0; ax < 3; ax++) entry.position[ax] += pos[ax]; // 偏移（引擎 pivot + 动画）
        }
        if (applyNumArr(scl)) {
            for (let ax = 0; ax < 3; ax++) entry.scale[ax] = scl[ax]; // 绝对值
        }
    }
    return pose;
}

const EvaluateAPI = {
    resolveLoopTime,
    catmullRomAxis,
    sampleChannel,
    evaluatePose,
    valueKind,
};
if (typeof module === 'object' && module.exports) module.exports = EvaluateAPI;
if (globalScope) globalScope.Evaluate = EvaluateAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
