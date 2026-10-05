#!/usr/bin/env node
/**
 * compute_hand_offset.js
 *
 * 计算第三人称下 getRenderOffset 应取的值，使剑的握把（main 骨 pivot）
 * 精确对齐玩家手（物品模型 origin）。
 *
 * 原理（对照 GeoItemRenderer.renderByItem 的矩阵顺序）：
 *   世界坐标 = A(原版手变换) · T(getRenderOffset) · R(getRenderRotation) · S(getRenderScale)
 *              · ma_bone(骨骼链) · 顶点
 *   - 原版把模型 origin(0,0,0) 放在玩家手上；
 *   - 骨骼链 ma_bone 把模型空间点映射到"模型根空间"（getRenderOffset 作用的空间）；
 *   - 故 offset = -p_grip（p_grip = 握把在模型根空间的位置）即可把握把挪回 origin。
 *
 * 本脚本严格按 Spark 引擎加载时的变换处理 JSON 数值（避免手算漏掉符号/缩放）：
 *   ModelModule.kt（模型）：
 *     bone pivot    (x,y,z)  -> (-x, y, z) / 16
 *     bone rotation (rx,ry,rz) -> (-rx,-ry,rz) 度 -> 弧度
 *   OBoneAnimation.kt（动画）：
 *     position  (x,y,z)   -> (-x/16, y/16, z/16)
 *     rotation  (rx,ry,rz) -> (-rx,-ry,rz) 度 -> 弧度
 *     scale     不变（默认 1）
 *
 * 骨骼局部矩阵（BonePose.getLocalTransformMatrix）：
 *   M = T(pivot)·T(animPos)·R(animRot+staticRot)·S(animScale)·T(-pivot)
 * 骨骼空间矩阵（applyTransformWithParents，根->自身连乘）：
 *   ma_bone = M_root · M_parent · ... · M_bone
 * 握把位置（模型根空间）：
 *   p_grip = ma_target · target_pivot   （target = main 骨）
 *
 * 用法：node tools/compute_hand_offset.js [model.json] [anim.json] [bone] [time]
 *   默认：sword.json, 3rd_still.json, main, 0
 */
'use strict';
const fs = require('fs');
const path = require('path');

const REPO = path.resolve(__dirname, '..');
const MODEL_PATH = process.argv[2] || path.join(REPO, 'src/main/resources/spark_models/item/catoxidesbattlerebuild/sword.json');
const ANIM_PATH = process.argv[3] || path.join(REPO, 'src/main/resources/spark_animations/item/catoxidesbattlerebuild/sword/3rd_still.json');
const TARGET_BONE = process.argv[4] || 'main';
const TIME = parseFloat(process.argv[5] || '0');

const D2R = Math.PI / 180;
// ---- Spark 加载变换 ----
const loadBonePivot = (p) => [-p[0] / 16, p[1] / 16, p[2] / 16];
const loadBoneRot = (r) => [-r[0] * D2R, -r[1] * D2R, r[2] * D2R];
const loadAnimPos = (p) => [-p[0] / 16, p[1] / 16, p[2] / 16];
const loadAnimRot = (r) => [-r[0] * D2R, -r[1] * D2R, r[2] * D2R];

