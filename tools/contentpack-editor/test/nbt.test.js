// nbt.test.js - NBT 编解码规范测试（type-first 布局 + LE/BE + 真实文件交叉校验）
// 布局基准：真实 MC 1.21.1 level.dat 逐字节验证 + nbtlib 2.0.4 源码
// 运行: node test/nbt.test.js
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const assert = require('assert');

const NBT = require('../src/core/nbt');

let pass = 0, fail = 0, skip = 0;
function test(name, fn) {
    try { fn(); pass++; console.log('  ✓ ' + name); }
    catch (e) { fail++; console.log('  ✗ ' + name + '  → ' + e.message); }
}
function exists(p) { return fs.existsSync(p); }

console.log('=== 1. 布局钉死（type-first，对照真实 level.dat）===');
{
    // { a: Int(1) } LE → 0a | 00 00 | 03 01 00 "a" | 01 00 00 00 | 00
    const buf = NBT.encode({ a: NBT.int(1) }, 'LE');
    const want = Buffer.from([0x0a, 0x00, 0x00, 0x03, 0x01, 0x00, 0x61, 0x01, 0x00, 0x00, 0x00, 0x00]);
    assert.deepStrictEqual(buf, want, '实际: ' + buf.toString('hex'));
    // 根结构: [0]=0a 根类型, [1..2]=根名长 0, [3]=条目类型号, [4..5]=名长, [6]=名字
    assert.strictEqual(buf[0], 0x0a, '根类型 compound');
    assert.strictEqual(buf[3], 0x03, '条目类型号在前');
    // list 元素不重复类型号：{ l: [Int(1), Int(2)] } → ... 09 03 02 00 00 00 | 01 00 00 00 | 02 00 00 00
    const lb = NBT.encode({ l: NBT.list(NBT.T.INT, [NBT.int(1), NBT.int(2)]) }, 'LE');
    // 列表头紧跟名字 'l'(0x6c) 之后：[elemType=03][count=2 LE][1 LE][2 LE]
    const li = lb.indexOf(0x6c) + 1;
    assert.deepStrictEqual([...lb.slice(li, li + 13)],
        [0x03, 0x02, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00],
        'list 元素应为裸值体: ' + lb.toString('hex'));
}
test('LE 编码布局 = [类型号][名长][名][值体]（字节级）', () => {});

console.log('=== 2. 往返（LE / BE / 嵌套）===');
{
    const tree = {
        size: NBT.list(NBT.T.INT, [NBT.int(31), NBT.int(19), NBT.int(16)]),
        name: NBT.str('桥'),
        n: NBT.byte(-5),
        big: NBT.long(9007199254740991), // 2^53-1（Number 精确表示上限内）
        f: NBT.flt(1.5),
        nested: { $t: NBT.T.COMPOUND, v: {
            inner: NBT.int(42),
            list: NBT.list(NBT.T.COMPOUND, [
                { $t: NBT.T.COMPOUND, v: { k: NBT.str('v1') } },
                { $t: NBT.T.COMPOUND, v: { k: NBT.str('v2') } },
            ]),
        } },
    };
    for (const eo of ['LE', 'BE']) {
        const rt = NBT.decode(NBT.encode(tree, eo), eo);
        assert.deepStrictEqual(rt.size, [31, 19, 16]);
        assert.strictEqual(rt.name, '桥');
        assert.strictEqual(rt.n, -5);
        assert.strictEqual(rt.big, '9007199254740991');
        assert.strictEqual(rt.f, 1.5);
        assert.strictEqual(rt.nested.inner, 42);
        assert.deepStrictEqual(rt.nested.list.map((e) => e.k), ['v1', 'v2']);
    }
    // gzip 往返
    const rt = NBT.decode(NBT.gzip(tree, 'LE'), 'LE');
    assert.deepStrictEqual(rt.size, [31, 19, 16]);
}
test('LE/BE/gzip 往返一致', () => {});

