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

// 注意：经典 script 顶层 const/let 共享全局词法环境，标识符不能与其他脚本
// （如 structureGrowth.js 的 API/YAW_ROT90）重名，否则整页解析失败。
const VoxAPI = { rotPos, expandPiece, surfaceOf, worldSurface, toFillBlocks, applyClip, isAir };
if (typeof module === 'object' && module.exports) module.exports = VoxAPI;
if (typeof self !== 'undefined') self.Voxel = VoxAPI;
