// 验证 0.9375 烘焙缩放对姿态数学的影响：
// 从当前（已缩放）模型反推出旧（未缩放）模型，用与 verify_pose_v3 完全相同的
// 变换约定分别算 A 静止姿态的 tip/grip/wrist，对比 new ≈ 0.9375×old？
// 动画 position 关键帧 delta 未缩放 ⇒ 预期 new = s·old + (1−s)·(链上 delta 贡献)。
'use strict';
const fs = require('fs');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const NS = 'catoxidesbattlerebuild';
const B = ROOT + '/build/resources/main';
const readJ = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const S = 0.9375;

const DEG = Math.PI / 180;
const V = { add: (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]] };
function mat4I() { return [[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]]; }
function matMul(a, b) {
  const r = mat4I();
  for (let i=0;i<4;i++) for (let j=0;j<4;j++) { let s=0; for (let k=0;k<4;k++) s+=a[i][k]*b[k][j]; r[i][j]=s; }
  return r;
}
function matT(v) { const m=mat4I(); m[0][3]=v[0]; m[1][3]=v[1]; m[2][3]=v[2]; return m; }
function rotX(a){const c=Math.cos(a),s=Math.sin(a);return [[1,0,0,0],[0,c,-s,0],[0,s,c,0],[0,0,0,1]];}
function rotY(a){const c=Math.cos(a),s=Math.sin(a);return [[c,0,s,0],[0,1,0,0],[-s,0,c,0],[0,0,0,1]];}
function rotZ(a){const c=Math.cos(a),s=Math.sin(a);return [[c,-s,0,0],[s,c,0,0],[0,0,1,0],[0,0,0,1]];}
// JOML rotateZYX(v) = Rz(v.z)·Ry(v.y)·Rx(v.x)（10/05 源码验证；旧 Rx·Ry·Rz 是错的）
function rotZYX(e){const [x,y,z]=e.map(d=>d*DEG);return matMul(rotZ(z),matMul(rotY(y),rotX(x)));}
function matApply(m,v){return [m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2]+m[0][3], m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2]+m[1][3], m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]+m[2][3]];}

function buildBones(geo, div) {   // div = 1（当前缩放模型）或 1/S（反推旧模型）
  const bones = {}; const parents = {};
  for (const b of geo.bones) {
    const nb = { name: b.name, parent: b.parent || null,
      pivot: [-(b.pivot[0] / div), b.pivot[1] / div, b.pivot[2] / div],
      rotation: b.rotation ? [-b.rotation[0], -b.rotation[1], b.rotation[2]] : null,
      cubes: (b.cubes || []).map((c) => ({
        origin: [-(c.origin[0] + c.size[0]) / div, c.origin[1] / div, c.origin[2] / div],
        size: [c.size[0] / div, c.size[1] / div, c.size[2] / div],
        pivot: c.pivot ? [-c.pivot[0] / div, c.pivot[1] / div, c.pivot[2] / div] : null,
        rotation: c.rotation ? [-c.rotation[0], -c.rotation[1], c.rotation[2]] : null,
      })),
    };
    bones[b.name] = nb; parents[b.name] = b.parent || null;
  }
  return { bones, parents };
}
function boneLocal(bones, parents, name, anim) {
  const b = bones[name];
  const pos = (anim && anim.position) || [0, 0, 0];
  const rot = V.add((anim && anim.rotation) || [0, 0, 0], b.rotation || [0, 0, 0]);
  let m = matT(V.add(b.pivot, pos));
  m = matMul(m, rotZYX(rot));
  m = matMul(m, matT([-b.pivot[0], -b.pivot[1], -b.pivot[2]]));
  return m;
}
function boneWorld(bones, parents, name, anims) {
  let m = mat4I(); let cur = name; const chain = [];
  while (cur) { chain.unshift(cur); cur = parents[cur]; }
  for (const n of chain) m = matMul(m, boneLocal(bones, parents, n, anims[n] || null));
  return m;
}
function cubeCorners(bones, parents, boneName, cubeIdx, anims) {
  const b = bones[boneName]; const c = b.cubes[cubeIdx];
  const cp = c.pivot || [c.origin[0] + c.size[0] / 2, c.origin[1] + c.size[1] / 2, c.origin[2] + c.size[2] / 2];
  let m = boneWorld(bones, parents, boneName, anims);
  m = matMul(m, matT(cp));
  if (c.rotation) m = matMul(m, rotZYX(c.rotation));
  m = matMul(m, matT([-cp[0], -cp[1], -cp[2]]));
  const corners = [];
  for (const dx of [0, c.size[0]]) for (const dy of [0, c.size[1]]) for (const dz of [0, c.size[2]])
    corners.push(matApply(m, [c.origin[0] + dx, c.origin[1] + dy, c.origin[2] + dz]));
  return corners;
}
function valOf(v) { return Array.isArray(v) ? v.slice() : (v.post || v.pre || [0,0,0]).slice(); }
function sampleKeyframes(kfs, t) {
  if (!kfs) return [0, 0, 0];
  const e = Object.entries(kfs).map(([k, v]) => [Number(k), v]).sort((a, b) => a[0] - b[0]);
  if (t <= e[0][0]) return valOf(e[0][1]);
  if (t >= e[e.length - 1][0]) return valOf(e[e.length - 1][1]);
  for (let i = 0; i < e.length - 1; i++) if (t >= e[i][0] && t <= e[i + 1][0]) {
    const va = valOf(e[i][1]), vb = valOf(e[i + 1][1]);
    const f = (t - e[i][0]) / (e[i + 1][0] - e[i][0]);
    return va.map((x, k) => x + (vb[k] - x) * f);
  }
  return [0, 0, 0];
}
function sampleAnim(animJson, animName, t) {
  const a = animJson.animations[animName]; const out = {};
  for (const [bn, bd] of Object.entries(a.bones || {})) {
    const o = {};
    for (const prop of ['position', 'rotation', 'scale']) {
      if (!bd[prop]) continue;
      let v = sampleKeyframes(bd[prop], t);
      if (prop === 'position') v = [-v[0], v[1], v[2]];
      else if (prop === 'rotation') v = [-v[0], -v[1], v[2]];
      o[prop] = v;
    }
    if (Object.keys(o).length) out[bn] = o;
  }
  return out;
}
function keyPoints(bones, parents, anims) {
  const bladeC = cubeCorners(bones, parents, 'blade', 0, anims).concat(cubeCorners(bones, parents, 'blade', 1, anims));
  const handleC = [];
  for (let i = 0; i < bones.handle.cubes.length; i++) handleC.push(...cubeCorners(bones, parents, 'handle', i, anims));
  const pommelPt = cubeCorners(bones, parents, 'handle', 4, anims).reduce((a, b) => (b[2] < a[2] ? b : a));
  const tip = bladeC.concat(handleC).reduce((a, b) => {
    const da = (a[0]-pommelPt[0])**2 + (a[1]-pommelPt[1])**2 + (a[2]-pommelPt[2])**2;
    const db = (b[0]-pommelPt[0])**2 + (b[1]-pommelPt[1])**2 + (b[2]-pommelPt[2])**2;
    return db > da ? b : a;
  });
  const gripC = cubeCorners(bones, parents, 'handle', 3, anims);
  let grip = [0,0,0]; for (const c of gripC) grip = V.add(grip, c); grip = [grip[0]/8, grip[1]/8, grip[2]/8];
  const wrist = matApply(boneWorld(bones, parents, 'rightItem', anims), bones.rightItem.pivot);
  return { tip, grip, wrist };
}

