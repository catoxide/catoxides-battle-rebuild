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

console.log('=== 7. blockAppearance 外观烘焙（vanilla 标准链路，对照游戏反编译源码）===');
{
    const I = M._internals;
    I._resetAppearanceCache();
    // 数据集在仓库外：D:\Software\MC develop pack\数据集（test → 上 5 级 = MC develop pack）
    const DS = path.resolve(__dirname, '..', '..', '..', '..', '..', '数据集', '1.21.1', 'client');
    if (!fs.existsSync(path.join(DS, 'assets', 'minecraft', 'blockstates'))) {
        console.log('  （数据集不存在，跳过外观测试）');
    } else {
        {
            M.setRootOverride(DS); // 锁定 vanilla 根
            const approx = (a, b, e = 1e-5) => Math.abs(a - b) <= e;
            const bbox = (faces) => {
                let x0 = 9, x1 = -9, y0 = 9, y1 = -9, z0 = 9, z1 = -9;
                for (const f of faces) for (let i = 0; i < 12; i += 3) {
                    x0 = Math.min(x0, f.pos[i]); x1 = Math.max(x1, f.pos[i]);
                    y0 = Math.min(y0, f.pos[i + 1]); y1 = Math.max(y1, f.pos[i + 1]);
                    z0 = Math.min(z0, f.pos[i + 2]); z1 = Math.max(z1, f.pos[i + 2]);
                }
                return { x0, x1, y0, y1, z0, z1 };
            };
            const faceNormal = (f) => {
                const [ax, ay, az] = [f.pos[3] - f.pos[0], f.pos[4] - f.pos[1], f.pos[5] - f.pos[2]];
                const [bx, by, bz] = [f.pos[6] - f.pos[3], f.pos[7] - f.pos[4], f.pos[8] - f.pos[5]];
                return [ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx];
            };
            const inBox = (faces, e = 1e-5) => {
                const b = bbox(faces);
                return b.x0 >= -e && b.x1 <= 1 + e && b.y0 >= -e && b.y1 <= 1 + e && b.z0 >= -e && b.z1 <= 1 + e;
            };

            // 玻璃（cube_all 全立方体，无属性）
            let r = M.blockAppearance('/nonexistent', 'minecraft:glass');
            ok('玻璃：6 面烘焙成功', r.ok && r.data.faces.length === 6, JSON.stringify(r.ok ? r.data.faces.length : r.error));
            if (r.ok) {
                ok('玻璃：包围盒 = 单位立方体', inBox(r.data.faces), JSON.stringify(bbox(r.data.faces)));
                const up = r.data.faces[1]; // cube 面序 down,up,north,south,west,east
                ok('玻璃：up 面法线朝 +Y（winding 正确）', (() => { const n = faceNormal(up); return n[1] > 0.9; })(), JSON.stringify(faceNormal(up)));
                const south = r.data.faces[3];
                ok('玻璃：south 面法线朝 +Z', faceNormal(south)[2] > 0.9, JSON.stringify(faceNormal(south)));
                ok('玻璃：贴图解析为 dataURL', r.data.textures['minecraft:block/glass'] && /^data:image\/png;base64,/.test(r.data.textures['minecraft:block/glass']),
                    Object.keys(r.data.textures).join(','));
                ok('玻璃：uv 全部在 [0,1]', r.data.faces.every((f) => f.uv.every((v) => v >= -1e-6 && v <= 1 + 1e-6)),
                    JSON.stringify(r.data.faces[0].uv));
                // P14 修正：UV 角点分配（BlockFaceUV.getU/getV 精确对照）——
                // 角点序 [c0,c1,c2,c3]，uv 数组 [u1,v1,u2,v2]，corner2=(u2,v2)、corner3=(u2,v1)
                // 玻璃 south 面：c0=(0,1,1) 应得贴图左上(three (0,1))；c2=(1,0,1) 应得右下(three (1,0))
                const uvOfVertex = (f, p) => {
                    for (let i = 0; i < 4; i++) {
                        const px = f.pos[i * 3], py = f.pos[i * 3 + 1], pz = f.pos[i * 3 + 2];
                        if (Math.abs(px - p[0]) < 1e-4 && Math.abs(py - p[1]) < 1e-4 && Math.abs(pz - p[2]) < 1e-4)
                            return [f.uv[i * 2], f.uv[i * 2 + 1]];
                    }
                    return null;
                };
                const sv0 = uvOfVertex(south, [0, 1, 1]);
                const sv2 = uvOfVertex(south, [1, 0, 1]);
                ok('玻璃 south：角点分配正确（c0=(0,1,1)→贴图左上(0,1)；c2=(1,0,1)→右下(1,0)）',
                    sv0 && sv2 && approx(sv0[0], 0, 1e-4) && approx(sv0[1], 1, 1e-4) && approx(sv2[0], 1, 1e-4) && approx(sv2[1], 0, 1e-4),
                    JSON.stringify({ sv0, sv2 }));
                const uv0 = uvOfVertex(up, [0, 1, 0]);
                const uv3 = uvOfVertex(up, [1, 1, 0]);
                ok('玻璃 up：顶边两角贴图 v=1（图像顶部在方块顶面）',
                    uv0 && uv3 && approx(uv0[1], 1, 1e-4) && approx(uv3[1], 1, 1e-4), JSON.stringify({ uv0, uv3 }));
            }

            // 楼梯（裸 ID → 默认 facing=north；凸起半应在北侧 z∈[0,0.5]）
            r = M.blockAppearance('/nonexistent', 'minecraft:oak_stairs');
            ok('楼梯（默认）：variant = facing=north 直形', r.ok && r.data.variantKey === 'facing=north,half=bottom,shape=straight',
                JSON.stringify(r.ok ? r.data.variantKey : r.error));
            if (r.ok) {
                ok('楼梯（默认）：包围盒在单位立方体内（y:270 旋转后）', inBox(r.data.faces));
                let northTop = -1, southTop = -1;
                for (const f of r.data.faces) for (let i = 0; i < 12; i += 3) {
                    if (f.pos[i + 2] <= 0.5 + 1e-4) northTop = Math.max(northTop, f.pos[i + 1]);
                    else southTop = Math.max(southTop, f.pos[i + 1]);
                }
                ok('楼梯（默认 north）：凸起半在北侧（z<0.5 处 y 到 1，南侧 ≤0.5）',
                    approx(northTop, 1, 1e-4) && southTop <= 0.5 + 1e-4, JSON.stringify({ northTop, southTop }));
                // uvlock 后 uv 仍是合法矩形（uv 为逐角序 [u0,v0,u1,v1,u2,v2,u3,v3]）
                ok('楼梯（uvlock）：所有面 uv 为合法矩形', r.data.faces.every((f) => {
                    const us = [f.uv[0], f.uv[2], f.uv[4], f.uv[6]];
                    const vs = [f.uv[1], f.uv[3], f.uv[5], f.uv[7]];
                    return Math.max(...us) - Math.min(...us) > 1e-4 && Math.max(...vs) - Math.min(...vs) > 1e-4
                        && new Set(us.map((v) => +v.toFixed(4))).size === 2
                        && new Set(vs.map((v) => +v.toFixed(4))).size === 2;
                }), JSON.stringify(r.data.faces[2].uv));
                ok('楼梯：贴图（oak_planks）解析', !!r.data.textures['minecraft:block/oak_planks'], Object.keys(r.data.textures).join(','));
                // 非方形 UV 的竖直性（uvlock 后，模型无关断言）：竖面上贴图顶部必须对齐方块顶边
                // （MC 默认/楼梯 uv 均保证竖面纹理不上下翻转；bug 的 corner2/3 V 交换会破坏这一点）
                const stVert = r.data.faces.find((f) => {
                    const ys = [], zs = new Set();
                    for (let i = 0; i < 12; i += 3) { ys.push(f.pos[i + 1]); zs.add(+f.pos[i + 2].toFixed(4)); }
                    return zs.size === 1 && Math.abs([...zs][0]) < 1e-4 && Math.max(...ys) - Math.min(...ys) > 0.3;
                });
                if (stVert) {
                    const corners = [];
                    for (let i = 0; i < 4; i++) corners.push([stVert.pos[i * 3 + 1], stVert.uv[i * 2 + 1]]);
                    const maxY = Math.max(...corners.map((c) => c[0]));
                    const minY = Math.min(...corners.map((c) => c[0]));
                    const vAtTop = Math.max(...corners.filter((c) => Math.abs(c[0] - maxY) < 1e-4).map((c) => c[1]));
                    const vAtBot = Math.min(...corners.filter((c) => Math.abs(c[0] - minY) < 1e-4).map((c) => c[1]));
                    ok('楼梯 north 竖面：贴图竖直（顶边 v 高 > 底边 v 低，未上下翻转）', vAtTop > vAtBot + 0.1,
                        JSON.stringify({ vAtTop, vAtBot }));
                } else {
                    ok('楼梯 north 竖面：找到 z=0 平面竖面', false, '未找到 z=0 平面竖面');
                }
            }

            // 楼梯 facing=east（显式属性 → 严格匹配，无旋转 → 凸起半在东侧 x∈[0.5,1]）
            r = M.blockAppearance('/nonexistent', 'minecraft:oak_stairs[facing=east,half=bottom,shape=straight]');
            if (r.ok) {
                let eastTop = -1, westTop = -1;
                for (const f of r.data.faces) for (let i = 0; i < 12; i += 3) {
                    if (f.pos[i] >= 0.5 - 1e-4) eastTop = Math.max(eastTop, f.pos[i + 1]); // 竖面在 x=0.5 平面，属东侧凸起
                    else westTop = Math.max(westTop, f.pos[i + 1]);
                }
                ok('楼梯（facing=east）：凸起半在东侧', approx(eastTop, 1, 1e-4) && westTop <= 0.5 + 1e-4,
                    JSON.stringify({ eastTop, westTop }));
            } else ok('楼梯（facing=east）：烘焙成功', false, JSON.stringify(r.error));

            // 门（3/16 宽板；开门 y:90 绕中心旋转）
            r = M.blockAppearance('/nonexistent', 'minecraft:iron_door[facing=east,half=lower,hinge=left,open=false]');
            ok('门（关）：variant 命中 iron_door_bottom_left', r.ok && /iron_door_bottom_left$/.test(r.data.model),
                JSON.stringify(r.ok ? r.data.model : r.error));
            if (r.ok) {
                const b = bbox(r.data.faces);
                ok('门（关）：板宽 3/16（x∈[0,0.1875]）', approx(b.x0, 0, 1e-4) && approx(b.x1, 3 / 16, 1e-4) && approx(b.y1, 1, 1e-4),
                    JSON.stringify(b));
            }
            r = M.blockAppearance('/nonexistent', 'minecraft:iron_door[facing=east,half=lower,hinge=left,open=true]');
            if (r.ok) {
                const b = bbox(r.data.faces);
                ok('门（开，y:90）：板转到 z∈[0,0.1875]，x 铺满', inBox(r.data.faces) && approx(b.z0, 0, 1e-4) && approx(b.z1, 3 / 16, 1e-4) && approx(b.x0, 0, 1e-4) && approx(b.x1, 1, 1e-4),
                    JSON.stringify(b));
            } else ok('门（开）：烘焙成功', false, JSON.stringify(r.error));

            // 半砖（type=top → 凸起半在下半）
            r = M.blockAppearance('/nonexistent', 'minecraft:oak_slab[type=top]');
            if (r.ok) {
                let lowTop = -1, highBottom = 2;
                for (const f of r.data.faces) for (let i = 0; i < 12; i += 3) {
                    const y = f.pos[i + 1];
                    if (y <= 0.5 + 1e-4) lowTop = Math.max(lowTop, y);
                    else highBottom = Math.min(highBottom, y);
                }
                ok('半砖（type=top）：几何占下半（y∈[0,0.5]）', approx(lowTop, 0.5, 1e-4) && highBottom >= 0.5 - 1e-4,
                    JSON.stringify({ lowTop, highBottom }));
            } else ok('半砖：烘焙成功', false, JSON.stringify(r.error));

            // 无 blockstate 的方块 → 失败（渲染端回退简单立方体）
            r = M.blockAppearance('/nonexistent', 'minecraft:no_such_block_xyz');
            ok('未知方块：ok=false（渲染端回退）', r.ok === false, JSON.stringify(r));

            // 缓存：二次调用同对象
            const a = M.blockAppearance('/nonexistent', 'minecraft:glass');
            ok('外观缓存：同 ID 返回同对象', a === M.blockAppearance('/nonexistent', 'minecraft:glass'));
        }
    }
}

