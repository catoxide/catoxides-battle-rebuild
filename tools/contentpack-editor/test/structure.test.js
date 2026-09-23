// structure.test.js - 建筑拼图（结构）模块测试
// 覆盖：NBT 编解码往返 / 结构模板构建 / blueprint → 原版文件映射 / schema 校验
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const os = require('os');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const NBT = require('../src/core/nbt');
const S = require('../src/core/structureSchema');
const V = require('../src/core/vanillaExport');

let passed = 0, failed = 0;
function test(name, fn) {
    try {
        fn();
        passed++;
        console.log(`  ✓ ${name}`);
    } catch (e) {
        failed++;
        console.log(`  ✗ ${name}\n    ${e.message}`);
    }
}

// ===== 1. NBT 编解码 =====
console.log('=== 1. NBT 编解码往返 ===');
test('标量 + compound + list 往返一致', () => {
    const root = {
        s: NBT.str('hello 中文'),
        i: NBT.int(3465),
        b: NBT.byte(1),
        f: NBT.flt(1.5),
        d: NBT.dbl(2.25),
        l: NBT.list(NBT.T.INT, [NBT.int(1), NBT.int(2), NBT.int(3)]),
        c: { $t: NBT.T.COMPOUND, v: { x: NBT.int(10), y: NBT.int(20) } },
    };
    const buf = NBT.encode(root);
    const dec = NBT.decode(buf);
    assert.strictEqual(dec.s, 'hello 中文');
    assert.strictEqual(dec.i, 3465);
    assert.strictEqual(dec.b, 1);
    assert.strictEqual(dec.f, 1.5);
    assert.strictEqual(dec.d, 2.25);
    assert.deepStrictEqual(dec.l, [1, 2, 3]);
    assert.strictEqual(dec.c.x, 10);
    assert.strictEqual(dec.c.y, 20);
});
test('gzip 往返（结构模板文件形态）', () => {
    const root = { a: NBT.str('x'), l: NBT.list(NBT.T.COMPOUND, [
        { $t: NBT.T.COMPOUND, v: { k: NBT.str('v') } },
        { $t: NBT.T.COMPOUND, v: { k: NBT.str('v2') } },
    ]) };
    const g = NBT.gzip(root);
    assert.strictEqual(g[0], 0x1f);
    assert.strictEqual(g[1], 0x8b);
    const dec = NBT.decode(g);
    assert.strictEqual(dec.a, 'x');
    assert.strictEqual(dec.l.length, 2);
    assert.strictEqual(dec.l[1].k, 'v2');
});
test('list of list（blocks 形态）', () => {
    const root = {
        blocks: NBT.list(NBT.T.LIST, [
            NBT.list(NBT.T.INT, [NBT.int(1), NBT.int(2), NBT.int(3), NBT.int(4)]),
            NBT.list(NBT.T.INT, [NBT.int(5), NBT.int(6), NBT.int(7), NBT.int(8)]),
        ]),
    };
    const dec = NBT.decode(NBT.encode(root));
    assert.deepStrictEqual(dec.blocks, [[1, 2, 3, 4], [5, 6, 7, 8]]);
});

// ===== 2. 方块 ID 解析 =====
console.log('=== 2. 方块 ID 解析 ===');
test('纯 ID', () => {
    const r = V.parseBlockId('minecraft:stone_bricks');
    assert.strictEqual(r.name, 'minecraft:stone_bricks');
    assert.deepStrictEqual(r.properties, {});
});
test('带属性', () => {
    const r = V.parseBlockId('minecraft:stone_bricks[minecraft:axis=y,minecraft:waterlogged=false]');
    assert.strictEqual(r.name, 'minecraft:stone_bricks');
    assert.strictEqual(r.properties['minecraft:axis'], 'y');
    assert.strictEqual(r.properties['minecraft:waterlogged'], 'false');
});
test('非法 ID 抛错', () => {
    assert.throws(() => V.parseBlockId('not a block !!'));
});

