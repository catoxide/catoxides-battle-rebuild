#!/usr/bin/env node
/**
 * verify_pose_3d.js —— 完整骨骼层级模拟，验证四姿态要求（不进游戏）
 *
 * 直接从 pack 里【实际生成】的 sword_arm_attack.json 读 rightArm/rightForeArm 旋转，
 * 用 Spark 的确切约定算剑尖世界坐标，验证四个姿态要求。
 *
 * Spark 约定（OBoneAnimation.getAnimRotAt 源码确认）：
 *   内部旋转(弧度) = toRadians( (文件rx,文件ry,文件rz) × (-1,-1,1) ) = (-rx,-ry,rz)
 *   骨局部变换 = T(pivot)·R·T(-pivot)，R = Rz(rz)·Ry(ry)·Rx(rx)（ZYX）
 *   剑尖世界坐标 = M_rightArm · M_rightForeArm · P_tip（其余骨恒等）
 *
 * 坐标系（模型空间 px，16px=1格）：+X=左 -X=右 +Y=上 +Z=前。
 */
'use strict';
const fs = require('fs');
const path = require('path');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const NS = (() => {
  const c = fs.readFileSync(ROOT + '/src/main/java/com/catoxide/catoxidesbattlerebuild/CatoxidesBattleRebuildConstants.java', 'utf8');
  return c.match(/MODID = "([^"]+)"/)[1];
})();
const ANIM = ROOT + `/run/client/spark_modules/catoxidesbattlerebuild/animations/${NS}/entity/player_wrist/sword_arm_attack.json`;

const rad = (d) => d * Math.PI / 180;
function mMul(A, B) {
  const n = A.length, m = B[0].length, p = B.length;
  const out = Array.from({ length: n }, () => new Array(m).fill(0));
  for (let i = 0; i < n; i++) for (let j = 0; j < m; j++) for (let k = 0; k < p; k++) out[i][j] += A[i][k] * B[k][j];
  return out;
}
function mApply(A, p) {
  return [A[0][0]*p[0]+A[0][1]*p[1]+A[0][2]*p[2]+A[0][3],
          A[1][0]*p[0]+A[1][1]*p[1]+A[1][2]*p[2]+A[1][3],
          A[2][0]*p[0]+A[2][1]*p[1]+A[2][2]*p[2]+A[2][3]];
}
const rotX = (a) => [[1,0,0,0],[0,Math.cos(a),-Math.sin(a),0],[0,Math.sin(a),Math.cos(a),0],[0,0,0,1]];
const rotY = (a) => [[Math.cos(a),0,Math.sin(a),0],[0,1,0,0],[-Math.sin(a),0,Math.cos(a),0],[0,0,0,1]];
const rotZ = (a) => [[Math.cos(a),-Math.sin(a),0,0],[Math.sin(a),Math.cos(a),0,0],[0,0,1,0],[0,0,0,1]];
const T4 = (x, y, z) => [[1,0,0,x],[0,1,0,y],[0,0,1,z],[0,0,0,1]];
// 文件欧拉 [rx,ry,rz](度) -> 4x4 骨局部变换（Spark 约定：内部=(-rx,-ry,rz)）
function boneLocal(pivot, fileEuler) {
  const [irx, iry, irz] = [rad(-fileEuler[0]), rad(-fileEuler[1]), rad(fileEuler[2])];
  const R = mMul(rotZ(irz), mMul(rotY(iry), rotX(irx)));
  const R4 = [R[0].concat(0), R[1].concat(0), R[2].concat(0), [0,0,0,1]];
  return mMul(T4(pivot[0], pivot[1], pivot[2]), mMul(R4, T4(-pivot[0], -pivot[1], -pivot[2])));
}

// 模型 pivot（模型全局空间 px）
const PIVOT_RIGHT_ARM = [-5, 22, 0];
const PIVOT_RIGHT_FOREARM = [-5, 18, 0];
// 剑尖 / 剑柄 静息位置（模型全局空间 px，从 player_wrist.json 的 cube 取）
const TIP = [-3.4583, 12.333, 17.828];   // blade 远端
const HILT = [-2.4833, 12.0, 2.7583];    // handle 中心

