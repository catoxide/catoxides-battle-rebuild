#!/usr/bin/env node
/**
 * verify_pose_v3.js — v3 叠加层姿态数学验算（无需进游戏）
 *
 * 变换约定（与 Spark 源码一致，已核对 ModelModule.read + OBoneAnimation + BonePose）：
 *   解析时全局变换（文件系 → 内部系）：
 *     · 骨 pivot / cube pivot: (x,y,z) → (−x, y, z)（再 ÷16，均匀缩放对相对验算可省）
 *     · cube origin: (ox,oy,oz) → (−(ox+sx), oy, oz)；size 不变
 *     · 骨/cube 旋转: (rx,ry,rz)° → (−rx, −ry, rz) 弧度
 *     · 动画 rotation: (rx,ry,rz)° → (−rx, −ry, rz) 弧度
 *     · 动画 position: (px,py,pz) → (−px, py, pz)（再 ÷16，可省）
 *   骨局部矩阵 M_bone = T(pivot)·T(animPos)·R_zxy(animRot+restRot)·S(animScale)·T(−pivot)
 *     · R_zxy = Rx·Ry·Rz（BonePose.getLocalTransformMatrix 的 rotateZYX）
 *   骨世界矩阵 world = M_root · M_… · M_bone（OBone.applyTransformWithParents 左乘链）
 *   cube 顶点 world = boneWorld · T(cubePivot)·R_zxy(cubeRot)·T(−cubePivot) · v
 *   内部系：+Y 上，+Z 玩家前，+X 玩家右（文件系 X 取反；Y/Z 比较不受影响）
 *
 * 场景：
 *   A 静止 = STATE(state.land.idle@0) 驱动手臂 + MAIN(3rd_still@0) 驱动剑
 *   B 挥击 = STATE(state.land.idle@0) + MAIN(3rd_attack@t)，t∈[0,0.5]
 *
 * 姿态要求：
 *   R1 剑尖 y 高于剑柄      R2 剑尖在剑柄前方（+Z）
 *   R3 挥动中剑尖先升高再降低（弧线）
 *   R4 手臂最低点朝上（报告肘→腕方向与高度，供人工裁定解读）
 */
'use strict';
const fs = require('fs');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const NS = 'catoxidesbattlerebuild';
const B = ROOT + '/build/resources/main';
const readJ = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));

const geo = readJ(`${B}/spark_models/entity/${NS}/player_wrist.json`)['minecraft:geometry'][0];
const bones = {};
const order = [];
for (const b of geo.bones) { bones[b.name] = b; order.push(b.name); }
const parents = {};
for (const b of geo.bones) parents[b.name] = b.parent || null;

// ---------------------------------------------------------------- 文件系 → 内部系（Spark 解析约定）
function toInternalBone(b) {
  const o = { name: b.name, parent: b.parent, pivot: [-b.pivot[0], b.pivot[1], b.pivot[2]] };
  if (b.rotation) o.rotation = [-b.rotation[0], -b.rotation[1], b.rotation[2]];
  if (b.cubes) {
    o.cubes = b.cubes.map((c) => {
      const n = { origin: [-(c.origin[0] + c.size[0]), c.origin[1], c.origin[2]], size: c.size.slice() };
      if (c.pivot) n.pivot = [-c.pivot[0], c.pivot[1], c.pivot[2]];
      if (c.rotation) n.rotation = [-c.rotation[0], -c.rotation[1], c.rotation[2]];
      return n;
    });
  }
  return o;
}
for (const name of Object.keys(bones)) bones[name] = toInternalBone(bones[name]);

