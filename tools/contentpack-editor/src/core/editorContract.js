// editorContract.js - 编辑器契约（Module-Editor-Schema）读取 / 校验 / 资产清单
//
// 扩展指南（EDITOR_EXTENSION_GUIDE）B 方案：
//   包在 MANIFEST 声明 `Module-Editor-Schema: editor/animation-contract.json`，
//   编辑器从包源目录动态读取契约并据此装配界面 —— 编辑器不硬编码任何包内容。
//   无契约的包 = 自由编辑模式（本模块返回 no-contract，UI 显示提示）。
//
// 契约 v1（content_type = spark_animation_set）：
//   {
//     "contract_version": 1,
//     "content_type": "spark_animation_set",
//     "sets": [
//       { "id": "sword", "label": "…",
//         "model": { "path": "spark_models/…/sword.json", "texture": "assets/…/sword.png" },
//         "animations": { "dir": "spark_animations/…/sword/",
//                         "required": [ { "name": "still", "loop": "true" },
//                                        { "name": "base_state", "bundle": ["state.land.idle", …] } ] } }
//     ],
//     "wiring":   { "referenced_by": "…", "state_machine": null },
//     "validation": { "bones_must_exist_in_model": true, "max_duration": 6.0 }
//   }
//
// 纯函数 + node 可测（风格同 manifest.js / stateMachine.js）。
// 路径约定：契约内所有 path/dir 均相对于包源目录（contentpacks-source/<包>/）。

const fs = require('fs');
const path = require('path');

const SUPPORTED_VERSIONS = [1];
const CONTENT_TYPES = ['spark_animation_set'];
const LOOP_VALUES = ['true', 'once', 'hold_on_last_frame'];

/** 读 UTF-8 JSON 文件（剥离 BOM —— 真实 spark 文件带 BOM，Spark 游戏侧容忍，编辑器工具同样容忍） */
function readJsonTolerant(file) {
    return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
}

/**
 * 校验契约对象结构（不触盘）。
 * @param {Object} obj JSON.parse 后的契约
 * @returns {{ok:boolean, contract?:Object, errors?:string[]}}
 */
