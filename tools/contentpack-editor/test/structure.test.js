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
    // 1.21.1 真实格式：Properties 大写 compound（游戏只认这个；小写 properties 字符串会被忽略）
    assert.ok(dec.palette[3].Properties, 'jigsaw palette 应有 Properties compound');
    assert.strictEqual(dec.palette[3].Properties.orientation, 'up_north');
    assert.strictEqual(dec.palette[3].properties, undefined, '不应再写小写 properties 字符串');
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

console.log(`\n结构模块测试: ${passed} 通过, ${failed} 失败`);
process.exit(failed ? 1 : 0);
