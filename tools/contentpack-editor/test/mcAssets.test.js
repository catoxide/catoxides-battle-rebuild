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

console.log('\nmcAssets 测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
