#!/usr/bin/env node
/**
 * search_embedding_v3.js — 搜索让"用户剑动画(3rd_still/3rd_attack 原样)"在玩家模型上
 * 满足 4 条姿态要求的嵌入刚体变换 A（锚点 wrist，B = wrist − A·anchor）。
 *
 * 姿态要求（玩家模型系：+Y 上，+Z 玩家前，−X 玩家右）：
 *   R1 剑尖 y > 剑柄 y（静止）
 *   R2 剑尖在剑柄前方 +Z（静止）
 *   R3 挥击(3rd_attack)中剑尖 y 先升高再降低（峰值在中段）
 *   附加合理性：剑柄离手腕近(≤14)、静止剑尖在地面之上(y≥0)、剑尖在玩家前方(z>腕z)
 *
 * 候选：A = Euler(a,b,c) ∈ {0,±90,180}³（旋转群 24 向 + 重复），anchor ∈ {main, hands}。
 * 每个候选完整重算嵌入（pivot/cube origin 用 A，size 用 Tsize，cube rotation 用 Trot）后
 * 模拟 3rd_still@0 与 3rd_attack@t 采样。
 */
'use strict';
const fs = require('fs');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const NS = 'catoxidesbattlerebuild';
const B = ROOT + '/build/resources/main';
const readJ = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));

const geo = readJ(`${B}/spark_models/entity/${NS}/player_wrist.json`)['minecraft:geometry'][0];
const bodyBones = geo.bones.filter((b) => !['hands', 'left', 'right', 'main', 'shade', 'blade', 'handle'].includes(b.name));
const parents = {};
for (const b of geo.bones) parents[b.name] = b.parent || null;
const bodyBoneMap = {};
for (const b of bodyBones) bodyBoneMap[b.name] = b;

const swordGeo = readJ('D:/Download/sword.geo.json')['minecraft:geometry'][0];
const swordBoneMap = {};
for (const b of swordGeo.bones) swordBoneMap[b.name] = b;

const ORIGIN_MODE = process.argv.includes('--tpt') ? 'tpt' : 'mincorner'; // tpt=旧约定(origin=A·o+B)，mincorner=刚体精确
console.log('ORIGIN_MODE =', ORIGIN_MODE);
const wrist = bodyBoneMap.rightItem.pivot.slice();
const A0 = [[1, 0, 0], [0, 0, -1], [0, 1, 0]]; // 当前 A=Rx(90)（对照基准）
const DEG = Math.PI / 180;
const FACE_MAP = { north: 'down', south: 'up', up: 'north', down: 'south', west: 'west', east: 'east' };
const SWORD_PARENT = { hands: 'rightItem', left: 'hands', right: 'hands', main: 'right', shade: 'main', blade: 'main', handle: 'main' };
const STRIP = new Set(['left', 'right']);

// ---------------------------------------------------------------- 矩阵
function mat4I() { return [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]; }
function matMul(a, b) {
  const r = mat4I();
  for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) { let s = 0; for (let k = 0; k < 4; k++) s += a[i][k] * b[k][j]; r[i][j] = s; }
  return r;
}
const matT = (v) => { const m = mat4I(); m[0][3] = v[0]; m[1][3] = v[1]; m[2][3] = v[2]; return m; };
function rotX(a) { const c = Math.cos(a), s = Math.sin(a); return [[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]]; }
function rotY(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]]; }
function rotZ(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]; }
function rotZYX(e) { return matMul(rotZ(e[2] * DEG), matMul(rotY(e[1] * DEG), rotX(e[0] * DEG))); }
const matApply = (m, v) => [
  m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2] + m[0][3],
  m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2] + m[1][3],
  m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2] + m[2][3],
];
// 3x3 共轭 R' = A·R·A⁻¹（A 正交）
function eulToMat3(e) {
  const m = rotZYX(e);
  return [[m[0][0], m[0][1], m[0][2]], [m[1][0], m[1][1], m[1][2]], [m[2][0], m[2][1], m[2][2]]];
}
function mat3Mul(a, b) {
  const r = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) for (let k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
  return r;
}
function mat3Inv(a) { // 转置（正交）
  return [[a[0][0], a[1][0], a[2][0]], [a[0][1], a[1][1], a[2][1]], [a[0][2], a[1][2], a[2][2]]];
}
function mat3Apply(a, v) {
  return [a[0][0] * v[0] + a[0][1] * v[1] + a[0][2] * v[2], a[1][0] * v[0] + a[1][1] * v[1] + a[1][2] * v[2], a[2][0] * v[0] + a[2][1] * v[1] + a[2][2] * v[2]];
}
function mat3ToEul(a) {
  const sy = -a[2][0];
  let x, y, z;
  if (Math.abs(sy) > 0.99999) { y = sy > 0 ? Math.PI / 2 : -Math.PI / 2; x = 0; z = Math.atan2(-a[0][1], a[1][1]); }
  else { y = Math.asin(sy); x = Math.atan2(a[2][1], a[2][2]); z = Math.atan2(a[1][0], a[0][0]); }
  return [x / DEG, y / DEG, z / DEG];
}