// 从实际动画 JSON 取某时刻的 rightArm/rightForeArm 旋转
const anim = JSON.parse(fs.readFileSync(ANIM, 'utf8'));
const bones = anim.animations.sword_arm_attack.bones;
function rotAt(bone, tSec) {
  const rot = bones[bone].rotation;
  const keys = Object.keys(rot).map(Number);
  const key = keys.reduce((a, b) => (Math.abs(b - tSec) < Math.abs(a - tSec) ? b : a));
  return rot[key.toFixed(4)];
}
const FRAMES = [0.00, 0.15, 0.34, 0.45].map((t) => ({
  t, ra: rotAt('rightArm', t), rf: rotAt('rightForeArm', t),
}));
console.log('从实际动画读到的关键帧（文件欧拉）:');
for (const f of FRAMES) console.log(`  t=${f.t.toFixed(2)}  ra=[${f.ra.map((v)=>v.toFixed(1)).join(', ')}]  rf=[${f.rf.map((v)=>v.toFixed(1)).join(', ')}]`);

console.log('\nt     前抬(ra[0])   剑尖世界(y,z)      剑柄世界(y,z)      尖-柄dy   尖z-柄z');
const tipYs = [], tipZs = [], hiltYs = [], hiltZs = [];
for (const f of FRAMES) {
  const M = mMul(boneLocal(PIVOT_RIGHT_ARM, f.ra), boneLocal(PIVOT_RIGHT_FOREARM, f.rf));
  const tip = mApply(M, TIP), hilt = mApply(M, HILT);
  tipYs.push(tip[1]); tipZs.push(tip[2]); hiltYs.push(hilt[1]); hiltZs.push(hilt[2]);
  console.log(`${f.t.toFixed(2)}   ${f.ra[0].toFixed(0).padStart(6)}     y=${tip[1].toFixed(2)} z=${tip[2].toFixed(2)}    y=${hilt[1].toFixed(2)} z=${hilt[2].toFixed(2)}    ${(tip[1]-hilt[1]).toFixed(2)}    ${(tip[2]-hilt[2]).toFixed(2)}`);
}

let fail = 0;
// [1] 剑尖 y 高于剑柄（取抬臂最高的帧 t0.15）
{
  const i = 1;
  const ok = tipYs[i] > hiltYs[i];
  console.log(`\n[1] 剑尖y > 剑柄y（t=${FRAMES[i].t} 高举）: ${tipYs[i].toFixed(2)} > ${hiltYs[i].toFixed(2)} -> ${ok ? '✓' : '✗'}`);
  if (!ok) fail++;
}
// [2] 剑尖在剑柄前方（+z，取 t0.00）
{
  const ok = tipZs[0] > hiltZs[0];
  console.log(`[2] 剑尖z > 剑柄z（前方, t0.00）: ${tipZs[0].toFixed(2)} > ${hiltZs[0].toFixed(2)} -> ${ok ? '✓' : '✗'}`);
  if (!ok) fail++;
}
// [3] 挥动中剑尖先升高再降低
{
  const peak = Math.max(...tipYs), trough = Math.min(...tipYs);
  const peakIdx = tipYs.indexOf(peak), troughIdx = tipYs.indexOf(trough);
  const ok = peakIdx < troughIdx && peak > trough;
  console.log(`[3] 剑尖先升后降: 峰=${peak.toFixed(2)}@t${FRAMES[peakIdx].t} 谷=${trough.toFixed(2)}@t${FRAMES[troughIdx].t} -> ${ok ? '✓' : '✗'}`);
  if (!ok) fail++;
}
// [4] 手臂最低点朝上（全程前抬量 ra[0] > 0 = 手臂不垂到最低）
{
  const raises = FRAMES.map((f) => f.ra[0]);
  const ok = raises.every((r) => r > 0);
  console.log(`[4] 手臂最低点朝上: 前抬量=${raises.map((r)=>r.toFixed(0)).join(',')} >0 -> ${ok ? '✓' : '✗'}`);
  if (!ok) fail++;
}
console.log(fail === 0 ? '\nALL POSE CHECKS PASS' : `\n${fail} POSE CHECKS FAILED`);
process.exit(fail === 0 ? 0 : 1);
