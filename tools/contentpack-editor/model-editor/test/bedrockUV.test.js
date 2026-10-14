// bedrockUV.test.js - Bedrock UV 布局（W2 渲染核心）
// 三层验证：
//   1. 手算黄金值（zombie_3 标准皮肤区域：顶(20,16) 前(20,20) 右(28,20) 背(32,20)）
//   2. 差分对比：Blockbench 参照实现 face_list 原样抄录（独立转写）× 3 模型全部 cube
//   3. 顶点 UV 角点映射 / uv_rotation / up-down 交换 / mirror / 顶点表法线朝向
const assert = require('assert');
const path = require('path');
const fs = require('fs');

const UV = require('../core/bedrockUV');
const Codec = require('../core/sparkCodec');

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
const rect = (f, r) => `${f} [${r[0]},${r[1]} ${r[2]}×${r[3]}]`;

// Blockbench face_list 原样抄录（js/outliner/types/cube.js updateUV L1306-1313 + mirror L1315-1333）
// —— 独立转写，用作差分 oracle
function bbFaceRects(cube) {
    const size = cube.size;
    const sx = size[0], sy = size[1], sz = size[2];
    let face_list;
    if (Array.isArray(cube.uv)) {
        face_list = [
            { face: 'east', from: [0, sz], size: [sz, sy] },
            { face: 'west', from: [sz + sx, sz], size: [sz, sy] },
            { face: 'up', from: [sz + sx, sz], size: [-sx, -sz] },
            { face: 'down', from: [sz + sx * 2, 0], size: [-sx, sz] },
            { face: 'south', from: [sz * 2 + sx, sz], size: [sx, sy] },
            { face: 'north', from: [sz, sz], size: [sx, sy] },
        ];
        if (cube.mirror) {
            face_list.forEach((f) => {
                f.from[0] += f.size[0];
                f.size[0] *= -1;
            });
            let p = {};
            p.from = face_list[0].from.slice();
            p.size = face_list[0].size.slice();
            face_list[0].from = face_list[1].from.slice();
            face_list[0].size = face_list[1].size.slice();
            face_list[1].from = p.from.slice();
            face_list[1].size = p.size.slice();
        }
        const out = {};
        for (const f of face_list) {
            out[f.face] = [f.from[0] + cube.uv[0], f.from[1] + cube.uv[1], f.size[0], f.size[1]];
        }
        return out;
    }
    const out = {};
    for (const f of UV.FACES) {
        const face = cube.uv[f];
        if (!face) { out[f] = null; continue; }
        const w = face.uv_size ? face.uv_size[0] : 0;
        const h = face.uv_size ? face.uv_size[1] : 0;
        out[f] = [face.uv[0], face.uv[1], w, h];
    }
    return out;
}

const ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const MODELS = [
    path.join(ROOT, 'contentpacks-source', 'modular-zombie-3', 'spark_models', 'entity', 'zombie3pack', 'modular_zombie_3.json'),
    path.join(ROOT, 'contentpacks-source', 'melee-combat-pack', 'spark_models', 'item', 'catoxidesbattlerebuild', 'sword.json'),
    path.join(ROOT, 'contentpacks-source', 'melee-combat-pack', 'spark_models', 'entity', 'catoxidesbattlerebuild', 'player_wrist.json'),
];