// ===== 3. 结构模板 NBT =====
console.log('=== 3. 结构模板 NBT ===');
const piece = {
    name: 'hall',
    size: [4, 3, 4],
    boxFills: [{ from: [0, 0, 0], to: [3, 0, 3], block: 'minecraft:stone_bricks' }],
    fillBlocks: [{ x: 1, y: 2, z: 1, block: 'minecraft:smooth_stone' }],
    jigsaws: [{
        x: 2, y: 2, z: 2, name: 'top', pool: 'tower', target: 'bottom',
        joint: 'rollable', orientation: 'up_north',
        finalState: 'minecraft:stone_bricks',
        selectionPriority: 10, placementPriority: 10,
    }],
};
test('模板含 air + 填充 + jigsaw 调色板与 blocks（1.21.1 格式）', () => {
    const buf = V.buildTemplateNbt(piece, 'testns');
    const dec = NBT.decode(buf);
    assert.strictEqual(dec.DataVersion, 3465);
    assert.deepStrictEqual(dec.size, [4, 3, 4]);
    // 1.21.1 顶层键：无旧格式的 author/nbtData/liquidBlocks/ui_data
    assert.strictEqual(dec.nbtData, undefined, '旧格式 nbtData 不应存在');
    assert.strictEqual(dec.liquidBlocks, undefined);
    // palette: air, stone_bricks, smooth_stone, jigsaw(orientation 为字符串属性)
    assert.strictEqual(dec.palette.length, 4);
    assert.strictEqual(dec.palette[0].Name, 'minecraft:air');
    assert.strictEqual(dec.palette[1].Name, 'minecraft:stone_bricks');
    assert.strictEqual(dec.palette[3].Name, 'minecraft:jigsaw');
    assert.strictEqual(dec.palette[3].properties, 'orientation=up_north');
    // blocks: 16 地板 + 1 填充 + 1 jigsaw = 18 个 {pos,state,nbt?}
    assert.strictEqual(dec.blocks.length, 18);
    const normal = dec.blocks.find((b) => !b.nbt);
    assert.ok(Array.isArray(normal.pos) && normal.pos.length === 3 && Number.isInteger(normal.state));
    // jigsaw 块：nbt 内为 JigsawBlockEntity 字段（无 id/包装层）
    const jb = dec.blocks.find((b) => b.nbt);
    assert.ok(jb, '应有 1 个带 nbt 的 jigsaw 块');
    assert.deepStrictEqual(jb.pos, [2, 2, 2]);
    assert.strictEqual(dec.palette[jb.state].Name, 'minecraft:jigsaw');
    assert.strictEqual(jb.nbt.name, 'top');
    assert.strictEqual(jb.nbt.target, 'bottom');
    assert.strictEqual(jb.nbt.pool, 'testns:tower');
    assert.strictEqual(jb.nbt.joint, 'rollable');
    assert.strictEqual(jb.nbt.final_state, 'minecraft:stone_bricks');
    assert.strictEqual(jb.nbt.selection_priority, 10);
    assert.strictEqual(jb.nbt.placement_priority, 10);
    // entities 空列表
    assert.deepStrictEqual(dec.entities, []);
});