function parseContract(obj) {
    const errors = [];
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
        return { ok: false, errors: ['契约根节点必须是对象'] };
    }
    if (!SUPPORTED_VERSIONS.includes(obj.contract_version)) {
        errors.push(`contract_version 不支持（当前支持: ${SUPPORTED_VERSIONS.join(', ')}）`);
    }
    if (!CONTENT_TYPES.includes(obj.content_type)) {
        errors.push(`content_type 不支持: ${obj.content_type}（当前支持: ${CONTENT_TYPES.join(', ')}）`);
    }

    const sets = obj.sets;
    if (!Array.isArray(sets) || sets.length === 0) {
        errors.push('sets 不能为空（契约至少包含一个模型/动画集）');
    } else {
        const seen = new Set();
        sets.forEach((set, i) => {
            const where = `sets[${i}]`;
            if (!set || typeof set !== 'object') { errors.push(`${where} 必须是对象`); return; }
            if (!set.id || typeof set.id !== 'string') { errors.push(`${where}.id 缺失`); }
            else if (seen.has(set.id)) { errors.push(`${where}.id 重复: ${set.id}`); }
            if (set.id) seen.add(set.id);

            // 2026-10-14 P4：跨包资产引用（数据驱动包复用他包模型/动画，如 zombie-4 复用 zombie-3）
            if (set.model && set.model.ref_pack != null && typeof set.model.ref_pack !== 'string') {
                errors.push(`${where}.model.ref_pack 必须是字符串（兄弟包目录名）`);
            }
            if (!set.model || typeof set.model.path !== 'string' || !set.model.path) {
                errors.push(`${where}.model.path 缺失（包内相对路径）`);
            }
            if (set.model && set.model.texture != null && typeof set.model.texture !== 'string') {
                errors.push(`${where}.model.texture 必须是字符串`);
            }

            const anims = set.animations;
            if (anims && anims.ref_pack != null && typeof anims.ref_pack !== 'string') {
                errors.push(`${where}.animations.ref_pack 必须是字符串（兄弟包目录名）`);
            }
            if (!anims || typeof anims.dir !== 'string' || !anims.dir) {
                errors.push(`${where}.animations.dir 缺失（包内相对目录，须以 / 结尾）`);
            } else if (!anims.dir.endsWith('/')) {
                errors.push(`${where}.animations.dir 须以 / 结尾: ${anims.dir}`);
            }
            if (!Array.isArray(anims && anims.required) || anims.required.length === 0) {
                errors.push(`${where}.animations.required 不能为空`);
            } else {
                anims.required.forEach((r, j) => {
                    const rw = `${where}.animations.required[${j}]`;
                    if (!r || typeof r.name !== 'string' || !r.name) { errors.push(`${rw}.name 缺失`); return; }
                    if (r.name.includes('/') || r.name.includes('\\') || r.name.endsWith('.json')) {
                        errors.push(`${rw}.name 只能是文件名（不带路径与 .json 后缀）: ${r.name}`);
                    }
                    if (r.loop != null && !LOOP_VALUES.includes(r.loop)) {
                        errors.push(`${rw}.loop 无效: ${r.loop}（允许: ${LOOP_VALUES.join(' / ')}）`);
                    }
                    if (r.bundle != null && (!Array.isArray(r.bundle) || r.bundle.some((n) => typeof n !== 'string'))) {
                        errors.push(`${rw}.bundle 必须是字符串数组（bundle 文件包含的动画名）`);
                    }
                });
            }
        });
    }

    if (obj.wiring != null && (typeof obj.wiring !== 'object' || Array.isArray(obj.wiring))) {
        errors.push('wiring 必须是对象（或缺省）');
    } else if (obj.wiring) {
        const sm = obj.wiring.state_machine;
        if (sm != null) {
            if (typeof sm !== 'object' || Array.isArray(sm)) {
                errors.push('wiring.state_machine 必须是对象（或缺省）');
            } else {
                if (!['java_state_machine', 'timetape'].includes(sm.type)) {
                    errors.push(`wiring.state_machine.type 无效: ${sm.type}（允许: java_state_machine / timetape）`);
                }
                if (sm.type === 'timetape' && (typeof sm.path !== 'string' || !sm.path)) {
                    errors.push('wiring.state_machine.path 缺失（timetape 须指向状态机 JSON，包内相对路径）');
                }
            }
        }
    }
    if (obj.validation != null) {
        const v = obj.validation;
        if (typeof v !== 'object' || Array.isArray(v)) { errors.push('validation 必须是对象（或缺省）'); }
        else {
            if (v.max_duration != null && !(typeof v.max_duration === 'number' && v.max_duration > 0)) {
                errors.push('validation.max_duration 必须是正数（秒）');
            }
            if (v.bones_must_exist_in_model != null && typeof v.bones_must_exist_in_model !== 'boolean') {
                errors.push('validation.bones_must_exist_in_model 必须是布尔');
            }
        }
    }

    if (errors.length) return { ok: false, errors };
    return { ok: true, contract: obj };
}

/**
 * 从包源目录读取契约（manifest 须来自同一包）。
 * @param {string} packDir 包源目录
 * @param {Object<string,string>} manifest parseManifest 结果
 * @returns {{ok:true, data:{schemaPath:string, contract:Object}} | {ok:false, error?:string, errors?:string[]}}
 */
function loadContract(packDir, manifest) {
    const schemaRel = manifest && manifest['Module-Editor-Schema'];
    if (!schemaRel) {
        return { ok: false, error: 'no-contract' }; // 该包未声明编辑器契约（合法状态，UI 提示即可）
    }
    const abs = path.resolve(packDir, schemaRel);
    const packRoot = path.resolve(packDir);
    if (abs !== packRoot && !abs.startsWith(packRoot + path.sep)) {
        return { ok: false, error: `契约路径越界: ${schemaRel}` };
    }
    if (!fs.existsSync(abs)) {
        return { ok: false, error: `契约文件不存在: ${schemaRel}` };
    }
    let obj;
    try {
        obj = JSON.parse(fs.readFileSync(abs, 'utf-8'));
    } catch (e) {
        return { ok: false, error: `契约 JSON 解析失败: ${e.message}` };
    }
    const parsed = parseContract(obj);
    if (!parsed.ok) return { ok: false, errors: parsed.errors };
    return { ok: true, data: { schemaPath: schemaRel, contract: parsed.contract } };
}

