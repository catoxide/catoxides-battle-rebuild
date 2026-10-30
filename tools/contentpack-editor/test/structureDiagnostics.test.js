// structureDiagnostics.test.js - 拼合诊断测试（P5）
// 运行: node test/structureDiagnostics.test.js
'use strict';

const D = require('../src/core/structureDiagnostics');
const G = require('../src/core/structureGrowth');
const { sampleBlueprint } = require('./fixtures/sampleStructure');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
    if (cond) { pass++; console.log('  ✓ ' + name); }
    else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}

const sampleBp = sampleBlueprint();

console.log('=== 1. mulberry32 确定性 ===');
{
    const a = D.mulberry32(42), b = D.mulberry32(42), c = D.mulberry32(43);
    const sa = [a(), a(), a()], sb = [b(), b(), b()], sc = [c(), c(), c()];
    ok('同种子同序列', JSON.stringify(sa) === JSON.stringify(sb), JSON.stringify([sa, sb]));
    ok('异种子不同序列', JSON.stringify(sa) !== JSON.stringify(sc));
    ok('值域 [0,1)', sa.every((v) => v >= 0 && v < 1), JSON.stringify(sa));
}

console.log('=== 2. 体素重叠检测 ===');
{
    // 两个 2x2 板，完全重合
    const pieces = [
        { name: 'a', size: [2, 1, 2], boxFills: [{ from: [0, 0, 0], to: [1, 0, 1], block: 'minecraft:stone' }], fillBlocks: [], jigsaws: [] },
    ];
    const placements = [
        { pieceName: 'a', offset: [0, 0, 0], rotation: 0 },
        { pieceName: 'a', offset: [0, 0, 0], rotation: 0 },
    ];
    const ov = D.detectVoxelOverlaps(placements, pieces);
    ok('完全重合 = 4 体素', ov.total === 4 && ov.pairs.length === 1, JSON.stringify(ov));
    // 平移错开 → 无重叠
    const ov2 = D.detectVoxelOverlaps([
        { pieceName: 'a', offset: [0, 0, 0], rotation: 0 },
        { pieceName: 'a', offset: [2, 0, 0], rotation: 0 },
    ], pieces);
    ok('错开 = 0 重叠', ov2.total === 0, JSON.stringify(ov2));
    // 旋转后仍检测（r1 把 2x2 板转到 z 轴方向）
    const ov3 = D.detectVoxelOverlaps([
        { pieceName: 'a', offset: [0, 0, 0], rotation: 0 },
        { pieceName: 'a', offset: [0, 0, 0], rotation: 1 },
    ], pieces);
    ok('旋转 180°(r1 对 2x2 对称) 重合仍 = 4', ov3.total === 4, JSON.stringify(ov3.pairs));
    // 样例结构：生长出的 8 片段应无体素重叠
    const g = G.growStructure(sampleBp, { rng: () => 0.5, maxPieces: 8 });
    const ovS = D.detectVoxelOverlaps(g.placements, sampleBp.pieces);
    ok('示例结构 8 片段 0 体素重叠', ovS.total === 0, JSON.stringify(ovS));
}

console.log('=== 3. 死端幽灵盒 ===');
{
    // 死端蓝图：起点池引用不存在片段 → 起点错误（无幽灵）
    const badBp = JSON.parse(JSON.stringify(sampleBp));
    badBp.structure.startPool = 'nope';
    const gBad = G.growStructure(badBp, { rng: () => 0.5 });
    ok('startPool 缺失 → error', !!gBad.error);
    // 死端蓝图：大厅 top jigsaw 指向空池 → 配置死端带幽灵
    const deBp = JSON.parse(JSON.stringify(sampleBp));
    deBp.pieces[0].jigsaws[0].pool = 'empty_pool'; // main_hall 的 top
    const gDe = G.growStructure(deBp, { rng: () => 0.5, maxPieces: 8 });
    const de = gDe.deadEnds.find((d) => d.reason.startsWith('池不存在'));
    ok('配置死端有记录', !!de, JSON.stringify(gDe.deadEnds));
    ok('配置死端带幽灵盒', !!de && !!de.ghost && de.ghost.w === 1 && de.ghost.h === 1, JSON.stringify(de));
    ok('幽灵位置 = 源块+方向（大厅 top 上方）', de && de.ghost.x === 5 && de.ghost.y === 8 && de.ghost.z === 5, JSON.stringify(de && de.ghost));
    // 重叠死端：强制两段完全重合 → 位置重叠 + 完整包围盒幽灵
    const ovBp = JSON.parse(JSON.stringify(sampleBp));
    // top 移到 (2,5,2)：塔落位 y=6 → 与大厅(y≤7) 在 y=6..7 两层相交 → 重叠失败
    ovBp.pieces[0].jigsaws[0] = Object.assign({}, ovBp.pieces[0].jigsaws[0], { x: 2, y: 5, z: 2 });
    const gOv = G.growStructure(ovBp, { rng: () => 0.5, maxPieces: 8 });
    const ovDe = gOv.deadEnds.find((d) => d.reason === '位置重叠');
    ok('重叠死端有记录', !!ovDe, JSON.stringify(gOv.deadEnds));
    ok('重叠幽灵 = 塔包围盒 6x12x6', ovDe && ovDe.ghost.w === 6 && ovDe.ghost.h === 12 && ovDe.ghost.d === 6, JSON.stringify(ovDe));
}