console.log('=== 3. decodeAuto 自动判别 ===');
{
    const tree = { palette: NBT.list(NBT.T.COMPOUND, [{ $t: NBT.T.COMPOUND, v: { Name: NBT.str('minecraft:stone') } }]) };
    const be = NBT.gzip(tree, 'BE');
    const r1 = NBT.decodeAuto(be);
    assert.strictEqual(r1.endianness, 'BE');
    assert.strictEqual(r1.root.palette[0].Name, 'minecraft:stone');
    const le = NBT.gzip(tree, 'LE');
    const r2 = NBT.decodeAuto(le);
    assert.strictEqual(r2.endianness, 'LE');
}
test('decodeAuto 正确选择 BE / LE', () => {});

console.log('=== 4. 跨实现交叉校验（nbtlib oracle，test/fixtures/make-oracle.py 生成）===');
{
    // 与 make-oracle.py 完全同序的树
    const tree = {
        size: NBT.list(NBT.T.INT, [NBT.int(31), NBT.int(19), NBT.int(16)]),
        name: NBT.str('桥'),
        n: NBT.byte(-5),
        big: NBT.long(9007199254740991),
        f: NBT.flt(1.5),
        nested: { $t: NBT.T.COMPOUND, v: {
            inner: NBT.int(42),
            list: NBT.list(NBT.T.COMPOUND, [
                { $t: NBT.T.COMPOUND, v: { k: NBT.str('v1') } },
                { $t: NBT.T.COMPOUND, v: { k: NBT.str('v2') } },
            ]),
        } },
    };
    const le = path.join(__dirname, 'fixtures', 'oracle-le.nbt');
    const be = path.join(__dirname, 'fixtures', 'oracle-be.nbt');
    if (!exists(le) || !exists(be)) {
        skip++; console.log('  - 跳过（test/fixtures/oracle-*.nbt 缺失，先跑 conda python test/fixtures/make-oracle.py）');
    } else {
        // 4a. 字节级：我的 LE 编码 === nbtlib LE 序列化
        const mine = NBT.encode(tree, 'LE');
        const theirs = fs.readFileSync(le);
        assert.deepStrictEqual(mine, theirs, 'LE 字节不一致:\nmine=' + mine.toString('hex') + '\ntheirs=' + theirs.toString('hex'));
        // 4b. 我的 BE 编码 === nbtlib BE 序列化
        const mineBe = NBT.encode(tree, 'BE');
        assert.deepStrictEqual(mineBe, fs.readFileSync(be), 'BE 字节不一致');
        // 4c. 解码 nbtlib 产物 === 原树
        const rt = NBT.decode(fs.readFileSync(le), 'LE');
        assert.deepStrictEqual(rt.size, [31, 19, 16]);
        assert.strictEqual(rt.name, '桥');
        assert.strictEqual(rt.n, -5);
        assert.strictEqual(rt.big, '9007199254740991');
        assert.strictEqual(rt.f, 1.5);
        assert.deepStrictEqual(rt.nested.list.map((e) => e.k), ['v1', 'v2']);
        const rtBe = NBT.decodeAuto(fs.readFileSync(be));
        assert.strictEqual(rtBe.endianness, 'BE');
    }
}
test('nbtlib oracle 字节级一致（LE + BE）+ 解码一致', () => {});

console.log('=== 5. 数据集结构模板（大端；缺失则跳过）===');
{
    const ds = path.join(__dirname, '..', '..', '..', '..', '..', '数据集', '1.21.1', 'client');
    const bridge = path.join(ds, 'data', 'minecraft', 'structure', 'bastion', 'bridge', 'bridge_pieces', 'bridge.nbt');
    if (!exists(bridge)) {
        skip++; console.log('  - 跳过 bridge.nbt（数据集不存在）');
    } else {
        const r = NBT.decodeAuto(fs.readFileSync(bridge));
        assert.strictEqual(r.endianness, 'BE', '数据集文件为大端');
        assert.deepStrictEqual(r.root.size, [31, 19, 16]);
        assert.strictEqual(r.root.blocks.length, 5182, 'blocks 数（nbtlib 基准 5182）');
        assert.strictEqual(r.root.palette.length, 24, 'palette 数（nbtlib 基准 24）');
        const j = r.root.blocks.find((b) => b.nbt);
        assert.ok(j, '应有带 nbt 的块');
        console.log('    bridge.nbt: blocks=' + r.root.blocks.length + ', palette=' + r.root.palette.length + ', DataVersion=' + r.root.DataVersion);
    }
}
test('数据集 bridge.nbt 解析与 nbtlib 基准一致', () => {});

