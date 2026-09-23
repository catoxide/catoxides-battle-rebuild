/**
 * voxel.js - 片段的体素展开（3D 方块级渲染/编辑的核心）
 *
 * 把蓝图片段的 boxFills（整块填充）+ fillBlocks（单方块）展开为
 * 局部坐标体素表 Map<"x,y,z" → blockId>（air 不入表），并提供：
 *   - 表面体素提取（六邻居任一缺失 = 可见；内部方块不渲染）
 *   - 模板旋转（与 structureGrowth.js 同一约定）
 *
 * 纯函数。Node（require）/ 浏览器经典 script → window.Voxel
 */
'use strict';

// 注：方向名旋转表 YAW_ROT90 在 structureGrowth.js 全局已声明（经典 script 顶层 const
// 不能重名），此处不重复声明；需要时用 window.StructureGrowth.YAW_ROT90。

/** 模板旋转 r 下的局部 (x,z)（y 不变） */
function rotPos(x, z, w, d, r) {
    switch (r & 3) {
        case 0: return [x, z];
        case 1: return [d - 1 - z, x];
        case 2: return [w - 1 - x, d - 1 - z];
        default: return [z, w - 1 - x];
    }
}

function isAir(blockId) {
    return !blockId || blockId === 'minecraft:air' || blockId === 'air';
}

/**
 * 展开片段为局部体素表
 * @param {Object} piece 蓝图片段 {name, size:[w,h,d], boxFills, fillBlocks}
 * @returns {Map<string,string>} "x,y,z" → blockId（air 跳过；fillBlocks 覆盖 boxFills）
 */
function expandPiece(piece) {
    const map = new Map();
    const [w, h, d] = piece.size || [1, 1, 1];
    const set = (x, y, z, bid) => {
        if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) return;
        if (isAir(bid)) return;
        map.set(x + ',' + y + ',' + z, bid);
    };
    for (const bf of piece.boxFills || []) {
        if (isAir(bf.block)) continue;
        const fx = Math.min(bf.from[0], bf.to[0]), tx = Math.max(bf.from[0], bf.to[0]);
        const fy = Math.min(bf.from[1], bf.to[1]), ty = Math.max(bf.from[1], bf.to[1]);
        const fz = Math.min(bf.from[2], bf.to[2]), tz = Math.max(bf.from[2], bf.to[2]);
        for (let x = fx; x <= tx; x++) for (let y = fy; y <= ty; y++) for (let z = fz; z <= tz; z++) {
            set(x, y, z, bf.block);
        }
    }
    for (const fb of piece.fillBlocks || []) {
        set(fb.x, fb.y, fb.z, fb.block);
    }
    return map;
}

const NEIGHBORS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];

/**
 * 表面体素：六邻居任一缺失（或越界）的体素
 * @param {Map<string,string>} map expandPiece 的结果
 * @returns {Array<[number,number,number,string]>} [x, y, z, blockId]
 */
function surfaceOf(map) {
    const out = [];
    for (const [key, bid] of map) {
        const parts = key.split(',');
        const x = +parts[0], y = +parts[1], z = +parts[2];
        let exposed = false;
        for (const [dx, dy, dz] of NEIGHBORS) {
            if (!map.has((x + dx) + ',' + (y + dy) + ',' + (z + dz))) { exposed = true; break; }
        }
        if (exposed) out.push([x, y, z, bid]);
    }
    return out;
}

/**
 * 把体素表转为【世界坐标】表面数组（旋转 + 偏移）
 * @returns {Array<[number,number,number,string]>} 世界 [x, y, z, blockId]
 */
function worldSurface(map, offset, rot, size) {
    const [w, h, d] = size || [1, 1, 1];
    const out = [];
    for (const [x, y, z, bid] of surfaceOf(map)) {
        const [rx, rz] = rotPos(x, z, w, d, rot || 0);
        out.push([offset[0] + rx, offset[1] + y, offset[2] + rz, bid]);
    }
    return out;
}

/** 体素表 → 扁平 fillBlocks 数组（编辑后写回蓝图用；air 跳过） */
function toFillBlocks(map) {
    const out = [];
    for (const [key, bid] of map) {
        const [x, y, z] = key.split(',').map(Number);
        out.push({ x, y, z, block: bid });
    }
    out.sort((a, b) => (a.y - b.y) || (a.x - b.x) || (a.z - b.z));
    return out;
}

/**
 * P9 编辑切片（剖切面语义）：隐藏"面向相机"一侧的体素，展示另一侧 + 切片层本身。
 * 切片层（index 那一层）恒显示 —— 它是编辑工作面。
 * @param {Map<string,string>} map expandPiece / 编辑表（局部坐标）
 * @param {{axis:'x'|'y'|'z', index:number}|null} slice null = 不过滤
 * @param {number} camSide +1 = 相机在 +axis 侧（隐藏 coord>index）；-1 = 相机在 -axis 侧（隐藏 coord<index）
 * @returns {Map<string,string>} 过滤后的新表（原表不动；未启用返回原表引用）
 */
