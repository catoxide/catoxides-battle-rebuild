// structureGrowth.test.js - jigsaw 生长模拟测试
// 运行: node test/structureGrowth.test.js
'use strict';

const fs = require('fs');
const path = require('path');
const G = require('../src/core/structureGrowth');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
    if (cond) { pass++; console.log('  ✓ ' + name); }
    else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}
const near = (a, b) => Math.abs(a - b) < 1e-9;

const SAMPLE = path.join(__dirname, '..', '..', '..', 'contentpacks-source', 'structure-example-pack', 'structures', 'example_structure', 'blueprint.json');
const sampleBp = JSON.parse(fs.readFileSync(SAMPLE, 'utf-8'));

// 固定 rng（单元素池下 pickWeighted 恒取第一个；aligned 下不消耗随机数）
const rng50 = () => 0.5;

console.log('=== 1. 朝向/旋转数学 ===');
ok('opposite: up_north → down_south', G.oppositeOrientation('up_north') === 'down_south');
ok('opposite: east_up → west_up', G.oppositeOrientation('east_up') === 'west_up');
ok('opposite: down_south → up_north', G.oppositeOrientation('down_south') === 'up_north');
ok('opposite: west_up → east_up', G.oppositeOrientation('west_up') === 'east_up');
ok('顶面旋转: up_east r1 → up_south', G.rotatedOrientation('up_east', 1) === 'up_south');
ok('顶面旋转: up_north r1 → up_east', G.rotatedOrientation('up_north', 1) === 'up_east');
ok('底面旋转: down_south r1 → down_west', G.rotatedOrientation('down_south', 1) === 'down_west');
ok('水平面旋转: east_up r1 → south_up', G.rotatedOrientation('east_up', 1) === 'south_up');
ok('水平面旋转: west_up r2 → east_up', G.rotatedOrientation('west_up', 2) === 'east_up');
ok('水平面旋转: north_up r3 → west_up', G.rotatedOrientation('north_up', 3) === 'west_up');
ok('水平面 r4 ≡ r0: west_up', G.rotatedOrientation('west_up', 4) === 'west_up');
ok('rotPos r1: (0,0) 10x8 → (7,0)', JSON.stringify(G.rotatedPos(0, 0, 10, 8, 1)) === JSON.stringify([7, 0]));
ok('rotPos r2: (2,3) 10x8 → (7,4)', JSON.stringify(G.rotatedPos(2, 3, 10, 8, 2)) === JSON.stringify([7, 4]));
ok('rotPos r3: (2,3) 10x8 → (3,7)', JSON.stringify(G.rotatedPos(2, 3, 10, 8, 3)) === JSON.stringify([3, 7]));
ok('生长方向: east_up → +X', JSON.stringify(G.orientationDir('east_up')) === JSON.stringify([1, 0, 0]));
ok('生长方向: up_north → +Y', JSON.stringify(G.orientationDir('up_north')) === JSON.stringify([0, 1, 0]));
ok('生长方向: north_up → -Z', JSON.stringify(G.orientationDir('north_up')) === JSON.stringify([0, 0, -1]));

console.log('=== 2. 示例结构端到端（大厅+塔+双向走廊链）===');
{
    const g = G.growStructure(sampleBp, { rng: rng50, maxPieces: 8 });
    ok('无错误', !g.error, g.error);
    ok('8 个片段放置', g.placements.length === 8, JSON.stringify(g.placements.map((p) => p.pieceName)));
    ok('起点 = main_hall 在原点', g.placements[0].pieceName === 'main_hall' && JSON.stringify(g.placements[0].offset) === JSON.stringify([0, 0, 0]));
    ok('塔在大厅顶 [2,8,2] 无旋转',
        g.placements[1].pieceName === 'tower' && JSON.stringify(g.placements[1].offset) === JSON.stringify([2, 8, 2]) && g.placements[1].rotation === 0,
        JSON.stringify(g.placements[1]));
    const corr = g.placements.filter((p) => p.pieceName === 'corridor');
    ok('6 条走廊', corr.length === 6);
    ok('东链 x=10,18,26', JSON.stringify(corr.filter((c) => c.offset[0] > 0).map((c) => c.offset[0]).sort((a, b) => a - b)) === JSON.stringify([10, 18, 26]));
    ok('西链 x=-8,-16,-24', JSON.stringify(corr.filter((c) => c.offset[0] < 0).map((c) => c.offset[0]).sort((a, b) => a - b)) === JSON.stringify([-24, -16, -8]));
    ok('所有走廊 z=4（对准大厅墙洞）', corr.every((c) => c.offset[2] === 4));
    // 无重叠
    let overlap = false;
    const byName = new Map(sampleBp.pieces.map((p) => [p.name, p]));
    for (let i = 0; i < g.placements.length; i++) for (let j = i + 1; j < g.placements.length; j++) {
        const a = g.placements[i], b = g.placements[j];
        const sa = byName.get(a.pieceName).size, sb = byName.get(b.pieceName).size;
        if (a.offset[0] < b.offset[0] + sb[0] && b.offset[0] < a.offset[0] + sa[0] &&
            a.offset[1] < b.offset[1] + sb[1] && b.offset[1] < a.offset[1] + sa[1] &&
            a.offset[2] < b.offset[2] + sb[2] && b.offset[2] < a.offset[2] + sa[2]) overlap = true;
    }
    ok('放置之间无包围盒重叠', !overlap);
    ok('塔对接自大厅 top', g.placements[1].via && g.placements[1].via.jigsawName === 'top');
    // 确定性：同样 rng 两次结果一致
    const g2 = G.growStructure(sampleBp, { rng: rng50, maxPieces: 8 });
    ok('确定性（同 rng 同结果）', JSON.stringify(g.placements) === JSON.stringify(g2.placements));
}

