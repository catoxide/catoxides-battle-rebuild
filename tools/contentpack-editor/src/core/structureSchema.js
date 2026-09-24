// structureSchema.js - 建筑结构（建筑拼图）契约层
//
// 设计原则（见 design/EDITOR_EXTENSION_GUIDE.md 三条铁律）：
//   blueprint.json 是「内容包源格式」，字段名与取值【一一对应原版 jigsaw 契约】，
//   导出（vanillaExport.js）是纯机械映射，无语义损失：
//     pieces[]            → data/<ns>/structure/<piece>.nbt      （结构模板，jigsaw 数据写 blocks[].nbt）
//     templatePools[]     → data/<ns>/worldgen/template_pool/<name>.json
//     structure           → data/<ns>/worldgen/structure/<structureId>.json
//     structureSet        → data/<ns>/worldgen/structure_set/<structureId>_set.json
//   游戏侧零自定义 worldgen 代码——原版引擎直接生成。
//
// 目标版本：1.21.1（模板在 data/<ns>/structure/，单数；DataVersion 3465）。
// 已对照 1.21.1 原版 jar（data/minecraft/structure/...）与解包 StructureTemplateManager 验证。

'use strict';

// ===== 原版常量（Java 1.21.1，与游戏内取值严格一致） =====

/** 结构生成步骤（structure JSON 的 step 字段） */
const STEPS = [
    'raw_generation', 'lakes', 'local_modifications', 'underground_structures',
    'surface_structures', 'strongholds', 'underground_ores', 'underground_decoration',
    'fluid_springs', 'vegetal_decoration', 'top_layer_modification',
];

/** 地形适应（Java 版取值；注意 beard_cut/ground_hug 是基岩版值，Java 非法） */
const TERRAIN_ADAPTATIONS = ['none', 'beard_thin', 'beard_box', 'bury', 'encapsulate'];

/** 池元素投影方式（rigid=固定放置，terrain_matching=贴合地表） */
const PROJECTIONS = ['rigid', 'terrain_matching'];

/** jigsaw 关节类型（仅 up/down 朝向有意义：rollable=随机旋转，aligned=对齐旋转） */
const JOINTS = ['rollable', 'aligned'];

/** jigsaw 朝向（12 个：朝向面_上/下 组合） */
const ORIENTATIONS = [
    'down_east', 'down_north', 'down_south', 'down_west',
    'east_up', 'north_up', 'south_up',
    'up_east', 'up_north', 'up_south', 'up_west',
    'west_up',
];

/** 空池元素 ID（终止连接/兜底） */
const EMPTY_POOL_ELEMENT = 'minecraft:empty_pool_element';

/** 1.21.1 数据版本（结构模板 NBT 的 DataVersion） */
const DATA_VERSION_1_21_1 = 3465;

// ===== 默认值 =====

/** 新建结构的默认蓝图（对齐原版村庄类结构常用参数） */
function defaultBlueprint() {
    return {
        formatVersion: 1,
        structureId: '',
        namespace: '',
        structure: {
            step: 'surface_structures',
            terrainAdaptation: 'beard_thin',
            startPool: 'start',
            maxDistanceFromCenter: 80,
            biomes: ['#minecraft:is_overworld'],
            projectToSurface: true,
            maxSurfaceLevel: 0,
        },
        pieces: [],
        templatePools: [],
        structureSet: {
            spacing: 32,
            separation: 12,
            salt: Math.floor(Math.random() * 1000000000),
            frequency: 1.0,
            exclusionZone: null,
        },
    };
}

/** 新建片段的默认对象 */
function defaultPiece() {
    return { name: '', size: [8, 4, 8], boxFills: [], fillBlocks: [], jigsaws: [] };
}

/** 新建 jigsaw 块的默认对象 */
function defaultJigsaw() {
    return {
        x: 0, y: 0, z: 0,
        name: '',
        pool: '',
        target: '',
        joint: 'rollable',
        orientation: 'north_up',
        finalState: 'minecraft:air',
        selectionPriority: 10,
        placementPriority: 10,
    };
}

/** 新建池元素的默认对象 */
function defaultPoolElement() {
    return { template: '', weight: 1, projection: 'rigid' };
}

// ===== ID 解析 =====