// ---------------------------------------------------------------- 生成嵌入后的剑骨（文件坐标，随后转内部系）
function buildSwordBones(A3, anchorPivot) {
  const Bv = [wrist[0] - mat3Apply(A3, anchorPivot)[0], wrist[1] - mat3Apply(A3, anchorPivot)[1], wrist[2] - mat3Apply(A3, anchorPivot)[2]];
  const Tpt = (p) => { const q = mat3Apply(A3, p); return [q[0] + Bv[0], q[1] + Bv[1], q[2] + Bv[2]]; };
  const Tsize = (v) => mat3Apply(A3, v).map((x) => Math.abs(x)); // A 为 90° 旋转 → size 轴置换，取绝对值
  const Trot = (e) => mat3ToEul(mat3Mul(mat3Mul(A3, eulToMat3(e)), mat3Inv(A3)));
  // cube origin 必须取旋转后包围盒的最小角（Spark: box = [origin, origin+size]，OVertexSet 已核对）：
  //   origin' = A·o + B + min(0, A·s)（逐分量）；旧版用 Tpt(o) 会把每个 cube 平移 |A·s| 的负分量，剑体散架
  const Torigin = (o, s) => { const q = mat3Apply(A3, s); return [
    Tpt(o)[0] + Math.min(0, q[0]), Tpt(o)[1] + Math.min(0, q[1]), Tpt(o)[2] + Math.min(0, q[2]) ]; };
  const out = {};
  for (const name of Object.keys(SWORD_PARENT)) {
    const src = swordBoneMap[name];
    const b = { name, parent: SWORD_PARENT[name], pivot: Tpt(src.pivot), rotation: src.rotation ? Trot(src.rotation) : undefined };
    if (!STRIP.has(name) && src.cubes && src.cubes.length) {
      b.cubes = src.cubes.map((c) => {
        const o = { origin: ORIGIN_MODE === 'tpt' ? Tpt(c.origin) : Torigin(c.origin, c.size), size: Tsize(c.size) };
        if (c.pivot) o.pivot = Tpt(c.pivot);
        if (c.rotation) o.rotation = Trot(c.rotation);
        return o;
      });
    }
    out[name] = toInternalBone(b);
  }
  return out;
}

// ---------------------------------------------------------------- 姿态模拟
// Spark 解析约定（ModelModule + OBoneAnimation，已核对源码）：
//   骨 pivot/cube pivot: (−x, y, z)·1/16；cube origin: (−(ox+sx), oy, oz)·1/16；size: ·1/16
//   骨/cube 旋转: (−rx, −ry, rz) 弧度；动画旋转: (−rx, −ry, rz) 弧度
//   动画 position: (−px, py, pz)·1/16
// 1/16 是均匀缩放，相对姿态验算可省略（不影响 y/z 比较与角度）。
function toInternalBone(b) {
  const o = { name: b.name, parent: b.parent, pivot: [-b.pivot[0], b.pivot[1], b.pivot[2]] };
  if (b.rotation) o.rotation = [-b.rotation[0], -b.rotation[1], b.rotation[2]];
  if (b.cubes) {
    o.cubes = b.cubes.map((c) => {
      const n = { origin: [-(c.origin[0] + c.size[0]), c.origin[1], c.origin[2]], size: c.size.slice() };
      if (c.uv) n.uv = c.uv;
      if (c.pivot) n.pivot = [-c.pivot[0], c.pivot[1], c.pivot[2]];
      if (c.rotation) n.rotation = [-c.rotation[0], -c.rotation[1], c.rotation[2]];
      return n;
    });
  }
  return o;
}
// 已采样动画值（{bone:{position:[..],rotation:[..],scale:[..]}}）→ 内部系
function internalizeSampled(s) {
  const o = {};
  for (const [bn, bd] of Object.entries(s || {})) {
    const n = {};
    for (const [prop, v] of Object.entries(bd)) {
      if (prop === 'position') n[prop] = [-v[0], v[1], v[2]];
      else if (prop === 'rotation') n[prop] = [-v[0], -v[1], v[2]];
      else n[prop] = v;
    }
    o[bn] = n;
  }
  return o;
}
const still = readJ(`${B}/spark_animations/${NS}/entity/player_wrist/3rd_still.json`);
const attack = readJ(`${B}/spark_animations/${NS}/entity/player_wrist/3rd_attack.json`);
const idle = readJ(`${B}/spark_animations/${NS}/entity/player_wrist/state.land.idle.json`);
const bodyBonesInternal = bodyBones.map(toInternalBone);
const bodyBoneMapInt = {};
for (const b of bodyBonesInternal) bodyBoneMapInt[b.name] = b;

