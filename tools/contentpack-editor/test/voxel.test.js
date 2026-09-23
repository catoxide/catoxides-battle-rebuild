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

console.log('=== 6. 剖切（P9 cutOf）===');
{
    // 5x1x1 长条 x=0..4，全部 stone；(2,0,0) 换成 dirt 标记切片层
    const p = {
        name: 't', size: [5, 1, 1],
        boxFills: [{ from: [0, 0, 0], to: [4, 0, 0], block: 'minecraft:stone' }],
        fillBlocks: [{ x: 2, y: 0, z: 0, block: 'minecraft:dirt' }],
    };
    const m = V.expandPiece(p);
    // 相机在 +x 侧：隐藏 x>2，保留 x<=2（含切片层 x=2）
    const cutPos = V.cutOf(m, { axis: 'x', index: 2 }, 1);
    ok('camSide+1 隐藏 x>2（剩 0,1,2）', cutPos.size === 3, 'got ' + cutPos.size);
    ok('切片层恒显示', cutPos.has('2,0,0'));
    // 相机在 -x 侧：隐藏 x<2，保留 x>=2（2,3,4）
    const cutNeg = V.cutOf(m, { axis: 'x', index: 2 }, -1);
    ok('camSide-1 隐藏 x<2（剩 2,3,4）', cutNeg.size === 3, 'got ' + cutNeg.size);
    ok('切片层在负侧也显示', cutNeg.has('2,0,0'));
    // null → 原表引用
    ok('null slice 返回原表', V.cutOf(m, null, 1) === m);
    // y 轴：2 层高
    const p2 = { name: 't', size: [1, 3, 1], boxFills: [{ from: [0, 0, 0], to: [0, 2, 0], block: 'minecraft:stone' }], fillBlocks: [] };
    const cutY = V.cutOf(V.expandPiece(p2), { axis: 'y', index: 1 }, -1);
    ok('y 轴切片保留 y>=1（2 层）', cutY.size === 2, 'got ' + cutY.size);
}

console.log('=== 7. 编辑写回（P9 mergePieceEdits）===');
{
    // 7a. 无差异 → 同引用
    const p = {
        name: 't', size: [2, 1, 1],
        boxFills: [{ from: [0, 0, 0], to: [1, 0, 0], block: 'minecraft:stone' }],
        fillBlocks: [],
        jigsaws: [],
    };
    ok('无差异返回同引用', V.mergePieceEdits(p, V.expandPiece(p)) === p);

    // 7b. 差异只落 fillBlocks 区域（新增块）→ boxFills 保留
    const m1 = V.expandPiece(p);
    m1.set('0,1,0', 'minecraft:planks');
    const r1 = V.mergePieceEdits(p, m1);
    ok('新增块 → boxFills 保留', r1 !== p && r1.boxFills.length === 1, JSON.stringify(r1.boxFills));
    ok('新增块进 fillBlocks', r1.fillBlocks.length === 1 && r1.fillBlocks[0].block === 'minecraft:planks' && r1.fillBlocks[0].y === 1);

    // 7c. 差异触及 boxFills（改色）→ 整片扁平化
    const m2 = V.expandPiece(p);
    m2.set('0,0,0', 'minecraft:dirt');
    const r2 = V.mergePieceEdits(p, m2);
    ok('触及 boxFills → 扁平化 boxFills=[]', r2 !== p && r2.boxFills.length === 0);
    ok('扁平化后 fillBlocks 完整（2 块，1 改色）', r2.fillBlocks.length === 2, JSON.stringify(r2.fillBlocks));
    const dirt = r2.fillBlocks.find((b) => b.x === 0 && b.y === 0);
    ok('改色生效', dirt && dirt.block === 'minecraft:dirt');

    // 7d. 删除 boxFills 内的块（air）→ 扁平化且少 1 块
    const m3 = V.expandPiece(p);
    m3.delete('1,0,0');
    const r3 = V.mergePieceEdits(p, m3);
    ok('删 boxFills 块 → 扁平化剩 1 块', r3.boxFills.length === 0 && r3.fillBlocks.length === 1, JSON.stringify(r3.fillBlocks));

    // 7e. jigsaw 位置保护
    const pJ = {
        name: 't', size: [2, 1, 1],
        boxFills: [{ from: [0, 0, 0], to: [1, 0, 0], block: 'minecraft:stone' }],
        fillBlocks: [],
        jigsaws: [{ x: 1, y: 0, z: 0, name: 'j', target: 't', pool: 'p', orientation: 'up' }],
    };
    // boxFill 覆盖 (1,0,0) 但 jigsaw 也在那 → 展开时 fillBlocks 无该位（expandPiece 不排 jigsaw，
    // 契约层校验冲突；此处验证 merge 不会把 jigsaw 位置写进 fillBlocks）
    const mJ = V.expandPiece(pJ);
    mJ.set('1,0,0', 'minecraft:planks');
    const rJ = V.mergePieceEdits(pJ, mJ);
    ok('jigsaw 位置不被写入', !rJ.fillBlocks.some((b) => b.x === 1 && b.y === 0 && b.z === 0 && b.block === 'minecraft:planks'), JSON.stringify(rJ.fillBlocks));
}

console.log('\n体素测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
