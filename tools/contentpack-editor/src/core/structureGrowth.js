/**
 * structureGrowth.js - 原版 jigsaw 生长模拟（3D 拼合预览的核心）
 *
 * 模拟 vanilla 1.21.1 的 jigsaw 结构展开规则（对照 minecraft.wiki "Jigsaw" / "Structure template"）：
 *   1. 从 startPool 按权重随机抽一个模板作为起点，放在原点
 *   2. 每个已放置片段上的 jigsaw 块按以下规则生长：
 *      - 按其 pool 按权重随机抽目标模板
 *      - 在目标模板中找 name == 本块 target 且 orientation == opposite(本块 orientation) 的 jigsaw 块
 *      - joint=rollable：随机取一个 90° 旋转（0/1/2/3）再找
 *      - joint=aligned：取所有能对齐的旋转
 *      - 多个候选按 selection_priority 高者优先（同分随机）
 *      - 新片段放置到使匹配块恰好落在 源块位置 + 朝向方向 上
 *   3. 被用来对接的 jigsaw 块被"消耗"，不再生长；其余 jigsaw 块继续入队
 *   4. 限制：maxPieces 总片段数、旋转后包围盒重叠视为放置失败
 *
 * 纯函数，无副作用。rng 可注入（测试用确定性种子）。
 *
 * 加载：Node（require）/ 浏览器经典 script → window.StructureGrowth
 */
'use strict';

/** orientation 首词（面）→ 生长方向向量（世界坐标，北 = -Z） */
const FACE_DIR = {
    up: [0, 1, 0],
    down: [0, -1, 0],
    east: [1, 0, 0],
    west: [-1, 0, 0],
    north: [0, 0, -1],
    south: [0, 0, 1],
};

/** 90° 俯视顺时针旋转（+X 东 → +Z 南 → -X 西 → -Z 北） */
const YAW_ROT90 = { east: 'south', south: 'west', west: 'north', north: 'east' };

/** 面 ↔ 对面 */
const FACE_OPPOSITE = { up: 'down', down: 'up', east: 'west', west: 'east', north: 'south', south: 'north' };

/** orientation 的"对面"（up_east ↔ down_west，east_up ↔ west_up） */
function oppositeOrientation(ori) {
    const { face, yaw } = splitOrientation(ori);
    if (!yaw) return ori;
    if (face === 'up' || face === 'down') {
        // 顶/底面：面翻转，yaw 取水平对向
        return (face === 'up' ? 'down' : 'up') + '_' + YAW_ROT90_OPP[yaw];
    }
    // 水平面：面取对向，yaw 恒为 up
    return YAW_ROT90_OPP[face] + '_up';
}
/** 水平方向对向 */
const YAW_ROT90_OPP = { east: 'west', west: 'east', north: 'south', south: 'north' };

/** 朝向拆分为 {face, yaw}（east_up → face=east, yaw=up；up_east → face=up, yaw=east） */
function splitOrientation(ori) {
    const i = ori.indexOf('_');
    if (i < 0) return { face: ori, yaw: null };
    const a = ori.slice(0, i);
    const b = ori.slice(i + 1);
    // 第一个词是水平方向 → 面=水平，yaw=up；否则面=up/down，yaw=水平
    if (FACE_OPPOSITE[a] && (a === 'east' || a === 'west' || a === 'north' || a === 'south')) {
        return { face: a, yaw: 'up' };
    }
    return { face: a, yaw: b };
}

/** 生长方向 = orientation 的"面"方向 */
function orientationDir(ori) {
    const { face } = splitOrientation(ori);
    return FACE_DIR[face] || [0, 1, 0];
}

/**
 * 模板旋转 r(0..3)（90° 俯视顺时针，+X→+Z）下的 orientation：
 *   - 顶/底面（up_X / down_X）：面不变，yaw 旋转（up_east → up_south → …）
 *   - 水平面（X_up）：面旋转（east_up → south_up → west_up → north_up），yaw 恒为 up
 */
function rotatedOrientation(ori, r) {
    const { face, yaw } = splitOrientation(ori);
    if (!yaw) return ori;
    let f = face, y = yaw;
    for (let i = 0; i < (r & 3); i++) {
        if (f === 'up' || f === 'down') y = YAW_ROT90[y] || y;
        else f = YAW_ROT90[f] || f; // 水平面旋转 = 面转
    }
    return f + '_' + y;
}

/**
 * 模板旋转 r(0..3) 下，局部 (x, z) 坐标（y 不变）。
 * 标准网格旋转（MC 结构模板旋转约定）：
 *   r=1: (x,z) → (d-1-z, x)；r=2: (w-1-x, d-1-z)；r=3: (z, w-1-x)
 */