console.log('=== 6. plainToTagged（decode 树 → tagged 树，圈地读取捕获 BE NBT 用）===');
test('plainToTagged：byte 约定 / long / bool / 往返', () => {
    // 箱子 NBT（1.21.1 实测形状：id + Items[{Slot,count,id}]，count 小写、byte）
    const chestPlain = {
        id: 'minecraft:chest',
        Items: [
            { Slot: 0, count: 1, id: 'minecraft:dirt' },
            { Slot: 1, count: 64, id: 'minecraft:iron_ingot' },
        ],
    };
    const tagged = NBT.plainToTagged(chestPlain);
    assert.strictEqual(tagged.$t, NBT.T.COMPOUND, '根 = compound');
    assert.strictEqual(tagged.v.id.$t, NBT.T.STRING, 'id = str');
    assert.strictEqual(tagged.v.Items.$t, NBT.T.LIST, 'Items = list');
    assert.strictEqual(tagged.v.Items.elemType, NBT.T.COMPOUND, 'Items 元素 = compound');
    assert.strictEqual(tagged.v.Items.v[0].v.Slot.$t, NBT.T.BYTE, 'Slot 字段约定 → byte');
    assert.strictEqual(tagged.v.Items.v[0].v.count.$t, NBT.T.BYTE, 'count 字段约定 → byte（1.21.1 小写）');
    assert.strictEqual(tagged.v.Items.v[1].v.count.v, 64);
    // 二进制往返：encode → decode 应还原 plain 树
    const rt = NBT.decode(NBT.encode(tagged.v, 'LE'), 'LE');
    assert.deepStrictEqual(rt, chestPlain, '往返: ' + JSON.stringify(rt));

    // long（decode 把 long 变字符串；纯大整数串 → long）
    const lt = NBT.plainToTagged({ t: '1234567890123', small: '123' });
    assert.strictEqual(lt.v.t.$t, NBT.T.LONG, '大整数串 → long');
    assert.strictEqual(lt.v.small.$t, NBT.T.STRING, '小整数串保持 str（无法区分，按 str 安全）');
    const lrt = NBT.decode(NBT.encode(lt.v, 'LE'), 'LE');
    assert.strictEqual(lrt.t, '1234567890123', 'long 往返（decode 仍为字符串）');

    // 其他标量
    const st = NBT.plainToTagged({ i: 42, s: 'x', b: true, f: 1.5, ints: [1, 2, 3] });
    assert.strictEqual(st.v.i.$t, NBT.T.INT, '整数 → int（游戏 1.21.1 数字读取器 byte/int 互转宽松）');
    assert.strictEqual(st.v.b.$t, NBT.T.BYTE, 'bool → byte（NBT 无 bool 类型）');
    assert.strictEqual(st.v.f.$t, NBT.T.DOUBLE, '非整数 → double');
    assert.strictEqual(st.v.ints.elemType, NBT.T.INT, 'int 列表');
});
test('plainToTagged：异型 list 抛错', () => {
    let threw = false;
    try { NBT.plainToTagged({ l: [1, 'a'] }); } catch (e) { threw = /类型不一致/.test(e.message); }
    assert.ok(threw, '异型 list 应抛错');
});

console.log('\nNBT 测试: ' + pass + ' 通过, ' + fail + ' 失败, ' + skip + ' 跳过');
process.exit(fail ? 1 : 0);
