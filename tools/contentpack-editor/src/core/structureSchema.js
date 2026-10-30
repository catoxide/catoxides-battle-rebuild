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

/** 空池元素 ID（终止连接/兜底用的元素类型） */
const EMPTY_POOL_ELEMENT = 'minecraft:empty_pool_element';

/** fallback 默认值：原版空池 id（1.21.1 原版池文件的 fallback 都是池 id，如 "minecraft:empty"） */
const EMPTY_POOL = 'minecraft:empty';

/** jigsaw 池元素类型（1.21.1 只有这两种能放在池里做“模板”元素；feature/list 无法用蓝图表达） */
const ELEMENT_TYPES = ['minecraft:single_pool_element', 'minecraft:legacy_single_pool_element'];

/** 结构最大扩展深度 size（1.21.1 codec 限 1-128；村庄 6 / 远古城市 7 / 试炼密室 20） */
const JIGSAW_SIZE_MIN = 1;
const JIGSAW_SIZE_MAX = 128;

/**
 * 方块 ID/状态语法（0.2.1）：`namespace:path` / `path` / 可选属性 `[k=v,...]`；
 * 允许 `#` 前缀表示蓝图占位符（导出时转 air）。与 vanillaExport.parseBlockId 的正则保持一致，
 * 让「非法方块 ID」在保存阶段就报出来，而不是导出时才失败。
 */
const BLOCK_ID_RE = /^#?[a-z0-9_.\-:]+(?:\[[^\]]*\])?$/;

/** project_start_to_heightmap 取值（Heightmap.Types 名，1.21.1 原版只用到前两个） */
const HEIGHTMAPS = [
    'WORLD_SURFACE_WG', 'OCEAN_FLOOR_WG', 'WORLD_SURFACE', 'OCEAN_FLOOR',
    'MOTION_BLOCKING', 'MOTION_BLOCKING_NO_LEAVES',
];

/** 1.21.1 数据版本（结构模板 NBT 的 DataVersion）
 *  实测 1180/1180 个原版模板都是 3955；此前写 3465（=1.20.1）会让游戏跑 DataFixer，
 *  导出的模板被当作旧版本数据升级（有被改写/丢字段的风险）。 */
const DATA_VERSION_1_21_1 = 3955;

// ===== 默认值 =====