// ---- 4x4 矩阵（列主序，JOML 约定：ma.mul(b) = ma*b；点变换 ma*v）----
const I = () => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
function matVec(m, v) {
  return [
    m[0] * v[0] + m[4] * v[1] + m[8] * v[2] + m[12],
    m[1] * v[0] + m[5] * v[1] + m[9] * v[2] + m[13],
    m[2] * v[0] + m[6] * v[1] + m[10] * v[2] + m[14],
  ];
}
function matMul(a, b) {
  const r = new Array(16).fill(0);
  for (let col = 0; col < 4; col++)
    for (let row = 0; row < 4; row++) {
      let s = 0;
      for (let k = 0; k < 4; k++) s += a[k * 4 + row] * b[col * 4 + k];
      r[col * 4 + row] = s;
    }
  return r;
}
// 列主序：平移在第 4 列（索引 12/13/14）
const T = (x, y, z) => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1];
const S = (x, y, z) => [x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1];
// JOML rotateZYX：等价于 R = Rx * Ry * Rz（列向量：先 Rz，再 Ry，再 Rx）
// 标准右手系旋转矩阵（JOML 约定，右手法则）：
//   Rx(θ): +Y→(0,c,−s)... 列主序存储（索引 = col*4+row）
function rotZYX(rx, ry, rz) {
  const cx = Math.cos(rx), sx = Math.sin(rx);
  const cy = Math.cos(ry), sy = Math.sin(ry);
  const cz = Math.cos(rz), sz = Math.sin(rz);
  // 右手 Rx(θ)：行 [1,0,0; 0,c,−s; 0,s,c]
  const Rx = [1, 0, 0, 0, 0, cx, sx, 0, 0, -sx, cx, 0, 0, 0, 0, 1];
  // 右手 Ry(θ)：行 [c,0,s; 0,1,0; −s,0,c]
  const Ry = [cy, 0, -sy, 0, 0, 1, 0, 0, sy, 0, cy, 0, 0, 0, 0, 1];
  // 右手 Rz(θ)：行 [c,−s,0; s,c,0; 0,0,1]
  const Rz = [cz, sz, 0, 0, -sz, cz, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
  return matMul(matMul(Rx, Ry), Rz);
}
const v3 = (v) => `[${v.map((n) => n.toFixed(4)).join(', ')}]`;
const f = (n) => n.toFixed(4);

// ---- 读取模型 ----
const model = JSON.parse(fs.readFileSync(MODEL_PATH, 'utf8'));
const bones = model['minecraft:geometry'][0].bones;
const boneByName = new Map(bones.map((b) => [b.name, b]));

// ---- 读取动画（目标时间帧）----
const anim = JSON.parse(fs.readFileSync(ANIM_PATH, 'utf8'));
const animName = Object.keys(anim.animations)[0];
const animBones = anim.animations[animName].bones || {};
function animAt(boneName, channel) {
  const b = animBones[boneName];
  if (!b || !b[channel]) return null;
  const keys = Object.keys(b[channel]).map(parseFloat).sort((a, b2) => a - b2);
  // 取 <= TIME 的最近帧（t=0 时即首帧）
  let cur = keys[0];
  for (const k of keys) if (k <= TIME + 1e-9) cur = k;
  return b[channel][String(cur)] ? b[channel][String(cur)].post : null;
}

console.log('=== 输入 ===');
console.log('模型:', path.relative(REPO, MODEL_PATH));
console.log('动画:', path.relative(REPO, ANIM_PATH), `(anim=${animName}, t=${TIME})`);
console.log('目标骨(握把):', TARGET_BONE);

// ---- 组装每根骨骼的局部矩阵 M = T(pivot)·T(animPos)·R(animRot+staticRot)·S(animScale)·T(-pivot) ----
function localMatrix(name) {
  const def = boneByName.get(name);
  if (!def) return null;
  const pivot = loadBonePivot(def.pivot || [0, 0, 0]);
  const staticRot = loadBoneRot(def.rotation || [0, 0, 0]);
  const animPosRaw = animAt(name, 'position');
  const animRotRaw = animAt(name, 'rotation');
  const animScaleRaw = animAt(name, 'scale');
  const animPos = animPosRaw ? loadAnimPos(animPosRaw) : [0, 0, 0];
  const animRot = animRotRaw ? loadAnimRot(animRotRaw) : [0, 0, 0];
  const animScale = animScaleRaw ? animScaleRaw.slice() : [1, 1, 1];
  const rot = [animRot[0] + staticRot[0], animRot[1] + staticRot[1], animRot[2] + staticRot[2]];

  console.log(`\n-- 骨骼 ${name} --`);
  console.log(`   pivot(JSON)        ${v3(def.pivot || [0, 0, 0])}  -> 加载后 ${v3(pivot)} 格`);
  console.log(`   staticRot(JSON)    ${v3(def.rotation || [0, 0, 0])}  -> 加载后 ${v3(staticRot.map((a) => a / D2R))} 度`);
  console.log(`   animPos(t=${TIME})  ${animPosRaw ? v3(animPosRaw) + ' -> ' + v3(animPos) + ' 格' : '(无) -> ' + v3(animPos)}`);
  console.log(`   animRot(t=${TIME})  ${animRotRaw ? v3(animRotRaw) + ' -> ' + v3(animRot.map((a) => a / D2R)) + ' 度' : '(无) -> ' + v3(animRot.map((a) => a / D2R))}`);
  console.log(`   合成旋转(度)        ${v3(rot.map((a) => a / D2R))}`);

  let m = I();
  m = matMul(m, T(pivot[0], pivot[1], pivot[2]));
  m = matMul(m, T(animPos[0], animPos[1], animPos[2]));
  m = matMul(m, rotZYX(rot[0], rot[1], rot[2]));
  m = matMul(m, S(animScale[0], animScale[1], animScale[2]));
  m = matMul(m, T(-pivot[0], -pivot[1], -pivot[2]));
  return m;
}

// ---- 目标骨的祖先链（自身->根），再反转为根->自身连乘 ----
const chain = [];
{
  let cur = boneByName.get(TARGET_BONE);
  if (!cur) { console.error('错误: 模型中没有骨骼', TARGET_BONE); process.exit(1); }
  while (cur) { chain.push(cur.name); cur = cur.parent ? boneByName.get(cur.parent) : null; }
}
console.log(`\n=== 骨骼链(自身->根) ===\n  ${chain.join(' <- ')}`);

let ma = I();
for (const name of chain.slice().reverse()) {
  ma = matMul(ma, localMatrix(name));
}

// ---- 握把 = 目标骨 pivot（模型空间），映射到模型根空间 ----
const targetPivotJSON = boneByName.get(TARGET_BONE).pivot || [0, 0, 0];
const targetPivot = loadBonePivot(targetPivotJSON);
console.log(`\n=== 握把位置 ===`);
console.log(`  target pivot(JSON)   ${v3(targetPivotJSON)}  -> 加载后 ${v3(targetPivot)} 格`);
const pGrip = matVec(ma, targetPivot);
console.log(`  p_grip = ma_target · pivot = ${v3(pGrip)} 格`);

// ---- offset = -p_grip ----
const offset = [-pGrip[0], -pGrip[1], -pGrip[2]];
console.log(`\n=== 结果 ===`);
console.log(`  p_grip (模型根空间)   = ${v3(pGrip)} 格`);
console.log(`  getRenderOffset(TP)   = -p_grip = ${v3(offset)} 格`);
console.log(`\n  填入 IronSwordWeapon.getRenderOffset 的 Java 字面量:`);
console.log(`    new Vector3f(${offset[0].toFixed(4)}f, ${offset[1].toFixed(4)}f, ${offset[2].toFixed(4)}f)`);