console.log('=== 1. 手算黄金值（zombie_3 标准皮肤区域） ===');
const body = { size: [8, 12, 4], uv: [16, 16], mirror: false };
test('躯干：顶(20,16) 前(20,20) 右(28,20) 背(32,20) 底(28,16) 左(16,20)（up/down 为 Blockbench from/size 角点反序表示）', () => {
    const r = UV.faceRects(body);
    assert.deepStrictEqual(r.up, [28, 20, -8, -4]);   // 范围 u[20,28] v[16,20]
    assert.deepStrictEqual(r.north, [20, 20, 8, 12]);
    assert.deepStrictEqual(r.west, [28, 20, 4, 12]);
    assert.deepStrictEqual(r.south, [32, 20, 8, 12]);
    assert.deepStrictEqual(r.down, [36, 16, -8, 4]);  // 范围 u[28,36] v[16,20]
    assert.deepStrictEqual(r.east, [16, 20, 4, 12]);
});
const head = { size: [8, 8, 8], uv: [0, 0], mirror: false };
test('头（8×8×8 @ (0,0)）：前(8,8) 上(8,0) 左(0,8) 右(16,8) 背(24,8) 下(16,0)', () => {
    const r = UV.faceRects(head);
    assert.deepStrictEqual(r.north, [8, 8, 8, 8]);
    assert.deepStrictEqual(r.up, [16, 8, -8, -8]);    // 范围 u[8,16] v[0,8]
    assert.deepStrictEqual(r.east, [0, 8, 8, 8]);
    assert.deepStrictEqual(r.west, [16, 8, 8, 8]);
    assert.deepStrictEqual(r.south, [24, 8, 8, 8]);
    assert.deepStrictEqual(r.down, [24, 0, -8, 8]);   // 范围 u[16,24] v[0,8]
});
test('mirror：左右臂同 uv，前/背面角点反转 + 东西面交换', () => {
    const arm = { size: [4, 12, 4], uv: [40, 16], mirror: false };
    const armM = { size: [4, 12, 4], uv: [40, 16], mirror: true };
    const r = UV.faceRects(arm), m = UV.faceRects(armM);
    // 前/背面：范围不变，角点反转（w 变负）
    assert.deepStrictEqual(m.north, [48, 20, -4, 12]);
    assert.deepStrictEqual(m.south, [56, 20, -4, 12]);
    assert.deepStrictEqual(m.up, [44, 20, 4, -4]);    // 只翻 u 维（Blockbench 只动 size[0]）
    // east ← 原 west 矩形（翻转），west ← 原 east 矩形（翻转）
    assert.deepStrictEqual(r.west, [48, 20, 4, 12]);
    assert.deepStrictEqual(m.east, [52, 20, -4, 12]);
    assert.deepStrictEqual(r.east, [40, 20, 4, 12]);
    assert.deepStrictEqual(m.west, [44, 20, -4, 12]);
});
test('per-face（sword shade north）：{uv:[0,0], uv_size:[13.8,10]} → (0,0) 13.8×10', () => {
    const cube = {
        size: [1, 1, 1],
        uv: { north: { uv: [0, 0], uv_size: [13.8, 10] } },
        mirror: false,
    };
    const r = UV.faceRects(cube);
    assert.deepStrictEqual(r.north, [0, 0, 13.8, 10]);
    assert.strictEqual(r.east, null);
});

console.log('=== 2. 差分对比：Blockbench face_list 原样抄录 oracle × 全部真实 cube ===');
let compared = 0;
for (const f of MODELS) {
    const g = Codec.importModel(fs.readFileSync(f, 'utf-8')).data;
    for (const b of g.bones) {
        for (const c of b.cubes) {
            const cube = { size: c.size.slice(), uv: c.uv, mirror: b.mirror || false };
            test(`${path.basename(f)} ${b.name}: 与 Blockbench oracle 一致`, () => {
                const mine = UV.faceRects(cube);
                const ref = bbFaceRects(cube);
                for (const face of UV.FACES) {
                    const a = mine[face], q = ref[face];
                    if (a == null || q == null) {
                        assert.strictEqual(a == null, q == null, `${face} 空面不一致`);
                    } else {
                        assert.deepStrictEqual(a, q, `${face}: mine ${rect(face, a)} vs ref ${rect(face, q)}`);
                    }
                }
                compared++;
            });
        }
    }
}
test(`共对比 ${compared} 个 cube（3 模型全量）`, () => {
    assert.ok(compared >= 20, `只有 ${compared} 个`);
});

