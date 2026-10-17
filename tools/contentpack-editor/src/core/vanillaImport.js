// vanillaImport.js - 原始 jigsaw 数据 → 蓝图（vanillaExport 的逆运算）
//
// 支持来源（一键导入）：
//   1) 单个结构模板 .nbt（1.21.1 现代格式；兼容 1.20.x 旧格式）→ 单片段蓝图
//   2) 原版数据包 / 模组 jar / zip / 文件夹（含 data/<ns>/worldgen/structure/*.json）
//      → 每个 minecraft:jigsaw 结构重建【完整蓝图】：
//          structure 定义 + 递归 template_pool + 全部被引用模板 NBT（片段）+ structure_set
//
// 关键映射（为什么不能 1:1 照抄 ID）：
//   * 原版模板/池 ID 是嵌套路径（minecraft:village/plains/town_centers/plains_fountain_01），
//     而蓝图的片段名/池名必须是单段 [a-z0-9_.-]（导出时也写成单段文件名）
//     → 扁平化成 "village.plains.town_centers.plains_fountain_01"，并维护 ID 重写表，
//       jigsaw.pool / start_pool / pool.fallback 全部改写到【目标命名空间】。
//   * 蓝图命名空间 = 目标内容包命名空间（默认 imported），避免直接覆盖 data/minecraft。
//   * 非 jigsaw 类型结构（mineshaft/shipwreck/stronghold/...共 24/34 个原版结构）蓝图无法表达
//     → 计入 skipped 并在报告里列出（它们需要自定义 piece 生成器，不是拼图）。
//   * 实体（entities，如盔甲架/物品展示框）蓝图不表达 → 统计后丢弃并报告。
//   * 多调色板模板（1.20.5+ palettes，原版 20 个沉船模板）：取第 1 个调色板，报告变体丢失。
//   * 找不到池定义（引用了原版/其它包里的池）：保留原完整 ID（游戏里仍可用），不塞进蓝图的池列表。
//
// 纯函数 + 只读文件系统，主进程调用。

'use strict';
const fs = require('fs');
const path = require('path');
const NBT = require('./nbt');
const S = require('./structureSchema');
const RI = require('./regionImport');
const Jar = require('./jarReader');

// ===== 名称扁平化 =====

/** 资源路径规整：去掉 .json 后缀，返回 {ns, path} */
function splitResId(resId) {
    const s = String(resId || '').trim();
    if (!s) return { ns: 'minecraft', path: '' };
    const i = s.indexOf(':');
    if (i < 0) return { ns: 'minecraft', path: s };
    return { ns: s.slice(0, i), path: s.slice(i + 1) };
}

/** "village/plains/town_centers/x" → "village.plains.town_centers.x"（合法单段名） */
function flattenName(resPath) {
    const flat = String(resPath || '')
        .replace(/\.json$/, '')
        .replace(/\/+/g, '/')
        .split('/')
        .filter(Boolean)
        .map((seg) => seg.toLowerCase().replace(/[^a-z0-9_.-]/g, '_'))
        .join('.');
    return flat || 'unnamed';
}

/** 唯一的扁平名（冲突加 _2/_3…） */
function uniqueName(base, used) {
    let name = base;
    let k = 2;
    while (used.has(name)) name = base + '_' + (k++);
    used.add(name);
    return name;
}

// ===== 来源读取（文件夹 / zip / jar）=====

/**
 * 打开一个来源，返回 {kind, list():string[], read(rel):Buffer|null, close()}
 * 路径统一用 '/' 分隔的相对路径。
 */
function openSource(srcPath) {
    const stat = fs.statSync(srcPath);
    if (stat.isDirectory()) {
        const cache = new Map();
        const listAll = () => {
            const out = [];
            (function walk(dir, prefix) {
                let ents;
                try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
                for (const e of ents) {
                    const p = path.join(dir, e.name);
                    const rel = prefix ? prefix + '/' + e.name : e.name;
                    if (e.isDirectory()) walk(p, rel);
                    else out.push(rel);
                }
            })(srcPath, '');
            return out;
        };
        return {
            kind: 'dir',
            list: listAll,
            read(rel) {
                if (cache.has(rel)) return cache.get(rel);
                const p = path.join(srcPath, ...rel.split('/'));
                const buf = fs.existsSync(p) && fs.statSync(p).isFile() ? fs.readFileSync(p) : null;
                cache.set(rel, buf);
                return buf;
            },
            close() {},
        };
    }
    // zip / jar：复用 jarReader（只读中央目录，整文件读入；≤64MB）
    const ar = Jar.openArchive(srcPath);
    const cache = new Map();
    const names = ar.list();
    return {
        kind: ar.kind,
        list: () => names.slice(),
        read(rel) {
            if (cache.has(rel)) return cache.get(rel);
            let buf = null;
            try { buf = ar.read(rel); } catch { buf = null; }
            cache.set(rel, buf);
            return buf;
        },
        close() { ar.close(); },
    };
}

