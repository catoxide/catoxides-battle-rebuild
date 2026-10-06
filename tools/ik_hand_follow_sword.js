#!/usr/bin/env node
/**
 * ik_hand_follow_sword.js — 剑保持原版大弧挥砍 + 手臂 IK 跟着剑柄走
 *
 * 原理：
 *  · 原版挥砍绕剑 `right` 骨转，剑柄(pommel)画大弧（离腕最远 16.3px、离肩最远 ~12.5px）。
 *  · 当前模型剑挂在 rightItem(手) 下 ⇒ 手一动剑跟着动（只能"剑跟手"，v6 被否）。
 *  · 解耦：`hands.parent` rightItem → body。链上无 rest 旋转 ⇒ 静止世界位置不变，
 *    但剑脱离手臂旋转 ⇒ 剑独立走原版大弧。
 *  · IK：每帧算 pommel 世界位置 P(t)（与臂无关），臂旋转使腕落在"肩→P(t)"方向、
 *    钳到臂长 9.014px（够不到的最外沿差 ≤3.5px，手尽量贴剑柄）。
 *
 * 输出（三路同步 + MD5 自检）：
 *   模型 player_wrist.json（hands.parent=body）
 *   动画 3rd_attack.json（rightArm = IK 关键帧，26 采样 @0.02s linear）
 */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const NS = 'catoxidesbattlerebuild';
const B = ROOT + '/build/resources/main';
const PACK = ROOT + '/run/client/spark_modules/catoxidesbattlerebuild';
const SRC = ROOT + '/src/main/resources';
const readJ = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const md5 = (s) => crypto.createHash('md5').update(s).digest('hex').slice(0, 8);

