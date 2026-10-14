// model.js - 模型数据模型（Geometry / Bone / Cube / Locator）+ 树构建 + 校验
// 纯函数，node 可测，零 DOM 零 three（model-editor/core 铁律）。
//
// 数据模型 = model-editor 的内存规范形（见 design/MODEL_EDITOR_REWRITE_PLAN.md §3.1）：
//   Geometry { identifier, formatVersion, textureWidth, textureHeight,
//              visibleBounds | null, bones: Bone[] }        // 扁平列表，顺序 = 文件顺序
//   Bone     { name, parent|null, pivot, rotation, scale, mirror, cubes: Cube[], locators: Locator[] }
//   Cube     { origin, size, uv: [u,v] | PerFaceUv, inflate, pivot, rotation }
//   Locator  { name, offset, rotation }
// 序列化规则（字段顺序/缺省省略/两种 bones 格式兼容）在 sparkCodec.js（§4 规则表）。

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.Model）
(function (globalScope) {
const FACES = ['north', 'east', 'south', 'west', 'up', 'down'];

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const isVec3 = (v) => Array.isArray(v) && v.length === 3 && v.every(isNum);
const isVec2 = (v) => Array.isArray(v) && v.length === 2 && v.every(isNum);

function vec3(x = 0, y = 0, z = 0) {
    return [x, y, z];
}

/** 逐分量精确相等（缺省省略判断用：rotation [0,0,0] / scale [1,1,1] / pivot [0,0,0]）。 */
function vec3Equals(a, b) {
    return a[0] === b[0] && a[1] === b[1] && a[2] === b[2];
}

/**
 * 建一个 cube（缺省值按 §4 规则4：inflate 0 / pivot [0,0,0] / rotation [0,0,0]）。
 * uv 必须是 [u,v] 或 per-face 对象（导入时由 codec 归一，这里只透传）。
 */
function createCube({ origin, size, uv = [0, 0], inflate = 0, pivot, rotation } = {}) {
    if (!isVec3(origin)) throw new Error('cube.origin 必须是有限数三元组');
    if (!isVec3(size)) throw new Error('cube.size 必须是有限数三元组');
    return {
        origin: origin.slice(),
        size: size.slice(),
        uv,
        inflate,
        pivot: pivot ? pivot.slice() : vec3(),
        rotation: rotation ? rotation.slice() : vec3(),
    };
}

/** 建一个 locator。 */
function createLocator({ name, offset, rotation }) {
    if (!name || typeof name !== 'string') throw new Error('locator.name 缺失');
    return {
        name,
        offset: offset ? offset.slice() : vec3(),
        rotation: rotation ? rotation.slice() : vec3(),
    };
}

/** 建一个 bone。parent 可以是 null（根骨）。 */
function createBone({ name, parent = null, pivot, rotation, scale, mirror = false, cubes = [], locators = [] } = {}) {
    if (!name || typeof name !== 'string') throw new Error('bone.name 缺失');
    return {
        name,
        parent,
        pivot: pivot ? pivot.slice() : vec3(),
        rotation: rotation ? rotation.slice() : vec3(),
        scale: scale ? scale.slice() : vec3(1, 1, 1),
        mirror: !!mirror,
        cubes: cubes.slice(),
        locators: locators.slice(),
    };
}

/** 建一个 geometry。 */
function createGeometry({ identifier, formatVersion = '1.12.0', textureWidth = 64, textureHeight = 64, visibleBounds = null, bones = [] } = {}) {
    if (!identifier || typeof identifier !== 'string') throw new Error('geometry.identifier 缺失');
    return {
        identifier,
        formatVersion,
        textureWidth,
        textureHeight,
        visibleBounds,
        bones: bones.slice(),
    };
}

// ==================== 树构建 ====================

/**
 * 由扁平骨骼列表构建父子树。
 * @returns {{roots: Array, byName: Map<string, node>, errors: string[]}}
 *   node = { name, bone, children: [] }；
 *   悬空 parent 的骨被当作根（并报错）；重名骨只保留第一个（并报错）；
 *   环上报错（环上骨不会出现在 roots 里）。
 */
function buildTree(geometry) {
    const errors = [];
    const byName = new Map();
    for (const b of geometry.bones) {
        if (!b || !b.name || typeof b.name !== 'string') {
            errors.push('存在无名骨骼');
            continue;
        }
        if (byName.has(b.name)) {
            errors.push(`骨骼重名: ${b.name}`);
            continue;
        }
        byName.set(b.name, { name: b.name, bone: b, children: [] });
    }
    const roots = [];
    for (const node of byName.values()) {
        const p = node.bone.parent;
        if (p == null || p === '') {
            roots.push(node);
            continue;
        }
        const parent = byName.get(p);
        if (!parent) {
            errors.push(`骨骼 ${node.name} 的 parent 不存在: ${p}`);
            roots.push(node);
            continue;
        }
        parent.children.push(node);
    }
    // 环检测：从每根骨沿 parent 上溯，步数超过总骨骼数或重访节点即成环
    for (const start of byName.values()) {
        const seen = new Set([start.name]);
        let cur = start;
        let steps = 0;
        while (cur && cur.bone.parent != null) {
            const parent = byName.get(cur.bone.parent);
            if (!parent) break;
            if (seen.has(parent.name)) {
                errors.push(`骨骼环: ${cur.name} → ${parent.name}`);
                break;
            }
            seen.add(parent.name);
            cur = parent;
            if (++steps > byName.size) {
                errors.push(`骨骼环（未达根）: ${start.name}`);
                break;
            }
        }
    }
    return { roots, byName, errors };
}

/** 取某骨骼的直接子骨骼名列表（不存在 → []）。 */
function getChildren(geometry, name) {
    const { byName } = buildTree(geometry);
    const node = byName.get(name);
    return node ? node.children.map((c) => c.name) : [];
}

/** 全部骨骼名（文件顺序）。 */
function getBoneNames(geometry) {
    return geometry.bones.map((b) => b.name);
}

// ==================== 编辑操作（W3：gizmo / 大纲 / 属性面板的数据侧） ====================

/** 生成不冲突的骨骼名：base 空闲 → base；否则 base_2 / base_3 … */
function uniqueBoneName(geometry, base) {
    const taken = new Set(geometry.bones.map((b) => b.name));
    if (!taken.has(base)) return base;
    for (let i = 2; ; i++) {
        const cand = `${base}_${i}`;
        if (!taken.has(cand)) return cand;
    }
}

/**
 * 加骨骼。
 * @param {Object} opts {name?, parent?, pivot?, rotation?, scale?, mirror?}
 * @returns {{ok:true, name:string} | {ok:false, error:string}}
 */
function addBone(geometry, opts = {}) {
    if (geometry.bones.some((b) => !b || !b.name)) return { ok: false, error: '模型存在无名骨骼，先修复' };
    const name = opts.name || uniqueBoneName(geometry, 'bone');
    if (geometry.bones.some((b) => b.name === name)) return { ok: false, error: `骨骼名已存在: ${name}` };
    const parent = opts.parent == null ? null : String(opts.parent);
    if (parent && !geometry.bones.some((b) => b.name === parent)) return { ok: false, error: `父骨骼不存在: ${parent}` };
    geometry.bones.push(createBone({ name, parent, pivot: opts.pivot, rotation: opts.rotation, scale: opts.scale, mirror: opts.mirror }));
    return { ok: true, name };
}

/**
 * 删骨骼（子骨骼重挂到祖父，无祖父 → 根）。
 * @returns {{ok:true} | {ok:false, error:string}}
 */
function removeBone(geometry, name) {
    const idx = geometry.bones.findIndex((b) => b.name === name);
    if (idx < 0) return { ok: false, error: `骨骼不存在: ${name}` };
    const target = geometry.bones[idx];
    const grand = target.parent;
    for (const b of geometry.bones) {
        if (b.parent === name) b.parent = grand == null ? null : grand;
    }
    geometry.bones.splice(idx, 1);
    return { ok: true };
}

/**
 * 复制骨骼（整棵子树深拷贝，新名字 unique）。
 * @returns {{ok:true, name:string} | {ok:false, error:string}}
 */
function duplicateBone(geometry, name) {
    const { byName, errors } = buildTree(geometry);
    if (errors.length) return { ok: false, error: `模型树不合法: ${errors[0]}` };
    const node = byName.get(name);
    if (!node) return { ok: false, error: `骨骼不存在: ${name}` };
    // 收集子树（DFS，保序）
    const subtree = [];
    const visit = (n) => {
        subtree.push(n.name);
        for (const c of n.children) visit(c);
    };
    visit(node);
    const newName = uniqueBoneName(geometry, `${name}_copy`);
    const renamed = new Map(subtree.map((n) => [n, n === name ? newName : `${newName}_${n}`]));
    for (const oldName of subtree) {
        const src = geometry.bones.find((b) => b.name === oldName);
        const copy = JSON.parse(JSON.stringify(src));
        copy.name = renamed.get(oldName);
        // parent 在子树内 → 映射到新名；在子树外（或根骨的父）→ 保持原值
        copy.parent = (src.parent != null && renamed.has(src.parent)) ? renamed.get(src.parent) : src.parent;
        geometry.bones.push(copy);
    }
    return { ok: true, name: newName };
}

/**
 * 重命名骨骼（同步 parent 引用；动画引用由上层负责提示）。
 * @returns {{ok:true} | {ok:false, error:string}}
 */
function renameBone(geometry, from, to) {
    if (!to || typeof to !== 'string') return { ok: false, error: '新名缺失' };
    if (from === to) return { ok: true };
    if (geometry.bones.some((b) => b.name === to)) return { ok: false, error: `骨骼名已存在: ${to}` };
    if (!geometry.bones.some((b) => b.name === from)) return { ok: false, error: `骨骼不存在: ${from}` };
    for (const b of geometry.bones) {
        if (b.name === from) b.name = to;
        else if (b.parent === from) b.parent = to;
    }
    return { ok: true };
}

/**
 * 给骨骼加 cube（追加到 cubes 末尾）。
 * @param {Object} cube createCube() 结果或同形普通对象
 * @returns {{ok:true, index:number} | {ok:false, error:string}}
 */
function addCube(geometry, boneName, cube) {
    const bone = geometry.bones.find((b) => b.name === boneName);
    if (!bone) return { ok: false, error: `骨骼不存在: ${boneName}` };
    if (!cube || !isVec3(cube.origin) || !isVec3(cube.size)) return { ok: false, error: 'cube 缺 origin/size' };
    const norm = createCube(cube);
    bone.cubes = bone.cubes || [];
    bone.cubes.push(norm);
    return { ok: true, index: bone.cubes.length - 1 };
}

/**
 * 删 cube。
 * @returns {{ok:true} | {ok:false, error:string}}
 */
function removeCube(geometry, boneName, index) {
    const bone = geometry.bones.find((b) => b.name === boneName);
    if (!bone) return { ok: false, error: `骨骼不存在: ${boneName}` };
    if (!bone.cubes || index < 0 || index >= bone.cubes.length) return { ok: false, error: 'cube 索引越界' };
    bone.cubes.splice(index, 1);
    return { ok: true };
}

// ==================== 校验 ====================

function isPerFaceUv(uv) {
    if (!uv || typeof uv !== 'object' || Array.isArray(uv)) return false;
    const faces = FACES.filter((f) => uv[f] != null);
    if (faces.length === 0) return false;
    return faces.every((f) => Array.isArray(uv[f].uv) && uv[f].uv.length === 2);
}

function uvOriginOf(cube) {
    // 简单形取 [u,v]；per-face 形取 north 的 uv（缺 north 取任一面的 uv）
    if (Array.isArray(cube.uv)) return cube.uv;
    for (const f of FACES) {
        if (cube.uv[f] && Array.isArray(cube.uv[f].uv)) return cube.uv[f].uv;
    }
    return null;
}

/**
 * 校验 geometry（不触盘）。
 * @returns {{errors: string[], warnings: string[]}}
 * 错误 = 结构不合法（无法导出/无法使用）；警告 = 能跑但可疑（UV 越界等）。
 */
function validateGeometry(geometry) {
    const errors = [];
    const warnings = [];
    if (!geometry || typeof geometry !== 'object') {
        return { errors: ['geometry 为空'], warnings };
    }
    if (!geometry.identifier || typeof geometry.identifier !== 'string') {
        errors.push('identifier 缺失');
    }
    if (!isNum(geometry.textureWidth) || !isNum(geometry.textureHeight)) {
        errors.push('texture_width/height 非法');
    }
    if (geometry.visibleBounds != null) {
        const vb = geometry.visibleBounds;
        if (!isNum(vb.width) || !isNum(vb.height) || !isVec3(vb.offset)) {
            errors.push('visible_bounds 非法（width/height/offset[3]）');
        }
    }
    errors.push(...buildTree(geometry).errors);

    const tw = isNum(geometry.textureWidth) ? geometry.textureWidth : 64;
    const th = isNum(geometry.textureHeight) ? geometry.textureHeight : 64;
    for (const b of geometry.bones) {
        if (!b || typeof b !== 'object') { errors.push('存在空骨骼'); continue; }
        if (!isVec3(b.pivot)) errors.push(`骨骼 ${b.name}: pivot 非法`);
        if (!isVec3(b.rotation)) errors.push(`骨骼 ${b.name}: rotation 非法`);
        if (!isVec3(b.scale)) errors.push(`骨骼 ${b.name}: scale 非法`);
        for (let i = 0; i < (b.cubes || []).length; i++) {
            const c = b.cubes[i];
            const where = `骨骼 ${b.name} cube[${i}]`;
            if (!c || typeof c !== 'object') { errors.push(`${where}: 空 cube`); continue; }
            if (!isVec3(c.origin)) errors.push(`${where}: origin 非法`);
            if (!isVec3(c.size)) errors.push(`${where}: size 非法`);
            else if (c.size.some((v) => v < 0)) errors.push(`${where}: size 有负分量`);
            if (!isNum(c.inflate)) errors.push(`${where}: inflate 非法`);
            if (!isVec3(c.pivot)) errors.push(`${where}: pivot 非法`);
            if (c.rotation != null && !isVec3(c.rotation)) errors.push(`${where}: rotation 非法`);
            if (Array.isArray(c.uv)) {
                if (!isVec2(c.uv)) {
                    errors.push(`${where}: uv 非法`);
                } else if (c.uv[0] < 0 || c.uv[0] > tw || c.uv[1] < 0 || c.uv[1] > th) {
                    warnings.push(`${where}: uv 原点超出贴图范围 (${c.uv[0]}, ${c.uv[1]}) > ${tw}x${th}`);
                }
            } else if (isPerFaceUv(c.uv)) {
                // per-face 形：只查结构（各面 uv/uv_size 为有限数对）；越界不做警告（面可超出，如 sword 的负 uv_size）
                for (const f of FACES) {
                    const face = c.uv[f];
                    if (face == null) continue;
                    if (!isVec2(face.uv)) errors.push(`${where}: 面 ${f} uv 非法`);
                    if (face.uv_size != null && !isVec2(face.uv_size)) errors.push(`${where}: 面 ${f} uv_size 非法`);
                    if (face.uv_rotation != null && !isNum(face.uv_rotation)) errors.push(`${where}: 面 ${f} uv_rotation 非法`);
                }
            } else {
                errors.push(`${where}: uv 缺失或非法`);
            }
        }
        for (const l of b.locators || []) {
            if (!l || !l.name) errors.push(`骨骼 ${b.name}: 无名 locator`);
            else if (!isVec3(l.offset)) errors.push(`骨骼 ${b.name} locator ${l.name}: offset 非法`);
        }
    }
    return { errors, warnings };
}

const ModelAPI = {
    FACES,
    isNum,
    isVec3,
    isVec2,
    isPerFaceUv,
    uvOriginOf,
    vec3,
    vec3Equals,
    createCube,
    createLocator,
    createBone,
    createGeometry,
    buildTree,
    getChildren,
    getBoneNames,
    uniqueBoneName,
    addBone,
    removeBone,
    duplicateBone,
    renameBone,
    addCube,
    removeCube,
    validateGeometry,
};
if (typeof module === 'object' && module.exports) module.exports = ModelAPI;
if (globalScope) globalScope.Model = ModelAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
