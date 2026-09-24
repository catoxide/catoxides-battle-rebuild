// mcAssets.test.js - blockstate 定义解析测试（P11 右键查方块：属性/朝向/变体数）
// 运行: node test/mcAssets.test.js
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const M = require('../src/core/mcAssets');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
    if (cond) { pass++; console.log('  ✓ ' + name); }
    else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}

function mkRoot() {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mcassets-'));
    fs.mkdirSync(path.join(tmp, 'assets', 'minecraft', 'blockstates'), { recursive: true });
    fs.mkdirSync(path.join(tmp, 'assets', 'example', 'blockstates'), { recursive: true });
    return tmp;
}
function writeBs(root, ns, name, obj) {
    fs.writeFileSync(path.join(root, 'assets', ns, 'blockstates', name + '.json'), JSON.stringify(obj));
}

console.log('=== 1. 扁平键（"a=1,b=2" → 属性） ===');
{
    const root = mkRoot();
    // 仿 oak_stairs：facing 4 向 × half
    const variants = {};
    for (const f of ['north', 'south', 'east', 'west'])
        variants['facing=' + f + ',half=lower'] = { model: 'minecraft:block/thing' };
    variants['facing=north,half=upper'] = [{ model: 'a' }, { model: 'b' }]; // 数组形态
    writeBs(root, 'minecraft', 'stairs_test', { variants });
    const d = M.blockState(root, 'minecraft:stairs_test');
    ok('返回结构', d && d.file === 'assets/minecraft/blockstates/stairs_test.json', JSON.stringify(d));
    ok('facing 属性 4 值', d.properties.facing && d.properties.facing.length === 4 && d.properties.facing.includes('north'), JSON.stringify(d.properties));
    ok('half 属性 2 值', d.properties.half && d.properties.half.length === 2, JSON.stringify(d.properties));
    ok('变体计数（4 单 + 数组 2 项 = 6）', d.variantCount === 6, 'got ' + d.variantCount);
    fs.rmSync(root, { recursive: true, force: true });
}

console.log('=== 2. 嵌套键（{ prop: { value: model } }） ===');
{
    const root = mkRoot();
    writeBs(root, 'minecraft', 'nested_test', {
        variants: {
            facing: {
                north: { model: 'a' },
                south: { model: 'b' },
            },
        },
    });
    const d = M.blockState(root, 'minecraft:nested_test');
    ok('facing 2 值', d.properties.facing && d.properties.facing.join(',') === 'north,south', JSON.stringify(d.properties));
    ok('变体数 2', d.variantCount === 2, 'got ' + d.variantCount);
    fs.rmSync(root, { recursive: true, force: true });
}

console.log('=== 3. 单模型 / 无属性 / 缺失 / 非法 ===');
{
    const root = mkRoot();
    writeBs(root, 'minecraft', 'plain_test', { variants: { model: 'minecraft:block/stone' } });
    const d = M.blockState(root, 'minecraft:plain_test');
    ok('无属性对象为空', Object.keys(d.properties).length === 0, JSON.stringify(d.properties));
    ok('单模型变体数 1', d.variantCount === 1, 'got ' + d.variantCount);

    const d2 = M.blockState(root, 'minecraft:no_such_block');
    ok('缺失文件 → null', d2 === null);

    const d3 = M.blockState(root, 'example/../../etc/passwd');
    ok('非法 ID（路径穿越）→ null', d3 === null);

    const d4 = M.blockState(root, 'example:custom_thing');
    ok('其他命名空间缺失 → null', d4 === null);
    fs.rmSync(root, { recursive: true, force: true });
}

console.log('=== 4. 其他命名空间存在时可读 ===');
{
    const root = mkRoot();
    writeBs(root, 'example', 'bridge', { variants: { 'facing=north,connected=both' : { model: 'm' } } });
    const d = M.blockState(root, 'example:bridge');
    ok('example 命名空间可读', d && d.properties.facing && d.properties.connected, JSON.stringify(d.properties));
    ok('file 路径含命名空间', d.file === 'assets/example/blockstates/bridge.json', d.file);
    fs.rmSync(root, { recursive: true, force: true });
}

