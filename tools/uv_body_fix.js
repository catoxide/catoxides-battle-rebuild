#!/usr/bin/env node
/**
 * uv_body_fix.js — 身体 face-UV 的"唯一基准"计算 + 与当前生成物 diff。
 *
 * 原理（反编译 cn.solarmoon.spark_core...OPolygon / OVertexSet 逐行核对）：
 *   对非镜像 cube，box-UV 分支与 face-UV 分支把【同一顶点序】映到纹理四角：
 *     box-UV(矩形[U1,U2]×[V1,V2], 左U1<右U2, 上V1/下V2)：
 *        W0←(U2,V1) W1←(U1,V1) W2←(U1,V2) W3←(U2,V2)   （init 内 !mirror 先换 u1/u2）
 *     face-UV(uv=(u,v), size=(w,h))：
 *        W0←(u+w,v) W1←(u,v) W2←(u,v+h) W3←(u+w,v+h)
 *   二者像素级相同  ⇔  face-UV = uv:[U1,V1]  size:[U2-U1, V2-V1]
 *   （即"原版 box-UV 矩形原样"，正宽；down 面 V2<V1 → h 为负）。
 *   ⇒ 任何"负宽"身体 face-UV 都是错的（会把采样区平移 |w| px）。
 *
 * 原版 box-UV 展开（OPolygon.of 的 X/Y 数组，a=size.x, b=size.y, c=size.z）：
 *   X=[u, u+c, u+c+a, u+c+2a, u+2c+a, u+2c+2a]  Y=[v, v+c, v+c+b]
 *   west=[X0,X1]×[Y1,Y2] north=[X1,X2]×[Y1,Y2] east=[X2,X4]×[Y1,Y2]
 *   south=[X4,X5]×[Y1,Y2] up=[X1,X2]×[Y0,Y1] down=[X2,X3]×[Y1-c,Y1](翻转)
 *
 * 分节肢体（原版单 cube 高 b=12 → 上/下两个 6px cube）：
 *   上 cube：4 侧面取上 6px [Y1, Y1+b/2]；up 面 = 原版 up 面；down 面 = 内部(隐藏)
 *   下 cube：4 侧面取下 6px [Y1+b/2, Y2]；down 面 = 原版 down 面；up 面 = 内部(隐藏)
 *
 * 用法：node uv_body_fix.js [diff|emit]
 *   diff：打印 当前生成物 vs 正确值 的逐面差异
 *   emit：打印可直接粘进生成器的 UV 对象（10 部位）
 */
'use strict';
const fs = require('fs');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const VAN = JSON.parse(fs.readFileSync(ROOT + '/tools/player_vanilla.json', 'utf8'))['minecraft:geometry'][0];
const BUILD = ROOT + '/build/resources/main/spark_models/entity/catoxidesbattlerebuild/player_wrist.json';

const vBones = {}; for (const b of VAN.bones) vBones[b.name] = b;

function boxFaces(boxUV, size) {
  const u = boxUV[0], v = boxUV[1], a = size[0], b = size[1], c = size[2];
  const X = [u, u + c, u + c + a, u + c + 2 * a, u + 2 * c + a, u + 2 * c + 2 * a];
  const Y = [v, v + c, v + c + b];
  return {
    west:  { U1: X[0], U2: X[1], V1: Y[1], V2: Y[2] },
    north: { U1: X[1], U2: X[2], V1: Y[1], V2: Y[2] },
    east:  { U1: X[2], U2: X[4], V1: Y[1], V2: Y[2] },
    south: { U1: X[4], U2: X[5], V1: Y[1], V2: Y[2] },
    up:    { U1: X[1], U2: X[2], V1: Y[0], V2: Y[1] },
    down:  { U1: X[2], U2: X[3], V1: Y[1], V2: Y[1] - c },
  };
}
// 矩形 → face-UV（正宽，锚左上；down 面 h 负）
const toFaceUV = (r) => ({ uv: [r.U1, r.V1], uv_size: [r.U2 - r.U1, r.V2 - r.V1] });