// ---- 向量/矩阵（内部系 +X右/+Y上/+Z后；R=Rz·Ry·Rx；文件↔内部：pos/pivot 仅X取反，rot (fx,fy,fz)→内部(−fx,−fy,fz)）
const DEG = Math.PI / 180;
const V = {
  add: (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]],
  sub: (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]],
  scale: (a, s) => [a[0] * s, a[1] * s, a[2] * s],
  dot: (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2],
  cross: (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]],
  len: (a) => Math.hypot(a[0], a[1], a[2]),
};
function mat4I() { return [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]; }
function matMul(a, b) { const r = mat4I(); for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) { let s = 0; for (let k = 0; k < 4; k++) s += a[i][k] * b[k][j]; r[i][j] = s; } return r; }
function matT(v) { const m = mat4I(); m[0][3] = v[0]; m[1][3] = v[1]; m[2][3] = v[2]; return m; }
function rotX(a) { const c = Math.cos(a), s = Math.sin(a); return [[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]]; }
function rotY(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]]; }
function rotZ(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]; }
function rotZYX(e) { const [x, y, z] = e.map(d => d * DEG); return matMul(rotZ(z), matMul(rotY(y), rotX(x))); }
function matApply(m, v) { return [m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2] + m[0][3], m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2] + m[1][3], m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2] + m[2][3]]; }
// 3x3 旋转矩阵 → 内部欧拉弧度（R=Rz·Ry·Rx）
function matToEul3(m) {
  const sy = -m[2][0]; let x, y, z;
  if (Math.abs(sy) > 0.99999) { y = sy > 0 ? Math.PI / 2 : -Math.PI / 2; x = 0; z = Math.atan2(-m[0][1], m[1][1]); }
  else { y = Math.asin(sy); x = Math.atan2(m[2][1], m[2][2]); z = Math.atan2(m[1][0], m[0][0]); }
  return [x, y, z];
}
// 最短弧 3x3 旋转矩阵 u→v（要求 |u|=|v|）
function arcRot3(u, v) {
  const c = Math.max(-1, Math.min(1, V.dot(u, v) / (V.len(u) * V.len(v))));
  const ang = Math.acos(c);
  if (ang < 1e-9) return [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
  let axis = V.cross(u, v); let al = V.len(axis);
  if (al < 1e-6) { axis = V.cross(u, [1, 0, 0]); if (V.len(axis) < 1e-6) axis = V.cross(u, [0, 1, 0]); }
  axis = V.scale(axis, 1 / V.len(axis));
  const k = [[0, -axis[2], axis[1]], [axis[2], 0, -axis[0]], [-axis[1], axis[0], 0]];
  const k2 = k.map((row, i) => [0, 1, 2].map(j => k[i][0] * k[0][j] + k[i][1] * k[1][j] + k[i][2] * k[2][j]));
  const ct = Math.cos(ang), st = Math.sin(ang), t = 1 - ct;
  // Rodrigues: R = I + K·sinθ + K²·(1−cosθ)
  return [0, 1, 2].map(i => [0, 1, 2].map(j => (i === j ? 1 : 0) + k[i][j] * st + k2[i][j] * t));
}

// ---- 读模型（内部系）
const modelJson = readJ(`${B}/spark_models/entity/${NS}/player_wrist.json`);
const geo = modelJson['minecraft:geometry'][0];
const bones = {}; const parents = {};
for (const b of geo.bones) { bones[b.name] = b; parents[b.name] = b.parent || null; }
function toInternalBone(b) {
  const o = { name: b.name, parent: b.parent, pivot: [-b.pivot[0], b.pivot[1], b.pivot[2]] };
  if (b.rotation) o.rotation = [-b.rotation[0], -b.rotation[1], b.rotation[2]];
  if (b.cubes) o.cubes = b.cubes.map((c) => {
    const n = { origin: [-(c.origin[0] + c.size[0]), c.origin[1], c.origin[2]], size: c.size.slice() };
    if (c.pivot) n.pivot = [-c.pivot[0], c.pivot[1], c.pivot[2]];
    if (c.rotation) n.rotation = [-c.rotation[0], -c.rotation[1], c.rotation[2]];
    return n;
  });
  return o;
}
for (const n of Object.keys(bones)) bones[n] = toInternalBone(bones[n]);

function boneLocal(name, anim) {
  const b = bones[name];
  const pos = (anim && anim.position) || [0, 0, 0];
  const rot = V.add((anim && anim.rotation) || [0, 0, 0], b.rotation || [0, 0, 0]);
  const scl = (anim && anim.scale) || [1, 1, 1];
  let m = matT(V.add(b.pivot, pos));
  m = matMul(m, rotZYX(rot));
  const ms = mat4I(); ms[0][0] = scl[0]; ms[1][1] = scl[1]; ms[2][2] = scl[2];
  m = matMul(m, ms);
  m = matMul(m, matT([-b.pivot[0], -b.pivot[1], -b.pivot[2]]));
  return m;
}
function boneWorld(name, anims) { let m = mat4I(); let cur = name; const chain = []; while (cur) { chain.unshift(cur); cur = parents[cur]; } for (const n of chain) m = matMul(m, boneLocal(n, anims[n] || null)); return m; }
function cubeCenter(boneName, cubeIdx, anims) {
  const b = bones[boneName]; const c = b.cubes[cubeIdx];
  const cp = c.pivot || [c.origin[0] + c.size[0] / 2, c.origin[1] + c.size[1] / 2, c.origin[2] + c.size[2] / 2];
  let m = boneWorld(boneName, anims); m = matMul(m, matT(cp));
  if (c.rotation) m = matMul(m, rotZYX(c.rotation));
  m = matMul(m, matT([-cp[0], -cp[1], -cp[2]]));
  return matApply(m, [c.origin[0] + c.size[0] / 2, c.origin[1] + c.size[1] / 2, c.origin[2] + c.size[2] / 2]);
}
function valOf(v) { return Array.isArray(v) ? v.slice() : (v.post || v.pre || [0, 0, 0]).slice(); }
function sampleKeyframes(kfs, t) {
  if (!kfs) return [0, 0, 0];
  const e = Object.entries(kfs).map(([k, v]) => [Number(k), v]).sort((a, b) => a[0] - b[0]);
  if (e.length === 1) return valOf(e[0][1]);
  if (t <= e[0][0]) return valOf(e[0][1]);
  if (t >= e[e.length - 1][0]) return valOf(e[e.length - 1][1]);
  for (let i = 0; i < e.length - 1; i++) { if (t >= e[i][0] && t <= e[i + 1][0]) { const va = valOf(e[i][1]), vb = valOf(e[i + 1][1]); const f = (t - e[i][0]) / (e[i + 1][0] - e[i][0]); return va.map((x, k) => x + (vb[k] - x) * f); } }
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

// ---- 1. 解耦：hands.parent rightItem → body（静止世界位置不变）
const handsBone = geo.bones.find(b => b.name === 'hands');
const oldParent = handsBone.parent;
handsBone.parent = 'body';
parents['hands'] = 'body'; // 同步仿真用 parents
console.log(`解耦: hands.parent ${oldParent} → body`);

// ---- 2. IK：每帧 pommel 世界位置（解耦后与臂无关）→ 臂旋转
const attack = readJ(`${B}/spark_animations/${NS}/entity/player_wrist/3rd_attack.json`);
const S = [5, 21.5, 0];            // 肩（rightArm pivot 内部系）
const Wrest = [5.5, 12.5, 0];      // 腕 rest（rightItem pivot 内部系）
const armLen = V.len(V.sub(Wrest, S));
const rest = V.sub(Wrest, S);
console.log(`肩=${S} 腕rest=${Wrest} 臂长=${armLen.toFixed(3)}px`);

function gripWorld(t) {
  const anims = sampleAnim(attack, '3rd_attack', t);
  const sword = {};
  for (const k of Object.keys(anims)) { if (k === 'rightArm' || k === 'rightForeArm') continue; sword[k] = anims[k]; }
  return cubeCenter('handle', 3, sword); // 握持点 = handle cube3（剑柄中段，手握处）
}

const N = 26; // t=0..0.5 每 0.02s
const kfs = {};
let maxGap = 0, maxGapT = 0;
console.log('\nt      握持点            |P−肩|   缺口    臂rot(文件系)');
for (let i = 0; i < N; i++) {
  const t = 0.5 * i / (N - 1);
  const P = gripWorld(t);
  const dP = V.sub(P, S); const distP = V.len(dP);
  const target = V.scale(dP, armLen / Math.max(distP, 1e-9)); // 腕目标（钳臂长）
  const R = arcRot3(rest, target);
  const eul = matToEul3(R); // 内部弧度
  const file = [+(-eul[0] / DEG).toFixed(2), +(-eul[1] / DEG).toFixed(2), +(eul[2] / DEG).toFixed(2)];
  kfs[String(+t.toFixed(3))] = { post: file, lerp_mode: 'linear' };
  const gap = distP - armLen;
  if (gap > maxGap) { maxGap = gap; maxGapT = t; }
  if (i % 2 === 0) console.log(`  ${t.toFixed(3)}  (${P[0].toFixed(2)},${P[1].toFixed(2)},${P[2].toFixed(2)})  ${distP.toFixed(2).padStart(6)}  ${gap.toFixed(2).padStart(6)}  [${file}]`);
}
console.log(`\nIK 采样 ${N} 帧；腕追 pommel 最大缺口 = ${maxGap.toFixed(2)}px @t=${maxGapT.toFixed(2)}`);

// ---- 3. 写动画：替换 3rd_attack 的 rightArm 关键帧（rightForeArm 保留，模型无此骨被忽略）
const atkBones = attack.animations['3rd_attack'].bones;
atkBones.rightArm = { rotation: kfs };

// ---- 4. 三路同步 + MD5 自检
const modelStr = JSON.stringify(modelJson, null, 2) + '\n';
const atkStr = JSON.stringify(attack, null, 2) + '\n';
const modelPaths = [
  [PACK + `/models/${NS}/entity/player_wrist.json`],
  [SRC + `/spark_models/entity/${NS}/player_wrist.json`],
  [B + `/spark_models/entity/${NS}/player_wrist.json`],
];
const atkPaths = [
  [PACK + `/animations/${NS}/entity/player_wrist/3rd_attack.json`],
  [SRC + `/spark_animations/${NS}/entity/player_wrist/3rd_attack.json`],
  [B + `/spark_animations/${NS}/entity/player_wrist/3rd_attack.json`],
];
const mhash = md5(modelStr), ahash = md5(atkStr);
for (const [p] of modelPaths) { fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, modelStr, 'utf8'); }
for (const [p] of atkPaths) { fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, atkStr, 'utf8'); }
console.log(`\nmodel MD5=${mhash} (3路)  attack MD5=${ahash} (3路)`);
for (const [p] of modelPaths) { const h = md5(fs.readFileSync(p, 'utf8')); if (h !== mhash) { console.error('MD5 不符:', p); process.exit(1); } }
for (const [p] of atkPaths) { const h = md5(fs.readFileSync(p, 'utf8')); if (h !== ahash) { console.error('MD5 不符:', p); process.exit(1); } }
console.log('三路 MD5 一致 ✓  done');