/**
 * 读取 spark 模型文件的骨骼名列表（读失败 → []）。
 * 兼容两种 Bedrock 格式：旧 = 对象映射 {boneName: {...}}；新（1.19+）= 数组 [{name, ...}]。
 */
function modelBoneNames(modelFile) {
    try {
        const obj = readJsonTolerant(modelFile);
        const geo = Array.isArray(obj['minecraft:geometry']) ? obj['minecraft:geometry'][0] : obj;
        const bones = (geo && geo.bones) || [];
        if (Array.isArray(bones)) {
            return bones.map((b) => b && b.name).filter(Boolean);
        }
        return Object.keys(bones);
    } catch {
        return [];
    }
}

/** 读单个动画文件 → 其包含的全部动画摘要（bundle 文件会有多个）。失败 → null。 */
function readAnimFile(file) {
    try {
        const obj = readJsonTolerant(file);
        const anims = Object.entries((obj && obj.animations) || {}).map(([name, a]) => ({
            name,
            // loop 在 Spark 文件里可能是字符串 "true" 或布尔 true，统一归一为字符串比较
            loop: a && a.loop == null ? undefined : (a.loop === true ? 'true' : String(a.loop)),
            duration: a && a.animation_length != null ? a.animation_length : undefined,
            bones: Object.keys((a && a.bones) || {}),
        }));
        return anims;
    } catch {
        return null;
    }
}

// REVIEW-S6：契约内的 model.path / model.texture / animations.dir 由包作者控制。
// loadContract 对 Module-Editor-Schema 本身有边界检查，但这里此前直接
// path.resolve(packDir, …) 无校验 → animations.dir='../../../任意目录/' 时对
// 每个 required 动画名执行 existsSync + readAnimFile（读文件、回显动画/骨骼名），
// model.path 越界时 modelBoneNames 读任意 JSON。统一：越界路径按"不存在"处理。
function safePackPath(packDir, rel) {
    if (typeof rel !== 'string' || !rel) return null;
    const abs = path.resolve(packDir, rel);
    const root = path.resolve(packDir);
    if (abs !== root && !abs.startsWith(root + path.sep)) return null;
    return abs;
}

// 2026-10-14 P4：跨包资产引用 —— 数据驱动包（zombie-4）运行时经 ResourceManager
// 引用他包（zombie-3）的模型/动画；编辑器侧对同仓库兄弟目录做存在性校验。
function siblingPackDir(packDir, refPack) {
    if (typeof refPack !== 'string' || !refPack) return null;
    return path.resolve(packDir, '..', refPack);
}

/**
 * 按契约盘点资产（只读，不改盘）。
 * @returns {Array<Object>} 每个 set 一条：
 *   { id, label, model:{path,exists,texture,textureExists},
 *     animations:[{name,file,exists,loopRequired,loopActual,duration,animations,bundle,missingBundled,bones}],
 *     errors:[], warnings:[] }
 */