// ===== 权重归一（分数 → 整数）=====

function gcd(a, b) {
    a = Math.abs(a); b = Math.abs(b);
    while (b) { const t = a % b; a = b; b = t; }
    return a || 1;
}
function lcm(a, b) { return Math.abs(a / gcd(a, b) * b); }

/**
 * 展开后的元素分数权重 → 整数权重（保持原版相对比例）。
 * list_pool_element 展开会把外层权重分摊到嵌套元素（如 1 → 1/2 + 1/2），
 * 这里用分母最小公倍数放大成整数；再约分；最后按 150 上限整体缩放。
 * @param {Array<{num:number, den:number, spec:Object}>} specs
 * @returns {Array<Object>} 蓝图池元素
 */
function specsToElements(specs) {
    if (!specs.length) return [];
    let L = 1;
    for (const s of specs) L = lcm(L, s.den || 1);
    if (!Number.isFinite(L) || L > 100000) L = 100000;
    let weights = specs.map((s) => Math.max(1, Math.round((s.num || 1) * L / (s.den || 1))));
    const g = weights.reduce((a, w) => gcd(a, w), 0) || 1;
    weights = weights.map((w) => Math.max(1, Math.round(w / g)));
    const maxW = Math.max.apply(null, weights);
    if (maxW > 150) weights = weights.map((w) => Math.max(1, Math.round(w * 150 / maxW)));
    return specs.map((s, i) => Object.assign({ weight: weights[i] }, s.spec));
}

// ===== 蓝图片段压缩（fillBlocks → boxFills 行程编码）=====

/**
 * 把逐方块的 fillBlocks 压成沿 x 轴的行程（boxFills），蓝图体积可降 5-20 倍。
 * 语义完全等价：导出时 expandFills 会原样展开（from→to 逐格）。
 */
function compressPiece(piece) {
    const [w, h, d] = piece.size || [1, 1, 1];
    const rows = new Map(); // "y,z" → [{x, block}]
    for (const b of piece.fillBlocks || []) {
        if (b.x < 0 || b.y < 0 || b.z < 0 || b.x >= w || b.y >= h || b.z >= d) continue;
        const k = b.y + ',' + b.z;
        let arr = rows.get(k);
        if (!arr) { arr = []; rows.set(k, arr); }
        arr.push(b);
    }
    const runs = [];
    for (const arr of rows.values()) {
        arr.sort((a, b) => a.x - b.x);
        let start = arr[0];
        let prev = arr[0];
        for (let i = 1; i < arr.length; i++) {
            const b = arr[i];
            if (b.block === prev.block && b.x === prev.x + 1) { prev = b; continue; }
            runs.push({ from: [start.x, start.y, start.z], to: [prev.x, prev.y, prev.z], block: start.block });
            start = b;
            prev = b;
        }
        runs.push({ from: [start.x, start.y, start.z], to: [prev.x, prev.y, prev.z], block: start.block });
    }
    const out = Object.assign({}, piece, { boxFills: runs, fillBlocks: [] });
    return out;
}

// ===== 模板 → 片段 =====

/**
 * 结构模板 NBT（Buffer）→ 蓝图片段（含压缩）
 * @returns {{piece:Object, info:{size:number[], jigsaws:number, blockNbt:number, entities:number,
 *            multiPalette:boolean, paletteAlternatives:number, dataVersion:number|null}}}
 */
function templateToPiece(buffer, pieceName) {
    const region = RI.parseRegionNbt(buffer);
    const raw = RI.regionToPiece(region, pieceName);
    const piece = compressPiece(raw);
    return {
        piece,
        info: {
            size: region.size,
            jigsaws: region.jigsaws.length,
            blockNbt: (raw.blockNbt || []).length,
            entities: region.entities || 0,
            multiPalette: !!region.multiPalette,
            paletteAlternatives: region.paletteAlternatives || 0,
            dataVersion: region.dataVersion || null,
            format: region.format,
        },
    };
}