/** 新建结构的默认蓝图（对齐原版村庄类结构常用参数） */
function defaultBlueprint() {
    return {
        formatVersion: 2,
        structureId: '',
        namespace: '',
        structure: {
            step: 'surface_structures',
            terrainAdaptation: 'beard_thin',
            startPool: 'start',
            maxDistanceFromCenter: 80,
            biomes: ['#minecraft:is_overworld'],
            // 1.21.1 jigsaw 结构定义的必填项（此前缺失 → worldgen JSON 不被 codec 接受，世界加载失败）
            // 默认 7：与原版村庄一致（可调到 128）；导入原版时按源文件覆盖
            size: 7,
            startHeight: { absolute: 0 },
            useExpansionHack: true,
            // 可选：贴地投影（原版 7/10 个 jigsaw 结构用 WORLD_SURFACE_WG）
            projectStartToHeightmap: 'WORLD_SURFACE_WG',
            // 可选：spawn_overrides 原样透传（{mobCategory: {bounding_box, spawns}}）
            spawnOverrides: null,
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

/** 旧蓝图（formatVersion 1）→ 新字段迁移；无副作用的浅拷贝 */
function migrateBlueprint(bp) {
    if (!bp || typeof bp !== 'object') return bp;
    const out = Object.assign({}, bp, { structure: Object.assign({}, bp.structure || {}) });
    const st = out.structure;
    // 旧字段 projectToSurface / maxSurfaceLevel 在 1.21.1 里根本不存在（编辑器早期自造）。
    // projectToSurface=true ⇒ project_start_to_heightmap = WORLD_SURFACE_WG；maxSurfaceLevel 丢弃。
    if (st.projectStartToHeightmap === undefined) {
        if (st.projectToSurface === true) st.projectStartToHeightmap = 'WORLD_SURFACE_WG';
        else if (st.projectToSurface === false) st.projectStartToHeightmap = null;
    }
    delete st.projectToSurface;
    delete st.maxSurfaceLevel;
    // 1.21.1 起 size 必填；旧蓝图/缺省值 → 7（与原版村庄一致，编辑器"新建结构"也是 7）
    st.size = (st.size === 0 || st.size === undefined) ? 7 : st.size;
    if (!st.startHeight || typeof st.startHeight !== 'object') st.startHeight = { absolute: 0 };
    if (st.useExpansionHack === undefined) st.useExpansionHack = true;
    if (st.projectStartToHeightmap === undefined) st.projectStartToHeightmap = null;
    out.formatVersion = Math.max(2, out.formatVersion || 0);
    return out;
}

/**
 * biomes 归一化为数组（导入/编辑内部表示）。原版允许：
 *   "biomes": "#minecraft:has_structure/village_plains"（单个 tag）
 *   "biomes": ["minecraft:plains", "minecraft:sunflower_plains"]
 * @returns {string[]}
 */
function normalizeBiomes(b) {
    if (b == null) return [];
    if (typeof b === 'string') return [b];
    if (Array.isArray(b)) return b.filter((x) => typeof x === 'string' && x.trim()).map((x) => x.trim());
    return [];
}

/**
 * 数组 → worldgen JSON 的 biomes 取值。
 * 单个 tag（#...）必须写成标量字符串；列表里放 "#tag" 是非法的（HolderSet codec 只接受
 * 标量 tag 或 id 列表）——此前导出的 ["#minecraft:is_overworld"] 就是非法形状。
 */
function biomesForJson(b) {
    const arr = normalizeBiomes(b);
    if (!arr.length) return ['#minecraft:is_overworld'];
    if (arr.length === 1) return arr[0]; // 标量（tag 或单个 id）
    return arr.map((s) => {
        if (s.startsWith('#')) throw new Error('biomes 混用 tag 与 id（tag "' + s + '" 只能单独出现）');
        return s;
    });
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
    // 旧蓝图（projectToSurface/maxSurfaceLevel 等 1.21.1 不存在的字段）先就地迁移再校验
    bp = migrateBlueprint(bp);

    // 标识
    if (!bp.structureId || !/^[a-z0-9_.-]+$/.test(bp.structureId)) {
        errors.push('structureId 必填，且只能含小写字母/数字/._-');
    } else if (bp.structureId === '.' || bp.structureId === '..') {
        // REVIEW-L6：'..' 作为目录段会拼出越界路径（staging 的 structures/.. = 包暂存根，
        // 草稿写入后 listStaged 不可见 → 静默丢失；writeStructure 同理）
        errors.push('structureId 不能是 . 或 ..');
    }
    if (bp.namespace !== undefined && bp.namespace !== '') {
        if (!/^[a-z0-9_.-]+$/.test(bp.namespace)) {
            errors.push('namespace 只能含小写字母/数字/._-');
        } else if (bp.namespace === '.' || bp.namespace === '..') {
            // REVIEW-L6：namespace 参与 data/<ns>/… 导出路径，'..' 会逃逸包目录
            errors.push('namespace 不能是 . 或 ..');
        }
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
    // 0.2.1：原版 codec 是 intRange(1,128)，超出范围是世界加载失败（不是警告）
    if (Number.isInteger(st.maxDistanceFromCenter) && st.maxDistanceFromCenter > 128) {
        errors.push('structure.maxDistanceFromCenter 不能超过 128（原版 codec 限制 1-128，超出会加载失败）');
    }
    // 1.21.1 jigsaw codec 必填/有约束的字段
    if (st.size !== undefined && (!Number.isInteger(st.size) || st.size < JIGSAW_SIZE_MIN || st.size > JIGSAW_SIZE_MAX)) {
        errors.push('structure.size 应为 ' + JIGSAW_SIZE_MIN + '-' + JIGSAW_SIZE_MAX + ' 的整数（最大扩展深度）');
    }
    if (st.startHeight !== undefined) {
        // 1.21.1 是 HeightProvider：{absolute:n} / {above_bottom:n}（常数），
        // 或 {type:"minecraft:uniform"|"trapezoid"|"weighted_list", ...} 等组合类型
        const sh = st.startHeight;
        const isObj = sh && typeof sh === 'object' && !Array.isArray(sh);
        if (!isObj) {
            errors.push('structure.startHeight 应为 HeightProvider 对象（{absolute:n} / {above_bottom:n} / {type:...}）');
        } else if (sh.type === undefined) {
            const hasAbs = Number.isInteger(sh.absolute);
            const hasBottom = Number.isInteger(sh.above_bottom);
            if (!(hasAbs !== hasBottom)) { // 恰好一个
                errors.push('structure.startHeight 应为 { absolute: 整数 } 或 { above_bottom: 整数 }（二选一）');
            }
        }
    }
    if (st.useExpansionHack !== undefined && typeof st.useExpansionHack !== 'boolean') {
        errors.push('structure.useExpansionHack 应为布尔值');
    }
    if (st.projectStartToHeightmap !== undefined && st.projectStartToHeightmap !== null &&
        !HEIGHTMAPS.includes(st.projectStartToHeightmap)) {
        errors.push('structure.projectStartToHeightmap 非法（取值: ' + HEIGHTMAPS.join('/') + '）: ' + st.projectStartToHeightmap);
    }
    if (st.spawnOverrides !== undefined && st.spawnOverrides !== null &&
        (typeof st.spawnOverrides !== 'object' || Array.isArray(st.spawnOverrides))) {
        errors.push('structure.spawnOverrides 应为对象（{ mobCategory: { bounding_box, spawns } }）');
    }
    if (st.dimensionPadding !== undefined && st.dimensionPadding !== null &&
        (!Number.isInteger(st.dimensionPadding) || st.dimensionPadding < 0)) {
        errors.push('structure.dimensionPadding 应为非负整数');
    }
    if (st.liquidSettings !== undefined && st.liquidSettings !== null && typeof st.liquidSettings !== 'string') {
        errors.push('structure.liquidSettings 应为字符串（如 ignore_waterlogging / apply_waterlogging）');
    }
    if (st.poolAliases !== undefined && st.poolAliases !== null && !Array.isArray(st.poolAliases)) {
        errors.push('structure.poolAliases 应为数组（原样透传 1.21.1 的 pool_aliases）');
    }
    if (st.biomes !== undefined) {
        const bs = normalizeBiomes(st.biomes);
        if (!bs.length) errors.push('structure.biomes 至少需要一个生物群系/tag');
        for (const b of bs) {
            const isTag = b.startsWith('#');
            const body = isTag ? b.slice(1) : b;
            if (!/^[a-z0-9_.-]+(:[a-z0-9_./-]+)?$/.test(body)) {
                errors.push('structure.biomes 含非法 ID: ' + b);
            }
            if (isTag && bs.length > 1) {
                errors.push('structure.biomes 里 tag 只能单独使用（当前与其它条目混用）: ' + b);
            }
        }
    }
    // 旧字段 projectToSurface/maxSurfaceLevel 已在函数开头经 migrateBlueprint 迁移
    // （1.21.1 的 jigsaw codec 里没有这两个字段，写出去会让世界加载失败）

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
        // REVIEW-M1：原式 `!size.length === 3` 恒为 false（先取反再比较）——长度校验是死代码，
        // size:[5] 能通过校验 → boxFills/jigsaws 的 y/z 边界检查对 undefined 恒不成立，
        // 且 buildTemplateNbt 会写出 size=[5,0,0] 的损坏模板 NBT
        if (size.length !== 3 || size.some((n) => !Number.isInteger(n) || n <= 0)) {
            errors.push(tag + ': size 应为三个正整数 [宽, 高, 深]');
        } else if (size.some((n) => n > 512)) {
            // REVIEW-M1：size 上限（原版结构模板远小于 512³；无上限时
            // expandFills/expandPiece 的三重循环可被 1e5³ 级数值拖死主进程/渲染端）
            errors.push(tag + ': size 单维不能超过 512（当前 ' + size.join('x') + '）');
        }
        // boxFills 在尺寸内
        (p.boxFills || []).forEach((bf, j) => {
            if (!bf.from || !bf.to || !bf.block) {
                errors.push(tag + ': boxFills#' + j + ' 需要 from/to/block');
                return;
            }
            // 0.2.1：方块 ID 语法提前校验（此前能保存，导出时才抛「非法方块 ID」）
            if (!BLOCK_ID_RE.test(String(bf.block).trim())) {
                errors.push(tag + ': boxFills#' + j + ' 方块 ID 非法: ' + bf.block);
            }
            for (let d = 0; d < 3; d++) {
                if (bf.from[d] < 0 || bf.to[d] < bf.from[d] || bf.to[d] >= size[d]) {
                    errors.push(tag + ': boxFills#' + j + ' 超出模板尺寸 ' + size.join('x'));
                    break;
                }
            }
        });
        // 0.2.1：fillBlocks 也要查边界与方块 ID（此前导出时静默丢弃越界项、非法 ID 才报错）
        (p.fillBlocks || []).forEach((fb, fi) => {
            const ftag = tag + ': fillBlocks#' + fi;
            if (!fb || !Number.isInteger(fb.x) || !Number.isInteger(fb.y) || !Number.isInteger(fb.z)) {
                errors.push(ftag + ' 需要整数坐标 x/y/z');
                return;
            }
            if (fb.x < 0 || fb.y < 0 || fb.z < 0 || fb.x >= size[0] || fb.y >= size[1] || fb.z >= size[2]) {
                errors.push(ftag + ' 坐标 (' + fb.x + ',' + fb.y + ',' + fb.z + ') 超出模板尺寸 ' + size.join('x'));
            }
            if (!fb.block || !BLOCK_ID_RE.test(String(fb.block).trim())) {
                errors.push(ftag + ' 方块 ID 非法: ' + (fb.block === undefined ? '(空)' : fb.block));
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
            // 0.2.1：finalState（jigsaw 块的目标方块状态）语法提前校验
            if (j.finalState !== undefined && j.finalState !== null && j.finalState !== '' &&
                !BLOCK_ID_RE.test(String(j.finalState).trim())) {
                errors.push(jtag + ': finalState 方块状态非法: ' + j.finalState);
            }
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
        // 同一位置不能既有填充方块又有 jigsaw（air 不算填充：原版模板允许在 jigsaw 格上写 air）
        const occ = new Set();
        (p.fillBlocks || []).forEach((fb) => {
            if (fb.block !== 'minecraft:air') occ.add(fb.x + ',' + fb.y + ',' + fb.z);
        });
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
            const hasTpl = !!el.template;
            const hasFeature = !!el.feature;
            if (hasTpl && hasFeature) {
                errors.push(tag + ' 元素#' + j + ': template 与 feature 只能二选一');
            } else if (!hasTpl && !hasFeature) {
                errors.push(tag + ' 元素#' + j + ': template 或 feature 必填（原版 feature_pool_element 用 feature）');
            }
            if (hasTpl) {
                if (ns && !el.template.includes(':') && !pieceNames.has(el.template)) {
                    errors.push(tag + ' 元素#' + j + ': template "' + el.template + '" 不存在于片段列表');
                }
            }
            if (hasFeature && !/^[a-z0-9_.-]+(:[a-z0-9_./-]+)?$/.test(el.feature)) {
                errors.push(tag + ' 元素#' + j + ': feature 不是合法的已配置特征 ID: ' + el.feature);
            }
            if (!Number.isInteger(el.weight) || el.weight < 1 || el.weight > 150) {
                errors.push(tag + ' 元素#' + j + ': weight 应为 1-150 的整数');
            }
            if (!PROJECTIONS.includes(el.projection)) errors.push(tag + ' 元素#' + j + ': projection 非法: ' + el.projection);
            // 1.21.1 池元素类型（可选，缺省 single_pool_element）
            if (el.elementType !== undefined && !ELEMENT_TYPES.includes(el.elementType)) {
                errors.push(tag + ' 元素#' + j + ': elementType 非法（' + ELEMENT_TYPES.join('/') + '）: ' + el.elementType);
            }
            // processors：原版允许 "处理器列表 id" 或内联 {processors:[...]}；
            // 绝不能导出空数组 []（codec 只接受 id 字符串或 compound）
            if (el.processors !== undefined && el.processors !== null) {
                if (typeof el.processors === 'string') {
                    if (!/^[a-z0-9_.-]+(:[a-z0-9_./-]+)?$/.test(el.processors)) {
                        errors.push(tag + ' 元素#' + j + ': processors 不是合法的处理器列表 ID: ' + el.processors);
                    }
                } else if (typeof el.processors !== 'object' || Array.isArray(el.processors)) {
                    errors.push(tag + ' 元素#' + j + ': processors 应为处理器列表 ID 字符串或内联对象 {processors:[...]}');
                }
            }
        });
        // fallback：原版是「池 ID」（如 minecraft:empty），不是元素类型 ID
        if (pl.fallback !== undefined && pl.fallback !== null && typeof pl.fallback !== 'string') {
            errors.push(tag + ': fallback 应为池 ID 字符串（如 minecraft:empty）');
        }
    });

    // jigsaw 引用的池必须存在（完整 ID 除外）
    pieces.forEach((p) => {
        (p.jigsaws || []).forEach((j) => {
            if (j.pool && !j.pool.includes(':') && !poolNames.has(j.pool)) {
                errors.push('piece "' + p.name + '" jigsaw "' + (j.name || '?') + '": 目标池 "' + j.pool + '" 不存在');
            }
        });
    });

    // startPool 必须存在（0.2.1：必填 —— 空值会让导出的 start_pool 变成 ""，世界加载失败）
    if (!st.startPool) {
        errors.push('structure.startPool 必填（起始模板池）');
    } else if (!st.startPool.includes(':') && !poolNames.has(st.startPool)) {
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
 * relFile（'structures/x/blueprint.json'）→ 安全绝对路径；越界返回 null
 * REVIEW-L5：此前 readStructure/deleteStructure 直接 path.join(packDir, relFile)，
 * relFile 含 '..' 可越界读/删任意文件（与 modelEditor:* 族的 resolveRepoFile 对齐）
 */
function resolveStructureFile(packDir, relFile) {
    const path = require('path');
    if (typeof relFile !== 'string' || !relFile) return null;
    const norm = relFile.replace(/\\/g, '/');
    if (!norm.startsWith('structures/')) return null;
    if (norm.split('/').some((s) => s === '..' || s === '.' || s === '')) return null;
    const p = path.join(packDir, ...norm.split('/'));
    if (!p.startsWith(path.resolve(packDir) + path.sep)) return null;
    return p;
}

/**
 * 读取蓝图
 * @param {string} packDir
 * @param {string} relFile 相对包目录的 blueprint.json 路径（如 structures/x/blueprint.json）
 * @returns {Object}
 */
function readStructure(packDir, relFile) {
    const fs = require('fs');
    const p = resolveStructureFile(packDir, relFile);
    if (!p) throw new Error('非法蓝图路径: ' + relFile);
    // 读入即迁移旧蓝图（1.21.1 不存在的 projectToSurface/maxSurfaceLevel 等字段）
    return migrateBlueprint(JSON.parse(fs.readFileSync(p, 'utf-8')));
}

/**
 * 删除蓝图文件
 * @returns {{deleted:boolean}}
 */
function deleteStructure(packDir, relFile) {
    const fs = require('fs');
    const path = require('path');
    const p = resolveStructureFile(packDir, relFile);
    if (!p) return { deleted: false };
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
    // REVIEW-L6 纵深防御：structureId 作为目录段（校验层已拒绝 '.'/'..'，此处防直接调用）
    if (!blueprint || !blueprint.structureId || blueprint.structureId === '.' || blueprint.structureId === '..') {
        throw new Error('非法 structureId');
    }
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
    EMPTY_POOL,
    ELEMENT_TYPES,
    HEIGHTMAPS,
    JIGSAW_SIZE_MIN,
    JIGSAW_SIZE_MAX,
    DATA_VERSION_1_21_1,
    defaultBlueprint,
    migrateBlueprint,
    normalizeBiomes,
    biomesForJson,
    defaultPiece,
    defaultJigsaw,
    defaultPoolElement,
    resolveId,
    validateBlueprint,
    listStructures,
    // REVIEW-P0：main.js 的 structure:read / structure:delete 用它做路径边界校验，
    // 但此前未导出 → 编辑器里所有「打开结构/删除结构」都抛
    // "StructureSchema.resolveStructureFile is not a function"（结构编辑功能整体不可用）。
    resolveStructureFile,
    readStructure,
    writeStructure,
    deleteStructure,
};
