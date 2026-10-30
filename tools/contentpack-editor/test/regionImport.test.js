// regionImport.test.js - 圈地读取（.nbt → 片段）测试
// 含真实 vanilla 结构文件交叉校验（数据集/1.21.1/client；缺失时跳过）
// 运行: node test/regionImport.test.js
'use strict';

const fs = require('fs');
const path = require('path');
const assert = require('assert');

const NBT = require('../src/core/nbt');
const RI = require('../src/core/regionImport');
const VE = require('../src/core/vanillaExport');

let pass = 0, fail = 0, skip = 0;
function ok(name, cond, extra) {
    if (cond) { pass++; console.log('  ✓ ' + name); }
    else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}
function test(name, fn) {
    try { fn(); ok(name, true); }
    catch (e) { fail++; console.log('  ✗ ' + name + '  → ' + e.message); }
}
function file(p) { return fs.existsSync(p) ? fs.readFileSync(p) : null; }

// 数据集路径（<MC develop pack>/数据集/1.21.1/client；项目根上两级）
const DS = path.join(__dirname, '..', '..', '..', '..', '..', '数据集', '1.21.1', 'client');
const hasDS = fs.existsSync(path.join(DS, 'data', 'minecraft', 'structure'));

console.log('=== 1. 往返：export → import ===');
{
    const piece = {
        name: 'rt', size: [4, 3, 4],
        boxFills: [{ from: [0, 0, 0], to: [3, 0, 3], block: 'minecraft:stone_bricks' }],
        fillBlocks: [{ x: 1, y: 2, z: 1, block: 'minecraft:smooth_stone' }],
        jigsaws: [{
            x: 2, y: 2, z: 2, name: 'top', pool: 'tower', target: 'bottom',
            joint: 'rollable', orientation: 'up_north',
            finalState: 'minecraft:stone_bricks',
            selectionPriority: 10, placementPriority: 10,
        }],
    };
    const region = RI.parseRegionNbt(VE.buildTemplateNbt(piece, 'testns'));
    ok('往返格式 = modern', region.format === 'modern');
    ok('往返尺寸', JSON.stringify(region.size) === '[4,3,4]');
    ok('往返 fillBlocks = 17', region.blocks.filter((b) => b.block !== 'minecraft:air').length === 17, String(region.blocks.length));
    ok('往返 jigsaw = 1', region.jigsaws.length === 1);
    const j = region.jigsaws[0];
    ok('jigsaw 字段齐全', j.name === 'top' && j.orientation === 'up_north' && j.target === 'bottom'
        && j.pool === 'testns:tower' && j.joint === 'rollable' && j.finalState === 'minecraft:stone_bricks'
        && j.selectionPriority === 10 && j.placementPriority === 10, JSON.stringify(j));
    const piece2 = RI.regionToPiece(region, 'rt2');
    ok('regionToPiece fillBlocks', piece2.fillBlocks.length === 17);
    ok('regionToPiece jigsaws', piece2.jigsaws.length === 1 && piece2.jigsaws[0].name === 'top');
    // 2026-09-24：状态属性往返（Properties 大写 compound，对照 1180 个 vanilla 模板实测格式）
    const pieceSt = {
        name: 'st', size: [3, 2, 3], boxFills: [],
        fillBlocks: [
            { x: 0, y: 0, z: 0, block: 'minecraft:oak_stairs[minecraft:facing=east,minecraft:shape=straight,minecraft:half=bottom]' },
            { x: 1, y: 0, z: 0, block: 'minecraft:oak_fence[north=true,south=true,east=false,west=false,waterlogged=false]' },
        ],
        jigsaws: [],
    };
    const decSt = NBT.decode(VE.buildTemplateNbt(pieceSt, 'ns'));
    const stairsPal = decSt.palette.find((e) => e.Name === 'minecraft:oak_stairs');
    ok('导出 palette：Properties 大写 compound（游戏只认这个）', stairsPal && stairsPal.Properties
        && stairsPal.Properties.facing === 'east' && stairsPal.Properties.shape === 'straight' && stairsPal.Properties.half === 'bottom',
        JSON.stringify(stairsPal));
    const rSt = RI.parseRegionNbt(VE.buildTemplateNbt(pieceSt, 'ns'));
    const pSt = RI.regionToPiece(rSt, 'st2');
    const stairB = pSt.fillBlocks.find((b) => b.block.startsWith('minecraft:oak_stairs'));
    ok('导入保留属性实例值（→ block 串 [props]）', stairB && stairB.block === 'minecraft:oak_stairs[facing=east,shape=straight,half=bottom]',
        JSON.stringify(pSt.fillBlocks));
    const fenceB = pSt.fillBlocks.find((b) => b.block.startsWith('minecraft:oak_fence'));
    ok('栅栏连接保留（north/south=true）', fenceB && fenceB.block.includes('north=true') && fenceB.block.includes('south=true'),
        JSON.stringify(fenceB));

    // 2026-09-24：BE NBT 捕获（导入 = 读取游戏里现成的 NBT）：export → import 往返
    // 蓝图格式：nbt = tagged compound { $t:10, v: <tagged 值映射> }
    const chestNbt = {
        $t: NBT.T.COMPOUND,
        v: {
            id: NBT.str('minecraft:chest'),
            Items: NBT.list(NBT.T.COMPOUND, [
                { $t: NBT.T.COMPOUND, v: { Slot: NBT.byte(0), count: NBT.byte(1), id: NBT.str('minecraft:dirt') } },
                { $t: NBT.T.COMPOUND, v: { Slot: NBT.byte(2), count: NBT.byte(64), id: NBT.str('minecraft:iron_ingot') } },
            ]),
        },
    };
    const pieceC = {
        name: 'chest_rt', size: [2, 2, 2], boxFills: [],
        fillBlocks: [{ x: 0, y: 0, z: 0, block: 'minecraft:chest' }, { x: 1, y: 0, z: 0, block: 'minecraft:oak_planks' }],
        jigsaws: [], blockNbt: [{ x: 0, y: 0, z: 0, nbt: chestNbt }],
    };
    const rC = RI.parseRegionNbt(VE.buildTemplateNbt(pieceC, 'ns'));
    ok('导入捕获非 jigsaw BE NBT（chest）', rC.blockNbts && rC.blockNbts.length === 1
        && rC.blockNbts[0].x === 0 && rC.blockNbts[0].block === 'minecraft:chest'
        && rC.blockNbts[0].nbt.id === 'minecraft:chest', JSON.stringify(rC.blockNbts));
    const pC = RI.regionToPiece(rC, 'chest_rt2');
    const bn = pC.blockNbt && pC.blockNbt[0];
    ok('blockNbt 还原为 tagged compound', bn && bn.nbt.$t === NBT.T.COMPOUND, JSON.stringify(bn && bn.nbt.$t));
    ok('Items 往返（count byte 保持 byte）', bn && bn.nbt.v.Items && bn.nbt.v.Items.v.length === 2
        && bn.nbt.v.Items.v[1].v.count.$t === NBT.T.BYTE && bn.nbt.v.Items.v[1].v.count.v === 64
        && bn.nbt.v.Items.v[1].v.id.v === 'minecraft:iron_ingot', JSON.stringify(bn && bn.nbt.v.Items));
    // 占位符 #empty 是编辑器内部符号，导出时跳过（不写块）；真正的 minecraft:air 才保留
    const piece3 = {
        name: 'ph', size: [2, 1, 1], boxFills: [],
        fillBlocks: [{ x: 0, y: 0, z: 0, block: 'minecraft:stone' }, { x: 1, y: 0, z: 0, block: '#empty' }],
        jigsaws: [],
    };
    const r3 = RI.parseRegionNbt(VE.buildTemplateNbt(piece3, 'ns'));
    const p3 = RI.regionToPiece(r3, 'ph3');
    ok('#empty 导出跳过（导入仅 1 块）', p3.fillBlocks.length === 1, JSON.stringify(p3.fillBlocks));
}

