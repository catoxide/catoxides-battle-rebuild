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
    // 游戏产出 = 小端；部分工具/数据集 = 大端 → decodeAuto 自动判别
    const root = (input && typeof input === 'object' && !Buffer.isBuffer(input)) ? input : NBT.decodeAuto(input).root;
    const size = root.size || [1, 1, 1];
    const [w, h, d] = [size[0], size[1], size[2]];

    // ---- palette（现代格式）
    // 1.21.1 真实格式：Properties（大写 P，compound{prop:str}）——对照 1180 个 vanilla 模板实测 +
    // NbtUtils.readBlockState 反编译源码（只认 Properties）。小写 properties（字符串）为 ≤1.16 旧格式兜底。
    let palette = null; // [{name, props}]
    if (Array.isArray(root.palette)) {
        palette = root.palette.map((e) => ({
            name: e.Name,
            props: (e.Properties && typeof e.Properties === 'object') ? { ...e.Properties } : parsePropertiesStr(e.properties),
        }));
    }
    const hasModernPalette = !!palette && palette.length > 0;
    const blocksRaw = root.blocks || [];
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
        for (const b of blocksRaw) {
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
                    blockNbts.push({ x, y, z, block: entry.name, nbt: b.nbt });
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
        if (Array.isArray(root.nbtData)) {
            for (const e of root.nbtData) {
                if (e.id && e.id !== 'minecraft:jigsaw') continue;
                const pos = e.pos || [];
                // orientation 藏在 palette 属性里
                let ori = '';
                if (palette) {
                    const st = (blocksRaw.find((bb) => bb.length === 4 && bb[1] === pos[0] && bb[2] === pos[1] && bb[3] === pos[2]) || [])[0];
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
        format: isLegacy ? 'legacy' : 'modern',
        dataVersion: root.DataVersion || null,
        paletteCount: palette ? palette.length : 0,
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
        if (id === 'minecraft:air') continue;
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
    // BE NBT（plain → tagged）：导入 = 读取游戏里现成的 NBT（箱子内容物等），可再经编辑器修改后导出
    const blockNbt = (region.blockNbts || [])
        .filter((b) => b.x >= 0 && b.y >= 0 && b.z >= 0 && b.x < w && b.y < h && b.z < d)
        .filter((b) => b.block && b.block !== 'minecraft:air' && b.nbt && typeof b.nbt === 'object')
        .map((b) => ({ x: b.x, y: b.y, z: b.z, nbt: NBT.plainToTagged(b.nbt) }));
    const piece = { name: pieceName, size: [w, h, d], boxFills: [], fillBlocks, jigsaws };
    if (blockNbt.length) piece.blockNbt = blockNbt;
    return piece;
}

const API = { parseRegionNbt, regionToPiece, parsePropertiesStr, blockIdStr };
if (typeof module === 'object' && module.exports) module.exports = API;
if (typeof self !== 'undefined') self.RegionImport = API;
