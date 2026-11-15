// itemBlock.test.js - 物品/方块契约单测（schema 校验 + blockstate/模型生成 + 原版导出）
// 权威来源：主 mod BatchContentRegistrar / ItemBlockParser / BlockPropertyCatalog + 原版 1.21.1 文件格式
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const S = require('../src/core/itemBlockSchema');
const X = require('../src/core/itemBlockExport');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

console.log('=== 1. 参数目录 ===');
test('内置目录 13 个标准参数（与主 mod 资源文件同构）', () => {
    const names = S.BUILTIN_CATALOG.map((p) => p.name);
    for (const n of ['facing', 'horizontal_facing', 'axis', 'waterlogged', 'lit', 'half', 'shape']) assert.ok(names.includes(n), n);
    assert.strictEqual(S.findCatalogSpec('facing').type, 'direction');
    assert.strictEqual(S.findCatalogSpec('level').type, 'int');
});
test('setCatalog 后用运行期目录（主 mod 资源文件覆盖）', () => {
    S.setCatalog([{ name: 'custom_prop', type: 'boolean', default: 'false' }]);
    assert.strictEqual(S.findCatalogSpec('custom_prop').name, 'custom_prop');
    assert.strictEqual(S.findCatalogSpec('facing'), null);
    S.setCatalog(null);
    assert.ok(S.findCatalogSpec('facing'));
});

console.log('=== 2. 物品校验（镜像 BatchContentRegistrar.validateItem） ===');
test('合法物品通过', () => {
    assert.ok(S.validateItem({ id: 'iron_plate', namespace: 'my_pack', stackSize: 64, rarity: 'common' }).valid);
});
test('id 大写/空 → 失败', () => {
    assert.ok(!S.validateItem({ id: 'IronPlate' }).valid);
    assert.ok(!S.validateItem({ id: '' }).valid);
});
test('stackSize 超 99 → 失败（1.21.1 ABSOLUTE_MAX_STACK_SIZE）', () => {
    assert.ok(!S.validateItem({ id: 'x', stackSize: 100 }).valid);
    assert.ok(S.validateItem({ id: 'x', stackSize: 99 }).valid);
});
test('rarity 非法 → 失败', () => {
    assert.ok(!S.validateItem({ id: 'x', rarity: 'legendary' }).valid);
});

console.log('=== 3. 方块校验（含自定义参数） ===');
const goodBlock = {
    id: 'example_wall',
    namespace: 'my_pack',
    material: { hardness: 2.0, sound: 'minecraft:block.stone', lightLevel: 0 },
    properties: [
        { name: 'facing', type: 'direction', values: ['north', 'south', 'west', 'east', 'up', 'down'], default: 'north' },
        { name: 'waterlogged', type: 'boolean', default: 'false' },
    ],
    // 完整覆盖 12 个组合（编辑器自动生成）
    blockstates: {
        mode: 'variants',
        variants: (() => {
            const out = [];
            for (const f of ['north', 'south', 'west', 'east', 'up', 'down']) {
                for (const w of ['true', 'false']) out.push({ when: { facing: f, waterlogged: w }, model: 'example_wall' });
            }
            return out;
        })(),
    },
    model: { type: 'cube_all', texture: 'block/example_wall' },
};
test('合法方块通过', () => {
    assert.ok(S.validateBlock(goodBlock).valid, JSON.stringify(S.validateBlock(goodBlock).errors));
});
test('参数名大写 → 失败（StateDefinition.NAME_PATTERN）', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.properties[0].name = 'Facing';
    assert.ok(!S.validateBlock(b).valid);
});
test('enum 取值 <2 → 失败（原版拒绝）', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.properties = [{ name: 'mode', type: 'enum', values: ['on'], default: 'on' }];
    assert.ok(!S.validateBlock(b).valid);
});
test('int 默认值越界 → 失败', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.properties = [{ name: 'level', type: 'int', min: 0, max: 3, default: '5' }];
    assert.ok(!S.validateBlock(b).valid);
});
test('状态空间 >4096 → 失败', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.properties = [{ name: 'big', type: 'int', min: 0, max: 4095, default: '0' }];
    assert.ok(!S.validateBlock(b).valid);
});
test('有参数但缺 blockstates → 失败', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    delete b.blockstates;
    assert.ok(!S.validateBlock(b).valid);
});
test('variants 未覆盖全部组合 → 失败（列出缺失组合）', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.properties = [{ name: 'facing', type: 'direction', values: ['north', 'south'], default: 'north' }];
    b.blockstates = { mode: 'variants', variants: [{ when: { facing: 'north' }, model: 'example_wall' }] };
    const r = S.validateBlock(b);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('facing=south')));
});
test('multipart 的 when 引用未定义参数 → 失败', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.blockstates = { mode: 'multipart', multipart: [{ when: { nope: 'true' }, model: 'example_wall' }] };
    assert.ok(!S.validateBlock(b).valid);
});
test('blockEntity custom 缺类名 → 失败', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.blockEntity = { type: 'custom', class: '' };
    assert.ok(!S.validateBlock(b).valid);
});
test('lightLevel 16 → 失败', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.material.lightLevel = 16;
    assert.ok(!S.validateBlock(b).valid);
});

