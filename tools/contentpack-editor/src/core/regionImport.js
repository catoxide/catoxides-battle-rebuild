/**
 * regionImport.js - 圈地读取：原版结构模板 .nbt → 蓝图片段（纯函数）
 *
 * 解析两种结构模板格式：
 *   1. 【1.21.1 当前格式】（对照 1180 个 vanilla 模板实测 + NbtUtils.readBlockState 反编译源码）：
 *        size: [w,h,d]（IntList）
 *        palette: [{Name, Properties?}]（Properties 为 compound{prop:str}，大写 P！
 *          例：{"Properties":{"half":"bottom","facing":"east"},"Name":"minecraft:oak_stairs"}）
 *        blocks: [{pos:[x,y,z], state:Int, nbt?:Compound}]
 *          - jigsaw 块：state 指向 minecraft:jigsaw，Properties.orientation 为朝向，
 *            nbt = {name,target,pool,joint,final_state,placement_priority,selection_priority}
 *   2. 【1.20.x 旧格式】（兼容旧工具/旧游戏版本产物）：
 *        blocks: [[state,x,y,z], ...]
 *        nbtData: [{pos, id, name, pool, target, joint, final_state, ...}]
 *
 * 产物（regionToPiece）与蓝图片段字段一一对应：
 *   fillBlocks: [{x,y,z,block}]（air 跳过；jigsaw 位置不入 fillBlocks；
 *              block 串可带状态属性 "name[props]"，来自 palette Properties）
 *   jigsaws:    [{x,y,z,name,orientation,target,finalState,joint,
 *                 selectionPriority,placementPriority,pool}]
 *   blockNbt:   [{x,y,z,nbt}]（非 jigsaw 方块的 BE NBT，plain→tagged compound；
 *                 导入即"读取游戏里现成的 NBT"，编辑器可改后随导出写回 block.nbt）
 */
'use strict';

const NBT = require('./nbt');

/** 解析 "k=v,k2=v2" 属性串（1.21.1 palette properties 字符串格式） */
function parsePropertiesStr(s) {
    const out = {};
    if (!s) return out;
    for (const kv of String(s).split(',')) {
        const i = kv.indexOf('=');
        if (i > 0) out[kv.slice(0, i).trim()] = kv.slice(i + 1).trim();
    }
    return out;
}

/** 方块 ID 串（带属性）："minecraft:oak_log[axis=y]" */
function blockIdStr(name, properties) {
    if (properties && Object.keys(properties).length) {
        const p = Object.entries(properties).map(([k, v]) => k + '=' + v).join(',');
        return name + '[' + p + ']';
    }
    return name;
}

/**
 * 解析结构模板 NBT（gzip Buffer 或已解码根对象）
 * @returns {{size:[number,number,number], blocks:Array, jigsaws:Array,
 *            entities:number, format:'modern'|'legacy', dataVersion?:number,
 *            paletteCount:number}}
 */