const DEG = Math.PI / 180;
const V = {
  add: (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]],
  sub: (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]],
};
// 4x4 矩阵（行主存，列向量约定 m·v）
function mat4I() { return [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]; }
function matMul(a, b) {
  const r = mat4I();
  for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) {
    let s = 0;
    for (let k = 0; k < 4; k++) s += a[i][k] * b[k][j];
    r[i][j] = s;
  }
  return r;
}
function matT(v) { const m = mat4I(); m[0][3] = v[0]; m[1][3] = v[1]; m[2][3] = v[2]; return m; }
function rotX(a) { const c = Math.cos(a), s = Math.sin(a); return [[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]]; }
function rotY(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]]; }
function rotZ(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]; }
// R = Rz·Ry·Rx（与 Spark/JOML rotateZYX 一致：rotateZYX(v)=Rz(v.z)·Ry(v.y)·Rx(v.x)，
// JOML 1.10.5 源码验证 10/05；旧 Rx·Ry·Rz 写法错误）
function rotZYX(e) {
  const [x, y, z] = e.map((d) => d * DEG);
  return matMul(rotZ(z), matMul(rotY(y), rotX(x)));
}
function matApply(m, v) {
  return [
    m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2] + m[0][3],
    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2] + m[1][3],
    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2] + m[2][3],
  ];
}
// 骨局部矩阵（Spark BonePose 约定）
function boneLocal(name, anim) {
  const b = bones[name];
  const pos = (anim && anim.position) || [0, 0, 0];
  const rot = V.add((anim && anim.rotation) || [0, 0, 0], b.rotation || [0, 0, 0]);
  const scl = (anim && anim.scale) || [1, 1, 1];
  // T(pivot)·T(pos)·R·S·T(−pivot)
  let m = matT(V.add(b.pivot, pos));
  m = matMul(m, rotZYX(rot));
  const ms = mat4I(); ms[0][0] = scl[0]; ms[1][1] = scl[1]; ms[2][2] = scl[2];
  m = matMul(m, ms);
  m = matMul(m, matT([-b.pivot[0], -b.pivot[1], -b.pivot[2]]));
  return m;
}
// 骨世界矩阵（从 root 左乘链）
function boneWorld(name, anims) {
  let m = mat4I();
  let cur = name;
  const chain = [];
  while (cur) { chain.unshift(cur); cur = parents[cur]; }
  for (const n of chain) m = matMul(m, boneLocal(n, anims[n] || null));
  return m;
}
// cube 的 8 个角（世界系）
function cubeCorners(boneName, cubeIdx, anims) {
  const b = bones[boneName];
  const c = b.cubes[cubeIdx];
  const cp = c.pivot || [c.origin[0] + c.size[0] / 2, c.origin[1] + c.size[1] / 2, c.origin[2] + c.size[2] / 2];
  let m = boneWorld(boneName, anims);
  m = matMul(m, matT(cp));
  if (c.rotation) m = matMul(m, rotZYX(c.rotation));
  m = matMul(m, matT([-cp[0], -cp[1], -cp[2]]));
  const corners = [];
  for (const dx of [0, c.size[0]]) for (const dy of [0, c.size[1]]) for (const dz of [0, c.size[2]]) {
    corners.push(matApply(m, [c.origin[0] + dx, c.origin[1] + dy, c.origin[2] + dz]));
  }
  return corners;
}

// ---------------------------------------------------------------- 动画采样（线性插值，忽略 catmullrom 平滑）
function valOf(v) { return Array.isArray(v) ? v.slice() : (v.post || v.pre || [0, 0, 0]).slice(); }
function sampleKeyframes(kfs, t) {
  if (!kfs) return [0, 0, 0];
  const entries = Object.entries(kfs).map(([k, v]) => [Number(k), v]).sort((a, b) => a[0] - b[0]);
  if (entries.length === 1) return valOf(entries[0][1]);
  if (t <= entries[0][0]) return valOf(entries[0][1]);
  if (t >= entries[entries.length - 1][0]) return valOf(entries[entries.length - 1][1]);
  for (let i = 0; i < entries.length - 1; i++) {
    if (t >= entries[i][0] && t <= entries[i + 1][0]) {
      const va = valOf(entries[i][1]), vb = valOf(entries[i + 1][1]);
      const f = (t - entries[i][0]) / (entries[i + 1][0] - entries[i][0]);
      return va.map((x, k) => x + (vb[k] - x) * f);
    }
  }
  return [0, 0, 0];
}
function sampleAnim(animJson, animName, t) {
  const a = animJson.animations[animName];
  const out = {};
  for (const [bn, bd] of Object.entries(a.bones || {})) {
    const o = {};
    for (const prop of ['position', 'rotation', 'scale']) {
      if (!bd[prop]) continue;
      let v = sampleKeyframes(bd[prop], t);
      if (prop === 'position') v = [-v[0], v[1], v[2]];       // (−px, py, pz)
      else if (prop === 'rotation') v = [-v[0], -v[1], v[2]];  // (−rx, −ry, rz)
      o[prop] = v;
    }
    if (Object.keys(o).length) out[bn] = o;
  }
  return out;
}

const still = readJ(`${B}/spark_animations/${NS}/entity/player_wrist/3rd_still.json`);
const attack = readJ(`${B}/spark_animations/${NS}/entity/player_wrist/3rd_attack.json`);
const idle = readJ(`${B}/spark_animations/${NS}/entity/player_wrist/base_state.json`);   // state.land.idle 取自 Spark 内置 base_state