function valOf(v) { return Array.isArray(v) ? v.slice() : (v.post || v.pre || [0, 0, 0]).slice(); }
function sampleKF(kfs, t) {
  if (!kfs) return [0, 0, 0];
  const es = Object.entries(kfs).map(([k, v]) => [Number(k), v]).sort((a, b) => a[0] - b[0]);
  if (es.length === 1 || t <= es[0][0]) return valOf(es[0][1]);
  if (t >= es[es.length - 1][0]) return valOf(es[es.length - 1][1]);
  for (let i = 0; i < es.length - 1; i++) {
    if (t >= es[i][0] && t <= es[i + 1][0]) {
      const va = valOf(es[i][1]), vb = valOf(es[i + 1][1]);
      const f = (t - es[i][0]) / (es[i + 1][0] - es[i][0]);
      return va.map((x, k) => x + (vb[k] - x) * f);
    }
  }
  return [0, 0, 0];
}
function sampleAnim(animJson, name, t) {
  const a = animJson.animations[name];
  const out = {};
  for (const [bn, bd] of Object.entries(a.bones || {})) {
    const o = {};
    for (const prop of ['position', 'rotation', 'scale']) if (bd[prop]) o[prop] = sampleKF(bd[prop], t);
    if (Object.keys(o).length) out[bn] = o;
  }
  return out;
}
function evalPose(swordBones, anims) {
  // 骨世界矩阵链（内部系：已含 X 取反 + 旋转 (−x,−y,z)）
  const all = {};
  for (const b of bodyBonesInternal) all[b.name] = b;
  for (const n in swordBones) all[n] = swordBones[n];
  const par = {};
  for (const n in all) par[n] = (all[n].parent || null);
  function boneWorld(name, anim) {
    let m = mat4I();
    let cur = name;
    const chain = [];
    while (cur) { chain.unshift(cur); cur = par[cur]; }
    for (const n of chain) {
      const b = all[n];
      const a = anim[n] || null;
      const pos = (a && a.position) || [0, 0, 0];
      const rot = (a && a.rotation) ? a.rotation.map((x, i) => x + ((b.rotation && b.rotation[i]) || 0)) : (b.rotation ? b.rotation.slice() : [0, 0, 0]);
      let mm = matT([b.pivot[0] + pos[0], b.pivot[1] + pos[1], b.pivot[2] + pos[2]]);
      mm = matMul(mm, rotZYX(rot));
      mm = matMul(mm, matT([-b.pivot[0], -b.pivot[1], -b.pivot[2]]));
      m = matMul(m, mm);
    }
    return m;
  }
  function cubeCorners(bn, ci, anim) {
    const b = all[bn], c = b.cubes[ci];
    const cp = c.pivot || [c.origin[0] + c.size[0] / 2, c.origin[1] + c.size[1] / 2, c.origin[2] + c.size[2] / 2];
    let m = boneWorld(bn, anim);
    m = matMul(m, matT(cp));
    if (c.rotation) m = matMul(m, rotZYX(c.rotation));
    m = matMul(m, matT([-cp[0], -cp[1], -cp[2]]));
    const cs = [];
    for (const dx of [0, c.size[0]]) for (const dy of [0, c.size[1]]) for (const dz of [0, c.size[2]]) {
      cs.push(matApply(m, [c.origin[0] + dx, c.origin[1] + dy, c.origin[2] + dz]));
    }
    return cs;
  }
  const bladeC = cubeCorners('blade', 0, anims).concat(cubeCorners('blade', 1, anims));
  const pommelC = cubeCorners('handle', 4, anims);
  const pommel = pommelC.reduce((a, b) => (b[2] < a[2] ? b : a));
  const tip = bladeC.reduce((a, b) => {
    const d = (p) => (p[0] - pommel[0]) ** 2 + (p[1] - pommel[1]) ** 2 + (p[2] - pommel[2]) ** 2;
    return d(b) > d(a) ? b : a;
  });
  const gripC = cubeCorners('handle', 3, anims);
  let grip = [0, 0, 0];
  for (const c of gripC) grip = [grip[0] + c[0], grip[1] + c[1], grip[2] + c[2]];
  grip = [grip[0] / 8, grip[1] / 8, grip[2] / 8];
  const wristW = matApply(boneWorld('rightItem', anims), all.rightItem.pivot);
  return { tip, grip, pommel, wrist: wristW };
}