// ===== jigsaw 结构 → 蓝图 =====

/**
 * 单个 jigsaw 结构 JSON → 蓝图。
 * @param {Object} ctx
 *   { json, structNs, structPath, structureId, namespace, readStructureJson, readPoolJson,
 *     readTemplate, listStructureSets }
 * @returns {{blueprint:Object, warnings:string[], stats:Object}}
 */
function buildJigsawBlueprint(ctx) {
    const { json, namespace } = ctx;
    const warnings = [];
    const usedPieceNames = new Set();
    const usedPoolNames = new Set();
    const pieces = [];
    const pools = [];
    const templateCache = new Map(); // 原模板 fullId → pieceName
    const poolCache = new Map();     // 原池 fullId → {name, json}
    const pendingPools = [];         // 待处理的原池 fullId
    const stats = { pieces: 0, pools: 0, jigsaws: 0, blockNbt: 0, droppedEntities: 0, multiPalette: 0, skippedElements: 0, featureElements: 0, flattenedLists: 0 };

    /** 原池 ID → 蓝图池名（新 ID = namespace:name）；未找到定义/已丢弃的池返回 null（保留原 ID） */
    const EXTERNAL_POOLS = new Set(['minecraft:empty']); // 原版空池：游戏里始终存在，不导入
    function resolvePool(origId) {
        const full = origId.includes(':') ? origId : ('minecraft:' + origId);
        if (EXTERNAL_POOLS.has(full)) return null;
        const cached = poolCache.get(full);
        if (cached) return cached.dropped ? null : cached;
        const { ns, path: p } = splitResId(full);
        const poolJson = ctx.readPoolJson(ns, p);
        if (!poolJson) return null; // 找不到定义 → 保留原完整 ID（游戏里可能来自原版/其它包）
        const name = uniqueName(flattenName(p), usedPoolNames);
        const entry = { name, json: poolJson, origId: full, dropped: false };
        poolCache.set(full, entry);
        pools.push(entry);
        pendingPools.push(full);
        return entry;
    }

    /** 原模板 ID → 片段名（不存在该文件返回 null） */
    function resolveTemplate(origId) {
        const full = origId.includes(':') ? origId : ('minecraft:' + origId);
        if (templateCache.has(full)) return templateCache.get(full);
        const { ns, path: p } = splitResId(full);
        const buf = ctx.readTemplate(ns, p);
        if (!buf) return null;
        const pieceName = uniqueName(flattenName(p), usedPieceNames);
        let conv;
        try {
            conv = templateToPiece(buf, pieceName);
        } catch (e) {
            warnings.push(`模板 ${full} 解析失败: ${e.message}`);
            usedPieceNames.delete(pieceName);
            return null;
        }
        const piece = conv.piece;
        // 来源回溯（导入 → 导出往返校验/界面提示用；导出时被忽略）
        piece.importFrom = full;
        stats.pieces++;
        stats.jigsaws += conv.info.jigsaws;
        stats.blockNbt += conv.info.blockNbt;
        stats.droppedEntities += conv.info.entities;
        if (conv.info.multiPalette) stats.multiPalette++;
        if (conv.info.multiPalette) {
            warnings.push(`模板 ${full} 有 ${conv.info.paletteAlternatives} 个调色板（变体），已取第 1 个`);
        }
        // 片段内 jigsaw 引用的池也要一起导入（递归展开依赖）
        for (const j of piece.jigsaws || []) {
            if (!j.pool) continue;
            const fullPool = j.pool.includes(':') ? j.pool : ('minecraft:' + j.pool);
            const known = poolCache.get(fullPool);
            if (known && !known.dropped) j.pool = namespace + ':' + known.name;
            else if (!known) {
                const poolEntry = resolvePool(j.pool);
                // 解析不到（外部池：原版/其它包）→ 保留原完整 ID，游戏里仍可用
                j.pool = poolEntry ? (namespace + ':' + poolEntry.name) : j.pool;
            }
        }
        templateCache.set(full, pieceName);
        pieces.push(piece);
        return pieceName;
    }

    // ---- 起点：start_pool
    const startPoolFull = json.start_pool;
    const startPoolEntry = resolvePool(startPoolFull);
    if (!startPoolEntry) {
        warnings.push(`起始模板池 ${startPoolFull} 在来源里找不到定义（引用外部数据？）—— 导出后仍指向原池`);
    }
    // ---- 逐个处理池（BFS：处理池时又会把模板里的 jigsaw 池加进 pendingPools）
    const processed = new Set();
    while (pendingPools.length) {
        const fullId = pendingPools.shift();
        if (processed.has(fullId)) continue;
        processed.add(fullId);
        const entry = poolCache.get(fullId);
        if (!entry || entry.dropped) continue;
        const els = (entry.json.elements || []);
        entry.specs = entry.specs || [];
        // 池元素递归展开：single/legacy_single 是模板；feature 是特征；list 是嵌套组（展开并分摊权重）；
        // empty 跳过。权重用分数（num/den）记录，组装时统一放大成整数（保持原版比例）。
        function addElementSpec(el, num, den, depth) {
            if (!el || typeof el !== 'object') return;
            const t = el.element_type || 'minecraft:single_pool_element';
            if (t === 'minecraft:empty_pool_element') return;
            if (t === 'minecraft:feature_pool_element') {
                if (!el.feature) { warnings.push(`池 ${fullId} 的 feature 元素缺 feature 字段，已跳过`); return; }
                entry.specs.push({ num, den, spec: { feature: el.feature, projection: el.projection || 'rigid' } });
                stats.featureElements = (stats.featureElements || 0) + 1;
                return;
            }
            if (t === 'minecraft:list_pool_element') {
                if (depth > 4) { warnings.push(`池 ${fullId} 的 list_pool_element 嵌套过深，已跳过`); return; }
                const nested = el.elements || [];
                const sum = nested.reduce((a, x) => a + Math.max(1, (x && x.weight) || 1), 0) || 1;
                stats.flattenedLists = (stats.flattenedLists || 0) + 1;
                for (const sub of nested) {
                    const sw = Math.max(1, (sub && sub.weight) || 1);
                    addElementSpec(sub, num * sw, den * sum, depth + 1);
                }
                return;
            }
            const loc = el.location;
            if (!loc) { warnings.push(`池 ${fullId} 的元素缺 location，已跳过`); return; }
            const pieceName = resolveTemplate(loc);
            if (!pieceName) {
                stats.skippedElements++;
                warnings.push(`池 ${fullId} 的模板 ${loc} 找不到（引用外部数据？），已跳过`);
                return;
            }
            entry.specs.push({
                num, den,
                spec: {
                    template: pieceName,
                    projection: el.projection || 'rigid',
                    elementType: t,
                    processors: el.processors,
                },
            });
        }
        for (let i = 0; i < els.length; i++) {
            const e = els[i] || {};
            const w = Math.max(1, parseInt(e.weight != null ? e.weight : 1, 10) || 1);
            if (w > 150) warnings.push(`池 ${fullId} 元素#${i} weight=${e.weight} 超出蓝图上限 150`);
            addElementSpec(e.element || {}, w, 1, 0);
        }
        // fallback：原版是「池 ID」；能解析到导入池则改写，否则保留原 ID（如 minecraft:empty）
        const fb = entry.json.fallback;
        if (fb && typeof fb === 'string' && !fb.startsWith('#')) {
            const fbEntry = resolvePool(fb);
            entry.fallback = fbEntry ? (namespace + ':' + fbEntry.name) : fb;
        }
    }

    // ---- 组装 templatePools：丢掉“没有可表达元素”的池（原版空池 / 只有 feature 的池保留 feature 元素）
    const templatePools = [];
    const nameToOrigId = new Map();
    for (const entry of pools) {
        const elements = specsToElements(entry.specs || []);
        nameToOrigId.set(entry.name, entry.origId);
        if (!elements.length) {
            entry.dropped = true;
            warnings.push(`池 ${entry.origId} 没有可表达的元素（空池），未导入`);
            continue;
        }
        templatePools.push({
            name: entry.name,
            elements,
            fallback: entry.fallback || S.EMPTY_POOL,
            importFrom: entry.origId,
        });
    }
    // 引用了被丢弃池的 jigsaw → 退回原完整 ID（外部池引用；校验/导出允许）
    const keptPoolNames = new Set(templatePools.map((p) => p.name));
    for (const piece of pieces) {
        for (const j of piece.jigsaws || []) {
            if (!j.pool || !j.pool.startsWith(namespace + ':')) continue;
            const nm = j.pool.slice(namespace.length + 1);
            if (!keptPoolNames.has(nm)) {
                const orig = nameToOrigId.get(nm);
                j.pool = orig || 'minecraft:empty';
            }
        }
    }
    if (startPoolEntry && startPoolEntry.dropped) {
        warnings.push(`起始池 ${startPoolEntry.origId} 无可表达元素 —— 蓝图里改用空池引用`);
    }

    // ---- 结构定义
    // 起始池被丢弃（空池）时不能写 namespace:被丢弃池名——那会导出成指向不存在池的
    // start_pool（数据包在游戏里报错）。改用 minecraft:empty（原版空池，永远存在）。
    const startPoolRef = startPoolEntry
        ? (startPoolEntry.dropped ? 'minecraft:empty' : (namespace + ':' + startPoolEntry.name))
        : (json.start_pool || '');
    const structure = {
        step: json.step || 'surface_structures',
        terrainAdaptation: json.terrain_adaptation || 'none',
        startPool: startPoolRef,
        maxDistanceFromCenter: json.max_distance_from_center != null ? json.max_distance_from_center : 80,
        biomes: S.normalizeBiomes(json.biomes),
        size: json.size != null ? Math.max(S.JIGSAW_SIZE_MIN, Math.min(S.JIGSAW_SIZE_MAX, json.size)) : 7,
        startHeight: (json.start_height && typeof json.start_height === 'object') ? json.start_height : { absolute: 0 },
        useExpansionHack: json.use_expansion_hack !== false,
        projectStartToHeightmap: json.project_start_to_heightmap || null,
    };
    if (json.spawn_overrides && typeof json.spawn_overrides === 'object' && Object.keys(json.spawn_overrides).length) {
        structure.spawnOverrides = json.spawn_overrides;
    }
    if (json.start_jigsaw_name) structure.startJigsawName = json.start_jigsaw_name;
    // 1.21.1 可选字段原样透传（试炼密室：dimension_padding / liquid_settings / pool_aliases）
    if (Number.isInteger(json.dimension_padding)) structure.dimensionPadding = json.dimension_padding;
    if (json.liquid_settings) structure.liquidSettings = json.liquid_settings;
    if (Array.isArray(json.pool_aliases) && json.pool_aliases.length) {
        structure.poolAliases = json.pool_aliases;
        warnings.push(`结构含 ${json.pool_aliases.length} 条 pool_aliases：已原样透传（别名目标仍指向原版池）`);
    }
    const unknownKeys = Object.keys(json).filter((k) => ![
        'type', 'biomes', 'max_distance_from_center', 'size', 'spawn_overrides', 'start_height',
        'start_pool', 'step', 'terrain_adaptation', 'use_expansion_hack', 'project_start_to_heightmap',
        'start_jigsaw_name', 'dimension_padding', 'liquid_settings', 'pool_aliases',
    ].includes(k));
    if (unknownKeys.length) warnings.push(`结构定义含未识别字段（已丢弃）: ${unknownKeys.join(', ')}`);

    // ---- 结构集合：找引用本结构的那一个
    const structureFullId = ctx.structNs + ':' + ctx.structPath;
    const sets = ctx.listStructureSets();
    let structureSet = { spacing: 32, separation: 12, salt: Math.floor(Math.random() * 1e9), frequency: 1.0, exclusionZone: null };
    let matchedSet = null;
    for (const s of sets) {
        const list = (s.json.structures || []);
        if (list.some((x) => x && x.structure === structureFullId)) { matchedSet = s; break; }
    }
    if (matchedSet) {
        const pl = matchedSet.json.placement || {};
        if (pl.type && pl.type !== 'minecraft:random_spread') {
            warnings.push(`结构集合 ${matchedSet.fullId} 的 placement 是 ${pl.type}，蓝图只支持 random_spread（已按 spacing/separation 近似）`);
        }
        structureSet = {
            spacing: pl.spacing != null ? pl.spacing : 32,
            separation: pl.separation != null ? pl.separation : 12,
            salt: pl.salt != null ? pl.salt : 0,
            frequency: pl.frequency != null ? pl.frequency : 1.0,
            exclusionZone: pl.exclusion_zone ? {
                chunkCount: pl.exclusion_zone.chunk_count,
                otherSet: pl.exclusion_zone.other_set,
            } : null,
        };
    } else {
        warnings.push(`没找到引用 ${structureFullId} 的结构集合（structure_set），放置规则用默认 random_spread 32/12`);
    }

    const blueprint = {
        formatVersion: 2,
        structureId: ctx.structureId,
        namespace,
        structure,
        pieces,
        templatePools,
        structureSet,
        // 导入来源信息（便于回溯；导出时忽略）
        importInfo: {
            source: ctx.sourceLabel || '',
            from: structureFullId,
            importedAt: new Date().toISOString(),
        },
    };
    stats.pools = templatePools.length;
    return { blueprint, warnings, stats };
}