console.log('=== 2. 真实 vanilla 文件（1.21.1 数据集）===');
if (!hasDS) {
    skip++; console.log('  - 跳过（数据集不存在：' + DS + '）');
} else {
    // 基准数来自 conda nbtlib 2.0.4 交叉核对（2026-07）
    const bridge = file(path.join(DS, 'data', 'minecraft', 'structure', 'bastion', 'bridge', 'bridge_pieces', 'bridge.nbt'));
    if (!bridge) { skip++; console.log('  - 跳过（bridge.nbt 不存在）'); }
    else {
        const r = RI.parseRegionNbt(bridge);
        ok('bridge.nbt 格式 modern', r.format === 'modern');
        ok('bridge.nbt 尺寸 [31,19,16]', JSON.stringify(r.size) === '[31,19,16]', JSON.stringify(r.size));
        // nbtlib 基准：blocks 共 5182（含 air 3236），其中 4 个是 jigsaw（blocks[].nbt）
        ok('bridge.nbt 普通块 = 5178', r.blocks.length === 5178, String(r.blocks.length));
        ok('bridge.nbt 非 air 普通块 = 1942', r.blocks.filter((b) => b.block !== 'minecraft:air').length === 1942);
        ok('bridge.nbt jigsaw = 4', r.jigsaws.length === 4, String(r.jigsaws.length));
        const bj = r.jigsaws.find((j) => j.x === 6 && j.y === 0 && j.z === 8);
        // orientation 对照真实文件实测：[6,0,8] 的 jigsaw 是 down_west（旧代码丢 Properties 误报默认 north_up）
    ok('jigsaw[6,0,8] 字段正确', bj && bj.name === 'minecraft:empty' && bj.pool === 'minecraft:bastion/bridge/legs'
            && bj.target === 'minecraft:leg_connector' && bj.joint === 'aligned'
            && bj.finalState === 'minecraft:polished_blackstone_bricks' && bj.orientation === 'down_west', JSON.stringify(bj));
    }
    const ac = file(path.join(DS, 'data', 'minecraft', 'structure', 'ancient_city', 'city', 'entrance', 'entrance_connector.nbt'));
    if (!ac) { skip++; console.log('  - 跳过（entrance_connector.nbt 不存在）'); }
    else {
        const r = RI.parseRegionNbt(ac);
        ok('ancient_city 有 jigsaw', r.jigsaws.length > 0, String(r.jigsaws.length));
        const j = r.jigsaws[0];
        ok('jigsaw name/target 正确', j.name === 'minecraft:empty' && j.target === 'minecraft:entrance_middle', JSON.stringify(j));
        ok('jigsaw pool 正确', j.pool === 'minecraft:ancient_city/city/entrance');
        // 对照真实文件实测：首个 jigsaw [0,0,19] orientation = west_up（从 Properties 读取，非默认）
        ok('jigsaw orientation 从 Properties 读取（实测 west_up）', j.orientation === 'west_up', JSON.stringify(j));
        ok('jigsaw final_state', j.finalState === 'minecraft:deepslate_tiles');
    }
    // 2026-09-24：真实村庄 BE NBT 捕获（savanna 小屋：chest 带 LootTable、bed、banner）
    const sh = file(path.join(DS, 'data', 'minecraft', 'structure', 'village', 'savanna', 'houses', 'savanna_small_house_4.nbt'));
    if (!sh) { skip++; console.log('  - 跳过（savanna_small_house_4.nbt 不存在）'); }
    else {
        const r = RI.parseRegionNbt(sh);
        ok('村庄 BE NBT 捕获 = 14', r.blockNbts && r.blockNbts.length === 14, String(r.blockNbts && r.blockNbts.length));
        const chest = r.blockNbts.find((b) => b.block === 'minecraft:chest');
        ok('chest NBT 含 LootTable（游戏原版形状）', chest && chest.nbt.id === 'minecraft:chest'
            && chest.nbt.LootTable === 'minecraft:chests/village/village_savanna_house', JSON.stringify(chest && chest.nbt));
        const p = RI.regionToPiece(r, 'savanna_house');
        const bn = p.blockNbt && p.blockNbt.find((b) => b.x === chest.x && b.y === chest.y && b.z === chest.z);
        ok('regionToPiece blockNbt（tagged，LootTable 为 str）', bn && bn.nbt.$t === NBT.T.COMPOUND
            && bn.nbt.v.LootTable && bn.nbt.v.LootTable.$t === NBT.T.STRING, JSON.stringify(bn && bn.nbt.v.LootTable));
    }
}