function rotatedPos(x, z, w, d, r) {
    switch (r & 3) {
        case 0: return [x, z];
        case 1: return [d - 1 - z, x];
        case 2: return [w - 1 - x, d - 1 - z];
        default: return [z, w - 1 - x];
    }
}

/** 按权重随机抽元素（rng 可注入）；空池返回 null */
function pickWeighted(elements, rng) {
    if (!elements || !elements.length) return null;
    let total = 0;
    for (const el of elements) total += Math.max(1, el.weight || 1);
    let roll = rng() * total;
    for (const el of elements) {
        roll -= Math.max(1, el.weight || 1);
        if (roll < 0) return el;
    }
    return elements[elements.length - 1];
}

/**
 * 模拟 jigsaw 生长
 * @param {Object} bp 通过 validateBlueprint 的蓝图
 * @param {{rng?:Function, maxPieces?:number}} opts
 * @returns {{placements:Array, deadEnds:Array, error?:string}}
 *   placements[i] = { pieceName, offset:[x,y,z], rotation:0..3,
 *                      via: null | {fromPiece, jigsawName, at:[x,y,z]} }
 */
function growStructure(bp, opts = {}) {
    const rng = opts.rng || Math.random;
    const maxPieces = opts.maxPieces || 24;

    const byName = new Map();
    for (const p of bp.pieces || []) byName.set(p.name, p);
    const poolByName = new Map();
    for (const pl of bp.templatePools || []) poolByName.set(pl.name, pl);

    const st = bp.structure || {};
    const startPool = poolByName.get(st.startPool);
    if (!startPool) return { placements: [], deadEnds: [], error: 'startPool 不存在: ' + st.startPool };
    const startEl = pickWeighted(startPool.elements, rng);
    if (!startEl) return { placements: [], deadEnds: [], error: 'startPool 无元素' };
    if (!byName.has(startEl.template)) {
        return { placements: [], deadEnds: [], error: 'startPool 引用的片段不存在: ' + startEl.template };
    }

    const placements = [];
    const deadEnds = [];

    function addPlacement(pieceName, offset, rotation, via) {
        // 包围盒重叠检查（简单碰撞）
        const pc = byName.get(pieceName);
        const [w, h, d] = pc.size;
        for (const q of placements) {
            const qc = byName.get(q.pieceName);
            const [qw, qh, qd] = qc.size;
            if (offset[0] < q.offset[0] + qw && q.offset[0] < offset[0] + w &&
                offset[1] < q.offset[1] + qh && q.offset[1] < offset[1] + h &&
                offset[2] < q.offset[2] + qd && q.offset[2] < offset[2] + d) {
                return null;
            }
        }
        const idx = placements.length;
        placements.push({ pieceName, offset, rotation, via });
        return idx;
    }

    // 起点
    addPlacement(startEl.template, [0, 0, 0], 0, null);

    // 生长队列：{ pi: 放置索引, ji: 该片段内的 jigsaw 索引 }
    const queue = [];
    {
        const p0 = byName.get(startEl.template);
        for (let ji = 0; ji < (p0.jigsaws || []).length; ji++) queue.push({ pi: 0, ji });
    }

    /** 死端记录（带幽灵盒：3D 预览用红色半透明框标出"本应生长/放置失败"的位置） */
    function pushDeadEnd(srcPl, srcJg, reason, ghost) {
        const d = { from: srcPl.pieceName, jigsaw: srcJg.name, reason };
        if (ghost) d.ghost = ghost;
        deadEnds.push(d);
    }

    let guard = 0;
    while (queue.length && placements.length < maxPieces && guard++ < 4096) {
        const src = queue.shift();
        const srcPl = placements[src.pi];
        if (srcPl === undefined) continue;
        const srcPiece = byName.get(srcPl.pieceName);
        const srcJg = srcPiece.jigsaws[src.ji];
        if (!srcJg) continue;

        // 生长尝试位置（源 jigsaw 世界坐标 + 朝向方向）——配置类死端的幽灵标记
        const D0 = orientationDir(srcJg.orientation);
        const attemptCell = [
            srcPl.offset[0] + srcJg.x + D0[0],
            srcPl.offset[1] + srcJg.y + D0[1],
            srcPl.offset[2] + srcJg.z + D0[2],
        ];
        const cellGhost = { x: attemptCell[0], y: attemptCell[1], z: attemptCell[2], w: 1, h: 1, d: 1 };

        const pool = poolByName.get(srcJg.pool);
        if (!pool) { pushDeadEnd(srcPl, srcJg, '池不存在' + srcJg.pool, cellGhost); continue; }
        const el = pickWeighted(pool.elements, rng);
        if (!el) { pushDeadEnd(srcPl, srcJg, '池为空', cellGhost); continue; }
        const tpl = byName.get(el.template);
        if (!tpl) { pushDeadEnd(srcPl, srcJg, '片段不存在', cellGhost); continue; }

        const targetOpp = oppositeOrientation(srcJg.orientation);
        // 候选旋转
        let rots;
        if (srcJg.joint === 'aligned') {
            rots = [0, 1, 2, 3].filter((r) =>
                (tpl.jigsaws || []).some((j) => j.name === srcJg.target && rotatedOrientation(j.orientation, r) === targetOpp));
        } else {
            rots = [Math.floor(rng() * 4)]; // rollable：随机旋转
        }
        // 收集候选 (旋转, jigsaw 索引)
        const cands = [];
        for (const r of rots) {
            const [w, h, d] = tpl.size;
            for (let ji = 0; ji < (tpl.jigsaws || []).length; ji++) {
                const j = tpl.jigsaws[ji];
                if (j.name !== srcJg.target) continue;
                if (rotatedOrientation(j.orientation, r) !== targetOpp) continue;
                cands.push({ r, ji });
            }
        }
        if (!cands.length) {
            pushDeadEnd(srcPl, srcJg, '无匹配目标', cellGhost);
            continue;
        }
        // selection_priority 高者优先，同分随机
        cands.sort((a, b) => (tpl.jigsaws[b.ji].selection_priority || 0) - (tpl.jigsaws[a.ji].selection_priority || 0));
        const bestPrio = tpl.jigsaws[cands[0].ji].selection_priority || 0;
        const tied = cands.filter((c) => (tpl.jigsaws[c.ji].selection_priority || 0) === bestPrio);
        const pick = tied[Math.floor(rng() * tied.length)];
        const J = tpl.jigsaws[pick.ji];

        // 放置：J（旋转后）落在 源块世界坐标 + 生长方向
        const [w, h, d] = tpl.size;
        const D = orientationDir(srcJg.orientation);
        const jPos = rotatedPos(J.x, J.z, w, d, pick.r);
        const srcWorld = [
            srcPl.offset[0] + srcJg.x,
            srcPl.offset[1] + srcJg.y,
            srcPl.offset[2] + srcJg.z,
        ];
        const targetWorld = [srcWorld[0] + D[0], srcWorld[1] + D[1], srcWorld[2] + D[2]];
        const newOffset = [targetWorld[0] - jPos[0], targetWorld[1] - J.y, targetWorld[2] - jPos[1]];

        const idx = addPlacement(tpl.name, newOffset, pick.r, {
            fromPiece: srcPl.pieceName,
            jigsawName: srcJg.name,
            at: srcWorld,
        });
        if (idx === null) {
            // 重叠失败：幽灵盒 = 尝试放置的完整包围盒（可看到"撞"在哪）
            pushDeadEnd(srcPl, srcJg, '位置重叠', {
                x: newOffset[0], y: newOffset[1], z: newOffset[2],
                w: tpl.size[0], h: tpl.size[1], d: tpl.size[2],
            });
            continue;
        }
        // 新片段其余 jigsaw 入队（对接块 pick.ji 被消耗）
        for (let ji = 0; ji < (tpl.jigsaws || []).length; ji++) {
            if (ji === pick.ji) continue;
            queue.push({ pi: idx, ji });
        }
    }

    return { placements, deadEnds };
}

