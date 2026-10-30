// templateCompat.test.js - 「万能读 + 按需求写」契约测试
//
// 背景（run/client/logs/latest.log 2026-10-26）：
//   * 导出的模板 NBT 是【小端】→ 游戏读 main_hall.nbt 直接 UTFDataFormatException、
//     corridor/tower 静默截断。Java 版 NBT = 大端，写侧必须固定大端。
//   * D:\Download\structure.zip（pack_format 41）把模板放在 data/<ns>/structures/（复数），
//     1.21+ 只在 data/<ns>/structure/（单数）找 → 导入 0 片段且报告里 warnings=[]，
//     用户只看到「至少需要一个片段」。读侧要兼容旧目录，且诊断不能被吞掉。
//
// 运行: node test/templateCompat.test.js
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const zlib = require('zlib');
const assert = require('assert');

const NBT = require('../src/core/nbt');
const V = require('../src/core/vanillaExport');
const VI = require('../src/core/vanillaImport');
const S = require('../src/core/structureSchema');

let pass = 0, fail = 0;
function test(name, fn) {
    try { fn(); pass++; console.log('  ✓ ' + name); }
    catch (e) { fail++; console.log('  ✗ ' + name + '  → ' + e.message); }
}

/** 一个能通过 schema 的最小片段（含方块与 jigsaw） */
function fixturePiece(name = 'piece_a') {
    return {
        name,
        size: [3, 3, 3],
        boxFills: [{ from: [0, 0, 0], to: [2, 0, 2], block: 'minecraft:stone_bricks' }],
        fillBlocks: [],
        jigsaws: [{
            x: 0, y: 1, z: 0, name: 'demo:out', pool: 'demo:start_pool', target: 'minecraft:empty',
            joint: 'rollable', orientation: 'north_up', finalState: 'minecraft:air',
        }],
    };
}

/**
 * 造一个单结构数据包。
 * @param {Object} o { templateDir='structure' | 'structures', writeTemplate=true }
 */
function mkPack(tmp, o = {}) {
    const templateDir = o.templateDir || 'structure';
    const writeTemplate = o.writeTemplate !== false;
    const startHeight = o.startHeight || { absolute: 0 };
    const w = (...p) => path.join(tmp, ...p);
    fs.mkdirSync(w('data', 'demo', 'worldgen', 'structure'), { recursive: true });
    fs.mkdirSync(w('data', 'demo', 'worldgen', 'template_pool'), { recursive: true });
    fs.mkdirSync(w('data', 'demo', templateDir), { recursive: true });
    fs.writeFileSync(w('data', 'demo', 'worldgen', 'structure', 'demo_struct.json'), JSON.stringify({
        type: 'minecraft:jigsaw',
        biomes: ['#minecraft:is_overworld'],
        step: 'surface_structures',
        start_pool: 'demo:start_pool',
        size: 7,
        start_height: startHeight,
        max_distance_from_center: 80,
    }));
    fs.writeFileSync(w('data', 'demo', 'worldgen', 'template_pool', 'start_pool.json'), JSON.stringify({
        elements: [{
            weight: 1,
            element: {
                element_type: 'minecraft:single_pool_element',
                location: 'demo:piece_a',
                projection: 'rigid',
            },
        }],
        fallback: 'minecraft:empty',
    }));
    if (writeTemplate) {
        fs.writeFileSync(w('data', 'demo', templateDir, 'piece_a.nbt'), V.buildTemplateNbt(fixturePiece(), 'demo'));
    }
}

console.log('=== 1. 写严格：模板 NBT 必须是大端且能被游戏格式完整消费 ===');
test('buildTemplateNbt 输出的大端布局（字节级）', () => {
    const buf = V.buildTemplateNbt(fixturePiece(), 'demo');
    const raw = zlib.gunzipSync(buf);
    assert.strictEqual(raw[0], 0x0a, '根类型 = compound');
    assert.deepStrictEqual([raw[1], raw[2]], [0x00, 0x00], '根名长度 = 0（大端 int16）');
    // 第一个条目应为 DataVersion: Int；名长 11 在大端是 00 0b（小端会读成 0b00 = 2816）
    assert.strictEqual(raw[3], NBT.T.INT, '条目类型号在前 = Int');
    assert.deepStrictEqual([raw[4], raw[5]], [0x00, 0x0b], '名长 11 应为大端');
    assert.strictEqual(raw.toString('utf-8', 6, 6 + 11), 'DataVersion');
    // Int 值 = S.DATA_VERSION_1_21_1（3955 = 0x00000F73），按大端写
    const dv = S.DATA_VERSION_1_21_1;
    assert.deepStrictEqual([...raw.slice(17, 21)],
        [(dv >>> 24) & 0xff, (dv >>> 16) & 0xff, (dv >>> 8) & 0xff, dv & 0xff],
        'DataVersion 大端 = ' + dv);
});