function listContractAssets(packDir, contract) {
    return contract.sets.map((set) => {
        const modelRoot = set.model.ref_pack ? siblingPackDir(packDir, set.model.ref_pack) : packDir;
        const modelFile = modelRoot ? safePackPath(modelRoot, set.model.path) : null;
        const modelExists = !!modelFile && fs.existsSync(modelFile);
        const texture = set.model.texture || null;
        const textureRoot = set.model.ref_pack ? siblingPackDir(packDir, set.model.ref_pack) : packDir;
        const textureFile = texture ? (textureRoot ? safePackPath(textureRoot, texture) : null) : null;
        const textureExists = texture ? (textureFile ? fs.existsSync(textureFile) : false) : null;

        const animRoot = set.animations.ref_pack ? siblingPackDir(packDir, set.animations.ref_pack) : packDir;
        const animDir = animRoot ? safePackPath(animRoot, set.animations.dir) : null;
        const animDirSafe = !!animDir;
        const animations = set.animations.required.map((r) => {
            // REVIEW-S6：animDir 越界（或 r.name 异常）→ 按"不存在"处理，不触盘
            const file = animDirSafe
                ? (path.isAbsolute(r.name) ? null : path.join(animDir, r.name + '.json'))
                : null;
            const exists = !!file && fs.existsSync(file);
            const entry = {
                name: r.name,
                file: path.join(set.animations.dir, r.name + '.json').split(path.sep).join('/'),
                exists,
                loopRequired: r.loop || null,
                loopActual: null,
                duration: null,
                animations: [],
                bundle: r.bundle || [],
                missingBundled: [],
                bones: [],
            };
            if (exists) {
                const anims = readAnimFile(file);
                if (anims === null) {
                    entry.parseError = true;
                } else {
                    entry.animations = anims;
                    // 单动画文件：主动画 = 与文件名同名者（若有），否则第一个
                    const main = anims.find((a) => a.name === r.name) || anims[0];
                    if (main) {
                        entry.loopActual = main.loop;
                        entry.duration = main.duration;
                    }
                    if (r.bundle && r.bundle.length) {
                        entry.missingBundled = r.bundle.filter((n) => !anims.some((a) => a.name === n));
                    }
                    entry.bones = Array.from(new Set(anims.flatMap((a) => a.bones)));
                }
            }
            return entry;
        });

        return {
            id: set.id,
            label: set.label || set.id,
            // ref_pack 随摘要传递（validateContract 的跨包骨骼校验/兄弟包存在性检查依赖它）
            model: { path: set.model.path, exists: modelExists, texture, textureExists, ref_pack: set.model.ref_pack || null },
            animRefPack: (set.animations && set.animations.ref_pack) || null,
            animations,
            errors: [],
            warnings: [],
        };
    });
}

/**
 * 契约校验：资产完整性 + 模型骨骼覆盖 + 时长上限。
 * 结果写回每个 set 的 errors/warnings，同时返回汇总。
 * @returns {{errors:string[], warnings:string[]}}
 */