console.log('=== 3. 顶点 UV 角点映射 / rotation / up-down 交换 ===');
test('躯干 north 面：4 顶点 UV = (20,20)(28,20)(20,32)(28,32)/64（v 向上翻转）', () => {
    const uvArr = UV.cubeFaceUVs(body, 64, 64);
    const i = UV.FACES.indexOf('north') * 8;
    assert.strictEqual(uvArr[i + 0], 20 / 64);
    assert.strictEqual(uvArr[i + 1], 1 - 20 / 64);
    assert.strictEqual(uvArr[i + 2], 28 / 64);
    assert.strictEqual(uvArr[i + 3], 1 - 20 / 64);
    assert.strictEqual(uvArr[i + 4], 20 / 64);
    assert.strictEqual(uvArr[i + 5], 1 - 32 / 64);
    assert.strictEqual(uvArr[i + 6], 28 / 64);
    assert.strictEqual(uvArr[i + 7], 1 - 32 / 64);
});
test('uv_rotation=90：角点循环 [2,0,3,1]', () => {
    const cube = { size: [1, 1, 1], uv: { north: { uv: [0, 0], uv_size: [4, 4], uv_rotation: 90 } }, mirror: false };
    const uvArr = UV.cubeFaceUVs(cube, 64, 64);
    const i = UV.FACES.indexOf('north') * 8;
    // 未旋转角点 A,B,C,D = [(0,1),(4/64,1),(0,1-4/64),(4/64,1-4/64)]
    // 90° 循环 [C,A,D,B] → v0=C=(0,1-4/64), v1=A=(0,1), v2=D, v3=B
    assert.strictEqual(uvArr[i + 0], 0);
    assert.strictEqual(uvArr[i + 1], 1 - 4 / 64);
    assert.strictEqual(uvArr[i + 2], 0);
    assert.strictEqual(uvArr[i + 3], 1);
});
test('per-face up 面：180° 角点交换生效', () => {
    const cube = { size: [1, 1, 1], uv: { up: { uv: [0, 0], uv_size: [4, 4] } }, mirror: false };
    const uvArr = UV.cubeFaceUVs(cube, 64, 64);
    const i = UV.FACES.indexOf('up') * 8;
    // 交换后 (u0,v0)=(4,4), (u1,v1)=(0,0)：
    // v0 = (u0, 1-v0) = (4/64, 1-4/64)；v2 = (u0, 1-v1) = (4/64, 1)
    assert.strictEqual(uvArr[i + 0], 4 / 64);
    assert.strictEqual(uvArr[i + 1], 1 - 4 / 64);
    assert.strictEqual(uvArr[i + 4], 4 / 64);
    assert.strictEqual(uvArr[i + 5], 1);
});
test('mirror 的 north 面：UV u 轴反转', () => {
    const arm = { size: [4, 12, 4], uv: [40, 16], mirror: false };
    const armM = { size: [4, 12, 4], uv: [40, 16], mirror: true };
    const a = UV.cubeFaceUVs(arm, 64, 64);
    const m = UV.cubeFaceUVs(armM, 64, 64);
    const i = UV.FACES.indexOf('north') * 8;
    // 未 mirror：v0.u = 44/64；mirror 后角点反转：v0.u = 48/64
    assert.strictEqual(a[i + 0], 44 / 64);
    assert.strictEqual(m[i + 0], 48 / 64);
    assert.strictEqual(m[i + 2], 44 / 64);
});

console.log('=== 4. 顶点表：外朝向法线 ===');
test('6 面法线全部朝外（from(0,0,0) to(2,3,4)）', () => {
    const pos = UV.cubeVertexPositions(0, 0, 0, 2, 3, 4);
    const idx = UV.cubeIndices();
    const normals = [];
    for (let f = 0; f < 6; f++) {
        const b = f * 4;
        const p = (k) => [pos[(b + k) * 3], pos[(b + k) * 3 + 1], pos[(b + k) * 3 + 2]];
        const [ax, ay, az] = p(2); const [bx, by, bz] = p(1); const [cx, cy, cz] = p(0);
        // 三角形 (v0,v2,v1)：法线 = (v2-v0) × (v1-v0)
        const ux = ax - cx, uy = ay - cy, uz = az - cz;
        const vx = bx - cx, vy = by - cy, vz = bz - cz;
        normals.push([uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx]);
    }
    const expect = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
    UV.FACES.forEach((f, i) => {
        const n = normals[i];
        const len = Math.hypot(n[0], n[1], n[2]);
        // +0 归一 -0（JS 浮点 (-3)*0 = -0）
        assert.deepStrictEqual([n[0] / len, n[1] / len, n[2] / len].map((v) => Math.round(v) + 0), expect[i], `${f} 法线`);
    });
    // 索引引用范围
    assert.ok(idx.every((k) => k >= 0 && k < 24));
    assert.strictEqual(idx.length, 36);
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
