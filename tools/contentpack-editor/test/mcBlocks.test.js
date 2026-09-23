// mcBlocks.test.js - 方块实时读取测试（目录扫描 + jar 中央目录解析）
// 运行: node test/mcBlocks.test.js
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const M = require('../src/core/mcBlocks');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
    if (cond) { pass++; console.log('  ✓ ' + name); }
    else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}

// 构造最小 zip（store 方法，无压缩）：entries = [{name, content(Buffer)}]
function makeZip(entries) {
    const chunks = [];
    const central = [];
    let offset = 0;
    for (const e of entries) {
        const nameBuf = Buffer.from(e.name, 'utf8');
        const data = e.content || Buffer.alloc(0);
        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4);           // version needed
        local.writeUInt16LE(0, 6);            // flags
        local.writeUInt16LE(0, 8);            // method 0 = store
        local.writeUInt16LE(0, 10);           // time
        local.writeUInt16LE(0, 12);           // date
        local.writeUInt32LE(0, 14);           // crc（store+空内容 = 0）
        local.writeUInt32LE(data.length, 18); // comp size
        local.writeUInt32LE(data.length, 22); // uncomp size
        local.writeUInt16LE(nameBuf.length, 26);
        local.writeUInt16LE(0, 28);           // extra len
        chunks.push(local, nameBuf, data);

        const cd = Buffer.alloc(46);
        cd.writeUInt32LE(0x02014b50, 0);
        cd.writeUInt16LE(20, 4);              // version made by
        cd.writeUInt16LE(20, 6);              // version needed
        cd.writeUInt16LE(0, 8);
        cd.writeUInt16LE(0, 10);
        cd.writeUInt16LE(0, 12);
        cd.writeUInt16LE(0, 14);
        cd.writeUInt32LE(0, 16);              // crc
        cd.writeUInt32LE(data.length, 20);
        cd.writeUInt32LE(data.length, 24);
        cd.writeUInt16LE(nameBuf.length, 28);
        cd.writeUInt16LE(0, 30);              // extra
        cd.writeUInt16LE(0, 32);              // comment
        cd.writeUInt16LE(0, 34);              // disk
        cd.writeUInt16LE(0, 36);              // int attrs
        cd.writeUInt32LE(0, 38);              // ext attrs
        cd.writeUInt32LE(offset, 42);         // local offset
        central.push(cd, nameBuf);
        offset += 30 + nameBuf.length + data.length;
    }
    const cdBuf = Buffer.concat(central);
    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(0, 4);
    eocd.writeUInt16LE(0, 6);
    eocd.writeUInt16LE(entries.length, 8);
    eocd.writeUInt16LE(entries.length, 10);
    eocd.writeUInt32LE(cdBuf.length, 12);
    eocd.writeUInt32LE(offset, 16);
    eocd.writeUInt16LE(0, 20);
    return Buffer.concat([...chunks, cdBuf, eocd]);
}

console.log('=== 1. 目录扫描（collectFromDir）===');
{
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mcblocks-'));
    // data/minecraft/blockstate/oak_log.json + data/example/blockstate/bridge.nbt.json（非 json 忽略）
    fs.mkdirSync(path.join(tmp, 'data', 'minecraft', 'blockstate'), { recursive: true });
    fs.writeFileSync(path.join(tmp, 'data', 'minecraft', 'blockstate', 'oak_log.json'), '{}');
    fs.mkdirSync(path.join(tmp, 'data', 'example', 'blockstate'), { recursive: true });
    fs.writeFileSync(path.join(tmp, 'data', 'example', 'blockstate', 'bridge.json'), '{}');
    fs.writeFileSync(path.join(tmp, 'data', 'example', 'blockstate', 'skip.nbt'), 'x');
    // 嵌套一层：saves/x/datapacks/dp1/data/foo/blockstate/bar.json
    fs.mkdirSync(path.join(tmp, 'saves', 'x', 'datapacks', 'dp1', 'data', 'foo', 'blockstate'), { recursive: true });
    fs.writeFileSync(path.join(tmp, 'saves', 'x', 'datapacks', 'dp1', 'data', 'foo', 'blockstate', 'bar.json'), '{}');
    // 非 blockstate 目录里的 json 不应计入
    fs.mkdirSync(path.join(tmp, 'assets', 'minecraft', 'models', 'block'), { recursive: true });
    fs.writeFileSync(path.join(tmp, 'assets', 'minecraft', 'models', 'block', 'stone.json'), '{}');

    const ids = new Set();
    M.collectFromDir(tmp, ids);
    ok('minecraft:oak_log 命中', ids.has('minecraft:oak_log'), [...ids].join(','));
    ok('example:bridge 命中', ids.has('example:bridge'));
    ok('非 json 忽略', !ids.has('example:skip.nbt'));
    ok('嵌套 datapack 命中', ids.has('foo:bar'));
    ok('非 blockstate 目录不计入', !ids.has('minecraft:stone'));
    fs.rmSync(tmp, { recursive: true, force: true });
}

console.log('=== 2. jar 中央目录（collectFromJar）===');
{
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mcblocks-jar-'));
    const jarPath = path.join(tmp, 'testmod.jar');
    const zip = makeZip([
        { name: 'META-INF/MANIFEST.MF', content: Buffer.from('Manifest-Version: 1.0') },
        { name: 'data/example/blockstate/bridge.json' },
        { name: 'data/example/blockstate/tower.json' },
        { name: 'data/minecraft/blockstate/oak_log.json' },
        { name: 'data/example/structure/bridge.nbt', content: Buffer.from([1, 2, 3]) },
        { name: 'assets/example/textures/block/bridge.png', content: Buffer.from([0]) },
    ]);
    fs.writeFileSync(jarPath, zip);
    const ids = new Set();
    const n = M.collectFromJar(jarPath, ids);
    ok('jar 内 3 个 blockstate 命中', n === 3, 'got ' + n);
    ok('example:bridge', ids.has('example:bridge'), [...ids].join(','));
    ok('example:tower', ids.has('example:tower'));
    ok('minecraft:oak_log', ids.has('minecraft:oak_log'));
    ok('非 blockstate 不计入', !ids.has('example:bridge.nbt'));
    // 空文件 / 非 zip → 0 不抛
    const bad = path.join(tmp, 'bad.jar');
    fs.writeFileSync(bad, 'not a zip');
    ok('非 zip 返回 0', M.collectFromJar(bad, ids) === 0);
    fs.rmSync(tmp, { recursive: true, force: true });
}

console.log('=== 3. scanBlocks 缓存/合并（无项目根兜底）===');
{
    M._resetCache();
    // 无 run/ 目录的项目根 → 只有 vanilla（数据集）部分（本机数据集可能为空，也允许 0）
    const r = M.scanBlocks(path.join(os.tmpdir(), 'no-such-project-xyz'), { force: true });
    ok('返回结构完整', Array.isArray(r.ids) && r.meta && typeof r.meta.vanilla === 'number', JSON.stringify(r.meta));
    ok('meta.at 是时间戳', r.meta.at > 0);
    // 缓存命中（force=false 第二次 → 同一引用）
    const r2 = M.scanBlocks(path.join(os.tmpdir(), 'no-such-project-xyz'), {});
    ok('缓存命中同引用', r2 === r);
    const r3 = M.scanBlocks(path.join(os.tmpdir(), 'no-such-project-xyz'), { force: true });
    ok('force 刷新新对象', r3 !== r);
    M._resetCache();
}

console.log('\nmcBlocks 测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