// 单 cube 部位（头/躯干）：整面
function singleCubeUVs(vanBone) {
  const f = boxFaces(vanBone.cubes[0].uv, vanBone.cubes[0].size);
  const out = {};
  for (const d of ['north', 'east', 'south', 'west', 'up', 'down']) out[d] = toFaceUV(f[d]);
  return out;
}
// 分节肢体（原版 4×12×4 → 上/下 4×6×4）：返回 {upper:{...}, lower:{...}}
function splitLimbUVs(vanBone) {
  const f = boxFaces(vanBone.cubes[0].uv, vanBone.cubes[0].size);
  const b = vanBone.cubes[0].size[1];          // =12
  const half = b / 2;                          // =6
  const Y1 = f.north.V1;                       // 侧面上沿
  const upper = {}, lower = {};
  for (const d of ['west', 'north', 'east', 'south']) {
    const r = f[d];
    upper[d] = toFaceUV({ U1: r.U1, U2: r.U2, V1: r.V1, V2: r.V1 + half });
    lower[d] = toFaceUV({ U1: r.U1, U2: r.U2, V1: r.V1 + half, V2: r.V2 });
  }
  upper.up = toFaceUV(f.up);                   // 上 cube 的 +Y = 原版 up 面
  lower.down = toFaceUV(f.down);               // 下 cube 的 -Y = 原版 down 面
  // 内部(隐藏)面：给不越界的合理值（上 cube 的 -Y 在 y=18；下 cube 的 +Y 在 y=18）
  upper.down = toFaceUV({ U1: f.down.U1, U2: f.down.U2, V1: Y1 + half, V2: Y1 + half - f.down.U2 + f.down.U1 - (f.down.U2 - f.down.U1) + (f.down.V1 - f.down.V2) });
  lower.up = toFaceUV({ U1: f.up.U1, U2: f.up.U2, V1: Y1 + half, V2: Y1 + half + (f.up.V2 - f.up.V1) });
  return { upper, lower };
}

// 部位 → {vanBone, kind, upper?, lower?}
const PARTS = [
  { name: 'head',       van: 'head',     kind: 'single' },
  { name: 'waist',      van: 'waist',    kind: 'single' },
  { name: 'rightArm',   van: 'rightArm', kind: 'split', upper: 'rightArm', lower: 'rightForeArm' },
  { name: 'leftArm',    van: 'leftArm',  kind: 'split', upper: 'leftArm',  lower: 'leftForeArm' },
  { name: 'rightLeg',   van: 'rightLeg', kind: 'split', upper: 'rightLeg', lower: 'rightForeLeg' },
  { name: 'leftLeg',    van: 'leftLeg',  kind: 'split', upper: 'leftLeg',  lower: 'leftForeLeg' },
];

function correctUVs() {
  const out = {};   // out[boneName] = {north:{uv,uv_size},...}
  for (const p of PARTS) {
    if (p.kind === 'single') {
      out[p.name] = singleCubeUVs(vBones[p.van]);
    } else {
      const s = splitLimbUVs(vBones[p.van]);
      out[p.upper] = s.upper;
      out[p.lower] = s.lower;
    }
  }
  return out;
}

const r2 = (x) => +x.toFixed(4);
const fmtUV = (o) => o ? `uv[${o.uv[0]},${o.uv[1]}] sz[${o.uv_size[0]},${o.uv_size[1]}]` : '(无)';

const mode = process.argv[2] || 'diff';

if (mode === 'emit') {
  const cu = correctUVs();
  console.log('// 由 uv_body_fix.js emit 生成（唯一基准 = 原版 box-UV，正宽）');
  console.log('const UV_FIX = {');
  for (const bone of Object.keys(cu)) {
    console.log(`  ${bone}: {`);
    for (const d of ['north', 'east', 'south', 'west', 'up', 'down']) {
      const f = cu[bone][d];
      console.log(`    ${d}: { uv: [${r2(f.uv[0])}, ${r2(f.uv[1])}], uv_size: [${r2(f.uv_size[0])}, ${r2(f.uv_size[1])}] },`);
    }
    console.log('  },');
  }
  console.log('};');
} else {
  const gen = JSON.parse(fs.readFileSync(BUILD, 'utf8'))['minecraft:geometry'][0];
  const gBones = {}; for (const b of gen.bones) gBones[b.name] = b;
  const cu = correctUVs();
  const dirs = ['north', 'east', 'south', 'west', 'up', 'down'];
  let total = 0, bad = 0;
  for (const bone of Object.keys(cu)) {
    const gb = gBones[bone];
    const guv = (gb && gb.cubes && gb.cubes[0] && gb.cubes[0].uv) ? gb.cubes[0].uv : null;
    for (const d of dirs) {
      const want = cu[bone][d];
      const got = guv ? guv[d] : null;
      const same = got && got.uv[0] === want.uv[0] && got.uv[1] === want.uv[1] &&
                   got.uv_size[0] === want.uv_size[0] && got.uv_size[1] === want.uv_size[1];
      total++;
      if (!same) {
        bad++;
        console.log(`[DIFF] ${bone}.${d}  当前=${fmtUV(got)}  应为=${fmtUV(want)}`);
      }
    }
  }
  console.log(`\n共 ${total} 面，不一致 ${bad} 面。`);
}
