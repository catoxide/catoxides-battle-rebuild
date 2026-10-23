// animation.js - 动画数据模型 + 关键帧操作（排序/去重/空轨剔除）+ 校验
// 纯函数，node 可测（design §3.2 / §4 动画规则 + design/ANIMATION_TECH_STACK.md §1.3）。
//
// Animation { name, loop: 'once'|'hold_on_last_frame'|'true',
//             durationSec,                    // 永远显式 = max 关键帧时间（导出时重算）
//             tracks: BoneTrack[],
//             timeline?: [{ timeSec, scripts: [String] }],  // W7：过点 MoLang 脚本（OAnimation.timeline）
//             params?: { [name]: { default, phase } } }     // 运行时参数声明（见 animParams.js）
// BoneTrack { bone, rotation?: Keyframe[], position?: Keyframe[], scale?: Keyframe[] }
// Keyframe  { timeSec, post: Vec3|String|Number, pre?: Vec3|String|Number,
//             lerpMode: 'linear'|'catmullrom'|<未知值透传> }
//   - String = MoLang 表达式，pass-through（v1 UI 只编辑数值）
//   - pre = 反向切线值（OKeyFrame.pre；缺省 = post）。引擎 catmullrom 四控制点
//     pre_{i-1},post_i,pre_{i+1},post_{i+2} 实际使用 pre；linear 段终点取 pre_{i+1}；
//     pre≠post = 关键帧处跳变。导入必须保全（丢失会改变游戏内曲线）。
//   - lerpMode 未知值：导入透传+警告（引擎 InterpolationType 只认 linear/catmullrom；
//     未知值写入文件会炸整个解析 → 导出侧拦截，见 sparkCodec.exportAnimationFile）

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.Animation）
(function (globalScope) {
const isNode = typeof module === 'object' && module.exports;
const P = isNode ? require('./animParams') : globalScope.AnimParams;
const LOOPS = ['once', 'hold_on_last_frame', 'true'];
const CHANNELS = ['rotation', 'position', 'scale'];
const LERP_MODES = ['linear', 'catmullrom'];

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const isVec3 = (v) => Array.isArray(v) && v.length === 3 && v.every(isNum);
/** 每分量数值或 MoLang（引擎 Vector3js = 三分量各可为数值或 MoLang；运行时参数常用 `["v.pitch * -0.5", 0, 0]`）。 */
const isMolangVec = (v) => Array.isArray(v) && v.length === 3
    && v.every((x) => isNum(x) || typeof x === 'string');
/** 关键帧值形态（Vec3 / 分量含 MoLang 的三元组 / 单值 / MoLang 字符串）。 */
const isValue = (v) => isVec3(v) || typeof v === 'string' || isNum(v) || isMolangVec(v);
/** 值形态是否一致（pre 与 post 必须同形态；引擎 Vector3js = 三分量各可为数值或 MoLang）。 */
function valueKind(v) {
    if (isVec3(v)) return 'vec3';
    if (isMolangVec(v)) return 'molangVec';
    if (typeof v === 'string') return 'string';
    if (isNum(v)) return 'num';
    return null;
}

/**
 * 建关键帧。post 形态（现网三种）：
 *   Vec3 [x,y,z]（主流）| number（单值，wrist 系 position 用）| string（MoLang 表达式）
 * W7：pre 可选（缺省 = post）；lerpMode 允许未知值透传（校验层给警告，导出层拦截）。
 */
function createKeyframe(timeSec, post, lerpMode = 'linear', pre = null) {
    if (!isNum(timeSec) || timeSec < 0) throw new Error(`关键帧时间非法: ${timeSec}`);
    if (!isValue(post)) throw new Error('post 必须是有限数三元组/单值/MoLang 字符串');
    if (typeof lerpMode !== 'string' || lerpMode.length === 0) throw new Error(`lerpMode 非法: ${lerpMode}`);
    const out = { timeSec, post: Array.isArray(post) ? post.slice() : post, lerpMode };
    if (pre != null) {
        // 引擎不要求 pre/post 同形态（各自都是 Vector3js）；只要求 pre 是合法值
        if (valueKind(pre) == null) throw new Error('pre 非法（必须是数值三元组/单值/MoLang）');
        out.pre = Array.isArray(pre) ? pre.slice() : pre;
    }
    return out;
}

/** 建动画。durationSec 缺省时按 max 关键帧时间计算。
 *  W7：timeline 可选 = [{ timeSec, scripts: [String] }]（过点 MoLang 脚本）。
 *  params 可选 = 运行时参数声明 { name: {default, phase} }（见 animParams.js）。 */
function createAnimation(name, loop = 'once', tracks = [], timeline = null, params = null) {
    if (!name || typeof name !== 'string') throw new Error('动画名缺失');
    if (!LOOPS.includes(loop)) throw new Error(`loop 非法: ${loop}`);
    const anim = { name, loop, durationSec: 0, tracks: tracks.slice() };
    if (timeline != null) anim.timeline = normalizeTimeline(timeline);
    if (params != null) {
        const { params: normalized, errors } = P.normalizeParams(params);
        if (errors.length) throw new Error(`params 非法: ${errors.join('; ')}`);
        if (Object.keys(normalized).length) anim.params = normalized;
    }
    anim.durationSec = computeDuration(anim);
    return anim;
}

/** timeline 归一（去重同时刻、合并脚本、升序）。输入 [{timeSec, scripts|string}]。 */
function normalizeTimeline(timeline) {
    if (!Array.isArray(timeline)) throw new Error('timeline 必须是数组');
    const merged = new Map();
    for (const entry of timeline) {
        const t = entry.timeSec;
        if (!isNum(t) || t < 0) throw new Error(`timeline 时间非法: ${t}`);
        const scripts = Array.isArray(entry.scripts) ? entry.scripts : [entry.scripts];
        for (const s of scripts) if (typeof s === 'string' && s.length > 0) {
            const arr = merged.get(t) || [];
            if (!arr.includes(s)) arr.push(s);
            merged.set(t, arr);
        }
    }
    return [...merged.keys()].sort((a, b) => a - b).map((t) => ({ timeSec: t, scripts: merged.get(t) }));
}

/** max 关键帧时间（无关键帧 → 0）。 */
function computeDuration(anim) {
    let max = 0;
    for (const t of anim.tracks || []) {
        for (const ch of CHANNELS) {
            for (const k of t[ch] || []) {
                if (isNum(k.timeSec) && k.timeSec > max) max = k.timeSec;
            }
        }
    }
    return max;
}

/** 取某骨骼的轨道（不存在 → null）。 */
function getTrack(anim, boneName) {
    return (anim.tracks || []).find((t) => t.bone === boneName) || null;
}

/** 克隆关键帧（深拷贝 post/pre 数组）。 */
function cloneKeyframe(k) {
    const out = { timeSec: k.timeSec, post: Array.isArray(k.post) ? k.post.slice() : k.post, lerpMode: k.lerpMode };
    if (k.pre != null) out.pre = Array.isArray(k.pre) ? k.pre.slice() : k.pre;
    return out;
}

/**
 * 规范化（不修改原对象，返回新动画）：
 *  1. 每通道按 timeSec 升序排序
 *  2. 同骨同通道同时刻去重（保留最后一个定义 —— 与"后写覆盖"语义一致）
 *  3. 剔除空通道 / 空轨
 *  4. durationSec 重算 = max 关键帧时间
 *  W7：pre 随关键帧保全；timeline 归一（升序/同时刻合并脚本）
 */
function normalizeAnimation(anim) {
    const out = { name: anim.name, loop: anim.loop, durationSec: 0, tracks: [] };
    if (anim.override === true) out.override = true; // override_previous_animation 透传
    if (anim.params && Object.keys(anim.params).length > 0) out.params = P.cloneParams(anim.params);
    for (const track of anim.tracks || []) {
        const newTrack = { bone: track.bone };
        let hasKey = false;
        for (const ch of CHANNELS) {
            const kfs = track[ch];
            if (!Array.isArray(kfs) || kfs.length === 0) continue;
            const sorted = kfs.slice().sort((a, b) => a.timeSec - b.timeSec);
            const deduped = [];
            const lastAt = new Map(); // timeSec → index in deduped
            for (const k of sorted) {
                const i = lastAt.get(k.timeSec);
                if (i == null) {
                    deduped.push(cloneKeyframe(k));
                    lastAt.set(k.timeSec, deduped.length - 1);
                } else {
                    deduped[i] = cloneKeyframe(k); // 后者覆盖
                }
            }
            newTrack[ch] = deduped;
            hasKey = true;
        }
        if (hasKey) out.tracks.push(newTrack);
    }
    if (Array.isArray(anim.timeline) && anim.timeline.length > 0) {
        try { out.timeline = normalizeTimeline(anim.timeline); } catch { /* 非法 timeline 由 validate 报告 */ }
    }
    out.durationSec = computeDuration(out);
    return out;
}

/**
 * 校验（不修改）。
 * @param {Object} anim
 * @param {string[]} [modelBoneNames] 提供时检查骨骼名 ⊆ 模型骨骼
 * @returns {{errors: string[], warnings: string[]}}
 */
function validateAnimation(anim, modelBoneNames) {
    const errors = [];
    const warnings = [];
    if (!anim || typeof anim !== 'object') return { errors: ['动画为空'], warnings };
    if (!anim.name || typeof anim.name !== 'string') errors.push('动画名缺失');
    if (!LOOPS.includes(anim.loop)) errors.push(`loop 非法: ${anim.loop}`);
    // 运行时参数声明（声明即契约；非法名字/相位导出会被拦截）
    if (anim.params != null) {
        const pv = P.validateParams(anim.params);
        errors.push(...pv.errors);
        warnings.push(...pv.warnings);
    }

    let maxTime = 0;
    const seenBones = new Set();
    for (const track of anim.tracks || []) {
        if (!track.bone || typeof track.bone !== 'string') { errors.push('存在无名轨道'); continue; }
        if (seenBones.has(track.bone)) errors.push(`骨骼 ${track.bone} 有多条轨道（应合并为一条）`);
        seenBones.add(track.bone);
        if (modelBoneNames && !modelBoneNames.includes(track.bone)) {
            warnings.push(`轨道骨骼 ${track.bone} 不在模型骨骼中（Spark 容错忽略）`);
        }
        for (const ch of CHANNELS) {
            const kfs = track[ch];
            if (!Array.isArray(kfs) || kfs.length === 0) continue;
            let prev = null;
            for (const k of kfs) {
                if (!isNum(k.timeSec) || k.timeSec < 0) errors.push(`骨骼 ${track.bone} ${ch}: 关键帧时间非法 ${k.timeSec}`);
                if (prev != null && k.timeSec < prev) errors.push(`骨骼 ${track.bone} ${ch}: 关键帧时间非单调 (${prev} → ${k.timeSec})`);
                prev = k.timeSec;
                if (isNum(k.timeSec) && k.timeSec > maxTime) maxTime = k.timeSec;
                if (!isValue(k.post)) errors.push(`骨骼 ${track.bone} ${ch}: post 非法`);
                else if (typeof k.post === 'string') warnings.push(`骨骼 ${track.bone} ${ch} t=${k.timeSec}: MoLang 表达式（只读透传，游戏内由引擎求值）`);
                else if (Array.isArray(k.post) && k.post.some((x) => typeof x === 'string')) {
                    warnings.push(`骨骼 ${track.bone} ${ch} t=${k.timeSec}: 含 MoLang 分量的三元组（只读透传，游戏内由引擎求值）`);
                }
                // W7：pre 必须是合法值（引擎不要求 pre/post 同形态，各自都是 Vector3js；
                // 形态不一致只影响插值，evaluate 按不插值容错）
                if (k.pre != null && valueKind(k.pre) == null) {
                    errors.push(`骨骼 ${track.bone} ${ch} t=${k.timeSec}: pre 非法`);
                }
                // W7：未知 lerp_mode 从"错误"降级为"警告"（透传保全；导出侧拦截未知值）
                if (typeof k.lerpMode !== 'string' || k.lerpMode.length === 0) errors.push(`骨骼 ${track.bone} ${ch}: lerp_mode 非法 ${k.lerpMode}`);
                else if (!LERP_MODES.includes(k.lerpMode)) warnings.push(`骨骼 ${track.bone} ${ch} t=${k.timeSec}: 未知 lerp_mode "${k.lerpMode}"（透传保全；引擎只认 linear/catmullrom，导出将拦截）`);
            }
            // 同时刻重复（未规范化前）
            const times = new Map();
            for (const k of kfs) times.set(k.timeSec, (times.get(k.timeSec) || 0) + 1);
            for (const [t, n] of times) {
                if (n > 1) warnings.push(`骨骼 ${track.bone} ${ch}: 时刻 ${t}s 有 ${n} 个关键帧（规范化将保留最后一个）`);
            }
        }
    }
    if (isNum(anim.durationSec) && Math.abs(anim.durationSec - maxTime) > 1e-6) {
        warnings.push(`durationSec ${anim.durationSec} ≠ max 关键帧时间 ${maxTime}（导出将按 max 重写）`);
    }
    // W7：timeline（过点 MoLang 脚本）
    if (anim.timeline != null) {
        if (!Array.isArray(anim.timeline)) errors.push('timeline 必须是数组');
        else {
            anim.timeline.forEach((entry, i) => {
                if (!entry || !isNum(entry.timeSec) || entry.timeSec < 0) errors.push(`timeline[${i}]: 时间非法`);
                if (entry && entry.scripts != null) {
                    if (!Array.isArray(entry.scripts) || !entry.scripts.every((s) => typeof s === 'string')) {
                        errors.push(`timeline[${i}]: scripts 必须是字符串数组`);
                    }
                }
            });
        }
    }
    return { errors, warnings };
}

const AnimationAPI = {
    LOOPS,
    CHANNELS,
    LERP_MODES,
    createKeyframe,
    createAnimation,
    computeDuration,
    getTrack,
    cloneKeyframe,
    normalizeTimeline,
    normalizeAnimation,
    validateAnimation,
};
if (typeof module === 'object' && module.exports) module.exports = AnimationAPI;
if (globalScope) globalScope.Animation = AnimationAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