// ===== P16：jigsaw 虚影拼合（1:1 单次附着，不做生长模拟）=====

/**
 * 确定性伪随机（mulberry32）：同种子 → 同序列（池逻辑模式可复现；测试注入）
 * @param {number} seed 任意整数
 * @returns {Function} () => [0,1)
 */
function mulberry32(seed) {
    let a = seed | 0;
    return function () {
        a = (a + 0x6D2B79F5) | 0;
        let t = Math.imul(a ^ (a >>> 15), 1 | a);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

/**
 * P16：枚举源片段某 jigsaw 块的全部 1:1 候选附着（确定性，无 rng）。
 * 对池内每个模板 × 每个旋转 r(0..3) × 每个匹配目标块（name==target 且
 * rotatedOrientation==opposite(源朝向)），按对齐公式算放置偏移：
 *   目标块 J′（旋转后）恰好落在 源块世界坐标 + 生长方向 上。
 * @param {Object} bp 蓝图（pieces + templatePools）
 * @param {string} srcPieceName 源片段名
 * @param {number} srcJi 源 jigsaw 索引（片段 jigsaws 数组下标）
 * @param {number[]} srcOffset 源片段当前放置的世界偏移 [x,y,z]
 * @returns {{cands:Array, error?:string}}
 *   cands[i] = {jigsawName, pool, template, weight, rotation, targetJi, targetName,
 *               priority, offset:[x,y,z], via:[x,y,z]}
 *   顺序 = 池元素序 → 旋转 0..3 → 目标块下标（确定性）
 */
function jigsawCandidates(bp, srcPieceName, srcJi, srcOffset) {
    const byName = new Map();
    for (const p of bp.pieces || []) byName.set(p.name, p);
    const poolByName = new Map();
    for (const pl of bp.templatePools || []) poolByName.set(pl.name, pl);
    const src = byName.get(srcPieceName);
    if (!src) return { cands: [], error: '源片段不存在: ' + srcPieceName };
    const jg = (src.jigsaws || [])[srcJi];
    if (!jg) return { cands: [], error: 'jigsaw 块不存在: ' + srcPieceName + '[' + srcJi + ']' };
    const pool = poolByName.get(jg.pool);
    if (!pool) return { cands: [], error: '池不存在: ' + jg.pool };
    const targetOpp = oppositeOrientation(jg.orientation);
    const D = orientationDir(jg.orientation);
    const off0 = srcOffset || [0, 0, 0];
    const srcWorld = [off0[0] + jg.x, off0[1] + jg.y, off0[2] + jg.z];
    const cands = [];
    for (const el of pool.elements || []) {
        const tpl = byName.get(el.template);
        if (!tpl) continue;
        const [w, , d] = tpl.size;
        for (let r = 0; r < 4; r++) {
            const tJis = tpl.jigsaws || [];
            for (let ji = 0; ji < tJis.length; ji++) {
                const j = tJis[ji];
                if (j.name !== jg.target) continue;
                if (rotatedOrientation(j.orientation, r) !== targetOpp) continue;
                const jPos = rotatedPos(j.x, j.z, w, d, r);
                cands.push({
                    jigsawName: jg.name,
                    pool: jg.pool,
                    template: tpl.name,
                    weight: Math.max(1, el.weight || 1),
                    rotation: r,
                    targetJi: ji,
                    targetName: j.name,
                    priority: j.selection_priority || 0,
                    offset: [
                        srcWorld[0] + D[0] - jPos[0],
                        srcWorld[1] + D[1] - j.y,
                        srcWorld[2] + D[2] - jPos[1],
                    ],
                    via: srcWorld,
                });
            }
        }
    }
    return { cands };
}

/**
 * P16：池逻辑 1:1 附着（模拟原版单次抽取，不做生长）：
 *   加权抽模板 → rollable 随机单旋转 / aligned 全部旋转 → selection_priority 高者优先（同分随机）
 * @param {Function} rng 可注入（mulberry32(seed) 可复现）
 * @returns 单个候选（同 jigsawCandidates.cands[i]）或 null（无池/无匹配/模板缺失）
 */
function poolPick1to1(bp, srcPieceName, srcJi, srcOffset, rng) {
    const rnd = rng || Math.random;
    const { cands, error } = jigsawCandidates(bp, srcPieceName, srcJi, srcOffset);
    if (error || !cands.length) return null;
    const byName = new Map();
    for (const p of bp.pieces || []) byName.set(p.name, p);
    const jg = (byName.get(srcPieceName).jigsaws || [])[srcJi];
    const pool = (bp.templatePools || []).find((p) => p.name === jg.pool);
    // 加权抽模板（池内同模板多元素 → 合并权重）
    const tplEls = [];
    for (const el of pool.elements || []) {
        const hit = tplEls.find((e) => e.template === el.template);
        if (hit) hit.weight = (hit.weight || 1) + (el.weight || 1);
        else tplEls.push({ template: el.template, weight: el.weight || 1 });
    }
    const el = pickWeighted(tplEls, rnd);
    if (!el) return null;
    let pool2 = cands.filter((c) => c.template === el.template);
    if (!pool2.length) return null;
    // 旋转：rollable = 随机取一个可用旋转；aligned = 全部（已在 cands 中）
    if (jg.joint !== 'aligned') {
        const rs = [];
        for (const c of pool2) if (!rs.includes(c.rotation)) rs.push(c.rotation);
        const r = rs[Math.floor(rnd() * rs.length)];
        pool2 = pool2.filter((c) => c.rotation === r);
    }
    // selection_priority 高者优先，同分随机
    pool2.sort((a, b) => b.priority - a.priority);
    const best = pool2[0].priority;
    const tied = pool2.filter((c) => c.priority === best);
    return tied[Math.floor(rnd() * tied.length)];
}

const API = {
    growStructure,
    oppositeOrientation,
    rotatedOrientation,
    rotatedPos,
    orientationDir,
    pickWeighted,
    mulberry32, // P16
    jigsawCandidates, // P16
    poolPick1to1, // P16
    FACE_DIR,
};

if (typeof module === 'object' && module.exports) module.exports = API;
if (typeof self !== 'undefined') self.StructureGrowth = API;
