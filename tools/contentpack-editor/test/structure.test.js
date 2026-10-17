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
const VI = require('../src/core/vanillaImport');

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
test('带属性（键归一化：剥 minecraft: 前缀，游戏 StateDefinition 用裸属性名）', () => {
    const r = V.parseBlockId('minecraft:stone_bricks[minecraft:axis=y,minecraft:waterlogged=false]');
    assert.strictEqual(r.name, 'minecraft:stone_bricks');
    assert.strictEqual(r.properties['axis'], 'y');
    assert.strictEqual(r.properties['waterlogged'], 'false');
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
    // 1.21.1 数据版本 = 3955（1180/1180 个原版模板实测；3465 是 1.20.1，会被游戏 DataFixer 升级）
    assert.strictEqual(dec.DataVersion, 3955);
    assert.deepStrictEqual(dec.size, [4, 3, 4]);
    // 1.21.1 顶层键：无旧格式的 author/nbtData/liquidBlocks/ui_data
    assert.strictEqual(dec.nbtData, undefined, '旧格式 nbtData 不应存在');
    assert.strictEqual(dec.liquidBlocks, undefined);
    // palette: air, stone_bricks, smooth_stone, jigsaw(orientation 为字符串属性)
    assert.strictEqual(dec.palette.length, 4);
    assert.strictEqual(dec.palette[0].Name, 'minecraft:air');
    assert.strictEqual(dec.palette[1].Name, 'minecraft:stone_bricks');
    assert.strictEqual(dec.palette[3].Name, 'minecraft:jigsaw');
    // 1.21.1 真实格式：Properties 大写 compound（游戏只认这个；小写 properties 字符串会被忽略）
    assert.ok(dec.palette[3].Properties, 'jigsaw palette 应有 Properties compound');
    assert.strictEqual(dec.palette[3].Properties.orientation, 'up_north');
    assert.strictEqual(dec.palette[3].properties, undefined, '不应再写小写 properties 字符串');
    // blocks: 16 地板 + 1 填充 + 1 jigsaw = 18 个 {pos,state,nbt?}
    assert.strictEqual(dec.blocks.length, 18);
    const normal = dec.blocks.find((b) => !b.nbt);
    assert.ok(Array.isArray(normal.pos) && normal.pos.length === 3 && Number.isInteger(normal.state));
    // jigsaw 块：nbt 内为 JigsawBlockEntity 字段（无包装层）
    const jb = dec.blocks.find((b) => b.nbt);
    assert.ok(jb, '应有 1 个带 nbt 的 jigsaw 块');
    assert.deepStrictEqual(jb.pos, [2, 2, 2]);
    assert.strictEqual(dec.palette[jb.state].Name, 'minecraft:jigsaw');
    // id 必须写：StructureTemplate 按 nbt.id 找 BlockEntityType，缺 id → 方块实体不创建，
    // jigsaw 的名字/目标池/关节全丢（结构无法拼接）
    assert.strictEqual(jb.nbt.id, 'minecraft:jigsaw');
    assert.strictEqual(jb.nbt.name, 'top');
    assert.strictEqual(jb.nbt.target, 'bottom');
    assert.strictEqual(jb.nbt.pool, 'testns:tower');
    assert.strictEqual(jb.nbt.joint, 'rollable');
    assert.strictEqual(jb.nbt.final_state, 'minecraft:stone_bricks');
    // 优先级是编辑器内部字段，1.21.1 的 JigsawBlockEntity 不保存它们
    assert.strictEqual(jb.nbt.selection_priority, undefined);
    assert.strictEqual(jb.nbt.placement_priority, undefined);
    // entities 空列表
    assert.deepStrictEqual(dec.entities, []);
});