function validateContract(contract, sets) {
    const errors = [];
    const warnings = [];
    const maxDur = contract.validation && contract.validation.max_duration;
    const bonesCheck = !!(contract.validation && contract.validation.bones_must_exist_in_model);

    for (const set of sets) {
        const tag = `set[${set.id}]`;
        if (!set.model.exists) {
            set.errors.push(`${tag} 模型文件缺失: ${set.model.path}`);
            errors.push(`${tag} 模型文件缺失: ${set.model.path}`);
        }
        if (set.model.texture && set.model.textureExists === false) {
            set.warnings.push(`${tag} 贴图缺失: ${set.model.texture}`);
            warnings.push(`${tag} 贴图缺失: ${set.model.texture}`);
        }

        // 2026-10-14 P4：ref_pack 时模型在兄弟包目录（与 listContractAssets 同解析逻辑；
        // 此前直接 resolve(set._packDir) 读到不存在文件 → 空骨骼表 → 全部动画误报"未知骨骼"）
        let modelBones = null;
        if (bonesCheck && set.model.exists) {
            const modelRoot = set.model.ref_pack ? siblingPackDir(set._packDir, set.model.ref_pack) : set._packDir;
            const modelAbs = modelRoot ? safePackPath(modelRoot, set.model.path) : null;
            modelBones = modelAbs ? modelBoneNames(modelAbs) : null;
        }

        // 2026-10-14 P4：跨包引用 —— 兄弟包目录不存在 = 无法校验（错误）
        if ((set.model.ref_pack || set.animRefPack)) {
            const refs = new Set([set.model.ref_pack, set.animRefPack].filter(Boolean));
            for (const rp of refs) {
                if (!fs.existsSync(siblingPackDir(set._packDir, rp))) {
                    const msg = `${tag} 外部包缺失: ${rp}（contentpacks-source/${rp}/ 不存在，运行时也需同时安装该包）`;
                    set.errors.push(msg); errors.push(msg);
                }
            }
        }

        for (const a of set.animations) {
            if (!a.exists) {
                const msg = `${tag} 必备动画缺失: ${a.file}`;
                set.errors.push(msg); errors.push(msg);
                continue;
            }
            if (a.parseError) {
                const msg = `${tag} 动画文件解析失败: ${a.file}`;
                set.errors.push(msg); errors.push(msg);
                continue;
            }
            if (a.loopRequired && a.loopActual && a.loopActual !== a.loopRequired) {
                const msg = `${tag} 动画 ${a.name} loop 与契约不符（契约=${a.loopRequired}，实际=${a.loopActual}）`;
                set.warnings.push(msg); warnings.push(msg);
            }
            if (a.missingBundled.length) {
                const msg = `${tag} bundle 文件 ${a.name} 缺少动画: ${a.missingBundled.join(', ')}`;
                set.errors.push(msg); errors.push(msg);
            }
            if (maxDur) {
                const over = a.animations.filter((x) => x.duration != null && x.duration > maxDur);
                if (over.length) {
                    const msg = `${tag} 动画超长（>${maxDur}s）: ${over.map((x) => `${x.name}=${x.duration}s`).join(', ')}`;
                    set.warnings.push(msg); warnings.push(msg);
                }
            }
            if (modelBones) {
                const unknown = a.bones.filter((b) => !modelBones.includes(b));
                if (unknown.length) {
                    // Spark 对未知骨骼引用是容错的（游戏里照常播放），故只警告不阻断
                    const msg = `${tag} 动画 ${a.name} 引用了模型不存在的骨骼: ${unknown.slice(0, 5).join(', ')}${unknown.length > 5 ? '…' : ''}`;
                    set.warnings.push(msg); warnings.push(msg);
                }
            }
        }
    }
    return { errors, warnings };
}

// ==================== 2026-10-14 P4：TimeTape 状态机契约校验 ====================
//
// 引擎侧（Java）的 TapeStateMachine.parse 是运行时真源；这里是编辑器侧的
// JS 镜像校验（结构 + 引用完整性），让包作者在保存前就发现状态机写坏。
// 词汇表与 Java 引擎保持同步（见 core/anim/tape/TapeStateMachine.java）：
//   条件词汇：is_moving/is_sprinting/has_target/is_on_ground/is_in_water/
//             is_on_climbable/is_dead/swinging + not/all/any
//   动作词汇：play_sound/apply_damage/swing/set_tag/spawn_particle

const TTAPE_CONDITIONS = new Set([
    'is_moving', 'is_sprinting', 'has_target', 'is_on_ground', 'is_in_water',
    'is_on_climbable', 'is_dead', 'swinging',
]);
const TTAPE_ACTIONS = new Set(['play_sound', 'apply_damage', 'swing', 'set_tag', 'spawn_particle']);

/** 条件表达式里的裸标识符（剔除 not/all/any 与字符串字面量）→ 未知词汇 = 警告 */
function conditionIdentifiers(expr) {
    if (typeof expr !== 'string') return [];
    const stripped = expr.replace(/'[^']*'|"[^"]*"/g, '');
    const ids = [];
    for (const m of stripped.matchAll(/[a-z_][a-z0-9_]*/g)) {
        if (m[0] !== 'not' && m[0] !== 'all' && m[0] !== 'any') ids.push(m[0]);
    }
    return ids;
}

/**
 * 校验 wiring.state_machine.type === 'timetape' 的状态机 JSON（结构+引用）。
 * @returns {{errors:string[], warnings:string[], machine:Object|null}}
 *   machine = 供 UI 展示的轨/状态/纸带/转移摘要
 */