// ---------------------------------------------------------------- 关键点提取
function keyPoints(anims) {
  const bladeC = cubeCorners('blade', 0, anims).concat(cubeCorners('blade', 1, anims));
  const handleC = [];
  for (let i = 0; i < bones.handle.cubes.length; i++) handleC.push(...cubeCorners('handle', i, anims));
  // 剑柄头（pommel）= handle cube4 中心附近最远端 → 用 cube4 各角里 z 最小的
  const pommel = cubeCorners('handle', 4, anims);
  const pommelPt = pommel.reduce((a, b) => (b[2] < a[2] ? b : a));
  // 剑尖 = 全部剑角里离 pommel 最远
  const tip = bladeC.concat(handleC).reduce((a, b) => {
    const da = (a[0] - pommelPt[0]) ** 2 + (a[1] - pommelPt[1]) ** 2 + (a[2] - pommelPt[2]) ** 2;
    const db = (b[0] - pommelPt[0]) ** 2 + (b[1] - pommelPt[1]) ** 2 + (b[2] - pommelPt[2]) ** 2;
    return db > da ? b : a;
  });
  // 剑柄参考点 = grip（handle cube3 中心）
  const gripC = cubeCorners('handle', 3, anims);
  let grip = [0, 0, 0];
  for (const c of gripC) grip = V.add(grip, c);
  grip = [grip[0] / 8, grip[1] / 8, grip[2] / 8];
  // 手臂：肩/腕（世界系）= 各骨 rest pivot 经世界矩阵；
  // 肘 = 右臂骨上沿臂向下 6px 的点（2026-10-02 起身体=原版 1:1，无肘骨；整臂刚性）
  const shoulder = matApply(boneWorld('rightArm', anims), bones.rightArm.pivot);
  const elbowLocal = [bones.rightArm.pivot[0], bones.rightArm.pivot[1] - 6, bones.rightArm.pivot[2]];
  const elbow = matApply(boneWorld('rightArm', anims), elbowLocal);
  const wrist = matApply(boneWorld('rightItem', anims), bones.rightItem.pivot);
  // 手臂最低点 = 右臂 cube 的 8 角中 y 最小
  const armLowC = cubeCorners('rightArm', 0, anims);
  const armLow = armLowC.reduce((a, b) => (b[1] < a[1] ? b : a));
  return { tip, pommel: pommelPt, grip, shoulder, elbow, wrist, armLow };
}
const fmt = (v) => `(${v[0].toFixed(2)}, ${v[1].toFixed(2)}, ${v[2].toFixed(2)})`;

// ---------------------------------------------------------------- 场景 A：静止
const armIdle = sampleAnim(idle, 'state.land.idle', 0);
const swordStill = sampleAnim(still, '3rd_still', 0);
const animsA = Object.assign({}, armIdle, swordStill);
const A = keyPoints(animsA);
console.log('=== A 静止姿态（3rd_still + state.land.idle@0）===');
console.log('  剑尖 tip      =', fmt(A.tip));
console.log('  剑柄 grip     =', fmt(A.grip));
console.log('  柄头 pommel   =', fmt(A.pommel));
console.log('  肩 =', fmt(A.shoulder), ' 肘 =', fmt(A.elbow), ' 腕 =', fmt(A.wrist));
console.log('  手臂最低点    =', fmt(A.armLow));

let pass = 0, fail = 0;
const check = (name, ok, detail) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${name}  ${detail}`);
  ok ? pass++ : fail++;
};
check('R1 剑尖y>剑柄y', A.tip[1] > A.grip[1], `tip.y=${A.tip[1].toFixed(2)} vs grip.y=${A.grip[1].toFixed(2)}`);
check('R2 剑尖在剑柄前方(+Z)', A.tip[2] > A.grip[2], `tip.z=${A.tip[2].toFixed(2)} vs grip.z=${A.grip[2].toFixed(2)}`);
const armDir = V.sub(A.wrist, A.elbow); // 肘→腕
check('R4 肘→腕朝上(最低点朝上解读)', armDir[1] > 0, `肘→腕=${fmt(armDir)} (y${armDir[1] > 0 ? '+' : ''}${armDir[1].toFixed(2)})`);

// ---------------------------------------------------------------- 场景 B：挥击弧线
console.log('\n=== B 挥击弧线（3rd_attack@t，手臂静止 idle@0）===');
const N = 26;
const samples = [];
for (let i = 0; i < N; i++) {
  const t = (0.5 * i) / (N - 1);
  const animsB = Object.assign({}, armIdle, sampleAnim(attack, '3rd_attack', t));
  const k = keyPoints(animsB);
  samples.push({ t, tip: k.tip, grip: k.grip });
}
for (const s of samples) {
  const dy = s.tip[1] - s.grip[1];
  const dz = s.tip[2] - s.grip[2];
  console.log(`  t=${s.t.toFixed(3)}  tip=${fmt(s.tip)}  Δy(tip-grip)=${dy >= 0 ? '+' : ''}${dy.toFixed(2)}  Δz=${dz >= 0 ? '+' : ''}${dz.toFixed(2)}`);
}
const ys = samples.map((s) => s.tip[1]);
const maxI = ys.indexOf(Math.max(...ys));
const minY = Math.min(ys[0], ys[ys.length - 1]);
check('R3 剑尖先升高再降低', maxI > 2 && maxI < ys.length - 3 && ys[maxI] > ys[0] + 1 && ys[maxI] > ys[ys.length - 1] + 1,
  `峰值 t=${samples[maxI].t.toFixed(2)} y=${ys[maxI].toFixed(2)}；起点 ${ys[0].toFixed(2)} 终点 ${ys[ys.length - 1].toFixed(2)}`);
// R1/R2 全程
const r1all = samples.every((s) => s.tip[1] > s.grip[1]);
const r2all = samples.every((s) => s.tip[2] > s.grip[2]);
check('R1 全程 剑尖y>剑柄y', r1all, r1all ? '所有采样点成立' : '存在违反点');
check('R2 全程 剑尖在前方', r2all, r2all ? '所有采样点成立' : '存在违反点');

console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
process.exit(fail === 0 ? 0 : 1);