// ===== 4. blueprint → 原版文件映射 =====
console.log('=== 4. blueprint → 原版文件映射 ===');
const bp = S.defaultBlueprint();
bp.structureId = 'example_structure';
bp.namespace = 'example';
bp.pieces = [
    {
        name: 'main_hall', size: [10, 8, 10],
        boxFills: [{ from: [0, 0, 0], to: [9, 0, 9], block: 'minecraft:stone_bricks' }],
        jigsaws: [
            { x: 5, y: 7, z: 5, name: 'top', pool: 'tower', target: 'bottom', joint: 'rollable', orientation: 'up_north', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
            { x: 9, y: 1, z: 5, name: 'east', pool: 'corridor', target: 'entry', joint: 'aligned', orientation: 'east_up', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
        ],
    },
    {
        name: 'tower', size: [6, 12, 6],
        boxFills: [],
        jigsaws: [
            { x: 3, y: 0, z: 3, name: 'bottom', pool: 'main_hall', target: 'top', joint: 'rollable', orientation: 'down_north', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
        ],
    },
    {
        name: 'corridor', size: [8, 4, 4],
        boxFills: [],
        jigsaws: [
            { x: 0, y: 1, z: 2, name: 'entry', pool: 'main_hall', target: 'east', joint: 'aligned', orientation: 'west_up', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
            { x: 7, y: 1, z: 2, name: 'exit', pool: 'end_cap', target: 'end', joint: 'rollable', orientation: 'east_up', finalState: 'minecraft:air', selectionPriority: 10, placementPriority: 10 },
        ],
    },
];
bp.templatePools = [
    { name: 'start', elements: [{ template: 'main_hall', weight: 1, projection: 'rigid' }] },
    { name: 'main_hall', elements: [{ template: 'main_hall', weight: 1, projection: 'rigid' }] },
    { name: 'tower', elements: [{ template: 'tower', weight: 1, projection: 'rigid' }] },
    { name: 'corridor', elements: [{ template: 'corridor', weight: 1, projection: 'rigid' }] },
    { name: 'end_cap', elements: [{ template: 'corridor', weight: 1, projection: 'rigid' }] },
];
test('校验通过（完整契约）', () => {
    const v = S.validateBlueprint(bp);
    assert.deepStrictEqual(v.errors, []);
});
test('生成 10 个文件（3 模板 + 5 池 + 结构 + 集合）', () => {
    const r = V.exportBlueprint(bp);
    assert.deepStrictEqual(r.errors, []);
    assert.strictEqual(r.files.length, 10);
    const paths = r.files.map((f) => f.relPath);
    assert.ok(paths.includes('data/example/structure/main_hall.nbt'));
    assert.ok(paths.includes('data/example/worldgen/template_pool/start.json'));
    assert.ok(paths.includes('data/example/worldgen/structure/example_structure.json'));
    assert.ok(paths.includes('data/example/worldgen/structure_set/example_structure_set.json'));
});
test('模板池 JSON 为原版字段（location/projection/fallback）', () => {
    const r = V.exportBlueprint(bp);
    const pool = JSON.parse(r.files.find((f) => f.relPath === 'data/example/worldgen/template_pool/start.json').content);
    assert.strictEqual(pool.fallback, 'minecraft:empty_pool_element');
    assert.strictEqual(pool.elements[0].weight, 1);
    assert.strictEqual(pool.elements[0].element.element_type, 'minecraft:single_pool_element');
    assert.strictEqual(pool.elements[0].element.location, 'example:main_hall');
    assert.strictEqual(pool.elements[0].element.projection, 'rigid');
});
test('结构定义 JSON 为原版字段', () => {
    const r = V.exportBlueprint(bp);
    const st = JSON.parse(r.files.find((f) => f.relPath === 'data/example/worldgen/structure/example_structure.json').content);
    assert.strictEqual(st.type, 'minecraft:jigsaw');
    assert.strictEqual(st.step, 'surface_structures');
    assert.strictEqual(st.terrain_adaptation, 'beard_thin');
    assert.strictEqual(st.start_pool, 'example:start');
    assert.strictEqual(st.max_distance_from_center, 80);
    assert.strictEqual(st.project_to_surface, true);
    assert.deepStrictEqual(st.biomes, ['#minecraft:is_overworld']);
    assert.deepStrictEqual(st.spawn_overrides, { bonus_chests: 0, spawns: [] });
});
test('结构集合 JSON 为原版字段（random_spread + salt）', () => {
    const r = V.exportBlueprint(bp);
    const set = JSON.parse(r.files.find((f) => f.relPath === 'data/example/worldgen/structure_set/example_structure_set.json').content);
    assert.strictEqual(set.structures[0].structure, 'example:example_structure');
    assert.strictEqual(set.placement.type, 'minecraft:random_spread');
    assert.strictEqual(set.placement.spacing, 32);
    assert.strictEqual(set.placement.separation, 12);
    assert.ok(Number.isInteger(set.placement.salt));
});

// ===== 5. schema 校验错误路径 =====
console.log('=== 5. schema 校验错误路径 ===');
test('缺 structureId 报错', () => {
    const b = S.defaultBlueprint();
    b.pieces = [S.defaultPiece()];
    b.pieces[0].name = 'a';
    const v = S.validateBlueprint(b);
    assert.strictEqual(v.valid, false);
    assert.ok(v.errors.some((e) => e.includes('structureId')));
});
test('基岩版 terrainAdaptation 被拒绝', () => {
    const b = S.defaultBlueprint();
    b.structureId = 'x';
    b.structure.terrainAdaptation = 'beard_cut';
    b.pieces = [{ name: 'a', size: [1, 1, 1], jigsaws: [] }];
    const v = S.validateBlueprint(b);
    assert.strictEqual(v.valid, false);
    assert.ok(v.errors.some((e) => e.includes('terrainAdaptation')));
});
test('jigsaw 位置越界报错', () => {
    const b = S.defaultBlueprint();
    b.structureId = 'x';
    b.pieces = [{ name: 'a', size: [2, 2, 2], jigsaws: [{ ...S.defaultJigsaw(), x: 5, name: 'j', pool: 'p' }] }];
    b.templatePools = [{ name: 'p', elements: [{ template: 'a', weight: 1, projection: 'rigid' }] }];
    b.structure.startPool = 'p';
    const v = S.validateBlueprint(b);
    assert.strictEqual(v.valid, false);
    assert.ok(v.errors.some((e) => e.includes('超出模板尺寸')));
});
test('jigsaw 目标池不存在报错', () => {
    const b = S.defaultBlueprint();
    b.structureId = 'x';
    b.pieces = [{ name: 'a', size: [2, 2, 2], jigsaws: [{ ...S.defaultJigsaw(), name: 'j', pool: 'nope' }] }];
    b.templatePools = [{ name: 'p', elements: [{ template: 'a', weight: 1, projection: 'rigid' }] }];
    b.structure.startPool = 'p';
    const v = S.validateBlueprint(b);
    assert.strictEqual(v.valid, false);
    assert.ok(v.errors.some((e) => e.includes('目标池')));
});
test('separation >= spacing 报错', () => {
    const b = S.defaultBlueprint();
    b.structureId = 'x';
    b.pieces = [{ name: 'a', size: [2, 2, 2], jigsaws: [] }];
    b.templatePools = [{ name: 'p', elements: [{ template: 'a', weight: 1, projection: 'rigid' }] }];
    b.structure.startPool = 'p';
    b.structureSet.spacing = 16;
    b.structureSet.separation = 16;
    const v = S.validateBlueprint(b);
    assert.strictEqual(v.valid, false);
    assert.ok(v.errors.some((e) => e.includes('separation')));
});
test('orientation 非法值报错（如基岩版 facing:up）', () => {
    const b = S.defaultBlueprint();
    b.structureId = 'x';
    b.pieces = [{ name: 'a', size: [2, 2, 2], jigsaws: [{ ...S.defaultJigsaw(), name: 'j', pool: 'p', orientation: 'up' }] }];
    b.templatePools = [{ name: 'p', elements: [{ template: 'a', weight: 1, projection: 'rigid' }] }];
    b.structure.startPool = 'p';
    const v = S.validateBlueprint(b);
    assert.strictEqual(v.valid, false);
    assert.ok(v.errors.some((e) => e.includes('orientation')));
});

// ===== 6. 导出到包目录（临时目录） =====
console.log('=== 6. 导出到包目录 ===');
test('exportToPack 写盘 + 目录结构正确', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-struct-'));
    try {
        const r = V.exportToPack(tmp, bp);
        assert.strictEqual(r.ok, true, r.errors && r.errors.join('; '));
        assert.strictEqual(r.files.length, 10);
        for (const f of r.files) {
            assert.ok(fs.existsSync(path.join(tmp, f)), '缺失: ' + f);
        }
        // NBT 文件可被自己的解码器读回（自洽性）
        const nbtBuf = fs.readFileSync(path.join(tmp, 'data/example/structure/main_hall.nbt'));
        const dec = NBT.decode(nbtBuf);
        assert.strictEqual(dec.size[0], 10);
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

console.log(`\n结构模块测试: ${passed} 通过, ${failed} 失败`);
process.exit(failed ? 1 : 0);