const geo = readJ(`${B}/spark_models/entity/${NS}/player_wrist.json`)['minecraft:geometry'][0];
const still = readJ(`${B}/spark_animations/${NS}/entity/player_wrist/3rd_still.json`);
const idle = readJ(`${B}/spark_animations/${NS}/entity/player_wrist/base_state.json`);
const armIdle = sampleAnim(idle, 'state.land.idle', 0);
const swordStill = sampleAnim(still, '3rd_still', 0);
// 10/03 后文件里已是 ×0.9375 的关键帧：
//   新计算直接用文件值；旧(真原始)参考 = 文件值 ÷ 0.9375（未缩放的关键帧）
const anims = Object.assign({}, armIdle, swordStill);
const animsOld = {};
for (const [bn, d] of Object.entries(anims)) {
  animsOld[bn] = {};
  for (const [p, v] of Object.entries(d)) animsOld[bn][p] = (p === 'position' ? v.map((x) => x / S) : v.slice());
}

const cur = buildBones(geo, 1);        // 当前（×0.9375 后）
const old = buildBones(geo, S);        // 反推旧（未缩放）= 新值 ÷ 0.9375
const P = (o) => ({ bones: o.bones, parents: o.parents });
const A_new = keyPoints(cur.bones, cur.parents, anims);
const A_old = keyPoints(old.bones, old.parents, animsOld);   // 真原始：未缩放模型 + 未缩放关键帧
const f = (v) => v.map(x => x.toFixed(3)).join(',');
console.log('旧(未缩放)  tip =', f(A_old.tip), ' grip =', f(A_old.grip), ' wrist =', f(A_old.wrist));
console.log('新(×0.9375) tip =', f(A_new.tip), ' grip =', f(A_new.grip), ' wrist =', f(A_new.wrist));
console.log('新/旧 比     tip =', A_new.tip.map((x, i) => (x / A_old.tip[i]).toFixed(4)).join(', '),
  '  grip =', A_new.grip.map((x, i) => (x / A_old.grip[i]).toFixed(4)).join(', '));
console.log('新 − 0.9375×旧  tip =', A_new.tip.map((x, i) => (x - S * A_old.tip[i]).toFixed(4)).join(', '),
  '  grip =', A_new.grip.map((x, i) => (x - S * A_old.grip[i]).toFixed(4)).join(', '));