/**
 * 解析相对名 → 完整 ResourceLocation。
 * 含 ':' 视为完整 ID，否则加命名空间前缀。
 * @param {string} name
 * @param {string} namespace
 * @returns {string}
 */
function resolveId(name, namespace) {
    if (!name) return '';
    return name.includes(':') ? name : (namespace + ':' + name);
}

// ===== 校验 =====

/**
 * 校验完整蓝图。
 * @param {Object} bp
 * @returns {{valid:boolean, errors:string[]}}
 */
function validateBlueprint(bp) {
    const errors = [];
    if (!bp || typeof bp !== 'object') return { valid: false, errors: ['蓝图不是对象'] };

    // 标识
    if (!bp.structureId || !/^[a-z0-9_.-]+$/.test(bp.structureId)) {
        errors.push('structureId 必填，且只能含小写字母/数字/._-');
    }
    if (bp.namespace !== undefined && bp.namespace !== '' && !/^[a-z0-9_.-]+$/.test(bp.namespace)) {
        errors.push('namespace 只能含小写字母/数字/._-');
    }

    // structure
    const st = bp.structure || {};
    if (st.step && !STEPS.includes(st.step)) errors.push('structure.step 非法: ' + st.step);
    if (st.terrainAdaptation && !TERRAIN_ADAPTATIONS.includes(st.terrainAdaptation)) {
        errors.push('structure.terrainAdaptation 非法（Java 版取值: ' + TERRAIN_ADAPTATIONS.join('/') + '）: ' + st.terrainAdaptation);
    }
    if (st.maxDistanceFromCenter !== undefined && (!Number.isInteger(st.maxDistanceFromCenter) || st.maxDistanceFromCenter <= 0)) {
        errors.push('structure.maxDistanceFromCenter 应为正整数');
    }
    if (st.projectToSurface !== undefined && typeof st.projectToSurface !== 'boolean') {
        errors.push('structure.projectToSurface 应为布尔值');
    }

    const ns = bp.namespace || '';
    const pieceNames = new Set();
    const poolNames = new Set();

    // pieces
    const pieces = bp.pieces || [];
    if (!pieces.length) errors.push('至少需要一个片段（piece）');
    pieces.forEach((p, i) => {
        const tag = 'piece#' + i + (p.name ? (' "' + p.name + '"') : '');
        if (!p.name || !/^[a-z0-9_.-]+$/.test(p.name)) {
            errors.push(tag + ': name 必填（小写字母/数字/._-）');
        } else if (pieceNames.has(p.name)) {
            errors.push(tag + ': 片段名重复');
        }
        pieceNames.add(p.name || ('#' + i));
        const size = p.size || [0, 0, 0];
        if (!size.length === 3 || size.some((n) => !Number.isInteger(n) || n <= 0)) {
            errors.push(tag + ': size 应为三个正整数 [宽, 高, 深]');
        }
        // boxFills 在尺寸内
        (p.boxFills || []).forEach((bf, j) => {
            if (!bf.from || !bf.to || !bf.block) {
                errors.push(tag + ': boxFills#' + j + ' 需要 from/to/block');
                return;
            }
            for (let d = 0; d < 3; d++) {
                if (bf.from[d] < 0 || bf.to[d] < bf.from[d] || bf.to[d] >= size[d]) {
                    errors.push(tag + ': boxFills#' + j + ' 超出模板尺寸 ' + size.join('x'));
                    break;
                }
            }
        });
        // jigsaws
        (p.jigsaws || []).forEach((j, jw) => {
            const jtag = tag + ' jigsaw#' + jw;
            if (!j.name) errors.push(jtag + ': name 必填');
            if (!j.target) errors.push(jtag + ': target 必填（要匹配的目标 jigsaw 块名）');
            if (!j.pool) errors.push(jtag + ': pool 必填（目标池名）');
            if (j.x < 0 || j.y < 0 || j.z < 0 || j.x >= size[0] || j.y >= size[1] || j.z >= size[2]) {
                errors.push(jtag + ': 位置 (' + j.x + ',' + j.y + ',' + j.z + ') 超出模板尺寸 ' + size.join('x'));
            }
            if (!ORIENTATIONS.includes(j.orientation)) errors.push(jtag + ': orientation 非法: ' + j.orientation);
            if (j.joint && !JOINTS.includes(j.joint)) errors.push(jtag + ': joint 非法: ' + j.joint);
        });
        // P12: blockNbt（方块实体 NBT，tagged JSON：顶层 {$t:10, v:{...}}；long 的 v 用字符串以保 JSON 安全）
        (p.blockNbt || []).forEach((bn, bi) => {
            const btag = tag + ' blockNbt#' + bi;
            if (!bn || typeof bn !== 'object' ||
                !Number.isInteger(bn.x) || !Number.isInteger(bn.y) || !Number.isInteger(bn.z) ||
                bn.x < 0 || bn.y < 0 || bn.z < 0 || bn.x >= size[0] || bn.y >= size[1] || bn.z >= size[2]) {
                errors.push(btag + ': 位置 (' + (bn && bn.x) + ',' + (bn && bn.y) + ',' + (bn && bn.z) + ') 非法或超出模板尺寸 ' + size.join('x'));
            }
            if (!bn.nbt || typeof bn.nbt !== 'object' || Array.isArray(bn.nbt) || bn.nbt.$t !== 10 ||
                !bn.nbt.v || typeof bn.nbt.v !== 'object' || Array.isArray(bn.nbt.v)) {
                errors.push(btag + ': nbt 须为 tagged compound {$t:10, v:{...}}');
            }
        });
        // 同一位置不能既有填充方块又有 jigsaw
        const occ = new Set();
        (p.fillBlocks || []).forEach((fb) => occ.add(fb.x + ',' + fb.y + ',' + fb.z));
        (p.jigsaws || []).forEach((j) => {
            if (occ.has(j.x + ',' + j.y + ',' + j.z)) {
                errors.push(tag + ': (' + j.x + ',' + j.y + ',' + j.z + ') 同时有填充方块和 jigsaw');
            }
        });
    });

    // templatePools
    (bp.templatePools || []).forEach((pl, i) => {
        const tag = 'pool#' + i + (pl.name ? (' "' + pl.name + '"') : '');
        if (!pl.name || !/^[a-z0-9_.-]+$/.test(pl.name)) {
            errors.push(tag + ': name 必填（小写字母/数字/._-）');
        } else if (poolNames.has(pl.name)) {
            errors.push(tag + ': 池名重复');
        }
        poolNames.add(pl.name || ('#' + i));
        const els = pl.elements || [];
        if (!els.length) errors.push(tag + ': 至少需要一个元素');
        els.forEach((el, j) => {
            if (!el.template) {
                errors.push(tag + ' 元素#' + j + ': template 必填');
            } else if (ns && !el.template.includes(':') && !pieceNames.has(el.template)) {
                errors.push(tag + ' 元素#' + j + ': template "' + el.template + '" 不存在于片段列表');
            }
            if (!Number.isInteger(el.weight) || el.weight < 1 || el.weight > 150) {
                errors.push(tag + ' 元素#' + j + ': weight 应为 1-150 的整数');
            }
            if (!PROJECTIONS.includes(el.projection)) errors.push(tag + ' 元素#' + j + ': projection 非法: ' + el.projection);
        });
    });

    // jigsaw 引用的池必须存在（完整 ID 除外）
    pieces.forEach((p) => {
        (p.jigsaws || []).forEach((j) => {
            if (j.pool && !j.pool.includes(':') && !poolNames.has(j.pool)) {
                errors.push('piece "' + p.name + '" jigsaw "' + (j.name || '?') + '": 目标池 "' + j.pool + '" 不存在');
            }
        });
    });

    // startPool 必须存在
    if (st.startPool && !st.startPool.includes(':') && !poolNames.has(st.startPool)) {
        errors.push('structure.startPool "' + st.startPool + '" 不存在于模板池列表');
    }

    // structureSet
    const ss = bp.structureSet || {};
    if (!Number.isInteger(ss.spacing) || ss.spacing < 1) errors.push('structureSet.spacing 应为正整数');
    if (!Number.isInteger(ss.separation) || ss.separation < 0) errors.push('structureSet.separation 应为非负整数');
    if (ss.separation !== undefined && ss.spacing !== undefined && ss.separation >= ss.spacing) {
        errors.push('structureSet.separation 必须小于 spacing');
    }
    if (ss.salt !== undefined && (!Number.isInteger(ss.salt) || ss.salt < 0)) errors.push('structureSet.salt 应为非负整数');
    if (ss.frequency !== undefined && (ss.frequency < 0 || ss.frequency > 1)) errors.push('structureSet.frequency 应为 0-1');
    if (ss.exclusionZone) {
        const ez = ss.exclusionZone;
        if (!Number.isInteger(ez.chunkCount) || ez.chunkCount < 1 || ez.chunkCount > 16) {
            errors.push('exclusionZone.chunkCount 应为 1-16 的整数');
        }
        if (!ez.otherSet) errors.push('exclusionZone.otherSet 必填（另一个结构集合 ID）');
    }

    return { valid: errors.length === 0, errors };
}

