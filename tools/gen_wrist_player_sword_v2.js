#!/usr/bin/env node
/**
 * gen_wrist_player_sword_v2.js
 *
 * 生成"手腕玩家模型（含剑）" player_wrist.json（v2）：
 *   - 基础 = 手腕玩家模型（分节手臂/腿，标准 64×64 玩家皮肤 UV，见 gen_wrist_player_model.js）。
 *   - 剑 = D:\Download\sword.geo.json 的完整骨树（hands/left/right/main/shade/blade/handle），
 *     经刚体变换 M' = A·p + B 嵌入 rightItem（手腕）：
 *       · A = Rx(90°)：把剑身从"竖直朝上"转到"水平朝前"（剑尖由朝下变朝前，修 3rd_still 下尖/在身后）。
 *       · 锚点 = main 骨 pivot（3rd_still 动画是相对 main 摆剑：main 位置 [-6,-2,12.3]），
 *         B = wrist − A·mainPivot，使 A·mainPivot 恰好落在手腕 [-5,12,0]。
 *     几何变换：cube origin/pivot 用 Tpt=A·p+B；size 用 Tsize（Y/Z 交换）；
 *     面 UV 重映射（north↔down, south↔up, up↔north, down↔south, west/east 不变）；u +64。
 *   - 去掉 left/right 骨的手臂 cube（只要骨，供动画驱动；不要手臂方块）。
 *   - 复合贴图 96×64：玩家皮肤 64×64 @ (0,0) + 剑贴图 32×32 @ (64,0)；剑 cube face-UV u +64。
 *
 * 动画（方案B 叠加层）：
 *   - 身体/腿/头/左臂走基础动画 (base_state，STATE 组)。
 *   - 剑（hands/left/right/main/shade/blade/handle）走用户的剑物品动画
 *     (3rd_still / 3rd_attack，MAIN 组) —— 直接复用，不猜角度。
 *
 * 为什么剑必须嵌入玩家模型（而非独立物品）：
 *   PlayerRendererMixin 在玩家用 Spark 几何渲染时取消整个原版 render（含 PlayerItemInHandLayer
 *   渲染层），所以独立物品模型不会被渲染。老方案剑能显示 = 剑嵌在玩家模型里。
 *   第一人称仍走 sword.json 物品模型 + GeoItemRenderer（不变）。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const SWORD_GEO = 'D:/Download/sword.geo.json';
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

// ---------------------------------------------------------------- 嵌入 M' = A · p + B
// A = 绕 X 轴 90°（把剑身从"竖着"转到"横着朝前"，剑尖由朝下变朝前）
// 锚点 = main 骨 pivot（3rd_still 动画是相对 main 摆剑的：main 位置 [-6,-2,12.3]）
const wrist = [-5, 12, 0];                    // rightItem pivot（玩家模型文件 px）
const mainPivot = [-0.07679, 4.46667, -2.44971]; // sword.geo.json 的 main 骨 pivot（剑模型文件 px）
const A = [[1, 0, 0], [0, 0, -1], [0, 1, 0]];    // Rx(90°)
const mulA = (v) => [
  A[0][0]*v[0] + A[0][1]*v[1] + A[0][2]*v[2],
  A[1][0]*v[0] + A[1][1]*v[1] + A[1][2]*v[2],
  A[2][0]*v[0] + A[2][1]*v[1] + A[2][2]*v[2],
];
const aMain = mulA(mainPivot);
const B = [wrist[0] - aMain[0], wrist[1] - aMain[1], wrist[2] - aMain[2]];
console.log("\nA = Rx(90°) = " + JSON.stringify(A));
console.log("锚 main pivot", JSON.stringify(mainPivot), " -> A·main =", JSON.stringify(aMain));
console.log("平移 B =", JSON.stringify(B), "（A·main 移到 手腕 [-5,12,0]）");
const Tpt = (p) => { const q = mulA(p); return [+(q[0]+B[0]).toFixed(4), +(q[1]+B[1]).toFixed(4), +(q[2]+B[2]).toFixed(4)]; };
// cube size：A=Rx(90°) 把 [sx,sy,sz] -> [sx, sz, sy]（Y/Z 交换，取正）
const Tsize = (v) => [+(v[0]).toFixed(4), +(v[2]).toFixed(4), +(v[1]).toFixed(4)];
// cube 自带 rotation：R' = A·R·A⁻¹（共轭变换，把局部旋转转到新坐标系）。
// A = Rx(90°)。剑里多个 cube 带 Rz(θ) 旋转（blade 45°、handle -80°/80°/-45°），
// 共轭 Rx(90)·Rz(θ)·Rx(-90) = Ry(−θ)（轴 Z→Y、符号翻转）。原样复制会让剑碎。
const DEG = Math.PI / 180;
function eulToMat(e) {
  const [x, y, z] = e.map((d) => d * DEG);
  const cx = Math.cos(x), sx = Math.sin(x), cy = Math.cos(y), sy = Math.sin(y), cz = Math.cos(z), sz = Math.sin(z);
  // 应用顺序 Z→Y→X（MC/Bedrock 约定），矩阵 = Rx·Ry·Rz
  return [
    [cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx],
    [sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx],
    [-sy, cy * sx, cy * cx],
  ];
}
function matToEul(m) {
  const sy = -m[2][0];
  let y, x, z;
  if (Math.abs(sy) > 0.99999) {
    y = sy > 0 ? Math.PI / 2 : -Math.PI / 2;
    x = 0;
    z = Math.atan2(-m[0][1], m[1][1]);
  } else {
    y = Math.asin(sy);
    x = Math.atan2(m[2][1], m[2][2]);
    z = Math.atan2(m[1][0], m[0][0]);
  }
  return [+(x / DEG).toFixed(4), +(y / DEG).toFixed(4), +(z / DEG).toFixed(4)];
}
function matMul(a, b) {
  const r = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) for (let k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
  return r;
}
function matInvRx90() { return [[1, 0, 0], [0, 0, 1], [0, -1, 0]]; } // Rx(-90°)
const Trot = (e) => matToEul(matMul(matMul(A, eulToMat(e)), matInvRx90()));
// 面 UV 重映射：A=Rx(90°) 把面法线 -Z->+Y, +Z->-Y, +Y->+Z, -Y->-Z, ±X 不变。
// 故 new.north=old.down, new.south=old.up, new.up=old.north, new.down=old.south, west/east 不变。
const FACE_MAP = { north: 'down', south: 'up', up: 'north', down: 'south', west: 'west', east: 'east' };

// ---------------------------------------------------------------- 输入
function readJSON(p) { return JSON.parse(fs.readFileSync(p, 'utf8')); }
const swordGeo = readJSON(SWORD_GEO);
const swordBones = swordGeo['minecraft:geometry'][0].bones;
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

// ---------------------------------------------------------------- 嵌入剑（完整骨树 -> rightItem 子级，纯平移）
// 只保留剑的骨（不要手臂 cube）：hands/left/right/main/shade/blade/handle
const SWORD_BONES = ['hands', 'left', 'right', 'main', 'shade', 'blade', 'handle'];
// left/right 骨只保留骨（去手臂 cube）；其余保留 cube
const STRIP_CUBES = new Set(['left', 'right']);
function transformSwordBone(srcBone, parentName) {
  const b = { name: srcBone.name, parent: parentName, pivot: Tpt(srcBone.pivot) };
  if (STRIP_CUBES.has(srcBone.name)) {
    b.cubes = []; // 去手臂 cube，只留骨供动画驱动
  } else if (srcBone.cubes && srcBone.cubes.length) {
    b.cubes = srcBone.cubes.map((c) => {
      const out = { origin: Tpt(c.origin), size: Tsize(c.size) };
      if (c.uv && typeof c.uv === 'object') {
        out.uv = {};
        for (const [newFace, srcFace] of Object.entries(FACE_MAP)) {
          const f = c.uv[srcFace];
          if (!f) continue;
          const nf = { uv: [f.uv[0] + 64, f.uv[1]] };   // u+64：合图里剑纹理在 (64,0)-(96,32)
          if (f.uv_size) nf.uv_size = [f.uv_size[0], f.uv_size[1]];
          if (f.uv_rotation !== undefined) nf.uv_rotation = f.uv_rotation;
          out.uv[newFace] = nf;
        }
      } else if (Array.isArray(c.uv)) {
        out.uv = [c.uv[0] + 64, c.uv[1]]; // 旧式 uv: [u,v]
      }
      if (c.pivot) out.pivot = Tpt(c.pivot);
      if (c.rotation) out.rotation = Trot(c.rotation);
      return out;
    });
  }
  // 【调试标记】把剑柄放大 5 倍：游戏里若看到巨型剑柄 = 读到新文件；没变化 = 没读到。
  if (srcBone.name === 'handle') b.scale = [5, 5, 5];
  return b;
}
// hands 挂 rightItem；left/right 挂 hands；main 挂 right；shade/blade/handle 挂 main
const SWORD_PARENT = { hands: 'rightItem', left: 'hands', right: 'hands', main: 'right', shade: 'main', blade: 'main', handle: 'main' };
for (const name of SWORD_BONES) {
  const src = swordBoneMap[name];
  if (!src) { console.error('自检失败: sword.geo.json 缺 ' + name); process.exit(1); }
  bones.push(transformSwordBone(src, SWORD_PARENT[name]));
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
    if (!c.uv || Array.isArray(c.uv)) continue;
    for (const face of Object.values(c.uv)) {
      const [x0, y0, x1, y1] = bbox(face);
      if (x0 < 0 || y0 < 0 || x1 > TW || y1 > TH) {
        console.error(`  [FAIL] ${b.name} 面越界 bbox=${JSON.stringify([x0, y0, x1, y1])}`);
        fail++;
      }
    }
  }
}
// 2) 剑 main 骨是 right 子级，且（A 变换后）pivot 在手腕 [-5,12,0]（±0.01）—— 锚点
const mb = bones.find((b) => b.name === 'main');
if (!mb || mb.parent !== 'right') { console.error('  [FAIL] main 缺失或父级非 right'); fail++; }
else {
  const d = Math.hypot(mb.pivot[0] - (-5), mb.pivot[1] - 12, mb.pivot[2] - 0);
  if (d > 0.01) { console.error(`  [FAIL] main pivot 不在手腕: ${JSON.stringify(mb.pivot)} (d=${d.toFixed(4)})`); fail++; }
  else console.log(`  main pivot = ${JSON.stringify(mb.pivot)} ≈ 手腕 [-5,12,0] ✓（锚点）`);
}
// 3) 剑子树 parent 链完整
const chain = { hands: 'rightItem', left: 'hands', right: 'hands', main: 'right', shade: 'main', blade: 'main', handle: 'main' };
for (const [n, p] of Object.entries(chain)) {
  const b = bones.find((x) => x.name === n);
  if (!b || b.parent !== p) { console.error(`  [FAIL] ${n} 缺失或父级非 ${p}`); fail++; }
}
// 4) left/right 骨无 cube（去手臂 cube）
for (const n of ['left', 'right']) {
  const b = bones.find((x) => x.name === n);
  if (b && b.cubes && b.cubes.length > 0) { console.error(`  [FAIL] ${n} 仍有 cube（应去手臂 cube）`); fail++; }
}
// 5) 剑 cube 数 = 源 sword.geo.json 的 main 子树 cube 数（shade/blade/handle）
const srcCubeCount = ['shade', 'blade', 'handle'].reduce((s, n) => s + (swordBoneMap[n].cubes ? swordBoneMap[n].cubes.length : 0), 0);
const dstCubeCount = ['shade', 'blade', 'handle'].reduce((s, n) => { const b = bones.find((x) => x.name === n); return s + (b.cubes ? b.cubes.length : 0); }, 0);
if (srcCubeCount !== dstCubeCount) { console.error(`  [FAIL] 剑 cube 数不符 src=${srcCubeCount} dst=${dstCubeCount}`); fail++; }
else console.log(`  剑 cube 数 = ${dstCubeCount} ✓`);

console.log(fail === 0 ? 'SELF-CHECK OK' : `SELF-CHECK FAILED (${fail})`);
process.exit(fail === 0 ? 0 : 1);