function validateTimeTape(packDir, contract, sets) {
    const out = { errors: [], warnings: [], machine: null };
    const sm = contract.wiring && contract.wiring.state_machine;
    if (!sm || sm.type !== 'timetape') return out;

    const file = safePackPath(packDir, sm.path);
    if (!file) {
        out.errors.push(`state_machine 路径越界: ${sm.path}`);
        return out;
    }
    if (!fs.existsSync(file)) {
        out.errors.push(`state_machine 文件缺失: ${sm.path}`);
        return out;
    }
    let obj;
    try {
        obj = readJsonTolerant(file);
    } catch (e) {
        out.errors.push(`state_machine JSON 解析失败: ${e.message}`);
        return out;
    }
    const tracks = obj.tracks;
    if (!tracks || typeof tracks !== 'object' || Array.isArray(tracks) || !Object.keys(tracks).length) {
        out.errors.push('state_machine.tracks 缺失或为空');
        return out;
    }

    const machine = { path: sm.path, initialTags: obj.initial_tags || {}, tracks: {} };
    for (const [tid, tr] of Object.entries(tracks)) {
        if (!tr || typeof tr !== 'object') {
            out.errors.push(`tracks.${tid} 必须是对象`);
            continue;
        }
        const states = tr.states;
        if (!states || typeof states !== 'object' || Array.isArray(states) || !Object.keys(states).length) {
            out.errors.push(`tracks.${tid}.states 缺失或为空`);
            continue;
        }
        const tinfo = { id: tid, initial: tr.initial || null, states: {} };
        if (!tr.initial || !states[tr.initial]) {
            out.errors.push(`tracks.${tid}.initial 无效: ${tr.initial}（须指向已定义状态）`);
        }
        for (const [sid, s] of Object.entries(states)) {
            if (!s || typeof s !== 'object') {
                out.errors.push(`tracks.${tid}.states.${sid} 必须是对象`);
                continue;
            }
            const sinfo = { id: sid, anims: [], segs: [], exits: [], onEnter: [], problems: [] };
            const collectAnim = (a) => {
                if (!a || typeof a !== 'object') return;
                if (typeof a.name === 'string') sinfo.anims.push(a.name);
                if (a.alt && typeof a.alt.then === 'string') sinfo.anims.push(a.alt.then);
            };
            if (s.anim) collectAnim(s.anim);
            let seq = false;
            if (Array.isArray(s.segments) && s.segments.length) {
                seq = true;
                let prevUntil = -1;
                s.segments.forEach((g, i) => {
                    if (!g || typeof g !== 'object') { sinfo.problems.push(`segments[${i}] 必须是对象`); return; }
                    if (typeof g.until !== 'number' || !isFinite(g.until) || g.until <= prevUntil) {
                        sinfo.problems.push(`segments[${i}].until 无效（须为递增正数）: ${g.until}`);
                    }
                    prevUntil = Math.max(prevUntil, g.until);
                    if (!g.anim) sinfo.problems.push(`segments[${i}] 缺少 anim`);
                    collectAnim(g.anim);
                    if (g.next != null && (typeof g.next !== 'string' || !states[g.next])) {
                        sinfo.problems.push(`segments[${i}].next 指向不存在的状态: ${g.next}`);
                    }
                    if (Array.isArray(g.milestones)) {
                        g.milestones.forEach((m, mi) => {
                            if (!m || typeof m !== 'object') { sinfo.problems.push(`segments[${i}].milestones[${mi}] 必须是对象`); return; }
                            if (typeof m.at !== 'number' || m.at < 0 || m.at > g.until) {
                                sinfo.problems.push(`segments[${i}].milestones[${mi}].at 超出段范围 [0, ${g.until}]`);
                            }
                            if (typeof m.do !== 'string' || !TTAPE_ACTIONS.has(m.do)) {
                                sinfo.problems.push(`segments[${i}].milestones[${mi}] 未知动作: ${m.do}`);
                            }
                        });
                    }
                    sinfo.segs.push({ until: g.until, next: g.next || null });
                });
            }
            if (!s.anim && !seq) {
                sinfo.problems.push('无循环动画且无纸带段（状态无可播动画，引擎解析会失败）');
            }
            if (s.on_enter != null) {
                if (!Array.isArray(s.on_enter)) sinfo.problems.push('on_enter 必须是数组');
                else s.on_enter.forEach((a, i) => {
                    if (!a || typeof a !== 'object' || typeof a.do !== 'string' || !TTAPE_ACTIONS.has(a.do)) {
                        sinfo.problems.push(`on_enter[${i}] 未知动作: ${a && a.do}`);
                    } else {
                        sinfo.onEnter.push(a.do);
                    }
                });
            }
            if (s.exit != null) {
                if (!Array.isArray(s.exit)) sinfo.problems.push('exit 必须是数组');
                else s.exit.forEach((e, i) => {
                    if (!e || typeof e !== 'object') { sinfo.problems.push(`exit[${i}] 必须是对象`); return; }
                    if (typeof e.to !== 'string' || !states[e.to]) {
                        sinfo.problems.push(`exit[${i}].to 指向不存在的状态: ${e.to}`);
                    } else {
                        sinfo.exits.push({ when: e.when || 'true', to: e.to, priority: e.priority || 0, after: e.after || 0 });
                    }
                    for (const id of conditionIdentifiers(e.when)) {
                        if (!TTAPE_CONDITIONS.has(id)) {
                            out.warnings.push(`state_machine[${tid}.${sid}].exit[${i}] 未知条件词汇: ${id}`);
                        }
                    }
                });
            }
            tinfo.states[sid] = sinfo;
        }
        machine.tracks[tid] = tinfo;
    }

    // 动画引用完整性：状态机引用的动画名必须在契约声明的必备动画内
    const knownAnims = new Set();
    for (const s of sets) for (const a of s.animations) knownAnims.add(a.name);
    for (const t of Object.values(machine.tracks)) {
        for (const s of Object.values(t.states)) {
            const unknown = Array.from(new Set(s.anims.filter((n) => !knownAnims.has(n))));
            if (unknown.length) {
                out.errors.push(`state_machine[${t.id}.${s.id}] 引用了契约未声明的动画: ${unknown.join(', ')}`);
            }
        }
    }
    for (const t of Object.values(machine.tracks)) {
        for (const s of Object.values(t.states)) {
            for (const p of s.problems) out.errors.push(`state_machine[${t.id}.${s.id}] ${p}`);
        }
    }
    out.machine = machine;
    return out;
}

