// voxel.test.js - 体素展开/表面/旋转/切片测试
// 运行: node test/voxel.test.js
'use strict';

const V = require('../src/core/voxel');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
    if (cond) { pass++; console.log('  ✓ ' + name); }
    else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}

console.log('=== 1. 展开 ===');
{
    const p = {
        name: 't', size: [4, 2, 4],
        boxFills: [{ from: [0, 0, 0], to: [3, 0, 3], block: 'minecraft:stone' }],
        fillBlocks: [{ x: 1, y: 1, z: 1, block: 'minecraft:diamond_block' }],
    };
    const m = V.expandPiece(p);
    ok('地板 16 + 填充 1 = 17 体素', m.size === 17, 'got ' + m.size);
    ok('填充覆盖正确', m.get('1,1,1') === 'minecraft:diamond_block');
    ok('地板块正确', m.get('0,0,0') === 'minecraft:stone');
    // air 跳过
    const p2 = { name: 't', size: [2, 1, 2], boxFills: [{ from: [0, 0, 0], to: [1, 0, 1], block: 'minecraft:air' }], fillBlocks: [] };
    ok('air 不入表', V.expandPiece(p2).size === 0);
    // 越界裁剪
    const p3 = { name: 't', size: [2, 1, 2], boxFills: [{ from: [0, 0, 0], to: [9, 0, 9], block: 'minecraft:stone' }], fillBlocks: [] };
    ok('越界裁剪到尺寸内', V.expandPiece(p3).size === 4, 'got ' + V.expandPiece(p3).size);
}

console.log('=== 2. 表面 ===');
{
    // 3x1x3 实心底板：每块顶/底邻居都缺失 → 9 块全暴露
    const p = { name: 't', size: [3, 1, 3], boxFills: [{ from: [0, 0, 0], to: [2, 0, 2], block: 'minecraft:stone' }], fillBlocks: [] };
    const s = V.surfaceOf(V.expandPiece(p));
    ok('3x1x3 板 9 块全暴露', s.length === 9, 'got ' + s.length);
    // 2x2x2 实心立方体：无内部 → 8 全表面
    const p2 = { name: 't', size: [2, 2, 2], boxFills: [{ from: [0, 0, 0], to: [1, 1, 1], block: 'minecraft:stone' }], fillBlocks: [] };
    ok('2x2x2 全表面 = 8', V.surfaceOf(V.expandPiece(p2)).length === 8);
    // 3x3x3 实心立方体：内部 1 个被隐藏
    const p3 = { name: 't', size: [3, 3, 3], boxFills: [{ from: [0, 0, 0], to: [2, 2, 2], block: 'minecraft:stone' }], fillBlocks: [] };
    ok('3x3x3 表面 = 26（内部 1 隐藏）', V.surfaceOf(V.expandPiece(p3)).length === 26, 'got ' + V.surfaceOf(V.expandPiece(p3)).length);
}

console.log('=== 3. 旋转 + 世界坐标 ===');
{
    // 1x1x2 条：fillBlock 在 (0,0,0)。绕 r=1 后 x,z → (d-1-z, x) = (1, 0)
    const p = { name: 't', size: [1, 1, 2], boxFills: [], fillBlocks: [{ x: 0, y: 0, z: 0, block: 'minecraft:stone' }] };
    const m = V.expandPiece(p);
    const ws0 = V.worldSurface(m, [10, 5, 20], 0, p.size);
    ok('r0 世界坐标 = 偏移+局部', JSON.stringify(ws0[0].slice(0, 3)) === JSON.stringify([10, 5, 20]), JSON.stringify(ws0));
    const ws1 = V.worldSurface(m, [10, 5, 20], 1, p.size);
    ok('r1 旋转后 x,z = (d-1-z, x)', JSON.stringify(ws1[0].slice(0, 3)) === JSON.stringify([10 + 1, 5, 20 + 0]), JSON.stringify(ws1));
}

console.log('=== 4. 切片 ===');
{
    const world = [[0, 0, 0, 'a'], [2, 0, 0, 'a'], [4, 0, 0, 'a'], [1, 5, 1, 'b']];
    ok('clip x>2 隐藏 x=4 一个', V.applyClip(world, { axis: 'x', dir: 1, threshold: 2 }).length === 3);
    ok('clip x<2 隐藏 2 个', V.applyClip(world, { axis: 'x', dir: -1, threshold: 2 }).length === 2);
    ok('clip y>4 只隐藏 b', V.applyClip(world, { axis: 'y', dir: 1, threshold: 4 }).length === 3);
    ok('无 clip 原样', V.applyClip(world, null).length === 4);
}

console.log('=== 5. 写回 ===');
{
    const p = { name: 't', size: [2, 1, 1], boxFills: [{ from: [0, 0, 0], to: [1, 0, 0], block: 'minecraft:stone' }], fillBlocks: [] };
    const fb = V.toFillBlocks(V.expandPiece(p));
    ok('toFillBlocks 数量', fb.length === 2);
    ok('toFillBlocks 字段', fb[0].x === 0 && fb[0].y === 0 && fb[0].z === 0 && fb[0].block === 'minecraft:stone');
}

console.log('\n体素测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
