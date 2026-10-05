// 诊断：旧嵌入 M_old 下剑尖的世界朝向（玩家面南 yRot=0，世界 x东 y上 z南）
'use strict';
const fs = require('fs');
const path = require('path');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const PACK = (() => {
  const d = fs.readdirSync(ROOT + '/run/client/spark_modules').filter((x) => !x.endsWith('.zip'))[0];
  return ROOT + '/run/client/spark_modules/' + d;
})();
const NS = fs.readFileSync(
  (function find(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name.startsWith('.git')) continue;
      const p = path.join(dir, e.name);
      if (e.isDirectory()) { const r = find(p); if (r) return r; }
      else if (e.name === 'CatoxidesBattleRebuildConstants.java') return p;
    }
    return null;
  })(ROOT + '/src/main/java'), 'utf8').match(/MODID = "([^"]+)"/)[1];

const readJSON = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const item = readJSON(PACK + '/models/' + NS + '/item/sword.json');
const still = readJSON(PACK + '/animations/' + NS + '/item/sword/3rd_still.json');
const bones = {};
for (const b of item['minecraft:geometry'][0].bones) bones[b.name] = b;
const ab = still.animations['3rd_still'].bones;

const rad = (d) => d * Math.PI / 180;
const V = (x, y, z) => [x, y, z];
const vAdd = (a, b) => V(a[0]+b[0], a[1]+b[1], a[2]+b[2]);
const vSub = (a, b) => V(a[0]-b[0], a[1]-b[1], a[2]-b[2]);
const vScale = (a, s) => V(a[0]*s, a[1]*s, a[2]*s);
const mMul = (A, B) => [[0,0,0],[0,0,0],[0,0,0]].map((r, i) => [0,1,2].map((j) => A[i][0]*B[0][j] + A[i][1]*B[1][j] + A[i][2]*B[2][j]));
const mT = (A) => [[A[0][0],A[1][0],A[2][0]],[A[0][1],A[1][1],A[2][1]],[A[0][2],A[1][2],A[2][2]]];
const mApply = (A, v) => V(A[0][0]*v[0]+A[0][1]*v[1]+A[0][2]*v[2], A[1][0]*v[0]+A[1][1]*v[1]+A[1][2]*v[2], A[2][0]*v[0]+A[2][1]*v[1]+A[2][2]*v[2]);
const rotX = (a) => { const c=Math.cos(a), s=Math.sin(a); return [[1,0,0],[0,c,-s],[0,s,c]]; };
const rotY = (a) => { const c=Math.cos(a), s=Math.sin(a); return [[c,0,s],[0,1,0],[-s,0,c]]; };
const rotZ = (a) => { const c=Math.cos(a), s=Math.sin(a); return [[c,-s,0],[s,c,0],[0,0,1]]; };
const rigid4 = (R, t) => [[R[0][0],R[0][1],R[0][2],t[0]],[R[1][0],R[1][1],R[1][2],t[1]],[R[2][0],R[2][1],R[2][2],t[2]],[0,0,0,1]];
const m4Mul = (A, B) => A.map((r, i) => [0,1,2,3].map((j) => A[i][0]*B[0][j] + A[i][1]*B[1][j] + A[i][2]*B[2][j] + A[i][3]*B[3][j]));
const m4Apply = (A, p) => V(A[0][0]*p[0]+A[0][1]*p[1]+A[0][2]*p[2]+A[0][3], A[1][0]*p[0]+A[1][1]*p[1]+A[1][2]*p[2]+A[1][3], A[2][0]*p[0]+A[2][1]*p[1]+A[2][2]*p[2]+A[2][3]);
const I3 = () => [[1,0,0],[0,1,0],[0,0,1]];

function sampleKey(bonesMap, boneName, track, t) {
  const b = bonesMap[boneName];
  if (!b || !b[track]) return null;
  for (const [k, kv] of Object.entries(b[track])) {
    if (Math.abs(parseFloat(k) - t) < 1e-9) return Array.isArray(kv) ? kv : kv.post;
  }
  return null;
}
const pInt = (fp) => [-fp[0]/16, fp[1]/16, fp[2]/16];
const vInt = (fv) => [-fv[0]/16, fv[1]/16, fv[2]/16];
const fileEulerToInt = (e) => [rad(-e[0]), rad(-e[1]), rad(e[2])];
function boneLocal(pivot, localPos, eulerInt) {
  const [rx, ry, rz] = eulerInt;
  const R = mMul(rotZ(rz), mMul(rotY(ry), rotX(rx)));
  let M = rigid4(I3(), pivot);
  M = m4Mul(M, rigid4(I3(), localPos));
  M = m4Mul(M, rigid4(R, V(0,0,0)));
  M = m4Mul(M, rigid4(I3(), vScale(pivot, -1)));
  return M;
}