// ===== 顶层：导入入口 =====

/** 递归枚举 data/<ns>/worldgen/{structure,template_pool,structure_set}/ */
function scanWorldgen(list, kind) {
    const re = new RegExp('^data/([^/]+)/worldgen/' + kind + '/(.+)\\.json$');
    const out = [];
    for (const rel of list) {
        const m = re.exec(rel);
        if (m) out.push({ rel, ns: m[1], path: m[2] });
    }
    return out;
}

/**
 * 一键导入来源 → 蓝图列表。
 * @param {string} srcPath 文件夹 / zip / jar / 单个 .nbt
 * @param {Object} [opts] { namespace='imported', structureId=null, sourceLabel }
 * @returns {{ok:boolean, blueprints:Array, skipped:Array, warnings:string[], errors:string[], kind:string}}
 */
function importVanilla(srcPath, opts = {}) {
    const namespace = sanitizeNamespace(opts.namespace || 'imported');
    const result = { ok: false, blueprints: [], skipped: [], warnings: [], errors: [], kind: '' };
    if (!srcPath || !fs.existsSync(srcPath)) {
        result.errors.push('来源不存在: ' + srcPath);
        return result;
    }
    const stat = fs.statSync(srcPath);
    // ---- 单个 .nbt：单片段蓝图
    if (stat.isFile() && /\.nbt$/i.test(srcPath)) {
        result.kind = 'template';
        try {
            const buf = fs.readFileSync(srcPath);
            const base = path.basename(srcPath, path.extname(srcPath));
            const sid = sanitizeId(opts.structureId || base);
            const conv = templateToPiece(buf, 'main');
            // 模板里的 jigsaw 池引用：保留原完整 ID（单模板导入时无法解析池）
            for (const j of conv.piece.jigsaws || []) {
                if (!j.pool) j.pool = 'minecraft:empty';
            }
            const blueprint = {
                formatVersion: 2,
                structureId: sid,
                namespace,
                structure: {
                    step: 'surface_structures',
                    terrainAdaptation: 'none',
                    startPool: 'start',
                    maxDistanceFromCenter: 80,
                    biomes: ['#minecraft:is_overworld'],
                    size: 7,
                    startHeight: { absolute: 0 },
                    useExpansionHack: true,
                    projectStartToHeightmap: null,
                },
                pieces: [conv.piece],
                templatePools: [{ name: 'start', elements: [{ template: 'main', weight: 1, projection: 'rigid' }], fallback: S.EMPTY_POOL }],
                structureSet: { spacing: 32, separation: 12, salt: Math.floor(Math.random() * 1e9), frequency: 1.0, exclusionZone: null },
                importInfo: {
                    source: srcPath,
                    from: path.basename(srcPath),
                    dataVersion: conv.info.dataVersion,
                    entitiesDropped: conv.info.entities,
                    multiPalette: conv.info.multiPalette,
                    importedAt: new Date().toISOString(),
                },
            };
            if (conv.info.entities) result.warnings.push(`模板含 ${conv.info.entities} 个实体（盔甲架/展示框等），蓝图不表达，已丢弃`);
            if (conv.info.multiPalette) result.warnings.push(`模板有 ${conv.info.paletteAlternatives} 个调色板（变体），已取第 1 个`);
            if (conv.piece.jigsaws && conv.piece.jigsaws.length) {
                result.warnings.push(`模板含 ${conv.piece.jigsaws.length} 个 jigsaw 块：目标池保留原 ID（未随单模板导入）`);
            }
            result.blueprints.push({ blueprint, warnings: [], stats: { pieces: 1, pools: 1 } });
            result.ok = true;
        } catch (e) {
            result.errors.push('模板解析失败: ' + (e.message || e));
        }
        return result;
    }

    // ---- 数据包 / jar / zip / 文件夹
    // 0.2.1：单独选一个 .json 是最常见的误操作 → 给明确提示，而不是「找不到 EOCD」
    if (stat.isFile() && /\.json$/i.test(srcPath)) {
        result.errors.push('选中的是单个 .json 文件，无法直接导入。\n' +
            '请选择：结构模板 .nbt，或数据包 .jar/.zip，或数据包文件夹（含 data/<命名空间>/worldgen）。\n' +
            '若想导入一个结构的定义，请把它放进数据包目录后整包导入。');
        return result;
    }
    let src;
    try {
        src = openSource(srcPath);
    } catch (e) {
        result.errors.push('无法读取来源: ' + (e.message || e));
        return result;
    }
    result.kind = src.kind;
    try {
        const names = src.list();
        const structureFiles = scanWorldgen(names, 'structure');
        if (!structureFiles.length) {
            result.errors.push('来源里没有 data/<ns>/worldgen/structure/*.json（不是数据包/模组？）');
            return result;
        }
        // 读取器（按需 + 缓存）
        const jsonCache = new Map();
        const readJson = (rel) => {
            if (jsonCache.has(rel)) return jsonCache.get(rel);
            const buf = src.read(rel);
            let v = null;
            if (buf) { try { v = JSON.parse(buf.toString('utf-8')); } catch { v = null; } }
            jsonCache.set(rel, v);
            return v;
        };
        const readPoolJson = (ns, p) => readJson(`data/${ns}/worldgen/template_pool/${p}.json`);
        const readTemplate = (ns, p) => src.read(`data/${ns}/structure/${p}.nbt`);
        const structureSets = scanWorldgen(names, 'structure_set').map((s) => {
            const json = readJson(s.rel);
            return json ? { fullId: s.ns + ':' + s.path, json } : null;
        }).filter(Boolean);

        let imported = 0;
        for (const sf of structureFiles) {
            const json = readJson(sf.rel);
            if (!json) { result.skipped.push({ id: sf.ns + ':' + sf.path, reason: 'JSON 解析失败' }); continue; }
            const type = json.type || '';
            const fullId = sf.ns + ':' + sf.path;
            if (type !== 'minecraft:jigsaw') {
                result.skipped.push({ id: fullId, type, reason: '非 jigsaw 类型（需要自定义 piece 生成器，蓝图无法表达）' });
                continue;
            }
            const sid = sanitizeId(fullId.replace(/[:/]/g, '_'));
            try {
                const built = buildJigsawBlueprint({
                    json,
                    structNs: sf.ns,
                    structPath: sf.path,
                    structureId: sid,
                    namespace,
                    readPoolJson,
                    readTemplate,
                    listStructureSets: () => structureSets,
                    sourceLabel: srcPath,
                });
                const v = S.validateBlueprint(built.blueprint);
                if (!v.valid) {
                    // 0.2.1：校验失败的蓝图不算「已导入」（此前 imported++ 仍执行 → 报告里
                    // 「写入 N 个」与实际不符，主进程二次校验时又会静默跳过）
                    result.errors.push(`${sid}: 生成的蓝图校验失败: ${v.errors.slice(0, 3).join('; ')}`);
                    continue;
                }
                result.blueprints.push({ blueprint: built.blueprint, warnings: built.warnings, stats: built.stats });
                for (const w of built.warnings) result.warnings.push(`[${sid}] ${w}`);
                imported++;
            } catch (e) {
                result.errors.push(fullId + ': ' + (e.message || e));
            }
        }
        result.ok = imported > 0;
        if (!imported) result.errors.push('没有可导入的 minecraft:jigsaw 结构');
        return result;
    } finally {
        try { src.close(); } catch { /* 忽略 */ }
    }
}

/** 命名空间归一化（小写、合法字符） */
function sanitizeNamespace(ns) {
    const s = String(ns || '').toLowerCase().replace(/[^a-z0-9_.-]/g, '_');
    return s && s !== '.' && s !== '..' ? s : 'imported';
}

/** structureId 归一化 */
function sanitizeId(id) {
    const s = String(id || '').toLowerCase().replace(/[^a-z0-9_.-]/g, '_');
    return (!s || s === '.' || s === '..') ? 'imported_structure' : s;
}

const VanillaImportAPI = {
    importVanilla,
    buildJigsawBlueprint,
    templateToPiece,
    compressPiece,
    openSource,
    flattenName,
    sanitizeNamespace,
    sanitizeId,
};
if (typeof module === 'object' && module.exports) module.exports = VanillaImportAPI;