console.log('=== 3. 旧格式（1.20.x）兼容 ===');
{
    // 手工构造旧格式：blocks = [[state,x,y,z]]，nbtData = [{pos,id,...}]，palette {Name, Properties}
    const legacyRoot = {
        author: NBT.str('old'),
        DataVersion: NBT.int(3371),
        palette: NBT.list(NBT.T.COMPOUND, [
            { $t: NBT.T.COMPOUND, v: { Name: NBT.str('minecraft:air') } },
            { $t: NBT.T.COMPOUND, v: { Name: NBT.str('minecraft:dirt') } },
            { $t: NBT.T.COMPOUND, v: { Name: NBT.str('minecraft:jigsaw'), Properties: { $t: NBT.T.COMPOUND, v: { orientation: NBT.str('up_east') } } } },
        ]),
        size: NBT.list(NBT.T.INT, [NBT.int(3), NBT.int(1), NBT.int(3)]),
        blocks: NBT.list(NBT.T.LIST, [
            NBT.list(NBT.T.INT, [NBT.int(1), NBT.int(0), NBT.int(0), NBT.int(0)]),
            NBT.list(NBT.T.INT, [NBT.int(1), NBT.int(2), NBT.int(0), NBT.int(2)]),
            NBT.list(NBT.T.INT, [NBT.int(2), NBT.int(1), NBT.int(0), NBT.int(1)]),
        ]),
        nbtData: NBT.list(NBT.T.COMPOUND, [{ $t: NBT.T.COMPOUND, v: {
            pos: NBT.list(NBT.T.INT, [NBT.int(1), NBT.int(0), NBT.int(1)]),
            id: NBT.str('minecraft:jigsaw'),
            name: NBT.str('side'), pool: NBT.str('ns:ring'), target: NBT.str('side'),
            joint: NBT.str('aligned'), final_state: NBT.str('minecraft:air'),
            selection_priority: NBT.int(10), placement_priority: NBT.int(10),
        } }]),
        entities: NBT.list(NBT.T.COMPOUND, []),
    };
    const r = RI.parseRegionNbt(NBT.gzip(legacyRoot));
    ok('旧格式识别', r.format === 'legacy');
    ok('旧格式 fillBlocks = 2', r.blocks.length === 2, String(r.blocks.length));
    ok('旧格式 jigsaw = 1（orientation 来自 Properties）', r.jigsaws.length === 1 && r.jigsaws[0].orientation === 'up_east', JSON.stringify(r.jigsaws));
}

console.log('\n圈地读取测试: ' + pass + ' 通过, ' + fail + ' 失败, ' + skip + ' 跳过');
process.exit(fail ? 1 : 0);
