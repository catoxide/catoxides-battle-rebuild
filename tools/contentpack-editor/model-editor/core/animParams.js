// animParams.js - 动画运行时参数（Spark-Core fork 的 `params` 契约）的编辑器侧模型
// 纯函数，node 可测。与 Spark-Core `AnimParamCatalog.kt` / `AnimParamDecl.kt` 对齐。
//
// 动画 JSON 里的写法（引擎侧 codec：AnimParamDecl.CODEC）：
//
//   "params": {
//     "pitch": 0.0,                                       // 简写 = 只声明默认值，相位 tick（20Hz）
//     "biome": "minecraft:plains",                         // 字符串默认值，同样可简写
//     "body_speed": { "default": 0.0, "phase": "substep" } // 完整形 = 默认值 + 相位（60Hz 物理子步）
//   }
//
// 语义（详见 fork docs/RUNTIME_ANIM_PARAMS.md）：
//   - `default` 只在运行时参数缺失时兜底（客户端还没收到同步包 / 单独预览动画），不参与同步；
//   - **声明即契约**：只有当前播放动画声明过的参数名才会被收集、同步到客户端；
//     没声明的运行动画参数在客户端拿不到值（只能用默认值）。
//   - `phase`：tick（默认，20Hz 主线程）/ substep（服务端物理线程 60Hz；
//     客户端仍是 20Hz 同步值 + 骨骼插值，不增加带宽）。
//
// 数据模型（编辑器内部规范形，animation.params）：
//   { [name]: { default: number|string, phase: 'tick'|'substep' } }
//   键顺序 = JSON 写出顺序（与导入顺序一致，round-trip 无损）。

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.AnimParams）
(function (globalScope) {
'use strict';

const PHASES = ['tick', 'substep'];

const GROUP_LABELS = {
    kinematics: '姿态 / 移动',
    aim: '瞄准对准',
    location: '地点 / 世界',
    substep: '物理子步（60Hz）',
};

/**
 * 内置参数目录 —— 与 Spark-Core `AnimParamCatalog.kt` 一一对应（改那边时请同步这里；
 * 编辑器的参数面板用它做自动补全/说明，未知名字按"业务侧自定义参数"处理）。
 * kind：num = 数值，str = 字符串（Molang 里用于比较）。
 */
const BUILTIN = [
    // ---------------- kinematics（tick 20Hz）
    { name: 'pitch', phase: 'tick', kind: 'num', group: 'kinematics', desc: '视线俯仰角（度）' },
    { name: 'yaw', phase: 'tick', kind: 'num', group: 'kinematics', desc: '身体朝向（度）' },
    { name: 'head_yaw', phase: 'tick', kind: 'num', group: 'kinematics', desc: '头部朝向（度）' },
    { name: 'speed', phase: 'tick', kind: 'num', group: 'kinematics', desc: '水平速度（米/秒）' },
    { name: 'vertical_speed', phase: 'tick', kind: 'num', group: 'kinematics', desc: '竖直速度（米/秒，向上为正）' },
    { name: 'on_ground', phase: 'tick', kind: 'num', group: 'kinematics', desc: '是否站在地面（0/1）' },
    { name: 'sprinting', phase: 'tick', kind: 'num', group: 'kinematics', desc: '是否疾跑（0/1）' },
    { name: 'sneaking', phase: 'tick', kind: 'num', group: 'kinematics', desc: '是否潜行（0/1）' },
    { name: 'swimming', phase: 'tick', kind: 'num', group: 'kinematics', desc: '是否游泳姿态（0/1）' },
    { name: 'in_water', phase: 'tick', kind: 'num', group: 'kinematics', desc: '是否在水中（0/1）' },
    { name: 'fall_distance', phase: 'tick', kind: 'num', group: 'kinematics', desc: '下落距离（格）' },
    { name: 'health', phase: 'tick', kind: 'num', group: 'kinematics', desc: '当前生命值（仅生物）' },
    { name: 'max_health', phase: 'tick', kind: 'num', group: 'kinematics', desc: '最大生命值（仅生物）' },
    { name: 'health_ratio', phase: 'tick', kind: 'num', group: 'kinematics', desc: '生命值比例 0~1（仅生物）' },
    { name: 'hurt_time', phase: 'tick', kind: 'num', group: 'kinematics', desc: '受击硬直剩余 tick（仅生物）' },
    { name: 'age', phase: 'tick', kind: 'num', group: 'kinematics', desc: '实体存活 tick 数' },
    // ---------------- aim（tick 20Hz）
    { name: 'aim_yaw', phase: 'tick', kind: 'num', group: 'aim', desc: '瞄准方向 yaw（度；无目标时 = 视线）' },
    { name: 'aim_pitch', phase: 'tick', kind: 'num', group: 'aim', desc: '瞄准方向 pitch（度；无目标时 = 视线）' },
    { name: 'aim_delta_yaw', phase: 'tick', kind: 'num', group: 'aim', desc: '瞄准与身体朝向的 yaw 差（就近回绕）' },
    { name: 'aim_delta_pitch', phase: 'tick', kind: 'num', group: 'aim', desc: '瞄准与当前俯仰的 pitch 差' },
    { name: 'has_target', phase: 'tick', kind: 'num', group: 'aim', desc: '是否有攻击目标（0/1）' },
    { name: 'target_distance', phase: 'tick', kind: 'num', group: 'aim', desc: '到攻击目标的直线距离（格）' },
    // ---------------- location（tick 20Hz）
    { name: 'pos_x', phase: 'tick', kind: 'num', group: 'location', desc: '世界坐标 X' },
    { name: 'pos_y', phase: 'tick', kind: 'num', group: 'location', desc: '世界坐标 Y' },
    { name: 'pos_z', phase: 'tick', kind: 'num', group: 'location', desc: '世界坐标 Z' },
    { name: 'dimension', phase: 'tick', kind: 'str', group: 'location', desc: '维度 id（按维度切换动画）' },
    { name: 'biome', phase: 'tick', kind: 'str', group: 'location', desc: '生物群系 id（按地点切换动画）' },
    { name: 'block_light', phase: 'tick', kind: 'num', group: 'location', desc: '方块光照等级 0~15' },
    { name: 'sky_light', phase: 'tick', kind: 'num', group: 'location', desc: '天空光照等级 0~15' },
    { name: 'raining', phase: 'tick', kind: 'num', group: 'location', desc: '是否下雨（0/1）' },
    { name: 'day_time', phase: 'tick', kind: 'num', group: 'location', desc: '当天时间 0~23999' },
    // ---------------- substep（60Hz）
    { name: 'body_rot_x', phase: 'substep', kind: 'num', group: 'substep', desc: '刚体朝向欧拉角 X（度）' },
    { name: 'body_rot_y', phase: 'substep', kind: 'num', group: 'substep', desc: '刚体朝向欧拉角 Y（度）' },
    { name: 'body_rot_z', phase: 'substep', kind: 'num', group: 'substep', desc: '刚体朝向欧拉角 Z（度）' },
    { name: 'body_speed', phase: 'substep', kind: 'num', group: 'substep', desc: '刚体水平速度（米/秒）' },
    { name: 'body_vspeed', phase: 'substep', kind: 'num', group: 'substep', desc: '刚体竖直速度（米/秒）' },
    { name: 'body_ang_speed', phase: 'substep', kind: 'num', group: 'substep', desc: '刚体角速度大小（度/秒）' },
    { name: 'body_active', phase: 'substep', kind: 'num', group: 'substep', desc: '刚体是否处于激活状态（0/1）' },
];

const byName = new Map(BUILTIN.map((e) => [e.name, e]));

/** 参数名必须是 Molang 可点访问的标识符（`v.名字`） */
const NAME_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const isIdentifier = (name) => typeof name === 'string' && NAME_RE.test(name);
const isBuiltin = (name) => byName.has(name);
/** 内置参数的元数据（自定义参数 → null） */
const builtin = (name) => byName.get(name) || null;

function emptyParams() {
    return {};
}

/** 深拷贝（保持键顺序） */
function cloneParams(params) {
    const out = {};
    for (const [name, decl] of Object.entries(params || {})) {
        out[name] = { default: decl.default, phase: decl.phase };
    }
    return out;
}

function isParamsEmpty(params) {
    return !params || Object.keys(params).length === 0;
}

/**
 * 声明值归一（导入侧）：
 *   number|string  → { default, phase: 'tick' }      （简写）
 *   { default, phase? } → { default, phase }          （完整形；phase 缺省 tick）
 * @returns {{ok: boolean, decl?: {default, phase}, error?: string, warnings: string[]}}
 */
function normalizeDecl(raw) {
    const warnings = [];
    if (isNum(raw) || typeof raw === 'string') {
        return { ok: true, decl: { default: raw, phase: 'tick' }, warnings };
    }
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
        return { ok: false, error: '声明必须是数值 / 字符串 / {default, phase} 对象', warnings };
    }
    let def = raw.default;
    if (def === undefined) {
        // 容错：写成 { "phase": "substep" } 时给 0 兜底（引擎侧会报错，这里保全文件）
        warnings.push('缺少 default，按 0 兜底（引擎侧要求 default 必填）');
        def = 0;
    } else if (def === null) {
        return { ok: false, error: 'default 不能为 null', warnings };
    }
    if (!isNum(def) && typeof def !== 'string') {
        return { ok: false, error: 'default 必须是有限数或字符串', warnings };
    }
    let phase = raw.phase == null ? 'tick' : raw.phase;
    if (typeof phase !== 'string' || !PHASES.includes(phase)) {
        return { ok: false, error: `phase 非法: ${JSON.stringify(phase)}（可选 tick / substep）`, warnings };
    }
    for (const key of Object.keys(raw)) {
        if (key !== 'default' && key !== 'phase') warnings.push(`未知字段 "${key}" 将被丢弃`);
    }
    return { ok: true, decl: { default: def, phase }, warnings };
}

/**
 * 导入 raw `params` 对象 → 规范形 + 错误/警告。
 * @returns {{params: Object, errors: string[], warnings: string[]}}
 */
function normalizeParams(raw) {
    const params = {};
    const errors = [];
    const warnings = [];
    if (raw == null) return { params, errors, warnings };
    if (typeof raw !== 'object' || Array.isArray(raw)) {
        errors.push('params 必须是对象（名字 → 默认值 / {default, phase}）');
        return { params, errors, warnings };
    }
    for (const [name, value] of Object.entries(raw)) {
        if (!isIdentifier(name)) {
            errors.push(`参数名 "${name}" 非法（必须是 [A-Za-z_][A-Za-z0-9_]*，Molang 里写成 v.${name}）`);
            continue;
        }
        const r = normalizeDecl(value);
        warnings.push(...r.warnings.map((w) => `${name}: ${w}`));
        if (!r.ok) {
            errors.push(`${name}: ${r.error}`);
            continue;
        }
        params[name] = r.decl;
        const meta = builtin(name);
        if (!meta) {
            warnings.push(`${name}: 非内置参数（需要业务侧 provider 提供，否则运行时一直是默认值）`);
        } else if (r.decl.phase !== meta.phase) {
            warnings.push(`${name}: 内置参数相位是 ${meta.phase}，这里声明为 ${r.decl.phase}（以声明为准）`);
        }
    }
    return { params, errors, warnings };
}

/**
 * 校验规范形 params（不修改）。导入后改名字/改默认值时也会走到这里。
 * @returns {{errors: string[], warnings: string[]}}
 */
function validateParams(params) {
    const errors = [];
    const warnings = [];
    if (params == null) return { errors, warnings };
    if (typeof params !== 'object' || Array.isArray(params)) {
        errors.push('params 必须是对象');
        return { errors, warnings };
    }
    for (const [name, decl] of Object.entries(params)) {
        if (!isIdentifier(name)) {
            errors.push(`参数名 "${name}" 非法（必须是 [A-Za-z_][A-Za-z0-9_]*）`);
            continue;
        }
        if (!decl || typeof decl !== 'object' || Array.isArray(decl)) {
            errors.push(`${name}: 声明必须是对象`);
            continue;
        }
        if (!isNum(decl.default) && typeof decl.default !== 'string') {
            errors.push(`${name}: default 必须是有限数或字符串`);
        }
        if (!PHASES.includes(decl.phase)) errors.push(`${name}: phase 非法 ${JSON.stringify(decl.phase)}`);
        const meta = builtin(name);
        if (!meta) {
            warnings.push(`${name}: 非内置参数（需要业务侧 provider 提供，否则运行时一直是默认值）`);
        } else {
            const wantNum = meta.kind === 'num';
            const gotNum = isNum(decl.default);
            if (wantNum !== gotNum) {
                warnings.push(`${name}: 内置参数是 ${meta.kind} 类型，默认值却是 ${gotNum ? 'num' : 'str'}`);
            }
        }
    }
    return { errors, warnings };
}

/**
 * 规范形 → JSON raw 形态（导出侧）：
 *   phase = tick → 简写成默认值本身（引擎 codec 两种都能读，简写让文件干净）
 *   phase = substep → { default, phase: 'substep' }
 */
function paramsToRaw(params) {
    const out = {};
    for (const [name, decl] of Object.entries(params || {})) {
        if (!decl || decl.phase === 'tick') out[name] = decl ? decl.default : 0;
        else out[name] = { default: decl.default, phase: decl.phase };
    }
    return out;
}

/** 增/改一个声明（返回新对象，保持原顺序；已有键保留原位置） */
function withDecl(params, name, decl) {
    const out = cloneParams(params);
    if (out[name] != null) {
        out[name] = { default: decl.default, phase: decl.phase };
        return out;
    }
    out[name] = { default: decl.default, phase: decl.phase };
    return out;
}

/** 删除一个声明（返回新对象） */
function withoutDecl(params, name) {
    const out = {};
    for (const [key, decl] of Object.entries(params || {})) {
        if (key !== name) out[key] = { default: decl.default, phase: decl.phase };
    }
    return out;
}

/** 改名（返回 {params, error}；与已有名字冲突/非法 → 不修改） */
function renameDecl(params, oldName, newName) {
    if (oldName === newName) return { params: cloneParams(params) };
    if (!isIdentifier(newName)) return { params: cloneParams(params), error: `参数名 "${newName}" 非法` };
    if (params && params[newName] != null) return { params: cloneParams(params), error: `参数名已存在: ${newName}` };
    const out = {};
    for (const [key, decl] of Object.entries(params || {})) {
        out[key === oldName ? newName : key] = { default: decl.default, phase: decl.phase };
    }
    return { params: out };
}

/**
 * 预览用参数值表：声明默认值（供 evaluate 把 `v.名字` 替换成数值）。
 * @returns {Object<string, number|string>}
 */
function defaultValueMap(params) {
    const out = {};
    for (const [name, decl] of Object.entries(params || {})) out[name] = decl.default;
    return out;
}

/** 新建动画的初始参数（空） */
function createParams(entries) {
    const r = normalizeParams(entries || {});
    return r.params;
}

const AnimParamsAPI = {
    PHASES,
    GROUP_LABELS,
    BUILTIN,
    emptyParams,
    cloneParams,
    isParamsEmpty,
    isIdentifier,
    isBuiltin,
    builtin,
    normalizeDecl,
    normalizeParams,
    validateParams,
    paramsToRaw,
    withDecl,
    withoutDecl,
    renameDecl,
    defaultValueMap,
    createParams,
};
if (typeof module === 'object' && module.exports) module.exports = AnimParamsAPI;
if (globalScope) globalScope.AnimParams = AnimParamsAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