// 手臂 idle@0（不变）
const armIdle = internalizeSampled(sampleAnim(idle, 'state.land.idle', 0));
const stillAnim = internalizeSampled(sampleAnim(still, '3rd_still', 0));

function evaluate(A3, anchor, anchorName) {
  const swordBones = buildSwordBones(A3, anchor);
  const p = evalPose(swordBones, Object.assign({}, armIdle, stillAnim));
  // R1/R2 静止
  const r1 = p.tip[1] > p.grip[1];
  const r2 = p.tip[2] > p.grip[2];
  const gripDist = Math.hypot(p.grip[0] - p.wrist[0], p.grip[1] - p.wrist[1], p.grip[2] - p.wrist[2]);
  const tipAboveGround = p.tip[1] >= -2;
  const tipInFront = p.tip[2] > p.wrist[2] + 2;
  // 指向性：grip→tip 方向与玩家前(+Z)的夹角
  const dir = [p.tip[0] - p.grip[0], p.tip[1] - p.grip[1], p.tip[2] - p.grip[2]];
  const len = Math.hypot(dir[0], dir[1], dir[2]) || 1;
  const facingDeg = Math.acos(Math.max(-1, Math.min(1, dir[2] / len))) / DEG; // 0=正前, 90=纯侧, 180=朝后
  // 挥击弧线
  let ys = [];
  for (let i = 0; i <= 20; i++) {
    const t = 0.5 * i / 20;
    const pp = evalPose(swordBones, Object.assign({}, armIdle, internalizeSampled(sampleAnim(attack, '3rd_attack', t))));
    ys.push(pp.tip[1]);
  }
  const maxI = ys.indexOf(Math.max(...ys));
  const r3 = maxI >= 3 && maxI <= 17 && ys[maxI] > ys[0] + 1 && ys[maxI] > ys[ys.length - 1] + 1;
  const minY = Math.min(...ys);
  return { A3, anchorName, p, r1, r2, r3, gripDist, tipAboveGround, tipInFront, minY, arcPeakY: ys[maxI], arcStartY: ys[0], facingDeg };
}

// ---------------------------------------------------------------- 搜索
const candidates = [];
for (const a of [0, 90, -90, 180]) for (const b of [0, 90, -90, 180]) for (const c of [0, 90, -90, 180]) {
  const A4 = rotZYX([a, b, c]);
  const A3 = [[A4[0][0], A4[0][1], A4[0][2]], [A4[1][0], A4[1][1], A4[1][2]], [A4[2][0], A4[2][1], A4[2][2]]];
  // 去重：与已有候选的 A 相同则跳过
  const sig = A3.flat().map((x) => Math.round(x)).join(',');
  if (candidates.some((x) => x.sig === sig)) continue;
  candidates.push({ a, b, c, A3, sig });
}
console.log(`候选 A 数: ${candidates.length}（×2 锚点 = main/hands）`);