/**
 * 一次性入口（供 IPC handler 用）：读契约 + 盘点 + 校验（含 TimeTape 状态机）。
 * @returns {{ok:true, data:{schemaPath, contract, sets, errors, warnings, stateMachine}} | {ok:false, error?, errors?}}
 */
function buildReport(packDir, manifest) {
    const loaded = loadContract(packDir, manifest);
    if (!loaded.ok) return loaded;
    const sets = listContractAssets(packDir, loaded.data.contract).map((s) => Object.assign({}, s, { _packDir: packDir }));
    const { errors, warnings } = validateContract(loaded.data.contract, sets);
    const tape = validateTimeTape(packDir, loaded.data.contract, sets);
    return {
        ok: true,
        data: {
            schemaPath: loaded.data.schemaPath,
            contract: loaded.data.contract,
            sets: sets.map(({ _packDir, ...rest }) => rest),
            errors: errors.concat(tape.errors),
            warnings: warnings.concat(tape.warnings),
            stateMachine: tape.machine,
        },
    };
}

module.exports = {
    LOOP_VALUES,
    CONTENT_TYPES,
    TTAPE_CONDITIONS,
    TTAPE_ACTIONS,
    parseContract,
    loadContract,
    modelBoneNames,
    readAnimFile, // 导出供 CLI（editor-cli.js）复用；主窗口 IPC 不暴露
    listContractAssets,
    validateContract,
    validateTimeTape,
    buildReport,
};