// ===== 3b. P12 方块实体 NBT（blockNbt）=====
console.log('=== 3b. P12 方块实体 NBT（blockNbt）===');
// 1.21.1 格式（对照 vanilla ancient_city 模板实测）：
//   chest = { id: "minecraft:chest", Items: [ { count: Byte, Slot: Byte, id: str } ] }
//   —— 小写 count；每个 BE 带 id 字段；long 在 blueprint JSON 里用字符串
const chestNbt = {
    $t: 10,
    v: {
        id: { $t: 8, v: 'minecraft:chest' },
        Items: { $t: 9, elemType: 10, v: [
            { $t: 10, v: {
                Slot: { $t: 1, v: 13 },
                count: { $t: 1, v: 5 },
                id: { $t: 8, v: 'minecraft:golden_apple' },
            } },
        ] },
        TestLong: { $t: 4, v: '1234567890123' },
    },
};
const pieceNbt = {
    name: 'hall_nbt',
    size: [4, 3, 4],
    boxFills: [{ from: [0, 0, 0], to: [3, 0, 3], block: 'minecraft:stone_bricks' }],
    fillBlocks: [{ x: 1, y: 1, z: 1, block: 'minecraft:chest' }],
    blockNbt: [{ x: 1, y: 1, z: 1, nbt: chestNbt }],
};
test('模板导出：blockNbt 写入对应位置 block.nbt（往返一致）', () => {
    const dec = NBT.decode(V.buildTemplateNbt(pieceNbt, 'testns'));
    const chest = dec.blocks.find((b) => b.nbt && b.nbt.id === 'minecraft:chest');
    assert.ok(chest, 'chest 块应带 nbt');
    assert.deepStrictEqual(chest.pos, [1, 1, 1]);
    assert.strictEqual(chest.nbt.id, 'minecraft:chest');
    assert.strictEqual(chest.nbt.Items.length, 1);
    assert.strictEqual(chest.nbt.Items[0].Slot, 13);
    assert.strictEqual(chest.nbt.Items[0].count, 5);
    assert.strictEqual(chest.nbt.Items[0].id, 'minecraft:golden_apple');
    // long（JSON 字符串）→ BigInt 编码 → 读回字符串
    assert.strictEqual(chest.nbt.TestLong, '1234567890123');
    // 普通方块不受影响（无 nbt）
    const plain = dec.blocks.find((b) => b.pos[0] === 0 && b.pos[1] === 0 && b.pos[2] === 0);
    assert.ok(plain && plain.nbt === undefined, '普通方块不应有 nbt');
});
const mkNbtBp = (blockNbt) => {
    const bp = S.defaultBlueprint();
    bp.structureId = 't';
    bp.pieces = [Object.assign(S.defaultPiece(), { name: 'p1', blockNbt })];
    return bp;
};
test('schema：合法 blockNbt 通过校验', () => {
    const v = S.validateBlueprint(mkNbtBp([{ x: 0, y: 0, z: 0, nbt: chestNbt }]));
    assert.deepStrictEqual(v.errors.filter((e) => e.includes('blockNbt')), []);
});
test('schema：blockNbt 位置越界 → error', () => {
    const v = S.validateBlueprint(mkNbtBp([{ x: 99, y: 0, z: 0, nbt: chestNbt }]));
    assert.ok(v.errors.some((e) => e.includes('blockNbt') && e.includes('超出模板尺寸')), JSON.stringify(v.errors));
});
test('schema：blockNbt 非 tagged compound → error', () => {
    const v = S.validateBlueprint(mkNbtBp([{ x: 0, y: 0, z: 0, nbt: { $t: 8, v: 'x' } }]));
    assert.ok(v.errors.some((e) => e.includes('blockNbt') && e.includes('tagged compound')), JSON.stringify(v.errors));
});
test('模板导出：熔炉类 BE（Items + BurnTime/CookTime 原样通过）', () => {
    const furnNbt = {
        $t: 10,
        v: {
            id: { $t: 8, v: 'minecraft:furnace' },
            Items: { $t: 9, elemType: 10, v: [] },
            BurnTime: { $t: 3, v: 800 },
            CookTime: { $t: 3, v: 300 },
            CookTimeTotal: { $t: 3, v: 200 },
        },
    };
    const p = { name: 'f', size: [2, 2, 2], boxFills: [], fillBlocks: [{ x: 0, y: 0, z: 0, block: 'minecraft:furnace' }], blockNbt: [{ x: 0, y: 0, z: 0, nbt: furnNbt }] };
    const dec = NBT.decode(V.buildTemplateNbt(p, 'ns'));
    const f = dec.blocks.find((b) => b.nbt);
    assert.ok(f, 'furnace 块应带 nbt');
    assert.strictEqual(f.nbt.id, 'minecraft:furnace');
    assert.strictEqual(f.nbt.BurnTime, 800);
    assert.strictEqual(f.nbt.CookTime, 300);
    assert.strictEqual(f.nbt.CookTimeTotal, 200);
    assert.deepStrictEqual(f.nbt.Items, []);
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
    // 1.21.1 原版：fallback 是「池 ID」（5 个原版池 186/186 都是 minecraft:empty），不是元素类型 ID
    assert.strictEqual(pool.fallback, 'minecraft:empty');
    assert.strictEqual(pool.elements[0].weight, 1);
    assert.strictEqual(pool.elements[0].element.element_type, 'minecraft:single_pool_element');
    assert.strictEqual(pool.elements[0].element.location, 'example:main_hall');
    assert.strictEqual(pool.elements[0].element.projection, 'rigid');
    // 原版不写空 processors（旧导出器写 processors: [] 会被 codec 拒绝）
    assert.ok(!('processors' in pool.elements[0].element));
});
test('结构定义 JSON 为原版字段', () => {
    const r = V.exportBlueprint(bp);
    const st = JSON.parse(r.files.find((f) => f.relPath === 'data/example/worldgen/structure/example_structure.json').content);
    assert.strictEqual(st.type, 'minecraft:jigsaw');
    assert.strictEqual(st.step, 'surface_structures');
    assert.strictEqual(st.terrain_adaptation, 'beard_thin');
    assert.strictEqual(st.start_pool, 'example:start');
    assert.strictEqual(st.max_distance_from_center, 80);
    // 1.21.1 jigsaw codec 必填项（缺了世界加载失败）
    assert.strictEqual(st.size, 7);
    assert.deepStrictEqual(st.start_height, { absolute: 0 });
    assert.strictEqual(st.use_expansion_hack, true);
    // biomes：单个 tag 必须写标量字符串（数组里放 "#tag" 非法）
    assert.strictEqual(st.biomes, '#minecraft:is_overworld');
    assert.deepStrictEqual(st.spawn_overrides, {});
    assert.strictEqual(st.project_start_to_heightmap, 'WORLD_SURFACE_WG');
    // 1.21.1 不存在的自造字段
    assert.ok(!('project_to_surface' in st));
    assert.ok(!('max_surface_level' in st));
});
test('旧蓝图（projectToSurface/maxSurfaceLevel）导出时自动迁移', () => {
    const legacy = S.defaultBlueprint();
    legacy.formatVersion = 1;
    legacy.structureId = 'legacy_one';
    legacy.namespace = 'example';
    legacy.structure = {
        step: 'surface_structures',
        terrainAdaptation: 'beard_thin',
        startPool: 'start',
        maxDistanceFromCenter: 80,
        biomes: ['#minecraft:is_overworld'],
        projectToSurface: true,
        maxSurfaceLevel: 0,
    };
    legacy.pieces = [{ name: 'p', size: [2, 2, 2], boxFills: [], jigsaws: [] }];
    legacy.templatePools = [{ name: 'start', elements: [{ template: 'p', weight: 1, projection: 'rigid' }] }];
    const v = S.validateBlueprint(legacy);
    assert.deepStrictEqual(v.errors, []);
    const r = V.exportBlueprint(legacy);
    assert.deepStrictEqual(r.errors, []);
    const st = JSON.parse(r.files.find((f) => f.relPath === 'data/example/worldgen/structure/legacy_one.json').content);
    assert.strictEqual(st.project_start_to_heightmap, 'WORLD_SURFACE_WG');
    assert.strictEqual(st.size, 7);
    assert.ok(!('project_to_surface' in st));
});
test('biomes 列表形式（多个 id）原样输出，tag 混用被拒', () => {
    const bp2 = S.defaultBlueprint();
    bp2.structureId = 'b1';
    bp2.namespace = 'example';
    bp2.structure.biomes = ['minecraft:plains', 'minecraft:sunflower_plains'];
    bp2.pieces = [{ name: 'p', size: [2, 2, 2], boxFills: [], jigsaws: [] }];
    bp2.templatePools = [{ name: 'start', elements: [{ template: 'p', weight: 1, projection: 'rigid' }] }];
    const r = V.exportBlueprint(bp2);
    assert.deepStrictEqual(r.errors, []);
    const st = JSON.parse(r.files.find((f) => f.relPath === 'data/example/worldgen/structure/b1.json').content);
    assert.deepStrictEqual(st.biomes, ['minecraft:plains', 'minecraft:sunflower_plains']);
    // tag + id 混用非法
    const bad = S.defaultBlueprint();
    bad.structureId = 'b2';
    bad.pieces = [{ name: 'p', size: [2, 2, 2], boxFills: [], jigsaws: [] }];
    bad.templatePools = [{ name: 'start', elements: [{ template: 'p', weight: 1, projection: 'rigid' }] }];
    bad.structure.biomes = ['#minecraft:is_overworld', 'minecraft:plains'];
    assert.strictEqual(S.validateBlueprint(bad).valid, false);
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
test('exportToPack：落盘 .nbt 含 blockNbt（文件级往返，模拟"导出原版数据"落盘）', () => {
    const nbtBp = S.defaultBlueprint();
    nbtBp.structureId = 'nbtpack';
    nbtBp.namespace = 'ex';
    nbtBp.structure.startPool = 'p1';
    nbtBp.pieces = [{
        name: 'hall', size: [4, 3, 4],
        boxFills: [{ from: [0, 0, 0], to: [3, 0, 3], block: 'minecraft:stone_bricks' }],
        fillBlocks: [{ x: 1, y: 1, z: 1, block: 'minecraft:chest' }],
        jigsaws: [],
        blockNbt: [{ x: 1, y: 1, z: 1, nbt: chestNbt }],
    }];
    nbtBp.templatePools = [{ name: 'p1', elements: [{ template: 'hall', weight: 1, projection: 'rigid' }] }];
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-nbt-'));
    try {
        const r = V.exportToPack(tmp, nbtBp);
        assert.strictEqual(r.ok, true, r.errors && r.errors.join('; '));
        const dec = NBT.decode(fs.readFileSync(path.join(tmp, 'data/ex/structure/hall.nbt')));
        const chest = dec.blocks.find((b) => b.nbt && b.nbt.id === 'minecraft:chest');
        assert.ok(chest, '落盘模板中 chest 块应带 nbt');
        assert.deepStrictEqual(chest.pos, [1, 1, 1]);
        assert.strictEqual(chest.nbt.Items[0].count, 5);
        assert.strictEqual(chest.nbt.Items[0].id, 'minecraft:golden_apple');
        assert.strictEqual(chest.nbt.Items[0].Slot, 13);
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

console.log('=== N. (REVIEW) schema 校验加固 ===');
const mkBp = (over) => Object.assign({
    formatVersion: 1, structureId: 't', namespace: 't',
    structure: {
        step: 'surface_structures', terrainAdaptation: 'beard_thin', startPool: 'start',
        maxDistanceFromCenter: 80, biomes: ['#minecraft:is_overworld'], projectToSurface: true, maxSurfaceLevel: 0,
    },
    pieces: [{ name: 'p', size: [4, 2, 4], boxFills: [], fillBlocks: [], jigsaws: [] }],
    templatePools: [{ name: 'start', elements: [{ template: 'p', weight: 1, projection: 'rigid' }] }],
    structureSet: { spacing: 32, separation: 12, salt: 1, frequency: 1 },
}, over);
test('基线：合法蓝图通过校验', () => {
    assert.strictEqual(S.validateBlueprint(mkBp({})).valid, true);
});
test('M1: size 长度 ≠ 3 被拒（此前 !size.length===3 死代码放行 size:[5]）', () => {
    const v = S.validateBlueprint(mkBp({ pieces: [{ name: 'p', size: [5], boxFills: [], fillBlocks: [], jigsaws: [] }] }));
    assert.strictEqual(v.valid, false);
    assert.ok(v.errors.some((e) => e.includes('size')), JSON.stringify(v.errors));
});
test('M1: size 单维 > 512 被拒、= 512 通过', () => {
    assert.strictEqual(S.validateBlueprint(mkBp({ pieces: [{ name: 'p', size: [513, 2, 4], boxFills: [], fillBlocks: [], jigsaws: [] }] })).valid, false);
    assert.strictEqual(S.validateBlueprint(mkBp({ pieces: [{ name: 'p', size: [512, 2, 4], boxFills: [], fillBlocks: [], jigsaws: [] }] })).valid, true);
});
test('L6: structureId/namespace 为 "." / ".." 被拒（防路径逃逸）', () => {
    assert.strictEqual(S.validateBlueprint(mkBp({ structureId: '..' })).valid, false);
    assert.strictEqual(S.validateBlueprint(mkBp({ structureId: '.' })).valid, false);
    assert.strictEqual(S.validateBlueprint(mkBp({ namespace: '..' })).valid, false);
});
test('L6: writeStructure 拒绝 "." / ".." structureId（纵深防御）', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-ws-'));
    try {
        assert.throws(() => S.writeStructure(tmp, { structureId: '..' }), /structureId/);
        assert.ok(!fs.existsSync(path.join(tmp, 'blueprint.json')), '.. 不得写到包根');
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});
test('L5: readStructure/deleteStructure 拒绝 structures/ 外路径', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-l5-'));
    try {
        fs.writeFileSync(path.join(tmp, 'outside.json'), '{}');
        assert.throws(() => S.readStructure(tmp, '../outside.json'));
        assert.deepStrictEqual(S.deleteStructure(tmp, '../outside.json'), { deleted: false });
        assert.ok(fs.existsSync(path.join(tmp, 'outside.json')), '包外文件不得被删');
        // 合法路径正常
        fs.mkdirSync(path.join(tmp, 'structures', 'x'), { recursive: true });
        fs.writeFileSync(path.join(tmp, 'structures', 'x', 'blueprint.json'), JSON.stringify({ structureId: 'x' }));
        assert.strictEqual(S.readStructure(tmp, 'structures/x/blueprint.json').structureId, 'x');
        assert.deepStrictEqual(S.deleteStructure(tmp, 'structures/x/blueprint.json'), { deleted: true });
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

console.log('=== O. 现代原版特性：feature 元素 / resolveStructureFile / 列表展开权重 ===');
test('O1: pool 可含 feature_pool_element（原版村庄树木），校验通过且导出为 feature 元素', () => {
    const b = mkBp({});
    b.templatePools[0].elements.push({ feature: 'minecraft:oak', weight: 2, projection: 'rigid' });
    const v = S.validateBlueprint(b);
    assert.deepStrictEqual(v.errors, []);
    const r = V.exportBlueprint(b);
    assert.deepStrictEqual(r.errors, []);
    const pool = JSON.parse(r.files.find((f) => f.relPath === 'data/t/worldgen/template_pool/start.json').content);
    const feat = pool.elements.find((e) => e.element.element_type === 'minecraft:feature_pool_element');
    assert.ok(feat, '应导出 feature_pool_element');
    assert.strictEqual(feat.element.feature, 'minecraft:oak');
    assert.strictEqual(feat.weight, 2);
});
test('O2: 元素 template/feature 二选一（都空或都给 → error）', () => {
    const none = mkBp({});
    none.templatePools[0].elements = [{ weight: 1, projection: 'rigid' }];
    assert.ok(S.validateBlueprint(none).errors.some((e) => e.includes('template') || e.includes('feature')));
    const both = mkBp({});
    both.templatePools[0].elements = [{ template: 'p', feature: 'minecraft:oak', weight: 1, projection: 'rigid' }];
    assert.ok(S.validateBlueprint(both).errors.some((e) => e.includes('二选一')));
});
test('O3: resolveStructureFile 已导出（此前 main.js 调不到 → 结构打不开编辑界面）', () => {
    assert.strictEqual(typeof S.resolveStructureFile, 'function');
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-rsf-'));
    try {
        fs.mkdirSync(path.join(tmp, 'structures', 'demo'), { recursive: true });
        fs.writeFileSync(path.join(tmp, 'structures', 'demo', 'blueprint.json'), JSON.stringify({ structureId: 'demo' }));
        const got = S.resolveStructureFile(tmp, 'structures/demo/blueprint.json');
        assert.ok(got && got.endsWith(path.join('structures', 'demo', 'blueprint.json')), String(got));
        assert.strictEqual(S.resolveStructureFile(tmp, '../escape.json'), null);
        assert.throws(() => S.readStructure(tmp, 'structures/nope/blueprint.json'));
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});
test('O4: 结构定义导出现代字段（size/start_height/use_expansion_hack），无自造旧字段', () => {
    const b = mkBp({});
    b.structure.size = 20;
    b.structure.startHeight = { absolute: -10 };
    b.structure.useExpansionHack = false;
    const r = V.exportBlueprint(b);
    assert.deepStrictEqual(r.errors, []);
    const st = JSON.parse(r.files.find((f) => f.relPath === 'data/t/worldgen/structure/t.json').content);
    assert.strictEqual(st.size, 20);
    assert.deepStrictEqual(st.start_height, { absolute: -10 });
    assert.strictEqual(st.use_expansion_hack, false);
    assert.ok(!('project_to_surface' in st) && !('max_surface_level' in st));
});

// ===== 7. 原版数据包 → 蓝图 → 原版数据（vanillaImport / vanillaExport 往返） =====
/** 造一个最小 1.21.1 jigsaw 数据包目录 */
function mkVanillaPack(tmp, { legacyElem = false } = {}) {
    const w = (...p) => path.join(tmp, ...p);
    fs.mkdirSync(w('data', 'demo', 'worldgen', 'structure'), { recursive: true });
    fs.mkdirSync(w('data', 'demo', 'worldgen', 'template_pool'), { recursive: true });
    fs.mkdirSync(w('data', 'demo', 'worldgen', 'structure_set'), { recursive: true });
    fs.mkdirSync(w('data', 'demo', 'structure'), { recursive: true });
    fs.writeFileSync(w('data', 'demo', 'worldgen', 'structure', 'demo_struct.json'), JSON.stringify({
        type: 'minecraft:jigsaw',
        biomes: ['#minecraft:is_overworld'],
        step: 'surface_structures',
        terrain_adaptation: 'beard_thin',
        start_pool: 'demo:start_pool',
        size: 7,
        start_height: { absolute: 0 },
        use_expansion_hack: true,
        max_distance_from_center: 80,
        spawn_overrides: {},
    }));
    const els = [{
        weight: 2,
        element: {
            element_type: 'minecraft:single_pool_element',
            location: 'demo:piece_a',
            projection: 'rigid',
            processors: 'minecraft:street_plains',
        },
    }];
    if (legacyElem) {
        els.push({ weight: 1, element: { element_type: 'minecraft:legacy_single_pool_element', location: 'demo:piece_a', projection: 'terrain_matching' } });
    }
    fs.writeFileSync(w('data', 'demo', 'worldgen', 'template_pool', 'start_pool.json'),
        JSON.stringify({ elements: els, fallback: 'minecraft:empty' }));
    fs.writeFileSync(w('data', 'demo', 'worldgen', 'structure_set', 'demo_set.json'), JSON.stringify({
        structures: [{ structure: 'demo:demo_struct', weight: 1 }],
        placement: { type: 'minecraft:random_spread', spacing: 24, separation: 8, salt: 12345 },
    }));
    // 模板 NBT 用导出器自己造（与游戏 1.21.1 格式一致），带 1 个 jigsaw 块
    const piece = {
        name: 'piece_a', size: [3, 3, 3],
        boxFills: [{ from: [0, 0, 0], to: [2, 0, 2], block: 'minecraft:stone_bricks' }],
        fillBlocks: [], jigsaws: [{
            x: 0, y: 1, z: 0, name: 'demo:out', pool: 'demo:start_pool', target: 'minecraft:empty',
            joint: 'rollable', orientation: 'north_up', finalState: 'minecraft:air',
        }],
    };
    fs.writeFileSync(w('data', 'demo', 'structure', 'piece_a.nbt'), V.buildTemplateNbt(piece, 'demo'));
    return tmp;
}

test('V1: 数据包文件夹导入 → 蓝图（池/模板/jigsaw/结构集全部重建，命名空间改写）', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-vimp-'));
    try {
        mkVanillaPack(tmp, { legacyElem: true });
        const r = VI.importVanilla(tmp, { namespace: 'packns' });
        assert.strictEqual(r.ok, true, JSON.stringify(r.errors));
        assert.strictEqual(r.blueprints.length, 1);
        const bp = r.blueprints[0].blueprint;
        assert.strictEqual(bp.namespace, 'packns');
        assert.strictEqual(bp.structure.startPool, 'packns:start_pool');
        assert.strictEqual(bp.pieces.length, 1);
        assert.strictEqual(bp.pieces[0].name, 'piece_a');
        assert.ok(bp.pieces[0].jigsaws.length >= 1);
        assert.strictEqual(bp.pieces[0].jigsaws[0].pool, 'packns:start_pool', 'jigsaw 池引用应改写到蓝图池');
        const pool = bp.templatePools.find((p) => p.name === 'start_pool');
        assert.ok(pool, 'start_pool 应被导入');
        // 不可编辑字段必须带进蓝图（否则编辑器「打开→保存」会洗掉）
        assert.strictEqual(pool.elements[0].processors, 'minecraft:street_plains');
        assert.strictEqual(pool.elements[0].elementType, 'minecraft:single_pool_element');
        const legacy = pool.elements.find((e) => e.elementType === 'minecraft:legacy_single_pool_element');
        assert.ok(legacy, 'legacy_single_pool_element 应保留 elementType');
        // 结构集 placement 来自原版文件
        assert.strictEqual(bp.structureSet.spacing, 24);
        assert.strictEqual(bp.structureSet.separation, 8);
        assert.strictEqual(bp.structureSet.salt, 12345);
        assert.strictEqual(S.validateBlueprint(bp).valid, true);
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

test('V2: 导入的蓝图导出回原版文件 → processors/element_type/start_pool/placement 不丢', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-vrt-'));
    try {
        mkVanillaPack(tmp);
        const bp = VI.importVanilla(tmp, { namespace: 'packns' }).blueprints[0].blueprint;
        const out = V.exportBlueprint(bp);
        assert.deepStrictEqual(out.errors, []);
        const pool = JSON.parse(out.files.find((f) => f.relPath === 'data/packns/worldgen/template_pool/start_pool.json').content);
        assert.strictEqual(pool.elements[0].element.processors, 'minecraft:street_plains');
        // 权重会被归一化（约分到互质，保持相对比例）：2:1 → 2:1 或等比例
        if (pool.elements.length > 1) {
            assert.strictEqual(pool.elements[0].weight / pool.elements[1].weight, 2);
        }
        assert.strictEqual(pool.fallback, 'minecraft:empty');
        const st = JSON.parse(out.files.find((f) => f.relPath === `data/packns/worldgen/structure/${bp.structureId}.json`).content);
        assert.strictEqual(st.start_pool, 'packns:start_pool');
        assert.strictEqual(st.terrain_adaptation, 'beard_thin');
        const set = JSON.parse(out.files.find((f) => f.relPath === `data/packns/worldgen/structure_set/${bp.structureId}_set.json`).content);
        assert.strictEqual(set.placement.spacing, 24);
        assert.strictEqual(set.placement.salt, 12345);
        // 模板 NBT 往返：导出的 .nbt 再解析回片段，方块与 jigsaw 都还在
        const nbtFile = out.files.find((f) => f.relPath === 'data/packns/structure/piece_a.nbt');
        assert.ok(nbtFile, '应导出模板 NBT');
        const roundTrip = VI.templateToPiece(nbtFile.content, 'piece_a');
        assert.deepStrictEqual(roundTrip.piece.size, [3, 3, 3]);
        assert.strictEqual(roundTrip.piece.jigsaws.length, 1);
        assert.ok(roundTrip.piece.boxFills.length + roundTrip.piece.fillBlocks.length > 0);
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

test('V3: 单个 .nbt 模板导入 → 单片段蓝图（含 jigsaw 池保留原 ID 的警告路径）', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-vnbt-'));
    try {
        const p = path.join(tmp, 'lonely.nbt');
        fs.writeFileSync(p, V.buildTemplateNbt({
            name: 'x', size: [2, 2, 2], boxFills: [{ from: [0, 0, 0], to: [1, 1, 1], block: 'minecraft:stone' }],
            fillBlocks: [], jigsaws: [{ x: 0, y: 0, z: 0, name: 'a', pool: 'minecraft:empty', target: 'b', joint: 'aligned', orientation: 'up_north', finalState: 'minecraft:air' }],
        }, 'demo'));
        const r = VI.importVanilla(p, { namespace: 'packns' });
        assert.strictEqual(r.ok, true, JSON.stringify(r.errors));
        assert.strictEqual(r.kind, 'template');
        const bp = r.blueprints[0].blueprint;
        assert.strictEqual(bp.pieces.length, 1);
        assert.strictEqual(bp.templatePools.length, 1);
        assert.strictEqual(bp.structure.startPool, 'start');
        assert.strictEqual(S.validateBlueprint(bp).valid, true);
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

// 0.2.1 自查：校验缺口回归（startPool 必填 / maxDistance 上限 / fillBlocks 边界与方块 ID）
test('V4: 校验缺口回归 —— startPool 必填 / maxDistance ≤128 / fillBlocks 边界与非法方块 ID', () => {
    const base = () => ({
        formatVersion: 2,
        structureId: 'v4',
        namespace: 'demo',
        structure: {
            step: 'surface_structures',
            terrainAdaptation: 'none',
            startPool: 'start',
            maxDistanceFromCenter: 80,
            biomes: ['#minecraft:is_overworld'],
            size: 7,
            startHeight: { absolute: 0 },
            useExpansionHack: true,
        },
        pieces: [{
            name: 'start', size: [8, 4, 8],
            boxFills: [{ from: [0, 0, 0], to: [7, 0, 7], block: 'minecraft:stone_bricks' }],
            fillBlocks: [], jigsaws: [],
        }],
        templatePools: [{ name: 'start', elements: [{ template: 'start', weight: 1, projection: 'rigid' }] }],
        structureSet: { spacing: 32, separation: 12, salt: 1, frequency: 1 },
    });
    // 基线必须通过
    assert.strictEqual(S.validateBlueprint(base()).valid, true, JSON.stringify(S.validateBlueprint(base()).errors));

    const noStart = base(); noStart.structure.startPool = '';
    assert.ok(S.validateBlueprint(noStart).errors.some((e) => e.includes('startPool 必填')), 'startPool 为空应报错');

    const tooFar = base(); tooFar.structure.maxDistanceFromCenter = 200;
    assert.ok(S.validateBlueprint(tooFar).errors.some((e) => e.includes('128')), 'maxDistance > 128 应报错');

    const oob = base(); oob.pieces[0].fillBlocks.push({ x: 9, y: 0, z: 0, block: 'minecraft:stone' });
    assert.ok(S.validateBlueprint(oob).errors.some((e) => e.includes('超出模板尺寸')), 'fillBlocks 越界应报错');

    const badBlock = base(); badBlock.pieces[0].fillBlocks.push({ x: 0, y: 0, z: 0, block: 'minecraft:Stone With Space' });
    assert.ok(S.validateBlueprint(badBlock).errors.some((e) => e.includes('方块 ID 非法')), '非法方块 ID 应报错');

    const badFinal = base();
    badFinal.pieces[0].jigsaws.push({ x: 0, y: 0, z: 0, name: 'j', pool: 'start', target: 't', joint: 'aligned', orientation: 'up_north', finalState: 'minecraft:stone[bad' });
    assert.ok(S.validateBlueprint(badFinal).errors.some((e) => e.includes('finalState')), '非法 finalState 应报错');
});

console.log(`\n结构模块测试: ${passed} 通过, ${failed} 失败`);
process.exit(failed ? 1 : 0);