// ===== 读写 =====

/**
 * 列出包内全部蓝图（递归扫描 structures/ 下的 blueprint.json）
 * @param {string} packDir
 * @returns {Array<{file:string, structureId:string, namespace:string, pieces:number, pools:number, staged:boolean}>}
 */
function listStructures(packDir) {
    const fs = require('fs');
    const path = require('path');
    const results = [];
    const base = path.join(packDir, 'structures');
    if (!fs.existsSync(base)) return results;
    (function walk(dir) {
        for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
            const p = path.join(dir, e.name);
            if (e.isDirectory()) walk(p);
            else if (e.name === 'blueprint.json') {
                try {
                    const bp = JSON.parse(fs.readFileSync(p, 'utf-8'));
                    results.push({
                        file: path.relative(packDir, p),
                        structureId: bp.structureId || path.basename(path.dirname(p)),
                        namespace: bp.namespace || '',
                        pieces: (bp.pieces || []).length,
                        pools: (bp.templatePools || []).length,
                        staged: false,
                    });
                } catch (err) {
                    results.push({
                        file: path.relative(packDir, p),
                        structureId: path.basename(path.dirname(p)),
                        namespace: '',
                        pieces: -1,
                        pools: 0,
                        staged: false,
                        error: err.message,
                    });
                }
            }
        }
    })(base);
    results.sort((a, b) => a.structureId.localeCompare(b.structureId));
    return results;
}