// 物品链 right->main @ t=0
const r0 = sampleKey(ab, 'right', 'rotation', 0) || [0,0,0];
const rp0 = sampleKey(ab, 'right', 'position', 0) || [0,0,0];
const m0 = sampleKey(ab, 'main', 'rotation', 0) || [0,0,0];
const mp0 = sampleKey(ab, 'main', 'position', 0) || [0,0,0];
let Ci = boneLocal(pInt(bones['right'].pivot), vInt(rp0), fileEulerToInt(r0));
Ci = m4Mul(Ci, boneLocal(pInt(bones['main'].pivot), vInt(mp0), fileEulerToInt(m0)));
console.log('3rd_still t=0: right rot', r0, 'pos', rp0, '| main rot', m0, 'pos', mp0);

// 剑上特征点（物品帧）：blade 骨 pivot、blade cube 两端、handle 末端、right pivot（手）
const blade = bones['blade'];
const bc = blade.cubes[0];
const bladePts = {
  'blade骨pivot': blade.pivot,
  'cube近端(origin)': bc.origin,
  'cube远端(origin+size)': [bc.origin[0]+bc.size[0], bc.origin[1]+bc.size[1], bc.origin[2]+bc.size[2]],
};
const handle = bones['handle'];
bladePts['handle骨pivot'] = handle.pivot;
bladePts['right骨pivot(手)'] = bones['right'].pivot;

// 嵌入候选
const M_old = rigid4([[1,0,0],[0,0,1],[0,-1,0]], [-0.0625, 0.125, 0.125]);
// M_candA: vanilla链(内部帧) 修正版 (z 符号翻转假设): (x−0.0625, z+0.125, y−0.125)
const M_candA = rigid4([[1,0,0],[0,0,1],[0,1,0]], [-0.0625, 0.125, -0.125]);
// M_new: 生成器刚算的 mixin 链 M = L_ra(0)·G（从生成器输出抄）
// A = R_ra(0)·R_X(−90), b 见生成器输出；这里直接用生成器写出的 A_f/b_f 反推内部：
// 生成器输出 A_f=[[1,0,0],[0,0,1],[0,-1,0]] b_f=[1.5,1.5,2.25] -> A_int=D·A_f·D, b_int=D·b_f/16
const D3 = [[-1,0,0],[0,1,0],[0,0,1]];
const Af = [[1,0,0],[0,0,1],[0,-1,0]], bf = [1.5, 1.5, 2.25];
const Aint = mMul(D3, mMul(Af, D3));
const Mint = vScale(vScale(mApply(D3, bf), 1/16));
const M_new = rigid4(Aint, Mint);

console.log('\n候选嵌入:');
console.log('  M_old  b =', [-0.0625, 0.125, 0.125], ' A = R_X(−90)');
console.log('  M_candA b =', [-0.0625, 0.125, -0.125], ' A = (x,z,y)  [vanilla链 z 修正]');
console.log('  M_new  b =', Mint.map(x=>+x.toFixed(4)), ' A = R_ra(0)·R_X(−90) [mixin链]');

// 世界 (玩家面南 yRot=0, pos=0): 内部 (x,y,z) -> 世界 (−x, y, −z)；世界 z>0 = 朝前(南)
console.log('\n特征点世界坐标（面南, z>0=朝前, x<0=玩家右侧）:');
for (const [name, p] of Object.entries(bladePts)) {
  const itemPt = m4Apply(Ci, pInt(p));      // 物品帧 -> 物品根帧(内部)
  const row = [name.padEnd(22)];
  for (const [mn, M] of [['M_old', M_old], ['M_candA', M_candA], ['M_new', M_new]]) {
    const pi = m4Apply(M, itemPt);
    const w = V(-pi[0], pi[1], -pi[2]);
    row.push(`${mn}: (${w[0].toFixed(3)}, ${w[1].toFixed(3)}, ${w[2].toFixed(3)})`);
  }
  console.log(' ', row.join('  '));
}
console.log('\n期望(批准视角): 剑尖 z>0(朝前), 剑尖 y<1(向下), 手在玩家右侧 x<0');
