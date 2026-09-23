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

console.log('\n生长模拟测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
