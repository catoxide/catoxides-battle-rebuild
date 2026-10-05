#!/usr/bin/env node
/**
 * verify_hand_align.js — 完整自检：验证 offset=-p_grip 时剑各部位相对玩家手腕的位置。
 *
 * 链（全部经源码/实验验证）：
 *   世界 = 肩部 · R_A · (t1 + offset + v_model)
 *   R_A = Rx(-90°)·Ry(180°)（ItemInHandLayer，右手），t1=(1/16, 0.125, -0.625)
 *   物品 origin = 手腕(腕部立方体底面中点偏后 2px)；相对手腕 = R_A·(offset + v)
 *   R_A·(x,y,z) = (-x, -z, -y)；实体系：+X=玩家左, +Y=上, +Z=玩家前（模型朝 +Z）
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
const v3 = (v) => `[${v.map(n => n.toFixed(4)).join(', ')}]`;

// R_A = Rx(-90)·Ry(180)，R_A·(x,y,z)=(-x,-z,-y)
const RA = (v) => [-v[0], -v[2], -v[1]];

const model = JSON.parse(fs.readFileSync(MODEL_PATH, 'utf8'));
const bones = model['minecraft:geometry'][0].bones;
const boneByName = new Map(bones.map(b => [b.name, b]));
const anim = JSON.parse(fs.readFileSync(ANIM_PATH, 'utf8'));
const animName = Object.keys(anim.animations)[0];
const animBones = anim.animations[animName].bones || {};
const TIME = parseFloat(process.argv[2] || '0');
function animAt(boneName, channel) {
  const b = animBones[boneName];
  if (!b || !b[channel]) return null;
  const keys = Object.keys(b[channel]).map(parseFloat).sort((a, b2) => a - b2);
  let cur = keys[0];
  for (const k of keys) if (k <= TIME + 1e-9) cur = k;
  return b[channel][String(cur)] ? b[channel][String(cur)].post : null;
}
function maOf(boneName) {
  const chain = [];
  let cur = boneByName.get(boneName);
  while (cur) { chain.push(cur.name); cur = cur.parent ? boneByName.get(cur.parent) : null; }
  let ma = I();
  for (const name of chain.slice().reverse()) {
    const def = boneByName.get(name);
    const pivot = loadBonePivot(def.pivot || [0, 0, 0]);
    const staticRot = loadBoneRot(def.rotation || [0, 0, 0]);
    const ap = animAt(name, 'position'); const ar = animAt(name, 'rotation'); const as = animAt(name, 'scale');
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

console.log(`=== t=${TIME} (anim=${animName}) ===`);
const pGrip = matVec(maOf('main'), loadBonePivot(boneByName.get('main').pivot));
console.log('p_grip (main pivot, 模型空间)      =', v3(pGrip));

// 剑尖：blade 骨 pivot
const pBlade = matVec(maOf('blade'), loadBonePivot(boneByName.get('blade').pivot));
console.log('p_blade (blade pivot, 模型空间)    =', v3(pBlade));

// 模型手腕：right 臂立方体远端中心（含立方体自身旋转，静态模型空间坐标）
{
  const cube = boneByName.get('right').cubes[0];
  const cOrigin = [(-cube.origin[0] - cube.size[0]) / 16, cube.origin[1] / 16, cube.origin[2] / 16]; // 加载后 min 角
  const cPivot = loadBonePivot(cube.pivot);
  const cRot = loadBoneRot(cube.rotation);
  // min 角经立方体旋转变换
  const rel = [cOrigin[0]-cPivot[0], cOrigin[1]-cPivot[1], cOrigin[2]-cPivot[2]];
  const rotV = matVec(rotZYX(cRot[0], cRot[1], cRot[2]), rel);
  const corner = [cPivot[0]+rotV[0], cPivot[1]+rotV[1], cPivot[2]+rotV[2]];
  // 长度方向 = R·(0,1,0)；腕中心 = corner + 中心偏移(0.5sx, 0.5sy, 0.5sz) 经旋转
  const lenDir = matVec(rotZYX(cRot[0], cRot[1], cRot[2]), [0, 1, 0]);
  const half = matVec(rotZYX(cRot[0], cRot[1], cRot[2]), [cube.size[0]/32, cube.size[1]/32, cube.size[2]/32]);
  const wristStatic = [corner[0]+half[0], corner[1]+half[1]+lenDir[1]*0 + 0, corner[2]+half[2]];
  // 腕中心 = min角 + (半宽x, 全长y, 半宽z)（旋转后）
  const wrist2 = [
    corner[0] + matVec(rotZYX(cRot[0],cRot[1],cRot[2]), [cube.size[0]/32, 0, 0])[0] + matVec(rotZYX(cRot[0],cRot[1],cRot[2]), [0, cube.size[1]/2, 0])[0] + matVec(rotZYX(cRot[0],cRot[1],cRot[2]), [0,0,cube.size[2]/32])[0],
    corner[1] + matVec(rotZYX(cRot[0],cRot[1],cRot[2]), [cube.size[0]/32, 0, 0])[1] + matVec(rotZYX(cRot[0],cRot[1],cRot[2]), [0, cube.size[1]/2, 0])[1] + matVec(rotZYX(cRot[0],cRot[1],cRot[2]), [0,0,cube.size[2]/32])[1],
    corner[2] + matVec(rotZYX(cRot[0],cRot[1],cRot[2]), [cube.size[0]/32, 0, 0])[2] + matVec(rotZYX(cRot[0],cRot[1],cRot[2]), [0, cube.size[1]/2, 0])[2] + matVec(rotZYX(cRot[0],cRot[1],cRot[2]), [0,0,cube.size[2]/32])[2],
  ];
  const pWrist = matVec(maOf('right'), wrist2);
  console.log('p_wrist (模型右臂腕端, 模型空间)   =', v3(pWrist));

  console.log('\n=== 相对玩家手腕的位置（offset = -p_grip）===');
  const relBlade = RA([pBlade[0]-pGrip[0], pBlade[1]-pGrip[1], pBlade[2]-pGrip[2]]);
  const relWrist = RA([pWrist[0]-pGrip[0], pWrist[1]-pGrip[1], pWrist[2]-pGrip[2]]);
  console.log('剑柄头(main pivot)               = [0.0000, 0.0000, 0.0000]（构造使然）');
  console.log('剑尖 相对手腕 =', v3(relBlade), '  (+X=玩家左, +Y=上, +Z=玩家前)');
  console.log('模型手腕 相对手腕 =', v3(relWrist), '  (应≈0：模型手与玩家手重合)');

  console.log('\n=== 对照：offset = 0 时 ===');
  console.log('剑柄头 相对手腕 =', v3(RA(pGrip)));
  console.log('剑尖 相对手腕   =', v3(RA(pBlade)));
}
console.log(`\n建议 offset(TP) = -p_grip = ${v3([-pGrip[0], -pGrip[1], -pGrip[2]])}`);