console.log('=== 3. maxPieces 限制 ===');
{
    const g = G.growStructure(sampleBp, { rng: rng50, maxPieces: 3 });
    ok('maxPieces=3 → 大厅+塔+1 走廊', g.placements.length === 3, JSON.stringify(g.placements.map((p) => p.pieceName)));
}

console.log('=== 4. 死端（无匹配目标）===');
{
    const bp = {
        structureId: 't', namespace: 't',
        structure: { step: 'surface_structures', terrainAdaptation: 'none', startPool: 's', maxDistanceFromCenter: 8 },
        pieces: [
            { name: 'a', size: [4, 2, 4], boxFills: [], fillBlocks: [], jigsaws: [
                { x: 3, y: 1, z: 2, name: 'out', pool: 'p', target: 'ghost', joint: 'aligned', orientation: 'east_up', finalState: 'minecraft:stone' },
            ] },
            { name: 'b', size: [4, 2, 4], boxFills: [], fillBlocks: [], jigsaws: [] },
        ],
        templatePools: [
            { name: 's', elements: [{ template: 'a', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty_pool_element' },
            { name: 'p', elements: [{ template: 'b', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty_pool_element' },
        ],
    };
    const g = G.growStructure(bp, { rng: rng50, maxPieces: 8 });
    ok('只放置起点', g.placements.length === 1);
    ok('死端被记录', g.deadEnds.length === 1 && /无匹配目标/.test(g.deadEnds[0].reason), JSON.stringify(g.deadEnds));
}

console.log('=== 5. 重叠拒绝 ===');
{
    const bp = {
        structureId: 't', namespace: 't',
        structure: { step: 'surface_structures', terrainAdaptation: 'none', startPool: 's', maxDistanceFromCenter: 8 },
        pieces: [
            { name: 'a', size: [4, 3, 4], boxFills: [], fillBlocks: [], jigsaws: [
                { x: 3, y: 1, z: 2, name: 'o1', pool: 'p', target: 'in', joint: 'aligned', orientation: 'east_up', finalState: 'minecraft:stone' },
                { x: 3, y: 2, z: 2, name: 'o2', pool: 'p', target: 'in', joint: 'aligned', orientation: 'east_up', finalState: 'minecraft:stone' },
            ] },
            { name: 'b', size: [4, 2, 4], boxFills: [], fillBlocks: [], jigsaws: [
                { x: 0, y: 1, z: 2, name: 'in', pool: 'p', target: 'in', joint: 'aligned', orientation: 'west_up', finalState: 'minecraft:stone' },
            ] },
        ],
        templatePools: [
            { name: 's', elements: [{ template: 'a', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty_pool_element' },
            { name: 'p', elements: [{ template: 'b', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty_pool_element' },
        ],
    };
    const g = G.growStructure(bp, { rng: rng50, maxPieces: 8 });
    ok('第二个重叠放置被拒绝', g.placements.length === 2 && g.deadEnds.length === 1 && /位置重叠/.test(g.deadEnds[0].reason),
        JSON.stringify({ n: g.placements.length, d: g.deadEnds }));
}

console.log('=== 6. 权重池（固定 rng 序列）===');
{
    // 序列: 0.2 → 第一个元素（权重 1/3 区间）；0.8 → 最后一个
    let seq = [0.2, 0.8];
    const rngSeq = () => seq.length ? seq.shift() : 0.5;
    const bp = {
        structureId: 't', namespace: 't',
        structure: { step: 'surface_structures', terrainAdaptation: 'none', startPool: 's', maxDistanceFromCenter: 8 },
        pieces: [
            { name: 'a', size: [4, 2, 4], boxFills: [], fillBlocks: [], jigsaws: [
                { x: 3, y: 1, z: 2, name: 'out', pool: 'p', target: 'in', joint: 'aligned', orientation: 'east_up', finalState: 'minecraft:stone' },
            ] },
            { name: 'b', size: [4, 2, 4], boxFills: [], fillBlocks: [], jigsaws: [
                { x: 0, y: 1, z: 2, name: 'in', pool: 'p', target: 'in', joint: 'aligned', orientation: 'west_up', finalState: 'minecraft:stone' },
            ] },
            { name: 'c', size: [4, 2, 4], boxFills: [], fillBlocks: [], jigsaws: [
                { x: 0, y: 1, z: 2, name: 'in', pool: 'p', target: 'in', joint: 'aligned', orientation: 'west_up', finalState: 'minecraft:stone' },
            ] },
        ],
        templatePools: [
            { name: 's', elements: [{ template: 'a', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty_pool_element' },
            { name: 'p', elements: [
                { template: 'b', weight: 1, projection: 'rigid' },
                { template: 'b', weight: 1, projection: 'rigid' },
                { template: 'c', weight: 1, projection: 'rigid' },
            ], fallback: 'minecraft:empty_pool_element' },
        ],
    };
    const g = G.growStructure(bp, { rng: rngSeq, maxPieces: 8 });
    // start(0.2→a) → a.out(0.8→c)；c.in 是对接块（被消耗，不再生长）
    ok('起点 a', g.placements[0].pieceName === 'a');
    ok('第一个子片段按权重抽中 c', g.placements.length === 2 && g.placements[1].pieceName === 'c', JSON.stringify(g.placements.map((p) => p.pieceName)));
}

console.log('=== 7. 错误路径 ===');
{
    const noStart = { structureId: 't', namespace: 't', structure: { startPool: 'nope' }, pieces: [], templatePools: [] };
    ok('startPool 缺失 → error', G.growStructure(noStart, { rng: rng50 }).error !== undefined);
    const emptyStart = {
        structureId: 't', namespace: 't',
        structure: { startPool: 's' },
        pieces: [], templatePools: [{ name: 's', elements: [], fallback: 'minecraft:empty_pool_element' }],
    };
    ok('startPool 空 → error', G.growStructure(emptyStart, { rng: rng50 }).error !== undefined);
}

console.log('=== 8. P16 jigsaw 虚影 1:1 候选 ===');
{
    // src 'a' 2x8x2，顶部 jigsaw 'out'（up_north）+ 侧墙 'east_out'（east_up）
    // tpl 'b' 3x3x3，'in'@(1,0,1) down_south（接顶面）+ 'side_in'@(0,1,1) west_up（接东墙）
    // tpl 'c' 3x3x3，'side_in'@(0,1,1) west_up（同池竞争，权重低）
    const bp16 = {
        structureId: 't16', namespace: 't', structure: { startPool: 's' },
        pieces: [
            { name: 'a', size: [2, 8, 2], boxFills: [], fillBlocks: [], jigsaws: [
                { name: 'top_out', x: 1, y: 7, z: 1, orientation: 'up_north', pool: 'p', target: 'in', joint: 'aligned' },
                { name: 'east_out', x: 1, y: 1, z: 1, orientation: 'east_up', pool: 'p', target: 'side_in', joint: 'rollable' },
            ] },
            { name: 'b', size: [3, 3, 3], boxFills: [], fillBlocks: [], jigsaws: [
                { name: 'in', x: 1, y: 0, z: 1, orientation: 'down_south', target: 'x', selection_priority: 0 },
                { name: 'side_in', x: 0, y: 1, z: 1, orientation: 'west_up', target: 'x', selection_priority: 5 },
                { name: 'side_in', x: 1, y: 1, z: 1, orientation: 'west_up', target: 'x', selection_priority: 1 },
            ] },
            { name: 'c', size: [3, 3, 3], boxFills: [], fillBlocks: [], jigsaws: [
                { name: 'side_in', x: 0, y: 1, z: 1, orientation: 'west_up', target: 'x', selection_priority: 0 },
            ] },
        ],
        templatePools: [
            { name: 's', elements: [{ template: 'a', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty_pool_element' },
            { name: 'p', elements: [
                { template: 'b', weight: 9, projection: 'rigid' },
                { template: 'c', weight: 1, projection: 'rigid' },
            ], fallback: 'minecraft:empty_pool_element' },
        ],
    };

    // 8a. 顶面 1:1：src a 在 [5,3,7]，top_out@(1,7,1) up_north → 生长 +Y
    const top = G.jigsawCandidates(bp16, 'a', 0, [5, 3, 7]);
    ok('顶面无错误', !top.error, top.error);
    ok('顶面候选=1（b 的 in，r0）', top.cands.length === 1, JSON.stringify(top.cands));
    if (top.cands.length === 1) {
        const c = top.cands[0];
        ok('顶面模板=b rot=0', c.template === 'b' && c.rotation === 0);
        // a 世界顶格 (1+5, 7+3, 1+7)=(6,10,8) +D(0,1,0) → (6,11,8) = b.in 世界坐标
        // b.in 局部 (1,0,1) → offset = (6-1, 11-0, 8-1) = (5,11,7)
        ok('顶面对齐 offset=[5,11,7]（in 恰落在源块+生长方向）', JSON.stringify(c.offset) === JSON.stringify([5, 11, 7]), JSON.stringify(c.offset));
        ok('顶面 via=源块世界坐标', JSON.stringify(c.via) === JSON.stringify([6, 10, 8]), JSON.stringify(c.via));
    }

    // 8b. 东墙 1:1：east_out@(1,1,1) east_up → 生长 +X；b.side_in 与 b.side_in2、c.side_in 同向
    const east = G.jigsawCandidates(bp16, 'a', 1, [0, 0, 0]);
    ok('东墙无错误', !east.error, east.error);
    // b: side_in@(0,1,1) r0 jPos=[0,1] offset=[2,0,0]；side_in@(1,1,1) jPos=[1,1] offset=[1,0,0]；c: [2,0,0]
    ok('东墙候选=3（b×2 + c×1，均 r0）', east.cands.length === 3, JSON.stringify(east.cands.map((c) => c.template)));
    ok('东墙 offset=[2,1,2]（x=源块+1 贴东面，z 不变）',
        JSON.stringify(east.cands.map((c) => c.offset)) === JSON.stringify([[2, 0, 0], [1, 0, 0], [2, 0, 0]]),
        JSON.stringify(east.cands.map((c) => c.offset)));

    // 8c. 错误路径
    ok('池不存在 → error', G.jigsawCandidates({ pieces: [{ name: 'a', size: [1, 1, 1], jigsaws: [{ name: 'j', x: 0, y: 0, z: 0, orientation: 'up', pool: 'nope', target: 't' }] }], templatePools: [] }, 'a', 0, [0, 0, 0]).error !== undefined);
    ok('源片段不存在 → error', G.jigsawCandidates(bp16, 'ghost', 0, [0, 0, 0]).error !== undefined);
    const missTpl = { pieces: bp16.pieces.filter((p) => p.name === 'a'), templatePools: [{ name: 'p', elements: [{ template: 'gone', weight: 1 }], fallback: 'minecraft:empty_pool_element' }] };
    ok('模板缺失 → 空候选', G.jigsawCandidates(missTpl, 'a', 0, [0, 0, 0]).cands.length === 0);

    // 8d. poolPick1to1 确定性（mulberry32 同种子同结果）
    const pickA = G.poolPick1to1(bp16, 'a', 0, [0, 0, 0], G.mulberry32(42));
    const pickB = G.poolPick1to1(bp16, 'a', 0, [0, 0, 0], G.mulberry32(42));
    ok('池逻辑：同种子同结果', JSON.stringify(pickA) === JSON.stringify(pickB) && pickA !== null, JSON.stringify(pickA));
    ok('池逻辑：顶面抽中 b（权重 9/10，rng0.01 段）', pickA.template === 'b', JSON.stringify(pickA));

    // 8e. 加权：rng→0.001 段恒抽 b（roll=0.01 < 9）
    let allB = true;
    for (let i = 0; i < 5; i++) {
        const p = G.poolPick1to1(bp16, 'a', 1, [0, 0, 0], () => 0.001);
        if (p.template !== 'b') allB = false;
    }
    ok('池逻辑：高权重模板恒中（east 侧）', allB);

    // 8f. rollable 旋转：rng 序列 [模板, 旋转, 同分] 全 0 → 取首个模板/首旋转/首同分
    const p0 = G.poolPick1to1(bp16, 'a', 1, [0, 0, 0], () => 0);
    ok('池逻辑：rollable r=0 且 priority=5 的 side_in 优先', p0.template === 'b' && p0.rotation === 0 && p0.targetName === 'side_in', JSON.stringify(p0));
    // priority 同分（构造 c 与 b 同优先级场景用 rng50 → tied[0]… 用单元素池验证稳定）
    const bpTie = JSON.parse(JSON.stringify(bp16));
    bpTie.templatePools[1].elements = [{ template: 'c', weight: 1, projection: 'rigid' }];
    const pTie = G.poolPick1to1(bpTie, 'a', 1, [0, 0, 0], () => 0);
    ok('池逻辑：单模板池 → c', pTie && pTie.template === 'c', JSON.stringify(pTie));

    // 8g. mulberry32 性质
    const r1 = G.mulberry32(7), r2 = G.mulberry32(7);
    let same = true;
    for (let i = 0; i < 10; i++) if (r1() !== r2()) same = false;
    ok('mulberry32 同种子同序列', same);
    const r3 = G.mulberry32(8);
    let inRange = true;
    for (let i = 0; i < 100; i++) { const v = r3(); if (v < 0 || v >= 1) inRange = false; }
    ok('mulberry32 值域 [0,1)', inRange);
}

console.log('=== 9. (REVIEW-M6) 旋转片段的碰撞检查按旋转后足迹（d×w）===');
{
    // 设计：start[4,2,2] →(east)→ mid[4,2,2] 以 r=1 落在 [4,0,1]（实际足迹 x4-5 × z1-4；
    // 旧代码按 w×d 认为是 x4-7 × z1-2）→(south)→ end[2,2,2] 落在 [4,0,3]：
    //   与 mid 的【旋转后】足迹真实重叠（z3-4 ∩ z1-4），但旧 w×d 检查判为不重叠 → 漏检。
    const jig = (x, z, name, pool, target, orientation) => ({
        x, y: 0, z, name, pool, target, joint: 'rollable', orientation,
        finalState: 'minecraft:air', selectionPriority: 10, placementPriority: 10,
    });
    const bp = {
        formatVersion: 1, structureId: 'rot_test', namespace: 't',
        structure: { step: 'surface_structures', terrainAdaptation: 'beard_thin', startPool: 'p0', maxDistanceFromCenter: 80, biomes: [], projectToSurface: true, maxSurfaceLevel: 0 },
        pieces: [
            { name: 'start', size: [4, 2, 2], boxFills: [], fillBlocks: [], jigsaws: [jig(3, 1, 'e', 'p1', 'w', 'east_up')] },
            { name: 'mid', size: [4, 2, 2], boxFills: [], fillBlocks: [], jigsaws: [jig(0, 1, 'w', 'p1', 'e', 'south_up'), jig(1, 1, 'e2', 'p2', 'w2', 'south_up')] },
            { name: 'end', size: [2, 2, 2], boxFills: [], fillBlocks: [], jigsaws: [jig(0, 1, 'w2', 'p2', 'e2', 'south_up')] },
        ],
        templatePools: [
            { name: 'p0', elements: [{ template: 'start', weight: 1, projection: 'rigid' }] },
            { name: 'p1', elements: [{ template: 'mid', weight: 1, projection: 'rigid' }] },
            { name: 'p2', elements: [{ template: 'end', weight: 1, projection: 'rigid' }] },
        ],
        structureSet: { spacing: 32, separation: 12, salt: 1, frequency: 1 },
    };
    // rng 消费顺序：[p0 抽模板, p1 抽模板, mid 旋转, mid 同分, p2 抽模板, end 旋转, end 同分]
    // （旧版漏计 p1 抽 → end 旋转错拿 0.3 → r=1 → south_up 转成 west_up 无匹配，假死端"无匹配目标"）
    // mid 旋转 0.3 → r=floor(0.3*4)=1；end 旋转 0.7 → r=floor(0.7*4)=2
    const seq = [0.3, 0.3, 0.3, 0.3, 0.3, 0.7, 0.3];
    let ri = 0;
    const rngSeq = () => (ri < seq.length ? seq[ri++] : 0.5);
    const g = G.growStructure(bp, { rng: rngSeq, maxPieces: 10 });
    ok('mid 以 r=1 落在 [4,0,1]', g.placements.length >= 2 && g.placements[1].pieceName === 'mid' && g.placements[1].rotation === 1 && JSON.stringify(g.placements[1].offset) === '[4,0,1]', JSON.stringify(g.placements.map((p) => [p.pieceName, p.offset, p.rotation])));
    ok('end 与旋转后足迹重叠 → 被拒（旧 w×d 检查会漏检放行）', g.placements.length === 2, 'placements=' + g.placements.length + ' deadEnds=' + JSON.stringify(g.deadEnds));
    ok('死端记录 = 位置重叠', g.deadEnds.some((d) => d.reason === '位置重叠'), JSON.stringify(g.deadEnds));
}

console.log('\n生长模拟测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