/**
 * 读取蓝图
 * @param {string} packDir
 * @param {string} relFile 相对包目录的 blueprint.json 路径（如 structures/x/blueprint.json）
 * @returns {Object}
 */
function readStructure(packDir, relFile) {
    const fs = require('fs');
    const path = require('path');
    const p = path.join(packDir, relFile);
    return JSON.parse(fs.readFileSync(p, 'utf-8'));
}

/**
 * 删除蓝图文件
 * @returns {{deleted:boolean}}
 */
function deleteStructure(packDir, relFile) {
    const fs = require('fs');
    const path = require('path');
    const p = path.join(packDir, relFile);
    if (!fs.existsSync(p)) return { deleted: false };
    // 删除文件及其所在目录（若目录只剩 blueprint.json）
    fs.unlinkSync(p);
    const dir = path.dirname(p);
    if (fs.existsSync(dir) && fs.readdirSync(dir).length === 0) {
        fs.rmdirSync(dir);
    }
    return { deleted: true };
}

/**
 * 写蓝图到正式包（structures/<structureId>/blueprint.json）
 * @returns {{file:string}}
 */
function writeStructure(packDir, blueprint) {
    const fs = require('fs');
    const path = require('path');
    const dir = path.join(packDir, 'structures', blueprint.structureId);
    fs.mkdirSync(dir, { recursive: true });
    const p = path.join(dir, 'blueprint.json');
    fs.writeFileSync(p, JSON.stringify(blueprint, null, 2), 'utf-8');
    return { file: path.relative(packDir, p) };
}

module.exports = {
    STEPS,
    TERRAIN_ADAPTATIONS,
    PROJECTIONS,
    JOINTS,
    ORIENTATIONS,
    EMPTY_POOL_ELEMENT,
    DATA_VERSION_1_21_1,
    defaultBlueprint,
    defaultPiece,
    defaultJigsaw,
    defaultPoolElement,
    resolveId,
    validateBlueprint,
    listStructures,
    readStructure,
    writeStructure,
    deleteStructure,
};