// ---- 红石灯行为（redstone 字段，镜像 BatchContentRegistrar） ----
const lampBlock = {
    id: 'brass_lantern',
    namespace: 'my_pack',
    material: { hardness: 3.0, sound: 'minecraft:block.stone', lightLevel: 0, solid: false },
    properties: [{ name: 'lit', type: 'boolean', default: 'false' }],
    redstone: { property: 'lit', lightLit: 15, lightUnlit: 1 },
    blockstates: {
        mode: 'variants',
        variants: [
            { when: { lit: 'true' }, model: 'brass_lantern_lit', texture: 'minecraft:block/redstone_lamp_lit' },
            { when: { lit: 'false' }, model: 'brass_lantern' },
        ],
    },
    model: { type: 'cube_all', texture: 'minecraft:block/redstone_lamp', file: 'brass_lantern', itemFile: 'brass_lantern_lit' },
};
test('红石行为合法（boolean 参数 + 亮度 0..15）', () => {
    assert.ok(S.validateBlock(lampBlock).valid, JSON.stringify(S.validateBlock(lampBlock).errors));
});
test('redstone.property 未声明 → 失败', () => {
    const b = JSON.parse(JSON.stringify(lampBlock));
    b.redstone = { property: 'nope' };
    const r = S.validateBlock(b);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('nope') && e.includes('未声明')));
});
test('redstone.property 指向非 boolean → 失败', () => {
    const b = JSON.parse(JSON.stringify(lampBlock));
    b.properties = [
        { name: 'lit', type: 'boolean', default: 'false' },
        { name: 'mode', type: 'enum', values: ['a', 'b'], default: 'a' },
    ];
    b.blockstates = { mode: 'variants', variants: [
        { when: { lit: 'true', mode: 'a' }, model: 'x' }, { when: { lit: 'true', mode: 'b' }, model: 'x' },
        { when: { lit: 'false', mode: 'a' }, model: 'x' }, { when: { lit: 'false', mode: 'b' }, model: 'x' },
    ]};
    b.redstone = { property: 'mode' };
    const r = S.validateBlock(b);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('必须是 boolean')));
});
test('redstone 亮度越界 → 失败', () => {
    const b = JSON.parse(JSON.stringify(lampBlock));
    b.redstone = { property: 'lit', lightLit: 16 };
    assert.ok(!S.validateBlock(b).valid);
});
test('material.lightLevel>0 与 redstone 同时声明 → 失败（静态亮度会被红石行为覆盖）', () => {
    const b = JSON.parse(JSON.stringify(lampBlock));
    b.material.lightLevel = 15;
    const r = S.validateBlock(b);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('lightLevel') && e.includes('覆盖')));
    b.material.lightLevel = 0; // 0 是默认值，不冲突
    assert.ok(S.validateBlock(b).valid);
});
test('variants 行纹理覆盖 + 附加模型导出（红石灯 lit/unlit）', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ib-lamp-'));
    try {
        const r = X.exportBlock(dir, lampBlock, [lampBlock]);
        assert.ok(r.ok, JSON.stringify(r));
        const main = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/models/block/brass_lantern.json'), 'utf-8'));
        const lit = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/models/block/brass_lantern_lit.json'), 'utf-8'));
        assert.strictEqual(main.textures.all, 'minecraft:block/redstone_lamp');
        assert.strictEqual(lit.textures.all, 'minecraft:block/redstone_lamp_lit'); // 行级纹理覆盖
        const bs = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/blockstates/brass_lantern.json'), 'utf-8'));
        assert.deepStrictEqual(bs.variants['lit=true'], { model: 'my_pack:block/brass_lantern_lit' });
        assert.deepStrictEqual(bs.variants['lit=false'], { model: 'my_pack:block/brass_lantern' });
        const item = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/models/item/brass_lantern.json'), 'utf-8'));
        assert.strictEqual(item.parent, 'my_pack:block/brass_lantern_lit'); // itemFile 指向点亮态
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
});
test('variants 行 texture 非法路径 → 失败', () => {
    const b = JSON.parse(JSON.stringify(lampBlock));
    b.blockstates.variants[0].texture = 'UPPER CASE/path';
    assert.ok(!S.validateBlock(b).valid);
});

