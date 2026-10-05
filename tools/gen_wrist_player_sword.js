#!/usr/bin/env node
/**
 * gen_wrist_player_sword.js
 *
 * 生成"手腕玩家模型（含剑）" player_wrist.json：
 *   - 基础 = 手腕玩家模型（分节手臂/腿，标准 64×64 玩家皮肤 UV，见 gen_wrist_player_model.js）。
 *   - 剑 = 纯剑物品模型 (sword.json) 的 main 子树（main/shade/blade/handle），
 *     经刚体变换 M' 嵌入，作为 rightItem（右手腕）的子节点。
 *   - 复合贴图 96×64：玩家皮肤 64×64 @ (0,0) + 剑贴图 32×32 @ (64,0)；
 *     剑 cube 的 face-UV u 整体 +64（模型 texture_width=96）。
 *
 * 方案B（叠加层）：
 *   - 身体/腿/头/左臂走基础动画 (base_state，STATE 组)。
 *   - 右臂 (rightArm/rightForeArm/rightItem+剑) 走独立挥剑图层 (MAIN 组)。
 *   - 剑作为 rightItem 子节点，随手腕（手臂动画）走 —— 满足"手臂单独动画图层"。
 *
 * 为什么剑必须嵌入玩家模型（而非独立物品）：
 *   PlayerRendererMixin 在玩家用 Spark 几何渲染时取消整个原版 render（含 PlayerItemInHandLayer
 *   渲染层），所以独立物品模型不会被渲染。老方案剑能显示 = 剑嵌在玩家模型里。
 *   第一人称仍走 sword.json 物品模型 + GeoItemRenderer（不变）。
 *
 * 变换 M'（文件 px，与 gen_player_sword_model.js 的 M 同旋转、改锚点）:
 *   A = [[-1,0,0],[0,0,1],[0,1,0]]（= 老 M 的旋转，剑朝向 = 原版持剑朝向，轴对齐 → cube 精确）
 *   b' = wrist − A·mainPivot，锚定 main 骨 pivot 到 rightItem（手腕 [-5,12,0]）。
 *   点 p' = A·p + b'；向量 v' = A·v；旋转 R' = A·R·A⁻¹（共轭）。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const PACK = (() => {
  const d = fs.readdirSync(ROOT + '/run/client/spark_modules').filter((x) => !x.endsWith('.zip'))[0];
  return ROOT + '/run/client/spark_modules/' + d;
})();
const SRC = ROOT + '/src/main/resources';

// MODID 从常量文件程序化读取（拼写易混淆，禁止手打）
const _constFile = (() => {
  function find(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name.startsWith('.git')) continue;
      const p = path.join(dir, e.name);
      if (e.isDirectory()) { const r = find(p); if (r) return r; }
      else if (e.name === 'CatoxidesBattleRebuildConstants.java') return p;
    }
    return null;
  }
  return find(ROOT + '/src/main/java');
})();
const NS = fs.readFileSync(_constFile, 'utf8').match(/MODID = "([^"]+)"/)[1];
console.log('MODID(NS):', NS, 'len', NS.length);

// ---------------------------------------------------------------- 矩阵工具
const V = (x, y, z) => [x, y, z];
function mMul(A, B) {
  const n = A.length, m = B[0].length, p = B.length;
  const out = Array.from({ length: n }, () => new Array(m).fill(0));
  for (let i = 0; i < n; i++) for (let j = 0; j < m; j++) for (let k = 0; k < p; k++)
    out[i][j] += A[i][k] * B[k][j];
  return out;
}
function mApply(A, p) {
  const x = p[0], y = p[1], z = p[2];
  return V(A[0][0]*x + A[0][1]*y + A[0][2]*z,
           A[1][0]*x + A[1][1]*y + A[1][2]*z,
           A[2][0]*x + A[2][1]*y + A[2][2]*z);
}
function mT(A) { return A[0].map((_, j) => A.map((r) => r[j])); }
function zyxDecomp(M) {
  const EPS = 1e-9;
  if (Math.abs(M[2][0]) < 1 - EPS) {
    const b = Math.asin(-M[2][0]);
    const a = Math.atan2(M[1][0], M[0][0]);
    const g = Math.atan2(M[2][1], M[2][2]);
    return [g, b, a];
  }
  if (M[2][0] < 0) { const a = Math.atan2(-M[0][1], M[0][2]); return [0, Math.PI / 2, a]; }
  const a = Math.atan2(-M[0][1], M[1][1]);
  return [0, -Math.PI / 2, a];
}
const rad = (d) => d * Math.PI / 180;
const deg = (r) => r * 180 / Math.PI;
function round3(v) {
  return v.map((x) => { const r = Math.round(x * 10000) / 10000; return Object.is(r, -0) ? 0 : r; });
}

// ---------------------------------------------------------------- 变换 M'（文件 px）
const A = [[-1, 0, 0], [0, 0, 1], [0, 1, 0]];
const wrist = [-5, 12, 0];                       // rightItem pivot（玩家模型文件 px）
const mainPivot = [-0.0768, 4.4667, -2.4497];    // sword main 骨 pivot（剑模型文件 px）
const B = V(wrist[0] - mApply(A, mainPivot)[0],
            wrist[1] - mApply(A, mainPivot)[1],
            wrist[2] - mApply(A, mainPivot)[2]);
console.log("\nM' 平移 b' =", B.map((x) => +x.toFixed(4)), "（锚 main pivot -> 手腕 [-5,12,0]）");
// det(A) 必须 +1
{
  const det = A[0][0]*(A[1][1]*A[2][2]-A[1][2]*A[2][1]) - A[0][1]*(A[1][0]*A[2][2]-A[1][2]*A[2][0]) + A[0][2]*(A[1][0]*A[2][1]-A[1][1]*A[2][0]);
  console.log('  det(A) =', det.toFixed(6), det > 0 ? '(+1 ✓)' : '(!! 必须 +1)');
  if (det <= 0) { console.error('自检失败: A 不是旋转'); process.exit(1); }
}
const Tpt = (p) => round3(V(mApply(A, p)[0] + B[0], mApply(A, p)[1] + B[1], mApply(A, p)[2] + B[2]));
const Tvec = (v) => round3(mApply(A, v));
// 旋转：文件欧拉 -> 文件欧拉（共轭 A·R·A⁻¹）
function fileRotToMat(e) {
  const [rx, ry, rz] = e;
  const cx = Math.cos(rad(-rx)), sx = Math.sin(rad(-rx));
  const cy = Math.cos(rad(-ry)), sy = Math.sin(rad(-ry));
  const cz = Math.cos(rad(rz)),  sz = Math.sin(rad(rz));
  const rotX = [[1,0,0],[0,cx,-sx],[0,sx,cx]];
  const rotY = [[cy,0,sy],[0,1,0],[-sy,0,cy]];
  const rotZ = [[cz,-sz,0],[sz,cz,0],[0,0,1]];
  return mMul(rotZ, mMul(rotY, rotX));
}
function Trot(e) {
  const R = fileRotToMat(e);
  const Rp = mMul(A, mMul(R, mT(A)));            // A·R·A⁻¹（A 是旋转，A⁻¹=Aᵀ）
  const [gx, gy, gz] = zyxDecomp(Rp);
  return round3([deg(-gx), deg(-gy), deg(gz)]);
}

// ---------------------------------------------------------------- 输入
function readJSON(p) { return JSON.parse(fs.readFileSync(p, 'utf8')); }
const swordItem = readJSON(PACK + '/models/' + NS + '/item/sword.json');
const swordBones = swordItem['minecraft:geometry'][0].bones;
const swordBoneMap = {};
for (const b of swordBones) swordBoneMap[b.name] = b;

// ---------------------------------------------------------------- 手腕玩家模型基础骨（= gen_wrist_player_model.js）
const F = (u, v, w, h) => ({ uv: [u, v], uv_size: [w, h] });
const UV_WAIST = {
  north: F(28, 20, -8, 12), south: F(40, 20, -8, 12),
  west: F(16, 20, 4, 12), east: F(28, 20, 4, 12),
  up: F(20, 16, 8, 4), down: F(28, 20, 8, -4),
};
const UV_HEAD = {
  north: F(16, 8, -8, 8), south: F(32, 8, -8, 8),
  west: F(0, 8, 8, 8), east: F(16, 8, 8, 8),
  up: F(8, 0, 8, 8), down: F(16, 8, 8, -8),
};
function limbSides(x, yTop, h) {
  return {
    west:  F(x,      yTop, 4, h),
    north: F(x + 8,  yTop, -4, h),
    east:  F(x + 8,  yTop, 4, h),
    south: F(x + 16, yTop, -4, h),
  };
}
function limbUpDown(x, ySideTop) {
  const yUpRow = ySideTop - 4;
  return { up: F(x + 4, yUpRow, 4, 4), down: F(x + 8, ySideTop, 4, -4) };
}
const UV = {
  rightArm:     Object.assign(limbSides(40, 20, 6),  limbUpDown(40, 20)),
  rightForeArm: Object.assign(limbSides(40, 26, 6),  { up: F(48, 20, 4, -4), down: F(48, 20, 4, -4) }),
  leftArm:      Object.assign(limbSides(32, 48, 6),  limbUpDown(32, 48)),
  leftForeArm:  Object.assign(limbSides(32, 54, 6),  { up: F(40, 48, 4, -4), down: F(40, 48, 4, -4) }),
  rightLeg:     Object.assign(limbSides(0, 20, 6),   limbUpDown(0, 20)),
  rightForeLeg: Object.assign(limbSides(0, 26, 6),   { up: F(8, 20, 4, -4), down: F(8, 20, 4, -4) }),
  leftLeg:      Object.assign(limbSides(16, 48, 6),  limbUpDown(16, 48)),
  leftForeLeg:  Object.assign(limbSides(16, 54, 6),  { up: F(24, 48, 4, -4), down: F(24, 48, 4, -4) }),
};
UV.waist = UV_WAIST;
UV.head = UV_HEAD;

function bone(name, parent, pivot, cubeOrigin, cubeSize, uvName, extra) {
  const b = { name };
  if (parent) b.parent = parent;
  b.pivot = pivot;
  if (cubeOrigin) {
    b.cubes = [{ origin: cubeOrigin, size: cubeSize, uv: JSON.parse(JSON.stringify(UV[uvName])) }];
  }
  if (extra) Object.assign(b, extra);
  return b;
}

const bones = [
  bone('root', null, [0, 0, 0]),
  bone('body', 'root', [0, 16, 0]),
  bone('waist', 'body', [0, 12, 0], [-4, 12, -2], [8, 12, 4], 'waist'),
  bone('head', 'waist', [0, 24, 0], [-4, 24, -4], [8, 8, 8], 'head'),
  bone('leftArm', 'waist', [5, 22, 0], [4, 18, -2], [4, 6, 4], 'leftArm'),
  bone('leftForeArm', 'leftArm', [5, 18, 0], [4, 12, -2], [4, 6, 4], 'leftForeArm'),
  bone('leftItem', 'leftForeArm', [5, 12, 0]),
  bone('rightArm', 'waist', [-5, 22, 0], [-8, 18, -2], [4, 6, 4], 'rightArm'),
  bone('rightForeArm', 'rightArm', [-5, 18, 0], [-8, 12, -2], [4, 6, 4], 'rightForeArm'),
  bone('rightItem', 'rightForeArm', [-5, 12, 0]),
  bone('leftLeg', 'body', [1.9, 12, 0], [0, 6, -2], [4, 6, 4], 'leftLeg'),
  bone('leftForeLeg', 'leftLeg', [1.9, 6, 0], [0, 0, -2], [4, 6, 4], 'leftForeLeg'),
  bone('leftFoot', 'leftForeLeg', [1.9, 0, 0]),
  bone('rightLeg', 'body', [-1.9, 12, 0], [-4, 6, -2], [4, 6, 4], 'rightLeg'),
  bone('rightForeLeg', 'rightLeg', [-1.9, 6, 0], [-4, 0, -2], [4, 6, 4], 'rightForeLeg'),
  bone('rightFoot', 'rightForeLeg', [-1.9, 0, 0]),
];

// ---------------------------------------------------------------- 嵌入剑（main 子树 -> rightItem 子级）
function transformSwordBone(srcBone, parentName) {
  const b = { name: srcBone.name, parent: parentName, pivot: Tpt(srcBone.pivot) };
  if (srcBone.cubes && srcBone.cubes.length) {
    b.cubes = srcBone.cubes.map((c) => {
      const out = { origin: Tpt(c.origin), size: Tvec(c.size) };
      if (c.uv) {
        out.uv = {};
        for (const [face, f] of Object.entries(c.uv)) {
          const nf = { uv: [f.uv[0] + 64, f.uv[1]] };   // u+64：合图里剑纹理在 (64,0)-(96,32)
          if (f.uv_size) nf.uv_size = [f.uv_size[0], f.uv_size[1]];
          if (f.uv_rotation !== undefined) nf.uv_rotation = f.uv_rotation;
          out.uv[face] = nf;
        }
      }
      if (c.pivot) out.pivot = Tpt(c.pivot);
      if (c.rotation) out.rotation = Trot(c.rotation);
      return out;
    });
  }
  return b;
}
for (const name of ['main', 'shade', 'blade', 'handle']) {
  const src = swordBoneMap[name];
  if (!src) { console.error('自检失败: sword 缺 ' + name); process.exit(1); }
  const parent = (name === 'main') ? 'rightItem' : src.parent;   // main 挂 rightItem；其余保持挂 main
  bones.push(transformSwordBone(src, parent));
}

const model = {
  format_version: '1.12.0',
  'minecraft:geometry': [{
    description: {
      identifier: 'geometry.player_wrist',
      texture_width: 96,      // 64(皮肤) + 32(剑)
      texture_height: 64,
      visible_bounds_width: 3,
      visible_bounds_height: 3.5,
      visible_bounds_offset: [0, 1.25, 0],
    },
    bones,
  }],
};

// ---------------------------------------------------------------- 写文件
function out(rel) {
  fs.mkdirSync(path.dirname(rel), { recursive: true });
  fs.writeFileSync(rel, JSON.stringify(model, null, 2) + '\n', 'utf8');
  console.log('  wrote', rel);
}
out(PACK + `/models/${NS}/entity/player_wrist.json`);
out(`${SRC}/spark_models/entity/${NS}/player_wrist.json`);

// ---------------------------------------------------------------- 自检
function bbox(f) {
  const [u, v] = f.uv, [w, h] = f.uv_size;
  return [Math.min(u, u + w), Math.min(v, v + h), Math.max(u, u + w), Math.max(v, v + h)];
}
let fail = 0;
const TW = 96, TH = 64;
// 1) 所有 cube 面 uv 不越出 96×64（复合贴图）
for (const b of bones) {
  if (!b.cubes) continue;
  for (const c of b.cubes) {
    if (!c.uv) continue;
    for (const face of Object.values(c.uv)) {
      const [x0, y0, x1, y1] = bbox(face);
      if (x0 < 0 || y0 < 0 || x1 > TW || y1 > TH) {
        console.error(`  [FAIL] ${b.name} 面越界 bbox=${JSON.stringify([x0, y0, x1, y1])}`);
        fail++;
      }
    }
  }
}
// 2) 剑 main 骨是 rightItem 子级，且 pivot 在手腕 [-5,12,0]（±0.01）
const mb = bones.find((b) => b.name === 'main');
if (!mb || mb.parent !== 'rightItem') { console.error('  [FAIL] main 缺失或父级非 rightItem'); fail++; }
else {
  const d = Math.hypot(mb.pivot[0] - (-5), mb.pivot[1] - 12, mb.pivot[2] - 0);
  if (d > 0.01) { console.error(`  [FAIL] main pivot 不在手腕: ${JSON.stringify(mb.pivot)} (d=${d.toFixed(4)})`); fail++; }
  else console.log(`  main pivot = ${JSON.stringify(mb.pivot)} ≈ 手腕 [-5,12,0] ✓`);
}
// 3) 剑子树 parent 链完整（shade/blade/handle 挂 main）
for (const n of ['shade', 'blade', 'handle']) {
  const b = bones.find((x) => x.name === n);
  if (!b || b.parent !== 'main') { console.error(`  [FAIL] ${n} 缺失或父级非 main`); fail++; }
}
// 4) 剑 cube 数 = 源 sword 的 main 子树 cube 数
const srcCubeCount = ['main', 'shade', 'blade', 'handle'].reduce((s, n) => s + (swordBoneMap[n].cubes ? swordBoneMap[n].cubes.length : 0), 0);
const dstCubeCount = ['main', 'shade', 'blade', 'handle'].reduce((s, n) => { const b = bones.find((x) => x.name === n); return s + (b.cubes ? b.cubes.length : 0); }, 0);
if (srcCubeCount !== dstCubeCount) { console.error(`  [FAIL] 剑 cube 数不符 src=${srcCubeCount} dst=${dstCubeCount}`); fail++; }
else console.log(`  剑 cube 数 = ${dstCubeCount} ✓`);

console.log(fail === 0 ? 'SELF-CHECK OK' : `SELF-CHECK FAILED (${fail})`);
process.exit(fail === 0 ? 0 : 1);