console.log('=== 4. checkGeneratability（示例蓝图 N=50）===');
{
    const r = D.checkGeneratability(sampleBp, { runs: 50, maxPieces: 24 });
    ok('50 次全成功', r.successRuns === 50, JSON.stringify(r.errors));
    ok('片段数在 1..24', r.pieces.min >= 1 && r.pieces.max <= 24, JSON.stringify(r.pieces));
    ok('死端统计结构', typeof r.deadEnds.total === 'number' && r.deadEnds.avgPerRun >= 0);
    ok('重叠统计结构', typeof r.overlaps.runsWithOverlaps === 'number');
    ok('占地 > 0', r.footprint.maxW > 0 && r.footprint.maxH > 0 && r.footprint.maxD > 0, JSON.stringify(r.footprint));
    ok('确定性：两次调用结果一致', JSON.stringify(D.checkGeneratability(sampleBp, { runs: 50, maxPieces: 24 })) === JSON.stringify(r));
    console.log('    片段 ' + r.pieces.min + '–' + r.pieces.max + ' (avg ' + r.pieces.avg + '), 死端 ' + r.deadEnds.total + ', 重叠 runs ' + r.overlaps.runsWithOverlaps + ', 占地 ' + r.footprint.maxW + 'x' + r.footprint.maxH + 'x' + r.footprint.maxD);
}

console.log('=== 5. checkGeneratability（坏蓝图 → 错误上报）===');
{
    const badBp = JSON.parse(JSON.stringify(sampleBp));
    badBp.structure.startPool = 'nope';
    const r = D.checkGeneratability(badBp, { runs: 5 });
    ok('5 次全失败', r.successRuns === 0 && r.errors.length === 5, JSON.stringify(r.errors));
}

console.log('=== 6. checkJigsawContracts（静态契约检查）===');
{
    const r = D.checkJigsawContracts(sampleBp);
    ok('示例蓝图 6 个 jigsaw 全部 OK', r.length === 6 && r.every((x) => x.ok), JSON.stringify(r));
    // 池不存在
    const b1 = JSON.parse(JSON.stringify(sampleBp));
    b1.pieces[0].jigsaws[0].pool = 'ghost_pool';
    const r1 = D.checkJigsawContracts(b1);
    const e1 = r1.find((x) => x.jigsaw === 'top');
    ok('池不存在 → error', e1 && e1.level === 'error' && /池不存在/.test(e1.msg), JSON.stringify(e1));
    // target 不存在
    const b2 = JSON.parse(JSON.stringify(sampleBp));
    b2.pieces[0].jigsaws[0].target = 'nope_target';
    const r2 = D.checkJigsawContracts(b2);
    const e2 = r2.find((x) => x.jigsaw === 'top');
    ok('target 不存在 → warn', e2 && e2.level === 'warn' && /target/.test(e2.msg), JSON.stringify(e2));
    // 朝向不可对接（把塔 bottom 改成 east_up，与大厅 top up_north 无对面）
    const b3 = JSON.parse(JSON.stringify(sampleBp));
    b3.pieces[1].jigsaws[0].orientation = 'east_up';
    const r3 = D.checkJigsawContracts(b3);
    const e3 = r3.find((x) => x.piece === 'main_hall' && x.jigsaw === 'top');
    ok('朝向不可对接 → warn', e3 && e3.level === 'warn' && /朝向/.test(e3.msg), JSON.stringify(e3));
}

console.log('\n拼合诊断测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