console.log('=== 5. blockFaces：带 [props] 的方块串剥属性后查贴图 ===');
{
    const root = mkRoot();
    fs.mkdirSync(path.join(root, 'assets', 'minecraft', 'models', 'block'), { recursive: true });
    fs.mkdirSync(path.join(root, 'assets', 'minecraft', 'textures', 'block'), { recursive: true });
    writeBs(root, 'minecraft', 'props_test', { variants: { 'axis=y': { model: 'minecraft:block/props_test' } } });
    fs.writeFileSync(path.join(root, 'assets', 'minecraft', 'models', 'block', 'props_test.json'),
        JSON.stringify({ parent: 'minecraft:block/cube_column', textures: { side: 'minecraft:block/props_tex', top: 'minecraft:block/props_tex' } }));
    fs.writeFileSync(path.join(root, 'assets', 'minecraft', 'textures', 'block', 'props_tex.png'),
        Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64'));
    const plain = M.blockFaces(root, 'minecraft:props_test');
    const withProps = M.blockFaces(root, 'minecraft:props_test[axis=y]');
    const withProps2 = M.blockFaces(root, 'minecraft:props_test[facing=north,waterlogged=false]');
    ok('基础名有贴图', plain && plain.side, JSON.stringify(plain));
    ok('[axis=y] 剥属性后同贴图', withProps && withProps.side === plain.side, JSON.stringify(withProps));
    ok('多属性串同样可查', withProps2 && withProps2.side === plain.side, JSON.stringify(withProps2));
    fs.rmSync(root, { recursive: true, force: true });
}

console.log('=== 6. blockStateAny 通用链路（vanilla → 内容包 → 数据包 → mod jar）===');
{
    const zlib = require('zlib');
    // 最小 zip（STORE 无压缩）：local header + central directory + EOCD
    function makeZip(entries) {
        const chunks = [], central = [];
        let offset = 0;
        for (const e of entries) {
            const nameBuf = Buffer.from(e.name);
            const crc = typeof zlib.crc32 === 'function' ? (zlib.crc32(e.buf) >>> 0) : 0;
            const lh = Buffer.alloc(30);
            lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0, 6); lh.writeUInt16LE(0, 8);
            lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(e.buf.length, 18); lh.writeUInt32LE(e.buf.length, 22);
            lh.writeUInt16LE(nameBuf.length, 26); lh.writeUInt16LE(0, 28);
            chunks.push(lh, nameBuf, e.buf);
            const ch = Buffer.alloc(46);
            ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6);
            ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(e.buf.length, 20); ch.writeUInt32LE(e.buf.length, 24);
            ch.writeUInt16LE(nameBuf.length, 28); ch.writeUInt32LE(offset, 42);
            central.push(Buffer.concat([ch, nameBuf]));
            offset += 30 + nameBuf.length + e.buf.length;
        }
        const cdBuf = Buffer.concat(central);
        const eocd = Buffer.alloc(22);
        eocd.writeUInt32LE(0x06054b50, 0);
        eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
        eocd.writeUInt32LE(cdBuf.length, 12); eocd.writeUInt32LE(offset, 16);
        return Buffer.concat([...chunks, cdBuf, eocd]);
    }

    const root = mkRoot();
    M.setRootOverride(root); // vanilla 根锁定为本测试临时目录（独立进程，无需还原）
    writeBs(root, 'minecraft', 'any_test', { variants: { 'axis=y': { model: 'm' } } });

    // 内容包
    const pack = fs.mkdtempSync(path.join(os.tmpdir(), 'mcassets-pack-'));
    fs.mkdirSync(path.join(pack, 'assets', 'example', 'blockstates'), { recursive: true });
    fs.writeFileSync(path.join(pack, 'assets', 'example', 'blockstates', 'pack_block.json'),
        JSON.stringify({ variants: { facing: { north: { model: 'm' }, south: { model: 'm' } } } }));

    // 数据包（run/saves/w/datapacks/dp）
    const proj = fs.mkdtempSync(path.join(os.tmpdir(), 'mcassets-proj-'));
    const dpDir = path.join(proj, 'run', 'saves', 'w', 'datapacks', 'dp', 'assets', 'example', 'blockstates');
    fs.mkdirSync(dpDir, { recursive: true });
    fs.writeFileSync(path.join(dpDir, 'dp_block.json'), JSON.stringify({ variants: { 'lit=true': { model: 'm' } } }));

    // mod jar（zip 内 assets/example2/blockstates/jar_block.json）
    const modsDir = path.join(proj, 'run', 'mods');
    fs.mkdirSync(modsDir, { recursive: true });
    const jarContent = JSON.stringify({ variants: { 'power=1': { model: 'm' }, 'power=2': { model: 'm' } } });
    fs.writeFileSync(path.join(modsDir, 'testmod.jar'),
        makeZip([{ name: 'assets/example2/blockstates/jar_block.json', buf: Buffer.from(jarContent) },
                 { name: 'data/example2/blockstate/jar_block.json', buf: Buffer.from('{}') }]));

    let r = M.blockStateAny('/nonexistent', 'minecraft:any_test');
    ok('vanilla 数据集命中', r && r.source === 'vanilla' && r.data.properties.axis, JSON.stringify(r));
    r = M.blockStateAny('/nonexistent', 'minecraft:any_test[axis=y]');
    ok('[props] 串剥属性后命中', r && r.source === 'vanilla', JSON.stringify(r && r.source));
    r = M.blockStateAny('/nonexistent', 'example:pack_block', { packDir: pack });
    ok('内容包命中', r && r.source === 'pack' && r.data.properties.facing && r.data.properties.facing.length === 2, JSON.stringify(r));
    r = M.blockStateAny(proj, 'example:dp_block');
    ok('数据包命中', r && r.source === 'datapack' && r.data.properties.lit, JSON.stringify(r));
    r = M.blockStateAny(proj, 'example2:jar_block');
    ok('mod jar 命中（zip 条目读取）', r && r.source === 'mod' && r.data.properties.power && r.data.properties.power.length === 2
        && r.data.file.includes('testmod.jar'), JSON.stringify(r));
    r = M.blockStateAny(proj, 'example2:no_such');
    ok('全链路未命中 → null', r === null, JSON.stringify(r));
    fs.rmSync(pack, { recursive: true, force: true });
    fs.rmSync(proj, { recursive: true, force: true });
    fs.rmSync(root, { recursive: true, force: true });
}

console.log('\nmcAssets 测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
