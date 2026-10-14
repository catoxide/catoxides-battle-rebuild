// chaining.js - 动画接续：帧搬运（A 尾帧 → B 开头 / B 头帧 → A 结尾）
// 纯函数，node 可测（design/ANIMATION_TECH_STACK.md §4.5）。
//
// 背景：动画接续（A 播完接 B）的帧连续性是资产问题 —— B 的 t=0 姿态 ≈ A 的末帧姿态
// 才不跳变。运行时由 handler 在 A 的完成/交接点触发 B；本模块提供把姿态"搬运"成
// 关键帧的纯函数（UI 按钮的后端）。
//
//   capturePoseAt(model, anim, t)   → { bone: {rotation, position, scale} }（绝对姿态）
//   insertPoseAsKeyframes(anim, t, pose, opts) → 新动画（在 t 处写关键帧）
//
// 插入语义：
//   - 只处理模型存在的骨（幽灵骨忽略，与引擎一致）
//   - rotation 关键帧 post = 姿态值 - 静态旋转（= 动画增量；引擎 rotation 加法语义）
//   - position 关键帧 post = 姿态值 - 静态 pivot（= 动画偏移）
//   - scale 关键帧 post = 姿态值（绝对值）
//   - bones 缺省 = pose 里所有骨；channels 缺省 = 全部三通道
//   - t 处已有关键帧 → 覆盖 post（不动 pre）；无 → 新建（lerpMode 缺省 linear）
//   - MoLang 姿态值（含字符串分量）不搬运（返回时报告在 skipped 里）

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.Chaining）
(function (globalScope) {
const Anim = (typeof module === 'object' && module.exports) ? require('./animation') : globalScope.Animation;
const KfOps = (typeof module === 'object' && module.exports) ? require('./keyframeOps') : globalScope.KeyframeOps;
const Evaluate = (typeof module === 'object' && module.exports) ? require('./evaluate') : globalScope.Evaluate;

const isNumArr = (v) => Array.isArray(v) && v.length === 3 && v.every((x) => typeof x === 'number' && Number.isFinite(x));
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];

/**
 * 捕获 (model, anim) 在 t 时刻的姿态（绝对值）。
 * @returns {Object<string, {rotation: Vec3, position: Vec3, scale: Vec3}>}
 */
function capturePoseAt(model, anim, t) {
    const pose = Evaluate.evaluatePose(model, anim, t);
    const out = {};
    for (const [bone, p] of pose) {
        out[bone] = { rotation: p.rotation.slice(), position: p.position.slice(), scale: p.scale.slice() };
    }
    return out;
}

/**
 * 把 pose（绝对姿态）在 t 处**原样**写为关键帧（rotation/position 不还原增量）。
 * 仅用于"静态姿态为零"的模型或调试场景；正常帧搬运用 insertPoseAsKeyframesOf（带 model）。
 * @returns {{anim: Object, written: string[], skipped: string[]}}
 *   written/skipped 元素形如 "boneName:channel"
 */
function insertPoseAsKeyframes(anim, t, pose, opts = {}) {
    if (!Number.isFinite(t) || t < 0) throw new Error(`帧搬运时刻非法: ${t}`);
    const bones = opts.bones || Object.keys(pose || {});
    const channels = opts.channels || Anim.CHANNELS;

    let out = KfOps.cloneAnimation(anim);
    const written = [];
    const skipped = [];
    for (const bone of bones) {
        const p = pose && pose[bone];
        if (!p) continue;
        for (const ch of channels) {
            const v = p[ch];
            if (!isNumArr(v)) {
                skipped.push(`${bone}:${ch}`);
                continue;
            }
            out = KfOps.setKeyframeValue(out, bone, ch, t, v, true).anim;
            written.push(`${bone}:${ch}`);
        }
    }
    out.durationSec = Anim.computeDuration(out);
    return { anim: out, written, skipped };
}

/**
 * 公开入口：帧搬运（带模型 → 正确还原 rotation/position 增量）。
 * @param {Geometry} model 目标动画所属模型
 * @param {Object} anim 目标动画
 * @param {number} t 写入时刻
 * @param {Object} pose 源姿态（capturePoseAt 输出，来自任意模型 —— 常用 = 另一动画的捕获值；
 *        注意跨模型搬运时静态姿态不同，rotation/position 增量按"目标模型"的静态值还原）
 * @param {Object} [opts] 同 insertPoseAsKeyframes
 */
function insertPoseAsKeyframesOf(model, anim, t, pose, opts = {}) {
    if (!model || !Array.isArray(model.bones)) throw new Error('insertPoseAsKeyframesOf: model 缺失');
    const bonesInModel = new Set(model.bones.map((b) => b.name));
    const bones = (opts.bones || Object.keys(pose || {})).filter((b) => bonesInModel.has(b));
    const channels = opts.channels || Anim.CHANNELS;
    const lerpMode = opts.lerpMode || 'linear';

    let out = KfOps.cloneAnimation(anim);
    const written = [];
    const skipped = [];
    for (const bone of bones) {
        const p = pose && pose[bone];
        if (!p) continue;
        const staticBone = model.bones.find((b) => b.name === bone);
        const sRot = staticBone.rotation || [0, 0, 0];
        const sPiv = staticBone.pivot || [0, 0, 0];
        for (const ch of channels) {
            const v = p[ch];
            if (!isNumArr(v)) {
                skipped.push(`${bone}:${ch}`);
                continue;
            }
            let post;
            if (ch === 'rotation') post = sub(v, sRot);      // 绝对 → 增量
            else if (ch === 'position') post = sub(v, sPiv);  // 绝对 → 偏移
            else post = v;                                    // scale 绝对
            out = KfOps.setKeyframeValue(out, bone, ch, t, post, true).anim;
            written.push(`${bone}:${ch}`);
        }
    }
    out.durationSec = Anim.computeDuration(out);
    return { anim: out, written, skipped };
}

/**
 * 组合工具：A 尾帧 → B 开头（在 B 的 t=0 写入 A 的末帧姿态）。
 * @returns insertPoseAsKeyframesOf 的结果
 */
function tailToHead(modelA, animA, modelB, animB, opts = {}) {
    const tA = Number.isFinite(animA.durationSec) && animA.durationSec > 0 ? animA.durationSec : 0;
    const pose = capturePoseAt(modelA, animA, tA);
    return insertPoseAsKeyframesOf(modelB, animB, 0, pose, opts);
}

/**
 * 组合工具：B 头帧 → A 结尾（在 A 的末时刻写入 B 的 t=0 姿态）。
 * @returns insertPoseAsKeyframesOf 的结果
 */
function headToTail(modelA, animA, modelB, animB, opts = {}) {
    const tA = Number.isFinite(animA.durationSec) && animA.durationSec > 0 ? animA.durationSec : 0;
    const pose = capturePoseAt(modelB, animB, 0);
    return insertPoseAsKeyframesOf(modelA, animA, tA, pose, opts);
}

/**
 * 组合工具（2026-10-14）：源状态 → 目标指定时刻。
 * 捕获 A 的 t=0 姿态（"该动画的状态"，如 3rd_still 的静止位），在 B 的 tB 处写为关键帧
 * —— 即"把某个状态插入到当前时间"（播放头停在哪儿就 K 到哪儿）。
 * @returns insertPoseAsKeyframesOf 的结果
 */
function stateToTime(modelA, animA, modelB, animB, tB, opts = {}) {
    if (!Number.isFinite(tB) || tB < 0) throw new Error(`stateToTime: 时刻非法 ${tB}`);
    const pose = capturePoseAt(modelA, animA, 0);
    return insertPoseAsKeyframesOf(modelB, animB, tB, pose, opts);
}

const ChainingAPI = {
    capturePoseAt,
    insertPoseAsKeyframes,
    insertPoseAsKeyframesOf,
    tailToHead,
    headToTail,
    stateToTime,
};
if (typeof module === 'object' && module.exports) module.exports = ChainingAPI;
if (globalScope) globalScope.Chaining = ChainingAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
