// keyframeOps.js - 关键帧增删改（auto-key 的姿态编辑基础）
// 纯函数，node 可测。所有操作"不修改输入"（返回新动画对象，与 normalizeAnimation 一致）。
//
// 语义（引擎对齐，design/ANIMATION_TECH_STACK.md §1.3）：
//   - 建关键帧只写 post（pre 缺省 = post）；**改关键帧只改 post**
//     （pre = 离开该帧的反向切线/跳变目标，由显式编辑管理，拖动不改）
//   - 时间重叠：同通道同时刻 → 后写覆盖（与 normalizeAnimation 的去重规则一致）
//   - 时间移动后通道保持升序（移动跨邻键时重排）

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.KeyframeOps）
(function (globalScope) {
const Anim = (typeof module === 'object' && module.exports) ? require('./animation') : globalScope.Animation;

/** 克隆动画（深拷贝 tracks/timeline）。 */
function cloneAnimation(anim) {
    const out = {
        name: anim.name,
        loop: anim.loop,
        durationSec: anim.durationSec,
        tracks: (anim.tracks || []).map((t) => {
            const nt = { bone: t.bone };
            for (const ch of Anim.CHANNELS) {
                if (Array.isArray(t[ch])) nt[ch] = t[ch].map(Anim.cloneKeyframe);
            }
            return nt;
        }),
    };
    if (anim.override === true) out.override = true;
    if (Array.isArray(anim.timeline)) out.timeline = anim.timeline.map((e) => ({ timeSec: e.timeSec, scripts: (e.scripts || []).slice() }));
    return out;
}

/** 取 (bone, ch) 在时间 t 的最近关键帧（≤ t；无 → null）。 */
function keyframeAtOrBefore(anim, bone, ch, t) {
    const track = Anim.getTrack(anim, bone);
    const kfs = track && Array.isArray(track[ch]) ? track[ch] : null;
    if (!kfs || kfs.length === 0) return null;
    let found = null;
    for (const k of kfs) {
        if (k.timeSec <= t + 1e-9) found = k;
        else break;
    }
    return found;
}

/**
 * 确保 (bone, ch) 在时间 t 有 post 值；无则用 fallback 建关键帧（lerpMode 缺省 linear）。
 * @returns {{anim: Object, created: boolean, timeSec: number}}
 *   created=true 表示本次新建了关键帧（调用方应据此标脏）
 */
function ensureKeyframe(anim, bone, ch, t, fallback, lerpMode = 'linear') {
    const track = Anim.getTrack(anim, bone);
    if (track && Array.isArray(track[ch])) {
        for (const k of track[ch]) {
            if (Math.abs(k.timeSec - t) <= 1e-9) {
                return { anim: anim, created: false, timeSec: t };
            }
        }
    }
    const out = cloneAnimation(anim);
    let oTrack = out.tracks.find((x) => x.bone === bone);
    if (!oTrack) {
        oTrack = { bone };
        out.tracks.push(oTrack);
    }
    if (!Array.isArray(oTrack[ch])) oTrack[ch] = [];
    const kf = Anim.createKeyframe(t, fallback, lerpMode);
    const i = oTrack[ch].findIndex((k) => k.timeSec > t);
    if (i < 0) oTrack[ch].push(kf);
    else oTrack[ch].splice(i, 0, kf);
    out.durationSec = Anim.computeDuration(out);
    return { anim: out, created: true, timeSec: t };
}

/**
 * 设置 (bone, ch) 在时间 t 的关键帧 post 值。
 * @param {boolean} create 无关键帧时是否新建（auto-key onMove 用 true）
 * @param {string} [lerpMode] 新建关键帧的 lerpMode（缺省 linear）
 * @returns {{anim: Object, changed: boolean}}
 */
function setKeyframeValue(anim, bone, ch, t, post, create = true, lerpMode = 'linear') {
    const track = Anim.getTrack(anim, bone);
    if (track && Array.isArray(track[ch])) {
        for (const k of track[ch]) {
            if (Math.abs(k.timeSec - t) <= 1e-9) {
                const out = cloneAnimation(anim);
                const oTrack = out.tracks.find((x) => x.bone === bone);
                const ok = oTrack[ch].find((x) => Math.abs(x.timeSec - t) <= 1e-9);
                ok.post = Array.isArray(post) ? post.slice() : post;
                return { anim: out, changed: true };
            }
        }
    }
    if (!create) return { anim: anim, changed: false };
    return { anim: ensureKeyframe(anim, bone, ch, t, post, lerpMode).anim, changed: true };
}

/**
 * 删除 (bone, ch) 在时间 t 的关键帧（空通道/空轨剔除）。
 * @returns {{anim: Object, removed: boolean}}
 */
function removeKeyframe(anim, bone, ch, t) {
    const track = Anim.getTrack(anim, bone);
    if (!track || !Array.isArray(track[ch]) || track[ch].length === 0) {
        return { anim: anim, removed: false };
    }
    const idx = track[ch].findIndex((k) => Math.abs(k.timeSec - t) <= 1e-9);
    if (idx < 0) return { anim: anim, removed: false };
    const out = cloneAnimation(anim);
    const oTrack = out.tracks.find((x) => x.bone === bone);
    oTrack[ch] = oTrack[ch].filter((_, i) => i !== idx);
    if (oTrack[ch].length === 0) delete oTrack[ch];
    if (!Anim.CHANNELS.some((c) => Array.isArray(oTrack[c]) && oTrack[c].length > 0)) {
        out.tracks = out.tracks.filter((x) => x.bone !== bone);
    }
    out.durationSec = Anim.computeDuration(out);
    return { anim: out, removed: true };
}

/**
 * 移动 (bone, ch) 在时间 t 的关键帧到新时刻。
 * @returns {{anim: Object, moved: boolean, timeSec: number}}
 */
function moveKeyframeTime(anim, bone, ch, t, newT) {
    const track = Anim.getTrack(anim, bone);
    if (!track || !Array.isArray(track[ch])) return { anim: anim, moved: false, timeSec: t };
    if (!Number.isFinite(newT) || newT < 0) throw new Error(`关键帧新时间非法: ${newT}`);
    const kf = track[ch].find((k) => Math.abs(k.timeSec - t) <= 1e-9);
    if (!kf) return { anim: anim, moved: false, timeSec: t };
    const out = cloneAnimation(anim);
    const oTrack = out.tracks.find((x) => x.bone === bone);
    const list = oTrack[ch];
    const idx = list.findIndex((k) => Math.abs(k.timeSec - t) <= 1e-9);
    const [k] = list.splice(idx, 1);
    k.timeSec = newT;
    const i = list.findIndex((x) => x.timeSec > newT);
    if (i < 0) list.push(k);
    else list.splice(i, 0, k);
    out.durationSec = Anim.computeDuration(out);
    return { anim: out, moved: true, timeSec: newT };
}

/**
 * 求 (bone, ch) 在时间 t 的当前通道值（用于建关键帧的 fallback / 数值输入回显）。
 * @returns {Vec3|string|number} 无贡献 → fallback（缺省 [0,0,0]）
 */
function currentValue(anim, bone, ch, t, fallback = [0, 0, 0]) {
    // 复用 Evaluate 的采样（同目录加载）
    const Evaluate = (typeof module === 'object' && module.exports) ? require('./evaluate') : globalScope.Evaluate;
    const track = Anim.getTrack(anim, bone);
    const kfs = track && Array.isArray(track[ch]) ? track[ch] : null;
    if (!kfs || kfs.length === 0) return Array.isArray(fallback) ? fallback.slice() : fallback;
    const v = Evaluate.sampleChannel(kfs, t);
    if (v == null) return Array.isArray(fallback) ? fallback.slice() : fallback;
    return Array.isArray(v) ? v.slice() : v;
}

const KeyframeOpsAPI = {
    cloneAnimation,
    keyframeAtOrBefore,
    ensureKeyframe,
    setKeyframeValue,
    removeKeyframe,
    moveKeyframeTime,
    currentValue,
};
if (typeof module === 'object' && module.exports) module.exports = KeyframeOpsAPI;
if (globalScope) globalScope.KeyframeOps = KeyframeOpsAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
