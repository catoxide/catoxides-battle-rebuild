/**
 * structureDiagnostics.js - 拼合诊断（P5）
 *
 * 在生长模拟之上做两件事：
 *   1. detectVoxelOverlaps：把每次放置的片段展开为【世界体素集】（含旋转），
 *      两两求交 → 体素级重叠报告（比包围盒检查更精确，能抓到"盒不撞但方块撞"）。
 *   2. checkGeneratability：用 N 个不同种子反复生长，聚合统计
 *      （成功率 / 片段数 / 死端 / 重叠 / 占地），回答"这套 jigsaw 契约在游戏里
 *      能不能稳定长出完整建筑"。
 *
 * 纯函数，rng 可注入。加载：Node（require）/ 浏览器经典 script → window.StructureDiagnostics
 */
'use strict';

const Voxel = (typeof require === 'function' && typeof module === 'object')
    ? require('./voxel')
    : (typeof self !== 'undefined' ? self.Voxel : null);

/** mulberry32 —— 小而快的确定性 PRNG（测试/多种子扫描用） */
function mulberry32(seed) {
    let a = seed >>> 0;
    return function () {
        a |= 0; a = (a + 0x6D2B79F5) | 0;
        let t = Math.imul(a ^ (a >>> 15), 1 | a);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

/**
 * 放置列表 → 每放置一个世界体素集
 * @param {Array} placements growStructure 的 placements
 * @param {Array} pieces 蓝图片段
 * @returns {Array<{placementIndex:number, pieceName:string, voxels:Set<string>}>}
 */
function placementVoxelSets(placements, pieces) {
    const byName = new Map();
    for (const p of pieces || []) byName.set(p.name, p);
    const out = [];
    for (let i = 0; i < placements.length; i++) {
        const pl = placements[i];
        const piece = byName.get(pl.pieceName);
        const set = new Set();
        if (piece) {
            const [w, h, d] = piece.size || [1, 1, 1];
            const local = Voxel.expandPiece(piece);
            const rot = pl.rotation || 0;
            const off = pl.offset || [0, 0, 0];
            for (const key of local.keys()) {
                const [x, y, z] = key.split(',').map(Number);
                const [rx, rz] = Voxel.rotPos(x, z, w, d, rot);
                set.add((off[0] + rx) + ',' + (off[1] + y) + ',' + (off[2] + rz));
            }
        }
        out.push({ placementIndex: i, pieceName: pl.pieceName, voxels: set });
    }
    return out;
}

/**
 * 体素级重叠检测（两两求交）
 * @returns {{total:number, pairs:Array<{a:string, b:string, count:number, samples:Array<Array<number>>}>}}
 */
function detectVoxelOverlaps(placements, pieces) {
    const sets = placementVoxelSets(placements, pieces);
    const pairs = [];
    let total = 0;
    for (let i = 0; i < sets.length; i++) {
        for (let j = i + 1; j < sets.length; j++) {
            const [small, big] = sets[i].voxels.size <= sets[j].voxels.size
                ? [sets[i], sets[j]] : [sets[j], sets[i]];
            const samples = [];
            let count = 0;
            for (const k of small.voxels) {
                if (big.voxels.has(k)) {
                    count++;
                    if (samples.length < 8) samples.push(k.split(',').map(Number));
                }
            }
            if (count) {
                total += count;
                pairs.push({
                    a: sets[i].pieceName, b: sets[j].pieceName,
                    ai: sets[i].placementIndex, bi: sets[j].placementIndex,
                    count, samples,
                });
            }
        }
    }
    return { total, pairs };
}

/**
 * 可生成性检验：N 个种子反复生长 + 聚合
 * @param {Object} bp 通过 validateBlueprint 的蓝图
 * @param {{runs?:number, maxPieces?:number, seedBase?:number}} opts
 * @returns {{
 *   runs:number, successRuns:number,
 *   pieces:{min:number,max:number,avg:number},
 *   deadEnds:{total:number, avgPerRun:number, byReason:Object<string,number>, ghosted:number},
 *   overlaps:{runsWithOverlaps:number, totalVoxels:number, worstVoxels:number},
 *   footprint:{maxW:number,maxH:number,maxD:number},
 *   errors:Array<string>
 * }}
 */
function checkGeneratability(bp, opts = {}) {
    const G = (typeof require === 'function' && typeof module === 'object')
        ? require('./structureGrowth')
        : (typeof self !== 'undefined' ? self.StructureGrowth : null);
    const runs = Math.max(1, Math.min(1000, opts.runs || 50));
    const maxPieces = Math.max(1, Math.min(128, opts.maxPieces || 24));
    const seedBase = (opts.seedBase || 1) >>> 0;

    let successRuns = 0;
    let pMin = Infinity, pMax = 0, pSum = 0;
    let deTotal = 0, deGhosted = 0;
    const byReason = {};
    let runsWithOverlaps = 0, ovTotal = 0, ovWorst = 0;
    let fW = 0, fH = 0, fD = 0;
    const errors = [];

    for (let s = 0; s < runs; s++) {
        const rng = mulberry32(seedBase + s * 7919);
        let g;
        try {
            g = G.growStructure(bp, { rng, maxPieces });
        } catch (e) {
            errors.push(String(e.message || e));
            continue;
        }
        if (g.error) { errors.push(g.error); continue; }
        successRuns++;

        const n = g.placements.length;
        pMin = Math.min(pMin, n); pMax = Math.max(pMax, n); pSum += n;
        deTotal += g.deadEnds.length;
        for (const d of g.deadEnds) {
            deGhosted += d.ghost ? 1 : 0;
            byReason[d.reason] = (byReason[d.reason] || 0) + 1;
        }
        const ov = detectVoxelOverlaps(g.placements, bp.pieces);
        if (ov.total) {
            runsWithOverlaps++;
            ovTotal += ov.total;
            ovWorst = Math.max(ovWorst, ov.total);
        }
        // 占地（包围盒）
        let mnx = Infinity, mny = Infinity, mnz = Infinity, mxx = -Infinity, mxy = -Infinity, mxz = -Infinity;
        const byName = new Map();
        for (const p of bp.pieces || []) byName.set(p.name, p);
        for (const pl of g.placements) {
            const pc = byName.get(pl.pieceName);
            if (!pc) continue;
            const [w, h, d] = pc.size;
            mnx = Math.min(mnx, pl.offset[0]); mny = Math.min(mny, pl.offset[1]); mnz = Math.min(mnz, pl.offset[2]);
            mxx = Math.max(mxx, pl.offset[0] + w); mxy = Math.max(mxy, pl.offset[1] + h); mxz = Math.max(mxz, pl.offset[2] + d);
        }
        if (isFinite(mnx)) {
            fW = Math.max(fW, mxx - mnx); fH = Math.max(fH, mxy - mny); fD = Math.max(fD, mxz - mnz);
        }
    }

    return {
        runs,
        successRuns,
        pieces: {
            min: isFinite(pMin) ? pMin : 0,
            max: pMax,
            avg: successRuns ? Math.round((pSum / successRuns) * 10) / 10 : 0,
        },
        deadEnds: { total: deTotal, avgPerRun: Math.round((deTotal / runs) * 10) / 10, byReason, ghosted: deGhosted },
        overlaps: { runsWithOverlaps, totalVoxels: ovTotal, worstVoxels: ovWorst },
        footprint: { maxW: fW, maxH: fH, maxD: fD },
        errors,
    };
}

/**
 * 静态 jigsaw 契约检查（不跑生长，直接扫蓝图）
 * 逐片段逐 jigsaw 校验：池存在 → 池有元素 → 元素引用的片段存在 →
 * 池中某模板确有 name==target 的 jigsaw → 且存在可对接朝向。
 * @param {Object} bp 蓝图
 * @returns {Array<{piece:string, jigsaw:string, level:'error'|'warn'|'ok', msg:string, ok:boolean}>}
 */
function checkJigsawContracts(bp) {
    const G = (typeof require === 'function' && typeof module === 'object')
        ? require('./structureGrowth')
        : (typeof self !== 'undefined' ? self.StructureGrowth : null);
    const results = [];
    // 0.2.1：导入蓝图的池引用可能是完整 ID（ns:name），池表用裸名 —— 归一化后再查，
    // 否则导入的结构在「检查可生成性」里全部报「池不存在」。
    const normRef = (ref) => String(ref == null ? '' : ref).replace(/^[a-z0-9_.-]+:/, '');
    const byName = new Map();
    for (const p of bp.pieces || []) byName.set(p.name, p);
    const poolByName = new Map();
    for (const pl of bp.templatePools || []) poolByName.set(pl.name, pl);

    const allJigsawNames = new Set();
    for (const p of bp.pieces || []) for (const j of p.jigsaws || []) allJigsawNames.add(j.name);

    for (const p of bp.pieces || []) {
        for (const j of p.jigsaws || []) {
            const base = { piece: p.name, jigsaw: j.name };
            const pool = poolByName.get(normRef(j.pool));
            if (!pool) {
                results.push(Object.assign({}, base, { level: 'error', msg: '池不存在: ' + j.pool, ok: false }));
                continue;
            }
            if (!pool.elements || !pool.elements.length) {
                results.push(Object.assign({}, base, { level: 'warn', msg: '池为空', ok: false }));
                continue;
            }
            const tpls = [];
            let missingTpl = false;
            for (const el of pool.elements) {
                const tpl = byName.get(el.template);
                if (!tpl) { missingTpl = true; continue; }
                tpls.push(tpl);
            }
            if (!tpls.length) {
                results.push(Object.assign({}, base, { level: 'error', msg: '池引用的片段都不存在', ok: false }));
                continue;
            }
            if (!allJigsawNames.has(j.target)) {
                results.push(Object.assign({}, base, { level: 'warn', msg: 'target 不存在于任何片段: ' + j.target, ok: false }));
                continue;
            }
            let hasTarget = false, hasOpposite = false;
            for (const tpl of tpls) {
                for (const tj of tpl.jigsaws || []) {
                    if (tj.name !== j.target) continue;
                    hasTarget = true;
                    if (G && G.oppositeOrientation && G.rotatedOrientation) {
                        const opp = G.oppositeOrientation(j.orientation);
                        for (let r = 0; r < 4; r++) {
                            if (G.rotatedOrientation(tj.orientation, r) === opp) { hasOpposite = true; break; }
                        }
                    } else { hasOpposite = true; }
                    if (hasOpposite) break;
                }
                if (hasOpposite) break;
            }
            if (!hasTarget) {
                results.push(Object.assign({}, base, { level: 'warn', msg: '池内模板无 name=' + j.target + ' 的 jigsaw', ok: false }));
                continue;
            }
            if (!hasOpposite) {
                results.push(Object.assign({}, base, { level: 'warn', msg: '无对接朝向（' + j.orientation + '）', ok: false }));
                continue;
            }
            results.push(Object.assign({}, base, { level: 'ok', msg: 'OK', ok: true }));
        }
    }
    return results;
}

// 注意：经典 script 顶层 const 共享全局词法环境，不能叫 API（与 structureGrowth.js 重名）
const DiagAPI = { mulberry32, placementVoxelSets, detectVoxelOverlaps, checkGeneratability, checkJigsawContracts };

if (typeof module === 'object' && module.exports) module.exports = DiagAPI;
if (typeof self !== 'undefined') self.StructureDiagnostics = DiagAPI;