console.log('=== 7. blockIcon：槽位缓存栏图标（物品纹理优先 → 面贴图回退）===');
{
    const root = mkRoot();
    const png1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
    const png2 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
    // ① 只有面贴图（无物品纹理）→ from=face
    fs.mkdirSync(path.join(root, 'assets', 'minecraft', 'models', 'block'), { recursive: true });
    fs.mkdirSync(path.join(root, 'assets', 'minecraft', 'textures', 'block'), { recursive: true });
    writeBs(root, 'minecraft', 'face_only', { variants: { model: 'minecraft:block/face_only' } });
    fs.writeFileSync(path.join(root, 'assets', 'minecraft', 'models', 'block', 'face_only.json'),
        JSON.stringify({ parent: 'minecraft:block/cube_all', textures: { all: 'minecraft:block/face_only_tex' } }));
    fs.writeFileSync(path.join(root, 'assets', 'minecraft', 'textures', 'block', 'face_only_tex.png'), png1);
    const fi = M.blockIcon(root, 'minecraft:face_only');
    ok('无物品纹理 → 面贴图回退（from=face）', fi && fi.from === 'face' && fi.icon && fi.icon.startsWith('data:image/png;base64,'), JSON.stringify({ from: fi.from, hasIcon: !!fi.icon }));
    ok('[props] 串同图标', JSON.stringify(M.blockIcon(root, 'minecraft:face_only[axis=y]')) === JSON.stringify(fi));
    // ② 物品纹理存在 → 优先（from=item；独立方块名，避免 ① 的缓存干扰）
    fs.mkdirSync(path.join(root, 'assets', 'minecraft', 'items'), { recursive: true });
    writeBs(root, 'minecraft', 'item_first', { variants: { model: 'minecraft:block/item_first' } });
    fs.writeFileSync(path.join(root, 'assets', 'minecraft', 'models', 'block', 'item_first.json'),
        JSON.stringify({ parent: 'minecraft:block/cube_all', textures: { all: 'minecraft:block/face_only_tex' } }));
    fs.writeFileSync(path.join(root, 'assets', 'minecraft', 'items', 'item_first.png'), png2);
    const ii = M.blockIcon(root, 'minecraft:item_first');
    ok('物品纹理优先（from=item）', ii && ii.from === 'item' && !!ii.icon, JSON.stringify({ from: ii.from, hasIcon: !!ii.icon }));
    ok('item 串 [props] 同图标', JSON.stringify(M.blockIcon(root, 'minecraft:item_first[facing=north]')) === JSON.stringify(ii));
    // ③ 都没有 → icon=null
    writeBs(root, 'minecraft', 'nothing_here', { variants: { model: 'minecraft:block/nothing_here' } });
    const ni = M.blockIcon(root, 'minecraft:nothing_here');
    ok('全无贴图 → icon=null', ni && ni.icon === null && ni.from === null, JSON.stringify(ni));
    // ④ 缓存：同 ID 同对象
    ok('图标缓存：同 ID 同对象', M.blockIcon(root, 'minecraft:item_first') === ii);
    fs.rmSync(root, { recursive: true, force: true });
}

console.log('\nmcAssets 测试: ' + pass + ' 通过, ' + fail + ' 失败');
process.exit(fail ? 1 : 0);