const results = [];
for (const cand of candidates) {
  for (const [anchorName, anchorBone] of [['main', 'main'], ['hands', 'hands']]) {
    const r = evaluate(cand.A3, swordBoneMap[anchorBone].pivot, anchorName);
    let score = 0;
    if (r.r1) score += 2;
    if (r.r2) score += 2;
    if (r.r3) score += 3;
    if (r.gripDist <= 14) score += 2;
    if (r.gripDist <= 10) score += 1;
    if (r.tipAboveGround) score += 1;
    if (r.tipInFront) score += 2;
    if (r.minY >= -8) score += 1;
    // 指向玩家前方（第三视角正前方）
    if (r.facingDeg <= 45) score += 3;
    else if (r.facingDeg <= 60) score += 2;
    else if (r.facingDeg <= 75) score += 1;
    r.score = score;
    results.push(r);
  }
}
results.sort((x, y) => y.score - x.score);
console.log('\n=== 全量 48 候选（按分排序，含弧线细节）===');
console.log('A(行) | 锚 | 分 | R1 R2 R3 | 柄距腕 | 尖y | 指向角 | 弧线 起/峰/谷 | 升/降');
for (const r of results) {
  const rise = r.arcPeakY - r.arcStartY, fall = r.arcPeakY - r.minY;
  console.log(
    `(${r.A3[0][0].toFixed(0)},${r.A3[0][1].toFixed(0)},${r.A3[0][2].toFixed(0)}|${r.A3[1][0].toFixed(0)},${r.A3[1][1].toFixed(0)},${r.A3[1][2].toFixed(0)}|${r.A3[2][0].toFixed(0)},${r.A3[2][1].toFixed(0)},${r.A3[2][2].toFixed(0)}) ` +
    `${r.anchorName.padEnd(5)} ${String(r.score).padStart(2)} | ${r.r1 ? '✓' : '✗'}${r.r2 ? '✓' : '✗'}${r.r3 ? '✓' : '✗'} | ${String(r.gripDist.toFixed(1)).padStart(5)} | ${String(r.p.tip[1].toFixed(1)).padStart(5)} | ${String(r.facingDeg.toFixed(0)).padStart(4)}° | ` +
    `${r.arcStartY.toFixed(1)}/${r.arcPeakY.toFixed(1)}/${r.minY.toFixed(1)} | +${rise.toFixed(1)}/-${fall.toFixed(1)}`
  );
}
console.log('\n=== Top 12 候选（按新评分） ===');
for (const r of results.slice(0, 12)) {
  const f = (v) => v.map((n) => n.toFixed(1)).join(',');
  console.log(`score=${r.score} A=(${r.A3[0][0].toFixed(0)},${r.A3[0][1].toFixed(0)},${r.A3[0][2].toFixed(0)}|${r.A3[1][0].toFixed(0)},${r.A3[1][1].toFixed(0)},${r.A3[1][2].toFixed(0)}|${r.A3[2][0].toFixed(0)},${r.A3[2][1].toFixed(0)},${r.A3[2][2].toFixed(0)}) anchor=${r.anchorName}`);
  console.log(`   静止: tip=(${f(r.p.tip)}) grip=(${f(r.p.grip)}) 腕=(${f(r.p.wrist)}) | R1=${r.r1} R2=${r.r2} 柄距腕=${r.gripDist.toFixed(1)} 尖离地=${r.p.tip[1].toFixed(1)} 指向角=${r.facingDeg.toFixed(0)}°`);
  console.log(`   弧线: 起=${r.arcStartY.toFixed(1)} 峰=${r.arcPeakY.toFixed(1)} 最低=${r.minY.toFixed(1)} R3=${r.r3}`);
}
// 恒等 A=I 两个锚点详情
console.log('\n=== 恒等 A=I（item 第一视角同向）详情 ===');
const I3 = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
for (const anchorName of ['main', 'hands']) {
  const r = evaluate(I3, swordBoneMap[anchorName].pivot, anchorName);
  const f = (v) => v.map((n) => n.toFixed(1)).join(',');
  console.log(`anchor=${anchorName}: score=${r.score} R1=${r.r1} R2=${r.r2} R3=${r.r3} 柄距腕=${r.gripDist.toFixed(1)} 尖离地=${r.p.tip[1].toFixed(1)} 指向角=${r.facingDeg.toFixed(0)}° 弧线 起=${r.arcStartY.toFixed(1)} 峰=${r.arcPeakY.toFixed(1)} 最低=${r.minY.toFixed(1)}`);
  console.log(`   tip=(${f(r.p.tip)}) grip=(${f(r.p.grip)}) 腕=(${f(r.p.wrist)})`);
}
// 对照：当前 build 里的 A=Rx(90)+main 锚
const cur = evaluate(A0, swordBoneMap.main.pivot, 'main');
console.log(`\n[对照] 当前 A=Rx(90) anchor=main: score=${cur.score} R1=${cur.r1} R2=${cur.r2} R3=${cur.r3} 柄距腕=${cur.gripDist.toFixed(1)} 尖离地=${cur.p.tip[1].toFixed(1)} 指向角=${cur.facingDeg.toFixed(0)}°`);