console.log('=== 4. 状态展开 + blockstate JSON（1.21.1 格式） ===');
test('无参数 → 单状态 variants 空键（顶层必须有 variants/multipart）', () => {
    const b = { id: 'plain', namespace: 'ns', model: { type: 'cube_all', file: '' } };
    assert.deepStrictEqual(S.buildBlockstatesJson(b), { variants: { '': { model: 'ns:block/plain' } } });
});
test('direction+boolean → 12 个组合全量展开', () => {
    const props = [
        { name: 'facing', type: 'direction', values: ['north', 'south', 'west', 'east', 'up', 'down'] },
        { name: 'waterlogged', type: 'boolean' },
    ];
    const combos = S.expandStates(props);
    assert.strictEqual(combos.length, 12);
    assert.deepStrictEqual(combos[0], { facing: 'north', waterlogged: 'true' });
    assert.deepStrictEqual(combos[11], { facing: 'down', waterlogged: 'false' });
});
test('variants 键序 = 属性声明序（facing=...,waterlogged=...）', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.blockstates.variants = S.expandStates(b.properties).map((c) => ({ when: c, model: 'example_wall' }));
    const out = S.buildBlockstatesJson(b);
    const keys = Object.keys(out.variants);
    assert.strictEqual(keys.length, 12);
    assert.strictEqual(keys[0], 'facing=north,waterlogged=true');
    assert.deepStrictEqual(out.variants[keys[0]], { model: 'my_pack:block/example_wall' });
});
test('带旋转 → 对象形式 {model,x,y}', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.properties = [{ name: 'waterlogged', type: 'boolean', default: 'false' }];
    b.blockstates = { mode: 'variants', variants: [
        { when: { waterlogged: 'true' }, model: 'example_wall' },
        { when: { waterlogged: 'false' }, model: 'example_wall', y: 90 },
    ]};
    const out = S.buildBlockstatesJson(b);
    assert.deepStrictEqual(out.variants['waterlogged=false'], { model: 'my_pack:block/example_wall', y: 90 });
});
test('multipart → when/apply 结构', () => {
    const b = JSON.parse(JSON.stringify(goodBlock));
    b.blockstates = { mode: 'multipart', multipart: [
        { model: 'wall_post' },
        { when: { facing: 'north' }, model: 'wall_side' },
    ]};
    const out = S.buildBlockstatesJson(b);
    assert.strictEqual(out.multipart.length, 2);
    assert.deepStrictEqual(out.multipart[0], { apply: { model: 'my_pack:block/wall_post' } });
    assert.deepStrictEqual(out.multipart[1], { when: { facing: 'north' }, apply: { model: 'my_pack:block/wall_side' } });
});
test('模型引用归一：完整 RL 原样 / 相对补 ns', () => {
    assert.strictEqual(S.normalizeModelRef('other:mod/block/x', 'ns', 'block'), 'other:mod/block/x');
    assert.strictEqual(S.normalizeModelRef('block/x', 'ns', 'block'), 'ns:block/x');
    assert.strictEqual(S.normalizeModelRef('x', 'ns', 'item'), 'ns:item/x');
});

console.log('=== 5. 模型 JSON ===');
test('cube_all 方块模型', () => {
    const m = S.buildBlockModelJson({ id: 'wall', namespace: 'ns', model: { type: 'cube_all', texture: 'block/wall' } });
    assert.deepStrictEqual(m, { parent: 'minecraft:block/cube_all', textures: { all: 'ns:block/wall' } });
});
test('cube 三面可分，缺省回落到 texture', () => {
    const m = S.buildBlockModelJson({ id: 'wall', namespace: 'ns', model: { type: 'cube', texture: 'block/base', top: 'block/top' } });
    assert.strictEqual(m.textures.top, 'ns:block/top');
    assert.strictEqual(m.textures.side, 'ns:block/base');
});
test('方块物品模型 parent 指方块模型', () => {
    const m = S.buildItemModelJson({ id: 'wall', namespace: 'ns', model: { file: 'wall_alt' } }, 'block');
    assert.deepStrictEqual(m, { parent: 'ns:block/wall_alt' });
});
test('纯物品模型 generated + layer0', () => {
    const m = S.buildItemModelJson({ id: 'plate', namespace: 'ns', model: {} }, 'item');
    assert.strictEqual(m.parent, 'minecraft:item/generated');
    assert.strictEqual(m.textures.layer0, 'ns:item/plate');
});
test('raw 模型原样输出', () => {
    const raw = { parent: 'minecraft:block/cube', textures: { side: 'a:b' } };
    assert.deepStrictEqual(S.buildBlockModelJson({ id: 'x', namespace: 'ns', model: { type: 'raw', raw } }), raw);
});