function cutOf(map, slice, camSide) {
    if (!slice) return map;
    const ai = slice.axis === 'x' ? 0 : slice.axis === 'y' ? 1 : 2;
    const idx = Math.trunc(slice.index || 0);
    const side = camSide >= 0 ? 1 : -1;
    const out = new Map();
    for (const [key, bid] of map) {
        const c = +key.split(',')[ai];
        if (c === idx) { out.set(key, bid); continue; }      // 切片层恒显示
        if ((side === 1 && c > idx) || (side === -1 && c < idx)) continue; // 隐藏面向相机一侧
        out.set(key, bid);
    }
    return out;
}

/**
 * 3D 切片过滤：隐藏某轴某方向上超出阈值的体素
 * @param {Array<[number,number,number,string]>} world 世界表面体素
 * @param {{axis:'x'|'y'|'z', dir:-1|1, threshold:number}|null} clip
 *   dir=1 隐藏 axis>threshold 的一侧；dir=-1 隐藏 axis<threshold 的一侧
 */
function applyClip(world, clip) {
    if (!clip) return world;
    const ai = clip.axis === 'x' ? 0 : clip.axis === 'y' ? 1 : 2;
    return world.filter((v) => (clip.dir === 1 ? v[ai] <= clip.threshold : v[ai] >= clip.threshold));
}

/**
 * P9 编辑写回：把 3D 体素编辑表合并进片段定义，返回【新片段对象】（不改原对象）
 * 规则：
 *  - 与展开基线无差异 → 原样返回（同引用）
 *  - 差异只落在 fillBlocks 区域 → fillBlocks 覆盖/删除（boxFills 保留）
 *  - 差异触及 boxFills 区域（air 无法覆盖整块填充）→ 整片扁平化：
 *    boxFills=[]，fillBlocks=编辑后的完整体素表
 *  - jigsaw 位置保护：写回跳过 jigsaw 占据的坐标（防契约冲突）
 * @param {Object} piece 蓝图片段
 * @param {Map<string,string>} edits "x,y,z"→blockId 的完整体素表（编辑表或展开表）
 */
function mergePieceEdits(piece, edits) {
    const base = expandPiece(piece);
    // 双向差异：新增/改色（edits 有、base 不同）+ 删除（base 有、edits 无 → null）
    const diff = new Map();
    for (const key of base.keys()) if (!edits.has(key)) diff.set(key, null);
    for (const [key, bid] of edits) {
        if (base.get(key) !== bid) diff.set(key, bid);
    }
    if (!diff.size) return piece;
    const jgPos = new Set((piece.jigsaws || []).map((j) => j.x + ',' + j.y + ',' + j.z));
    const clean = new Map();
    for (const [key, bid] of diff) if (!jgPos.has(key)) clean.set(key, bid);
    if (!clean.size) return piece;
    const boxFills = piece.boxFills || [];
    const inBox = (x, y, z) => boxFills.some((bf) => {
        if (isAir(bf.block)) return false;
        const fx = Math.min(bf.from[0], bf.to[0]), tx = Math.max(bf.from[0], bf.to[0]);
        const fy = Math.min(bf.from[1], bf.to[1]), ty = Math.max(bf.from[1], bf.to[1]);
        const fz = Math.min(bf.from[2], bf.to[2]), tz = Math.max(bf.from[2], bf.to[2]);
        return x >= fx && x <= tx && y >= fy && y <= ty && z >= fz && z <= tz;
    });
    let touchesBox = false;
    for (const key of clean.keys()) {
        const [x, y, z] = key.split(',').map(Number);
        if (inBox(x, y, z)) { touchesBox = true; break; }
    }
    if (!touchesBox) {
        // fm 的值已是 {x,y,z,block} 对象（不是 blockId 字符串）→ 不能走 toFillBlocks
        const fm = new Map((piece.fillBlocks || []).map((fb) => [fb.x + ',' + fb.y + ',' + fb.z, fb]));
        for (const [key, bid] of clean) {
            if (isAir(bid)) fm.delete(key);
            else { const [x, y, z] = key.split(',').map(Number); fm.set(key, { x, y, z, block: bid }); }
        }
        const out = [...fm.values()];
        out.sort((a, b) => (a.y - b.y) || (a.x - b.x) || (a.z - b.z));
        return Object.assign({}, piece, { boxFills: piece.boxFills || [], fillBlocks: out });
    }
    // 触及 boxFills → 扁平化整片（基线 = 完整展开，含 boxFills 内容）
    const flat = new Map(expandPiece(piece));
    for (const [key, bid] of clean) {
        if (isAir(bid)) flat.delete(key);
        else flat.set(key, bid);
    }
    return Object.assign({}, piece, { boxFills: [], fillBlocks: toFillBlocks(flat) });
}

// 注意：经典 script 顶层 const/let 共享全局词法环境，标识符不能与其他脚本
// （如 structureGrowth.js 的 API/YAW_ROT90）重名，否则整页解析失败。
const VoxAPI = { rotPos, expandPiece, surfaceOf, worldSurface, toFillBlocks, applyClip, cutOf, mergePieceEdits, isAir };
if (typeof module === 'object' && module.exports) module.exports = VoxAPI;
if (typeof self !== 'undefined') self.Voxel = VoxAPI;
