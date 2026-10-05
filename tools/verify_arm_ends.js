#!/usr/bin/env node
/**
 * verify_arm_ends.js — 检查 right 臂立方体两端在动画各时刻的模型空间位置：
 *  - 哪一端靠近剑柄头(main pivot) = 手腕/手端
 *  - 另一端 = 肩端（对齐目标：玩家肩关节）
 *  - 肩端是否近似固定（决定固定 offset 的可行性）
 */
'use strict';
const fs = require('fs');
const path = require('path');
const REPO = path.resolve(__dirname, '..');
const MODEL_PATH = path.join(REPO, 'src/main/resources/spark_models/item/catoxidesbattlerebuild/sword.json');
const ANIM_PATH = path.join(REPO, 'src/main/resources/spark_animations/item/catoxidesbattlerebuild/sword/3rd_still.json');
const D2R = Math.PI / 180;
const loadBonePivot = (p) => [-p[0] / 16, p[1] / 16, p[2] / 16];
const loadBoneRot = (r) => [-r[0] * D2R, -r[1] * D2R, r[2] * D2R];
const loadAnimPos = (p) => [-p[0] / 16, p[1] / 16, p[2] / 16];
const loadAnimRot = (r) => [-r[0] * D2R, -r[1] * D2R, r[2] * D2R];

const I = () => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
function matVec(m, v) {
  return [m[0]*v[0]+m[4]*v[1]+m[8]*v[2]+m[12], m[1]*v[0]+m[5]*v[1]+m[9]*v[2]+m[13], m[2]*v[0]+m[6]*v[1]+m[10]*v[2]+m[14]];
}
function matMul(a, b) {
  const r = new Array(16).fill(0);
  for (let c = 0; c < 4; c++) for (let rw = 0; rw < 4; rw++) {
    let s = 0; for (let k = 0; k < 4; k++) s += a[k*4+rw]*b[c*4+k];
    r[c*4+rw] = s;
  }
  return r;
}
const T = (x, y, z) => [1,0,0,0, 0,1,0,0, 0,0,1,0, x,y,z,1];
const S = (x, y, z) => [x,0,0,0, 0,y,0,0, 0,0,z,0, 0,0,0,1];
function rotZYX(rx, ry, rz) {
  const cx = Math.cos(rx), sx = Math.sin(rx), cy = Math.cos(ry), sy = Math.sin(ry), cz = Math.cos(rz), sz = Math.sin(rz);
  const Rx = [1,0,0,0, 0,cx,sx,0, 0,-sx,cx,0, 0,0,0,1];
  const Ry = [cy,0,-sy,0, 0,1,0,0, sy,0,cy,0, 0,0,0,1];
  const Rz = [cz,sz,0,0, -sz,cz,0,0, 0,0,1,0, 0,0,0,1];
  return matMul(matMul(Rx, Ry), Rz);
}
const v3 = (v) => `[${v.map(n => n.toFixed(3)).join(', ')}]`;
const dist = (a, b) => Math.hypot(a[0]-b[0], a[1]-b[1], a[2]-b[2]);

const model = JSON.parse(fs.readFileSync(MODEL_PATH, 'utf8'));
const bones = model['minecraft:geometry'][0].bones;
const boneByName = new Map(bones.map(b => [b.name, b]));
const anim = JSON.parse(fs.readFileSync(ANIM_PATH, 'utf8'));
const animName = Object.keys(anim.animations)[0];
const animBones = anim.animations[animName].bones || {};
function animAt(time, boneName, channel) {
  const b = animBones[boneName];
  if (!b || !b[channel]) return null;
  const keys = Object.keys(b[channel]).map(parseFloat).sort((a, b2) => a - b2);
  let cur = keys[0];
  for (const k of keys) if (k <= time + 1e-9) cur = k;
  return b[channel][String(cur)] ? b[channel][String(cur)].post : null;
}
function maOf(time, boneName) {
  const chain = [];
  let cur = boneByName.get(boneName);
  while (cur) { chain.push(cur.name); cur = cur.parent ? boneByName.get(cur.parent) : null; }
  let ma = I();
  for (const name of chain.slice().reverse()) {
    const def = boneByName.get(name);
    const pivot = loadBonePivot(def.pivot || [0, 0, 0]);
    const staticRot = loadBoneRot(def.rotation || [0, 0, 0]);
    const ap = animAt(time, name, 'position'); const ar = animAt(time, name, 'rotation'); const as = animAt(time, name, 'scale');
    const animPos = ap ? loadAnimPos(ap) : [0, 0, 0];
    const animRot = ar ? loadAnimRot(ar) : [0, 0, 0];
    const animScale = as ? as.slice() : [1, 1, 1];
    const rot = [animRot[0]+staticRot[0], animRot[1]+staticRot[1], animRot[2]+staticRot[2]];
    let m = I();
    m = matMul(m, T(pivot[0], pivot[1], pivot[2]));
    m = matMul(m, T(animPos[0], animPos[1], animPos[2]));
    m = matMul(m, rotZYX(rot[0], rot[1], rot[2]));
    m = matMul(m, S(animScale[0], animScale[1], animScale[2]));
    m = matMul(m, T(-pivot[0], -pivot[1], -pivot[2]));
    ma = matMul(ma, m);
  }
  return ma;
}

// right 臂立方体两端（静态模型空间坐标，已含立方体自身旋转）
// endA = min 角端 (z=0.5)，endB = 长度方向端 (z=1.25)
const cube = boneByName.get('right').cubes[0];
const cPivot = loadBonePivot(cube.pivot);
const cRot = loadBoneRot(cube.rotation);
const cOrigin = [(-cube.origin[0] - cube.size[0]) / 16, cube.origin[1] / 16, cube.origin[2] / 16];
function cubeTransform(p) {
  const rel = [p[0]-cPivot[0], p[1]-cPivot[1], p[2]-cPivot[2]];
  const r = matVec(rotZYX(cRot[0], cRot[1], cRot[2]), rel);
  return [cPivot[0]+r[0], cPivot[1]+r[1], cPivot[2]+r[2]];
}
const endA_static = cubeTransform(cOrigin);                    // min 角
const endB_static = cubeTransform([cOrigin[0], cOrigin[1]+cube.size[1]/16, cOrigin[2]]); // 长度端
console.log('臂立方体端A(min角,静态) =', v3(endA_static));
console.log('臂立方体端B(长端 ,静态) =', v3(endB_static));

for (const time of [0, 0.1, 0.2, 0.3, 0.4, 0.5]) {
  const maR = maOf(time, 'right');
  const pGrip = matVec(maOf(time, 'main'), loadBonePivot(boneByName.get('main').pivot));
  const eA = matVec(maR, endA_static);
  const eB = matVec(maR, endB_static);
  console.log(`\nt=${time}:`);
  console.log(`  剑柄头 = ${v3(pGrip)}`);
  console.log(`  端A    = ${v3(eA)}   距剑柄头 ${dist(eA, pGrip).toFixed(3)}`);
  console.log(`  端B    = ${v3(eB)}   距剑柄头 ${dist(eB, pGrip).toFixed(3)}`);
}
