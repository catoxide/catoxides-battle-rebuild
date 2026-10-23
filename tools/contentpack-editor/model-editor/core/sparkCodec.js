// sparkCodec.js - Spark JSON ↔ 数据模型（model-editor/core 的序列化层）
// 纯函数，node 可测。执行 design §4 规则表（模型 7 条 + 动画 8 条）。
//
// 设计要点：
//   - 内存形（model.js / animation.js 的规范形）是 round-trip 的锚点：
//     导入时把缺省值全部物化（rotation [0,0,0]、scale [1,1,1]、inflate 0…），
//     故 import(export(m)) 与 m 可 deep-equal（语义等价黄金测试的判据）。
//   - 导出 = 规范化形式：bones 数组 + locators 对象映射 + 固定字段顺序 + 缺省省略。
//   - 未知字段丢弃（规范形是封闭的）；MoLang 字符串 post 原样 pass-through。

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.SparkCodec）
(function (globalScope) {
const isNode = typeof module === 'object' && module.exports;
const Model = isNode ? require('./model') : globalScope.Model;
const Anim = isNode ? require('./animation') : globalScope.Animation;
const P = isNode ? require('./animParams') : globalScope.AnimParams;
const Tick = isNode ? require('./tick') : globalScope.Tick;
const { round4, formatTime } = Tick;

const FACES = Model.FACES;

// ==================== 通用 ====================

function toObject(source, label) {
    if (typeof source === 'string') {
        try {
            // 真实文件带 UTF-8 BOM（modular-zombie-3 动画已核）→ 剥掉再解析
            return JSON.parse(source.replace(/^\uFEFF/, ''));
        } catch (e) {
            throw new Error(`${label} JSON 解析失败: ${e.message}`);
        }
    }
    if (!source || typeof source !== 'object') throw new Error(`${label} 不是 JSON 对象`);
    return source;
}

// ==================== 模型导入 ====================

function boneFromRaw(raw) {
    const bone = {
        name: raw.name,
        parent: raw.parent == null ? null : raw.parent,
        pivot: raw.pivot ? raw.pivot.slice() : Model.vec3(),
        rotation: raw.rotation ? raw.rotation.slice() : Model.vec3(),
        scale: raw.scale ? raw.scale.slice() : Model.vec3(1, 1, 1),
        mirror: raw.mirror === true,
        cubes: [],
        locators: [],
    };
    const cubes = raw.cubes == null ? [] : raw.cubes;
    if (Array.isArray(cubes)) {
        for (const c of cubes) bone.cubes.push(cubeFromRaw(c));
    } else if (typeof cubes === 'object') {
        for (const v of Object.values(cubes)) bone.cubes.push(cubeFromRaw(v)); // 旧映射格式（cube 无 name，直接透传）
    }
    let locators = raw.locators == null ? [] : raw.locators;
    if (typeof locators === 'object' && !Array.isArray(locators)) {
        locators = Object.entries(locators).map(([name, v]) => ({ name, offset: v.offset, rotation: v.rotation }));
    }
    for (const l of locators) {
        bone.locators.push({
            name: l.name,
            offset: l.offset ? l.offset.slice() : Model.vec3(),
            rotation: l.rotation ? l.rotation.slice() : Model.vec3(),
        });
    }
    return bone;
}

function cubeFromRaw(raw) {
    let uv;
    if (Array.isArray(raw.uv)) {
        uv = raw.uv.slice();
    } else if (raw.uv && typeof raw.uv === 'object') {
        uv = {};
        for (const f of FACES) {
            const face = raw.uv[f];
            if (face == null) continue;
            const entry = { uv: face.uv.slice() };
            if (face.uv_size != null) entry.uv_size = face.uv_size.slice();
            if (face.uv_rotation != null) entry.uv_rotation = face.uv_rotation;
            uv[f] = entry;
        }
    } else {
        throw new Error('cube.uv 缺失或非法');
    }
    return {
        origin: raw.origin.slice(),
        size: raw.size.slice(),
        uv,
        inflate: raw.inflate == null ? 0 : raw.inflate,
        pivot: raw.pivot ? raw.pivot.slice() : Model.vec3(),
        rotation: raw.rotation ? raw.rotation.slice() : Model.vec3(),
    };
}

/**
 * 导入 spark 模型 JSON（字符串或对象）。
 * @returns {{ok: true, data: Geometry}}
 * @throws 结构非法（缺 minecraft:geometry / identifier / 坏 cube）
 */
function importModel(source) {
    const obj = toObject(source, '模型');
    const geos = obj['minecraft:geometry'];
    if (!Array.isArray(geos) || geos.length === 0) throw new Error('缺少 minecraft:geometry[0]');
    const geo = geos[0];
    const desc = geo.description || {};
    if (!desc.identifier || typeof desc.identifier !== 'string') throw new Error('description.identifier 缺失');

    let bonesRaw = geo.bones == null ? [] : geo.bones;
    if (typeof bonesRaw === 'object' && !Array.isArray(bonesRaw)) {
        // 旧 Bedrock 对象映射格式 → 数组（保序；键名即骨骼名，注入 name 字段）
        bonesRaw = Object.entries(bonesRaw).map(([name, v]) => Object.assign({}, v, { name }));
    }
    if (!Array.isArray(bonesRaw)) throw new Error('bones 格式非法');

    let visibleBounds = null;
    if (desc.visible_bounds_width != null || desc.visible_bounds_height != null || desc.visible_bounds_offset != null) {
        visibleBounds = {
            width: desc.visible_bounds_width == null ? 0 : desc.visible_bounds_width,
            height: desc.visible_bounds_height == null ? 0 : desc.visible_bounds_height,
            offset: desc.visible_bounds_offset ? desc.visible_bounds_offset.slice() : Model.vec3(),
        };
    }

    const geometry = {
        identifier: desc.identifier,
        formatVersion: obj.format_version || '1.12.0',
        textureWidth: desc.texture_width == null ? 64 : desc.texture_width,
        textureHeight: desc.texture_height == null ? 64 : desc.texture_height,
        visibleBounds,
        bones: bonesRaw.map(boneFromRaw),
    };
    const { errors } = Model.validateGeometry(geometry);
    if (errors.length) throw new Error(`模型结构非法: ${errors.join('; ')}`);
    return { ok: true, data: geometry };
}

// ==================== 模型导出 ====================

function cubeToRaw(c) {
    const out = { origin: c.origin.slice(), size: c.size.slice() };
    if (Array.isArray(c.uv)) {
        out.uv = c.uv.slice();
    } else {
        const uv = {};
        for (const f of FACES) {
            const face = c.uv && c.uv[f];
            if (face == null) continue;
            const entry = { uv: face.uv.slice() };
            if (face.uv_size != null) entry.uv_size = face.uv_size.slice();
            if (face.uv_rotation != null) entry.uv_rotation = face.uv_rotation;
            uv[f] = entry;
        }
        out.uv = uv;
    }
    if (c.inflate !== 0) out.inflate = c.inflate;          // 规则4：缺省省略
    if (!Model.vec3Equals(c.pivot, [0, 0, 0])) out.pivot = c.pivot.slice();
    if (!Model.vec3Equals(c.rotation, [0, 0, 0])) out.rotation = c.rotation.slice();
    return out;
}

function boneToRaw(b) {
    const out = { name: b.name };
    if (b.parent != null) out.parent = b.parent;
    out.pivot = b.pivot.slice();
    if (!Model.vec3Equals(b.rotation, [0, 0, 0])) out.rotation = b.rotation.slice();
    if (!Model.vec3Equals(b.scale, [1, 1, 1])) out.scale = b.scale.slice();
    if (b.mirror) out.mirror = true;
    out.cubes = (b.cubes || []).map(cubeToRaw);            // 空 cubes 保留 []（现网 sword 风格）
    if (b.locators && b.locators.length) {
        const locs = {};
        for (const l of b.locators) locs[l.name] = { offset: l.offset.slice(), rotation: l.rotation.slice() };
        out.locators = locs;
    }
    return out;
}

/**
 * 导出 spark 模型 JSON 文本（规范化形式，2 空格缩进）。
 * 规则：外层 format_version + minecraft:geometry[0]；字段顺序固定（规则5）；缺省省略（规则4）。
 * @throws geometry 未通过校验时
 */
function exportModel(geometry) {
    const { errors } = Model.validateGeometry(geometry);
    if (errors.length) throw new Error(`无法导出（先修结构）: ${errors.join('; ')}`);

    const desc = {
        identifier: geometry.identifier,
        texture_width: geometry.textureWidth,
        texture_height: geometry.textureHeight,
    };
    if (geometry.visibleBounds) {
        desc.visible_bounds_width = geometry.visibleBounds.width;
        desc.visible_bounds_height = geometry.visibleBounds.height;
        desc.visible_bounds_offset = geometry.visibleBounds.offset.slice();
    }
    const geo = { description: desc, bones: geometry.bones.map(boneToRaw) };
    const out = { format_version: geometry.formatVersion || '1.12.0', 'minecraft:geometry': [geo] };
    return JSON.stringify(out, null, 2) + '\n';
}

// ==================== 动画导入 ====================

/**
 * 值归一（引擎 Vector3js = 三分量，每分量数值或 MoLang）：
 *   单值 number s  → [s,s,s]（= 引擎通道常量形的语义：三分量复制）
 *   单值 string s  → [s,s,s]（MoLang 三分量复制）
 *   三元组         → 原样（含字符串分量 = molangVec）
 * 引擎侧单值只作为"通道常量"合法（MAP_CODEC 的 JSMolangValue 分支 → t=0 常量关键帧）；
 * 映射内裸单值引擎解析不了 → 导入时按常量语义归一（容错保全，不炸整文件）。
 */
function normalizeValue(v) {
    if (Array.isArray(v)) return v.slice();
    return [v, v, v];
}

/**
 * 单个关键帧 raw → 规范形。现网形态：
 *   紧凑形 { "<t>": [x, y, z] }            （wrist 系；缺省 lerp = linear）
 *   完整形 { "<t>": { pre?, post, lerp_mode? } }（sword/zombie 系）
 * W7：pre（反向切线）保全 —— 引擎 catmullrom 四控制点 pre_{i-1},post_i,pre_{i+1},post_{i+2}
 * 实际使用 pre；丢失会改变游戏内曲线。
 */
function keyframeFromRaw(name, boneName, ch, tStr, v) {
    const timeSec = parseFloat(tStr);
    if (!Number.isFinite(timeSec)) throw new Error(`动画 ${name} 骨骼 ${boneName} ${ch}: 关键帧时间非法 "${tStr}"`);
    if (Array.isArray(v) || typeof v === 'string' || typeof v === 'number') {
        // 紧凑形：值直接是 post
        return {
            timeSec,
            post: normalizeValue(v),
            lerpMode: 'linear',
        };
    }
    // 完整形 { pre?, post, lerp_mode? }
    if (v.post == null && !Array.isArray(v.post)) {
        throw new Error(`动画 ${name} 骨骼 ${boneName} ${ch} t=${tStr}: 完整形关键帧缺 post`);
    }
    const out = {
        timeSec,
        post: normalizeValue(v.post),
        lerpMode: typeof v.lerp_mode === 'string' && v.lerp_mode.length > 0 ? v.lerp_mode : 'linear',
    };
    if (v.pre != null) out.pre = normalizeValue(v.pre);
    return out;
}

/**
 * 通道 raw → 关键帧数组。三种通道级形态（MAP_CODEC）：
 *   映射 { "<t>": 值 }（主流）
 *   裸三元组 [x,y,z] = t=0 常量关键帧
 *   裸单值/MoLang = t=0 常量关键帧（三分量复制）
 */
function channelFromRaw(name, boneName, ch, map) {
    if (map == null) return null;
    if (!Array.isArray(map) && typeof map !== 'string' && typeof map !== 'number') {
        if (typeof map !== 'object') return null;
        const kfs = Object.entries(map).map(([tStr, v]) => keyframeFromRaw(name, boneName, ch, tStr, v));
        kfs.sort((a, b) => a.timeSec - b.timeSec);
        return kfs;
    }
    // 通道常量 → t=0 单关键帧
    return [{ timeSec: 0, post: normalizeValue(map), lerpMode: 'linear' }];
}

/**
 * timeline raw（OAnimation.timeline: Map<time, List<MoLang>>）→ [{timeSec, scripts}]。
 * 容错：值允许裸字符串（引擎要求 list，导出时归一为 list）。
 */
function timelineFromRaw(raw) {
    if (raw == null) return null;
    if (!Array.isArray(raw) && typeof raw !== 'object') return null;
    const entries = Object.entries(raw).map(([tStr, v]) => ({
        timeSec: parseFloat(tStr),
        scripts: Array.isArray(v) ? v.slice() : [v],
    }));
    const cleaned = entries.filter((e) => Number.isFinite(e.timeSec));
    if (cleaned.length === 0) return null;
    return cleaned.sort((a, b) => a.timeSec - b.timeSec);
}

function animationFromRaw(name, raw) {
    let loop = raw.loop;
    if (loop === true) loop = 'true'; // 容错：布尔 true 归一为字符串
    if (!Anim.LOOPS.includes(loop)) loop = 'once';

    const tracks = [];
    const bones = raw.bones == null ? {} : raw.bones;
    for (const [boneName, channels] of Object.entries(bones)) {
        const track = { bone: boneName };
        for (const ch of Anim.CHANNELS) {
            const kfs = channelFromRaw(name, boneName, ch, channels[ch]);
            if (kfs && kfs.length) track[ch] = kfs;
        }
        const has = Anim.CHANNELS.some((ch) => track[ch] && track[ch].length);
        if (has) tracks.push(track);
    }
    const anim = { name, loop, durationSec: 0, tracks };
    anim.durationSec = raw.animation_length != null ? raw.animation_length : Anim.computeDuration(anim);
    // 真实字段透传（wrist 状态动画用；false/缺省 = 不写）
    if (raw.override_previous_animation === true) anim.override = true;
    // 运行时参数声明（`params`；声明即契约，见 animParams.js）
    if (raw.params != null) {
        const pv = P.normalizeParams(raw.params);
        if (pv.errors.length) throw new Error(`动画 ${name} params 非法: ${pv.errors.join('; ')}`);
        if (Object.keys(pv.params).length) anim.params = pv.params;
    }
    // W7：timeline（过点 MoLang 脚本）保全
    const timeline = timelineFromRaw(raw.timeline);
    if (timeline) anim.timeline = timeline;
    return anim;
}

/**
 * 导入 spark 动画文件（一文件可含多动画，如 base_state bundle）。
 * @returns {{ok: true, data: Map<string, Animation>}}
 * @throws 结构非法
 */
function importAnimationFile(source) {
    const obj = toObject(source, '动画');
    const map = obj.animations;
    if (!map || typeof map !== 'object' || Array.isArray(map)) throw new Error('缺少 animations 映射');
    const out = new Map();
    for (const [name, raw] of Object.entries(map)) {
        out.set(name, animationFromRaw(name, raw || {}));
    }
    for (const anim of out.values()) {
        const { errors } = Anim.validateAnimation(anim);
        if (errors.length) throw new Error(`动画 ${anim.name} 非法: ${errors.join('; ')}`);
    }
    return { ok: true, data: out };
}

// ==================== 动画导出 ====================

/**
 * 导出单个动画为一文件 JSON 文本（规则1/2/3/6）：
 *   { animations: { [name]: { loop, animation_length, override?, params?, bones, timeline? } } }
 * - loop 恒为字符串；animation_length 永远显式 = max 关键帧时间（重算，不信存储值）
 * - lerp_mode 恒写（现网风格）；空通道/空轨不写出
 * - params：tick 相位写简写（默认值本身），substep 相位写 {default, phase}（见 animParams.js）
 * W7：
 * - pre 写出当且仅当 pre ≠ post（引擎 codec 缺省 pre=post，无损）
 * - timeline 写出为 { "<t>": [scripts] }（引擎要求 list）
 * - 未知 lerp_mode **拦截导出**（引擎 InterpolationType 只认 linear/catmullrom，
 *   未知值写入会炸整个动画文件解析）
 * @throws 动画未通过校验 / 含未知 lerp_mode
 */
function exportAnimationFile(anim) {
    const { errors } = Anim.validateAnimation(anim);
    if (errors.length) throw new Error(`无法导出动画 ${anim.name}: ${errors.join('; ')}`);

    const bones = {};
    for (const track of anim.tracks || []) {
        const chOut = {};
        for (const ch of Anim.CHANNELS) {
            const kfs = track[ch];
            if (!Array.isArray(kfs) || kfs.length === 0) continue;
            const keyMap = {};
            for (const k of kfs) {
                if (!Anim.LERP_MODES.includes(k.lerpMode)) {
                    throw new Error(`无法导出动画 ${anim.name}: 骨骼 ${track.bone} ${ch} t=${k.timeSec} 含未知 lerp_mode "${k.lerpMode}"（引擎不支持，请先改为 linear/catmullrom）`);
                }
                const post = normalizeValue(k.post);
                const entry = { post };
                // pre 恒写（当导入时存在）：现网文件显式写 pre==post 的关键帧，
                // 省略会破坏 import(export(m)) 的 deep-equal 往返（语义无损但数据模型不等）
                if (k.pre != null) entry.pre = normalizeValue(k.pre);
                entry.lerp_mode = k.lerpMode;
                keyMap[formatTime(k.timeSec)] = entry;
            }
            chOut[ch] = keyMap;
        }
        if (Object.keys(chOut).length) bones[track.bone] = chOut;
    }

    // animation_length 永远显式；值 = 存储的 durationSec（导入时保留文件原值，无损回写），
    // 新建动画的 durationSec 已 = max 关键帧时间（createAnimation 保证）
    const duration = Number.isFinite(anim.durationSec) ? anim.durationSec : Anim.computeDuration(anim);
    const animRaw = {
        loop: anim.loop,
        animation_length: round4(duration),
    };
    if (anim.override === true) animRaw.override_previous_animation = true; // 文件顺序：loop, animation_length, override, params, bones, timeline
    if (anim.params && Object.keys(anim.params).length) animRaw.params = P.paramsToRaw(anim.params);
    if (Object.keys(bones).length) animRaw.bones = bones;
    if (Array.isArray(anim.timeline) && anim.timeline.length) {
        const tl = {};
        for (const entry of anim.timeline) {
            tl[formatTime(entry.timeSec)] = (entry.scripts || []).slice();
        }
        animRaw.timeline = tl;
    }
    const out = { animations: { [anim.name]: animRaw } };
    return JSON.stringify(out, null, 2) + '\n';
}

// ==================== 文档级（模型 + 全套动画） ====================

/**
 * 载入一个"文档"：模型文件 + 动画目录（D2：一窗口 = 一个模型的全套动画）。
 * @param {string} modelFile 模型 json 绝对路径
 * @param {string} [animDir] 动画目录（缺省 = 模型同级 <stem>/ 目录，即 spark 惯例）
 * @returns {{ok: true, data: {model: Geometry, animations: Map<string, Animation>, animFiles: Object<string,string>}}}
 *   animFiles = { 动画名: 文件绝对路径 }（多动画 bundle 文件里每个动画都指向同一文件）
 */
/**
 * 由模型文件路径推导默认动画目录（本项目布局）：
 *   spark_models/<type>/<ns>/<model>.json → spark_animations/<type>/<ns>/<model>/
 * 非 spark_models 布局 → null（调用方须显式给 animDir）。
 */
function defaultAnimDir(modelFile) {
    const path = require('path');
    const parts = path.resolve(modelFile).split(path.sep);
    const i = parts.lastIndexOf('spark_models');
    if (i < 0) return null;
    parts[i] = 'spark_animations';
    parts.pop(); // <model>.json
    return parts.join(path.sep) + path.sep + path.basename(modelFile, '.json');
}

function loadDocument(modelFile, animDir) {
    const fs = require('fs');
    const path = require('path');
    const modelText = fs.readFileSync(modelFile, 'utf-8');
    const model = importModel(modelText).data;

    const dir = animDir || defaultAnimDir(modelFile);
    const animations = new Map();
    const animFiles = {};
    if (fs.existsSync(dir) && fs.statSync(dir).isDirectory()) {
        for (const file of fs.readdirSync(dir).sort().filter((f) => f.endsWith('.json'))) {
            const abs = path.join(dir, file);
            const { data } = importAnimationFile(fs.readFileSync(abs, 'utf-8'));
            for (const [name, anim] of data) {
                if (animations.has(name)) {
                    throw new Error(`动画重名: ${name}（${animFiles[name]} 与 ${abs}）`);
                }
                animations.set(name, anim);
                animFiles[name] = abs;
            }
        }
    }
    return { ok: true, data: { model, animations, animFiles } };
}

const SparkCodecAPI = {
    importModel,
    exportModel,
    importAnimationFile,
    exportAnimationFile,
    loadDocument,
};
if (isNode) module.exports = SparkCodecAPI;
if (globalScope) globalScope.SparkCodec = SparkCodecAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