function parseRegionNbt(input) {
    // Java 版 NBT = 大端；部分第三方工具/旧包产出小端 → decodeTaggedAuto 自动判别
    // （打分优先「完整消费缓冲区」，避免把大端文件按小端解析出前缀树）。
    // 用 tagged 精确解码：保留 NBT 类型（实体 UUID 的 IntArray、byte 字段等），
    // 否则 plainToTagged 的启发式推断会把 IntArray 写成 List<Int>，导出后字节不一致。
    let plainRoot;
    let taggedRoot = null; // {$t:10, v:{...}}
    let nbtEndianness = null;
    let nbtComplete = true;
    if (input && typeof input === 'object' && !Buffer.isBuffer(input) && !ArrayBuffer.isView(input)) {
        plainRoot = input; // 调用方已解码的 plain 树（兼容旧调用/测试）
    } else {
        const d = NBT.decodeTaggedAuto(input);
        taggedRoot = d.root;
        nbtEndianness = d.endianness;
        nbtComplete = d.complete;
        plainRoot = NBT.taggedToPlain(d.root);
    }
    const root = plainRoot;
    const size = root.size || [1, 1, 1];
    const [w, h, d] = [size[0], size[1], size[2]];

    // ---- palette（现代格式）
    // 1.21.1 真实格式：Properties（大写 P，compound{prop:str}）——对照 1180 个 vanilla 模板实测 +
    // NbtUtils.readBlockState 反编译源码（只认 Properties）。小写 properties（字符串）为 ≤1.16 旧格式兜底。
    // 1.20.5+ 多调色板：字段名 palettes（复数，20/1180 个 vanilla 模板，含沉船）。各调色板等长，
    // blocks[].state 对每个调色板都成立 → 导入取第 1 个，并记录 multiPalette 供上层提示。
    let palette = null; // [{name, props}]
    let multiPalette = false;
    let paletteList = null;
    if (Array.isArray(root.palette)) {
        paletteList = [root.palette];
    } else if (Array.isArray(root.palettes) && root.palettes.length) {
        paletteList = root.palettes;
        multiPalette = true;
    }
    if (paletteList) {
        palette = (paletteList[0] || []).map((e) => ({
            name: e.Name,
            props: (e.Properties && typeof e.Properties === 'object') ? { ...e.Properties } : parsePropertiesStr(e.properties),
        }));
    }
    const hasModernPalette = !!palette && palette.length > 0;
    const blocksRaw = root.blocks || [];
    // tagged 侧：与 blocksRaw 同序，用来原样保留每个方块实体的 NBT（类型不丢）
    const blocksTagged = (taggedRoot && taggedRoot.v && taggedRoot.v.blocks && taggedRoot.v.blocks.$t === NBT.T.LIST)
        ? taggedRoot.v.blocks.v : null;
    const isLegacy = blocksRaw.length > 0 && Array.isArray(blocksRaw[0]);

    const blocks = [];   // {x,y,z,block,properties}
    const jigsaws = [];  // {x,y,z,name,orientation,target,finalState,joint,selectionPriority,placementPriority,pool}
    const blockNbts = []; // {x,y,z,block,nbt(plain)} —— 非 jigsaw 方块的 BE NBT（箱子/刷怪笼/酿造台等，
                          // “读取现成 NBT”的数据源；jigsaw 的 nbt 归 jigsaws[] 管）

    function addJigsaw(x, y, z, o) {
        // o: {name?, orientation?, target?, pool?, joint?, finalState?, selectionPriority?, placementPriority?}
        const j = { x, y, z };
        j.name = o.name != null ? o.name : '';
        j.orientation = o.orientation || '';
        j.target = o.target != null ? o.target : '';
        if (o.pool) j.pool = o.pool;
        j.finalState = o.finalState != null ? o.finalState : 'minecraft:air';
        j.joint = o.joint || 'rollable';
        j.selectionPriority = o.selectionPriority != null ? o.selectionPriority : 10;
        j.placementPriority = o.placementPriority != null ? o.placementPriority : 10;
        jigsaws.push(j);
    }

    if (!isLegacy) {
        // ---- 现代格式：blocks = [{pos, state, nbt?}]
        if (!hasModernPalette) throw new Error('无法识别的结构模板：缺 palette 且 blocks 非旧格式');
        const jigsawPos = new Set();
        for (let bi = 0; bi < blocksRaw.length; bi++) {
            const b = blocksRaw[bi];
            const pos = b.pos || [];
            const x = pos[0], y = pos[1], z = pos[2];
            if (x == null || y == null || z == null) continue;
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) continue;
            const entry = palette[b.state];
            if (!entry) continue;
            if (entry.name === 'minecraft:jigsaw') {
                jigsawPos.add(x + ',' + y + ',' + z);
                const n = b.nbt || {};
                addJigsaw(x, y, z, {
                    name: n.name,
                    // orientation 是方块状态属性（存于 palette）；缺失时 JigsawBlock 默认 NORTH_UP
                    orientation: entry.props.orientation || 'north_up',
                    target: n.target,
                    pool: n.pool,
                    joint: n.joint,
                    finalState: n.final_state,
                    selectionPriority: n.selection_priority,
                    placementPriority: n.placement_priority,
                });
            } else {
                blocks.push({ x, y, z, block: entry.name, properties: entry.props });
                if (b.nbt && entry.name !== 'minecraft:air') {
                    // nbtTagged：精确类型（导回时字节一致）；nbt：plain（旧调用方兼容）
                    const bt = blocksTagged && blocksTagged[bi] && blocksTagged[bi].v && blocksTagged[bi].v.nbt;
                    blockNbts.push({ x, y, z, block: entry.name, nbt: b.nbt, nbtTagged: bt || null });
                }
            }
        }
        // 旧格式 nbtData（个别混合文件）
        if (Array.isArray(root.nbtData)) {
            for (const e of root.nbtData) {
                const pos = e.pos || [];
                if (jigsawPos.has(pos[0] + ',' + pos[1] + ',' + pos[2])) continue;
                if (e.id && e.id !== 'minecraft:jigsaw') {
                    // 非 jigsaw 的 BE NBT（旧格式 nbtData 条目 = {pos, id, ...BE字段}）
                    const nbt = { id: e.id };
                    for (const [k, val] of Object.entries(e)) if (k !== 'pos' && k !== 'id') nbt[k] = val;
                    blockNbts.push({ x: pos[0], y: pos[1], z: pos[2], block: e.id, nbt });
                    continue;
                }
                addJigsaw(pos[0], pos[1], pos[2], {
                    name: e.name, target: e.target, pool: e.pool, joint: e.joint,
                    finalState: e.final_state,
                    selectionPriority: e.selection_priority, placementPriority: e.placement_priority,
                });
            }
        }
    } else {
        // ---- 旧格式：blocks = [[state,x,y,z]]，jigsaw 实体在 nbtData
        // 旧格式 palette：{Name, Properties:{}}（大写 P，compound）或现代 {Name, properties:str}
        if (Array.isArray(root.palette)) {
            palette = root.palette.map((e) => ({
                name: e.Name,
                props: (e.Properties && typeof e.Properties === 'object') ? e.Properties : parsePropertiesStr(e.properties),
            }));
        }
        const jigsawByPos = new Map();
        // REVIEW-M8：此前每个 jigsaw 对 blocksRaw 线性 find → O(blocks×jigsaws)。
        // 实测 20万×200 ≈ 270ms；128³ 模板 ×2000 jigsaws 外推 ≈ 28s 主线程卡死。
        // 预建 pos→state 哈希表（同位置取首个 state，与原 find 语义一致）。
        const stateByPos = new Map();
        for (const bb of blocksRaw) {
            if (!Array.isArray(bb) || bb.length !== 4) continue;
            const k = bb[1] + ',' + bb[2] + ',' + bb[3];
            if (!stateByPos.has(k)) stateByPos.set(k, bb[0]);
        }
        if (Array.isArray(root.nbtData)) {
            for (const e of root.nbtData) {
                if (e.id && e.id !== 'minecraft:jigsaw') continue;
                const pos = e.pos || [];
                // orientation 藏在 palette 属性里
                let ori = '';
                if (palette) {
                    const st = stateByPos.get(pos[0] + ',' + pos[1] + ',' + pos[2]);
                    const pe = palette[st];
                    if (pe && pe.props && pe.props.orientation) ori = pe.props.orientation;
                }
                if (!ori) ori = 'north_up'; // JigsawBlock 默认方向
                jigsawByPos.set(pos[0] + ',' + pos[1] + ',' + pos[2], {
                    name: e.name, orientation: ori, target: e.target, pool: e.pool, joint: e.joint,
                    finalState: e.final_state,
                    selectionPriority: e.selection_priority, placementPriority: e.placement_priority,
                });
            }
        }
        for (const bb of blocksRaw) {
            if (!Array.isArray(bb) || bb.length < 4) continue;
            const st = bb[0], x = bb[1], y = bb[2], z = bb[3];
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) continue;
            const key = x + ',' + y + ',' + z;
            if (jigsawByPos.has(key)) {
                addJigsaw(x, y, z, jigsawByPos.get(key));
                continue;
            }
            const entry = palette ? palette[st] : null;
            if (!entry) continue;
            blocks.push({ x, y, z, block: entry.name, properties: entry.props });
        }
    }

    return {
        size: [w, h, d],
        blocks,
        jigsaws,
        blockNbts,
        entities: Array.isArray(root.entities) ? root.entities.length : 0,
        entityList: Array.isArray(root.entities) ? root.entities : [],
        format: isLegacy ? 'legacy' : 'modern',
        dataVersion: root.DataVersion || null,
        paletteCount: palette ? palette.length : 0,
        // 1.20.5+ palettes（复数）：只取第 1 个调色板，上层应提示用户变体丢失
        multiPalette,
        paletteAlternatives: paletteList ? paletteList.length : 0,
        tagged: !!taggedRoot,
        nbtEndianness,
        nbtComplete,
    };
}