console.log('=== 6. 原版导出（写盘 → 文件内容断言） ===');
function tmpPack() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'ib-export-'));
}
test('exportBlock 写出 blockstate + 模型 + 物品模型 + lang + 掉落 + 标签', () => {
    const dir = tmpPack();
    try {
        const def = JSON.parse(JSON.stringify(goodBlock));
        def.lang = { en_us: 'Example Wall', zh_cn: '示例墙' };
        def.tags = ['minecraft:mineable/pickaxe'];
        const r = X.exportBlock(dir, def);
        assert.ok(r.ok, r.error);
        const bs = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/blockstates/example_wall.json'), 'utf-8'));
        assert.strictEqual(Object.keys(bs.variants).length, 12);
        const blockModel = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/models/block/example_wall.json'), 'utf-8'));
        assert.strictEqual(blockModel.parent, 'minecraft:block/cube_all');
        const itemModel = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/models/item/example_wall.json'), 'utf-8'));
        assert.strictEqual(itemModel.parent, 'my_pack:block/example_wall');
        const lang = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/lang/en_us.json'), 'utf-8'));
        assert.strictEqual(lang['block.my_pack.example_wall'], 'Example Wall');
        const zh = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/lang/zh_cn.json'), 'utf-8'));
        assert.strictEqual(zh['block.my_pack.example_wall'], '示例墙');
        const loot = JSON.parse(fs.readFileSync(path.join(dir, 'data/my_pack/loot_table/blocks/example_wall.json'), 'utf-8'));
        assert.strictEqual(loot.type, 'minecraft:block');
        assert.strictEqual(loot.pools[0].entries[0].name, 'my_pack:example_wall');
        const tag = JSON.parse(fs.readFileSync(path.join(dir, 'data/minecraft/tags/block/mineable/pickaxe.json'), 'utf-8'));
        assert.deepStrictEqual(tag.values, ['my_pack:example_wall']);
    } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
test('exportItem 写出物品模型 + lang', () => {
    const dir = tmpPack();
    try {
        const def = { id: 'iron_plate', namespace: 'my_pack', lang: { en_us: 'Iron Plate', zh_cn: '铁板' }, stackSize: 64, rarity: 'common', model: { type: 'generated', texture: '' } };
        const r = X.exportItem(dir, def);
        assert.ok(r.ok, r.error);
        const m = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/models/item/iron_plate.json'), 'utf-8'));
        assert.strictEqual(m.textures.layer0, 'my_pack:item/iron_plate');
        const zh = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/lang/zh_cn.json'), 'utf-8'));
        assert.strictEqual(zh['item.my_pack.iron_plate'], '铁板');
    } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
test('exportAll 同包多方块共用标签 → 合并写一个文件', () => {
    const dir = tmpPack();
    try {
        const mk = (id, tag) => Object.assign(JSON.parse(JSON.stringify(goodBlock)), {
            id, properties: [], blockstates: { mode: 'variants', variants: [] }, tags: [tag],
        });
        const r = X.exportAll(dir, { items: [], blocks: [mk('wall_a', 'minecraft:mineable/pickaxe'), mk('wall_b', 'minecraft:mineable/pickaxe')], packId: 'my-pack', packDisplayName: 'My Pack' });
        assert.ok(r.ok, JSON.stringify(r.report.problems));
        const tag = JSON.parse(fs.readFileSync(path.join(dir, 'data/minecraft/tags/block/mineable/pickaxe.json'), 'utf-8'));
        assert.deepStrictEqual(tag.values, ['my_pack:wall_a', 'my_pack:wall_b']);
        const lang = JSON.parse(fs.readFileSync(path.join(dir, 'assets/my_pack/lang/en_us.json'), 'utf-8'));
        // 原版创造栏 lang key 含冒号：itemGroup.<ns>:<id>（如 itemGroup.minecraft:combat）
        assert.strictEqual(lang['itemGroup.my_pack:pack_my_pack'], 'My Pack');
    } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
test('lang 合并保留已有键', () => {
    const dir = tmpPack();
    try {
        const def = { id: 'a', namespace: 'ns', lang: { en_us: 'A' }, model: {} };
        X.exportItem(dir, def);
        X.mergeLangFile(dir, 'ns', 'en_us', { 'custom.key': 'old' });
        const lang = JSON.parse(fs.readFileSync(path.join(dir, 'assets/ns/lang/en_us.json'), 'utf-8'));
        assert.strictEqual(lang['item.ns.a'], 'A');
        assert.strictEqual(lang['custom.key'], 'old');
    } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
test('tagFilePath 命名空间/路径解析', () => {
    assert.strictEqual(X.tagFilePath('minecraft:mineable/pickaxe'), 'data/minecraft/tags/block/mineable/pickaxe.json');
    assert.strictEqual(X.tagFilePath('my_pack:mineable/axe'), 'data/my_pack/tags/block/mineable/axe.json');
    assert.strictEqual(X.tagFilePath('bad'), null);
});

console.log(`\n=== 结果: ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