test('导出文件按大端严格解析且完整消费（auto 判别：BE + complete）', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-compat-exp-'));
    try {
        mkPack(tmp);
        const bp = VI.importVanilla(tmp, { namespace: 'packns' }).blueprints[0].blueprint;
        const out = V.exportBlueprint(bp);
        assert.deepStrictEqual(out.errors, []);
        const nbtFile = out.files.find((f) => f.relPath === 'data/packns/structure/piece_a.nbt');
        assert.ok(nbtFile, '应导出模板 NBT');
        const r = NBT.decodeTaggedAuto(nbtFile.content);
        assert.strictEqual(r.endianness, 'BE', '导出必须是大端');
        assert.strictEqual(r.complete, true, '必须完整消费缓冲区（小端文件按大端解会留尾巴）');
        assert.deepStrictEqual(NBT.taggedToPlain(r.root.v.size), [3, 3, 3]);
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

test('不带端序的 decode 自动判别（大端/小端都能读）', () => {
    const tree = { a: NBT.int(1), s: NBT.str('桥') };
    assert.strictEqual(NBT.decode(NBT.gzip(tree)).a, 1, '默认大端');
    assert.strictEqual(NBT.decode(NBT.gzip(tree, 'LE')).s, '桥', '小端也可读');
});

console.log('=== 2. 万能读：1.20.x 旧目录 data/<ns>/structures/ ===');
test('旧目录（复数）导入成功，并给出旧布局告警', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-compat-legacy-'));
    try {
        mkPack(tmp, { templateDir: 'structures' });
        const r = VI.importVanilla(tmp, { namespace: 'packns' });
        assert.strictEqual(r.ok, true, JSON.stringify(r.errors));
        assert.strictEqual(r.blueprints.length, 1);
        assert.strictEqual(r.blueprints[0].blueprint.pieces.length, 1, '旧目录模板应被读到');
        assert.ok(r.warnings.some((m) => m.includes('structures/') && m.includes('旧模板目录')),
            '应有旧布局告警，实际: ' + JSON.stringify(r.warnings));
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

test('旧目录导入后导出到单数路径', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-compat-legacy-exp-'));
    try {
        mkPack(tmp, { templateDir: 'structures' });
        const bp = VI.importVanilla(tmp, { namespace: 'packns' }).blueprints[0].blueprint;
        const out = V.exportBlueprint(bp);
        assert.deepStrictEqual(out.errors, []);
        assert.ok(out.files.some((f) => f.relPath === 'data/packns/structure/piece_a.nbt'), '导出走 1.21+ 单数路径');
        assert.ok(!out.files.some((f) => f.relPath.includes('/structures/')), '不再产出复数目录');
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

test('小端模板（第三方工具产物）也能读', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-compat-le-'));
    try {
        // 大端导出 → tagged → 重新按小端编码，模拟旧工具产物
        const be = V.buildTemplateNbt(fixturePiece(), 'demo');
        const tagged = NBT.decodeTagged(be, 'BE');
        const le = zlib.gzipSync(NBT.encode(tagged.v, 'LE'));
        const p = path.join(tmp, 'le_piece.nbt');
        fs.writeFileSync(p, le);
        const r = VI.importVanilla(p, { namespace: 'packns' });
        assert.strictEqual(r.ok, true, JSON.stringify(r.errors));
        const piece = r.blueprints[0].blueprint.pieces[0];
        assert.deepStrictEqual(piece.size, [3, 3, 3]);
        assert.strictEqual(piece.jigsaws.length, 1);
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

console.log('=== 3. 按需求写：1.21.2+ 字段改写为 1.21.1 形状 ===');
test('start_height 的 {type,value} 分派形式 → 1.21.1 常数形式', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-compat-sh-'));
    try {
        mkPack(tmp, { startHeight: { type: 'minecraft:constant', value: { absolute: 0 } } });
        const r = VI.importVanilla(tmp, { namespace: 'packns' });
        assert.strictEqual(r.ok, true, JSON.stringify(r.errors));
        const bp = r.blueprints[0].blueprint;
        assert.deepStrictEqual(bp.structure.startHeight, { absolute: 0 }, '蓝图内应是 1.21.1 常数形式');
        const out = V.exportBlueprint(bp);
        assert.deepStrictEqual(out.errors, []);
        const st = JSON.parse(out.files.find((f) => f.relPath === 'data/packns/worldgen/structure/' + bp.structureId + '.json').content);
        assert.deepStrictEqual(st.start_height, { absolute: 0 },
            '导出 JSON 必须是 1.21.1 可解析的形状（vanilla 用 {"absolute":n}）');
        assert.ok(r.warnings.some((m) => m.includes('start_height') && m.includes('1.21.2+')),
            '应有改写告警，实际: ' + JSON.stringify(r.warnings));
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

console.log('=== 4. 读保真：air 不能丢（原版里 air = 清除，structure_void 才是保持）===');test('导入 → 导出往返保留 air 格', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-compat-air-'));
    try {
        mkPack(tmp);
        const piece = fixturePiece();
        // 体积 3x3x3，在 (2,2,2) 放一格 air（模拟原版模板里的「清空」格）
        fs.writeFileSync(path.join(tmp, 'data', 'demo', 'structure', 'piece_a.nbt'),
            V.buildTemplateNbt(Object.assign(piece, {
                fillBlocks: [{ x: 2, y: 2, z: 2, block: 'minecraft:air' }],
            }), 'demo'));
        const r = VI.importVanilla(tmp, { namespace: 'packns' });
        assert.strictEqual(r.ok, true, JSON.stringify(r.errors));
        const bp = r.blueprints[0].blueprint;
        const runs = bp.pieces[0].boxFills || [];
        assert.ok(runs.some((x) => x.block === 'minecraft:air'),
            '导入应保留 air 运行段，实际: ' + JSON.stringify(runs.slice(0, 5)));
        const out = V.exportBlueprint(bp);
        assert.deepStrictEqual(out.errors, []);
        const nbtFile = out.files.find((f) => f.relPath === 'data/packns/structure/piece_a.nbt');
        const back = VI.templateToPiece(nbtFile.content, 'piece_a');
        const air = (back.piece.boxFills || []).filter((x) => x.block === 'minecraft:air');
        assert.ok(air.length > 0, '导出后 air 仍应在');
        assert.ok(air.some((x) => x.from[0] === 2 && x.from[1] === 2 && x.from[2] === 2),
            'air 位置应保持不变，实际: ' + JSON.stringify(air));
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

console.log('=== 5. 诊断：读不到模板时不能静默（此前 warnings=[]）===');test('缺模板 → 导入失败，但逐条告警可见 + 明确两条查找路径', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-compat-miss-'));
    try {
        mkPack(tmp, { writeTemplate: false });
        const r = VI.importVanilla(tmp, { namespace: 'packns' });
        assert.strictEqual(r.ok, false, '没有模板不能算导入成功');
        assert.ok(r.warnings.some((m) => m.includes('demo:piece_a') && m.includes('找不到')),
            '应逐条指出哪个模板找不到，实际: ' + JSON.stringify(r.warnings));
        assert.ok(r.errors.some((m) => m.includes('没有任何模板被读到')),
            '错误里应给出查找过的两条路径，实际: ' + JSON.stringify(r.errors));
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

test('单个 .nbt 校验：解析不完整时告警（尾部未消费字节）', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cped-compat-tail-'));
    try {
        const be = V.buildTemplateNbt(fixturePiece(), 'demo');
        const raw = zlib.gunzipSync(be);
        // 合法 NBT + 尾部垃圾：解析器仍能还原前缀，但必须把「没吃干净」报出来
        const p = path.join(tmp, 'trailing.nbt');
        fs.writeFileSync(p, zlib.gzipSync(Buffer.concat([raw, Buffer.from([0xde, 0xad, 0xbe, 0xef])])));
        const r = VI.importVanilla(p, { namespace: 'packns' });
        assert.strictEqual(r.ok, true, JSON.stringify(r.errors));
        assert.ok(r.warnings.some((m) => m.includes('未消费字节')), '应有不完整解析告警，实际: ' + JSON.stringify(r.warnings));
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

console.log('\n模板兼容测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