/**
 * region → 蓝图片段
 * @param {Object} region parseRegionNbt 的结果
 * @param {string} pieceName
 * @returns {Object} 片段 {name, size, boxFills:[], fillBlocks, jigsaws, blockNbt?}
 *         blockNbt：非 jigsaw 方块的 BE NBT（plain → tagged compound，P12 蓝图格式）
 */
function regionToPiece(region, pieceName) {
    const [w, h, d] = region.size;
    const fillBlocks = [];
    const seen = new Set();
    for (const b of region.blocks) {
        if (b.x < 0 || b.y < 0 || b.z < 0 || b.x >= w || b.y >= h || b.z >= d) continue;
        const id = blockIdStr(b.block, b.properties);
        // 保留 air：原版模板里的 air 是「清除」语义（放置时覆盖原地形），
        // structure_void 才是「保持不动」。丢掉 air 会让导入的建筑嵌进地形里
        // （实测 structure.zip 20 个模板共 1225+ 个 air 格）。编辑器体素表本就跳过 air
        // （voxel.js isAir），这里保留不影响 3D 显示。
        const key = b.x + ',' + b.y + ',' + b.z;
        if (seen.has(key)) continue;
        seen.add(key);
        fillBlocks.push({ x: b.x, y: b.y, z: b.z, block: id });
    }
    const jigsaws = region.jigsaws
        .filter((j) => j.x >= 0 && j.y >= 0 && j.z >= 0 && j.x < w && j.y < h && j.z < d)
        .map((j) => {
            const o = { x: j.x, y: j.y, z: j.z, name: j.name, orientation: j.orientation, target: j.target };
            if (j.pool) o.pool = j.pool;
            o.finalState = j.finalState;
            o.joint = j.joint;
            o.selectionPriority = j.selectionPriority;
            o.placementPriority = j.placementPriority;
            return o;
        });
    // BE NBT：导入 = 读取游戏里现成的 NBT（箱子内容物等），可再经编辑器修改后导出。
    // 优先用 tagged 原样（类型不丢：UUID IntArray / byte 字段）；无 tagged 时退回 plain→tagged 推断。
    const blockNbt = (region.blockNbts || [])
        .filter((b) => b.x >= 0 && b.y >= 0 && b.z >= 0 && b.x < w && b.y < h && b.z < d)
        .filter((b) => b.block && b.block !== 'minecraft:air' &&
            ((b.nbtTagged && b.nbtTagged.$t === NBT.T.COMPOUND) || (b.nbt && typeof b.nbt === 'object')))
        .map((b) => ({
            x: b.x, y: b.y, z: b.z,
            nbt: (b.nbtTagged && b.nbtTagged.$t === NBT.T.COMPOUND) ? b.nbtTagged : NBT.plainToTagged(b.nbt),
        }));
    const piece = { name: pieceName, size: [w, h, d], boxFills: [], fillBlocks, jigsaws };
    if (blockNbt.length) piece.blockNbt = blockNbt;
    return piece;
}

const API = { parseRegionNbt, regionToPiece, parsePropertiesStr, blockIdStr };
if (typeof module === 'object' && module.exports) module.exports = API;
if (typeof self !== 'undefined') self.RegionImport = API;
