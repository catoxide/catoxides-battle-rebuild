#!/usr/bin/env node
/**
 * gen_wrist_sword_anim_v3.js
 *
 * 生成 player_wrist 的 MAIN 组动画（3rd_still / 3rd_attack），在用户剑物品动画
 * （main/left/right/shade，10/05 起经 A=Ry180 嵌入转换：rotation (fx,fy,fz)→(−fx,fy,−fz)、
 * position (px,py,pz)→(−px,py,−pz)，scale 原样）基础上加入抬臂关键帧（rightArm / rightForeArm）：
 *
 *   需求 4"手臂最低点朝上" → 手腕高于肘部（前臂朝上）。
 *   抬臂 IK（内部系数值解，Spark 精确约定）：
 *     · 肩 S、rest 肘 E0、rest 腕 W0 取自 steve.json 骨 pivot
 *     · 目标腕 W1（肩高、身前）→ 刚性腕位 E1' = 球(S,|W0−S|) ∩ 球(E0,|W1−E0|) 交线
 *     · θ 参数化 72 采样：E1'→R1=arcRot(W0−S, E1'−S)→E1=S+R1·(E0−S)
 *     · R2=arcRot(E1'−E0, W1−E0)（绕 rest 肘 pivot 的局部旋转，世界系弧）
 *     · 选 θ：硬约束 W1.y>E1.y+1（腕高于肘）；最小化 |R1|+|R2|（最自然）
 *   关键帧写文件系度数：Spark 解析时内部旋转 = (−fx, −fy, fz)，故 文件 = (−ix, −iy, iz)
 *
 * 输出（pack + src + build/resources/main 各一份）：
 *   spark_animations/<NS>/entity/player_wrist/3rd_still.json   （剑骨原样 + 臂 0 点静态）
 *   spark_animations/<NS>/entity/player_wrist/3rd_attack.json  （剑骨原样 + 臂全程钉死）
 *
 * 注意：state.land.idle/move 的 rightArm 由 gen_wrist_state_anim_v3.js 负责剔除
 *       （同一根骨不能被 MAIN 与 STATE 两个组同时驱动）。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const NS = 'catoxidesbattlerebuild';
const BODY_GEO = ROOT + '/tools/spark_player_model/steve.json';
const PACK = ROOT + '/run/client/spark_modules/catoxidesbattlerebuild';
const SRC = ROOT + '/src/main/resources';
const BUILD = ROOT + '/build/resources/main';

const DEG = Math.PI / 180;

// ---------------------------------------------------------------- 向量/矩阵
const vsub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const vadd = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const vscale = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const vdot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const vcross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const vlen = (a) => Math.hypot(a[0], a[1], a[2]);
const vnorm = (a) => vscale(a, 1 / vlen(a));
const matI = () => [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
const matMul = (a, b) => a.map((row, i) => [0, 1, 2].map((j) => row[0] * b[0][j] + row[1] * b[1][j] + row[2] * b[2][j]));
const matApply = (m, v) => [vdot(m[0], v), vdot(m[1], v), vdot(m[2], v)];
function rotX(a) { const c = Math.cos(a), s = Math.sin(a); return [[1, 0, 0], [0, c, -s], [0, s, c]]; }
function rotY(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, 0, s], [0, 1, 0], [-s, 0, c]]; }
function rotZ(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, -s, 0], [s, c, 0], [0, 0, 1]]; }
// R = Rz·Ry·Rx（Spark/JOML rotateZYX：rotateZYX(v)=Rz(v.z)·Ry(v.y)·Rx(v.x)，
// JOML 1.10.5 源码+字节码双重验证，10/05；旧 Rx·Ry·Rz 写法是错的——自检腕落点 1.9px 偏差的根因）
const rotZYX = (e) => matMul(rotZ(e[2]), matMul(rotY(e[1]), rotX(e[0])));
function matToEul(m) {
  const sy = -m[2][0];
  let x, y, z;
  if (Math.abs(sy) > 0.99999) {
    y = sy > 0 ? Math.PI / 2 : -Math.PI / 2;
    x = 0;
    z = Math.atan2(-m[0][1], m[1][1]);
  } else {
    y = Math.asin(sy);
    x = Math.atan2(m[2][1], m[2][2]);
    z = Math.atan2(m[1][0], m[0][0]);
  }
  return [x, y, z];
}
// 最短弧旋转 u→v（Rodrigues）
function arcRot(u, v) {
  const lu = vlen(u), lv = vlen(v);
  const c = Math.max(-1, Math.min(1, vdot(u, v) / (lu * lv)));
  const ang = Math.acos(c);
  if (ang < 1e-9) return { m: matI(), ang: 0 };
  let axis = vcross(u, v);
  const alen = vlen(axis);
  if (alen < 1e-6) axis = vnorm(vcross(u, [1, 0, 0]) || vcross(u, [0, 1, 0])); // 共线反向
  else axis = vnorm(axis);
  // Rodrigues：R = I + K·(1−cosθ) + K²·sinθ，K = skew(axis)
  const k = [[0, -axis[2], axis[1]], [axis[2], 0, -axis[0]], [-axis[1], axis[0], 0]];
  const k2 = matMul(k, k);
  const ct = Math.cos(ang), st = Math.sin(ang), t = 1 - ct;
  const m = matI().map((row, i) => row.map((diag, j) => diag + k[i][j] * st + k2[i][j] * t));
  return { m, ang };
}

// ---------------------------------------------------------------- 读输入
const readJ = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const bodyBones = {};
for (const b of readJ(BODY_GEO)['minecraft:geometry'][0].bones) bodyBones[b.name] = b;
for (const n of ['rightArm', 'rightForeArm', 'rightItem']) {
  if (!bodyBones[n]) { console.error('自检失败: steve.json 缺 ' + n); process.exit(1); }
}
const itemStill = readJ(PACK + `/animations/${NS}/item/sword/3rd_still.json`);
const itemAttack = readJ(PACK + `/animations/${NS}/item/sword/3rd_attack.json`);
const stillBones = itemStill.animations['3rd_still'].bones;
const attackBones = itemAttack.animations['3rd_attack'].bones;
for (const n of ['main', 'left', 'right', 'shade']) {
  if (!stillBones[n]) { console.error('自检失败: 物品 3rd_still 缺骨 ' + n); process.exit(1); }
}

// ---------------------------------------------------------------- 抬臂（内部系，无脱位方案）
// Spark/Bedrock 链 world = M_item·M_fore·M_arm·v：子骨旋转绕"rest pivot 的模型系位置"。
// 若上臂转动（肘 E0→E1）且前臂自由旋转，肘关节会脱位 |R2·(E1−E0)−(E1−E0)|。
// 无脱位方案：上臂保持 rest（R1=单位阵，肘恒在 E0），前臂绕肘 E0 摆到目标腕 W1：
//   · W1 = E0 + L·d̂，L = |W0−E0|（前臂长，刚性保持）
//   · R2 = arcRot(W0−E0, W1−E0)（纯绕肘旋转，肘关节永不脱位）
// 姿态 = 上臂自然下垂 + 前臂抬起朝前上方 → 腕高于肘（需求 4"最低点朝上"），剑握于胸前。
const S = [-bodyBones.rightArm.pivot[0], bodyBones.rightArm.pivot[1], bodyBones.rightArm.pivot[2]];
const E0 = [-bodyBones.rightForeArm.pivot[0], bodyBones.rightForeArm.pivot[1], bodyBones.rightForeArm.pivot[2]];
const W0 = [-bodyBones.rightItem.pivot[0], bodyBones.rightItem.pivot[1], bodyBones.rightItem.pivot[2]];
const d2 = vsub(W0, E0);              // rest 前臂向量（肘→腕）
const L = vlen(d2);                   // 前臂长
const dirW = [-0.5, 4.1, 3.6];        // 期望腕向（内部系）：前上方、略收向中线
const W1 = vadd(E0, vscale(vnorm(dirW), L));
const E1 = E0.slice();                // 肘不动（R1=I）
const R1 = { m: matI(), ang: 0 };
const R2 = arcRot(d2, vsub(W1, E0));
const wristAbove = W1[1] - E1[1];
console.log(`IK: S=${JSON.stringify(S)} E0=${JSON.stringify(E0)} W0=${JSON.stringify(W0)}`);
console.log(`    前臂长=${L.toFixed(3)} 目标腕 W1=${W1.map((x) => x.toFixed(2))} 腕高于肘=${wristAbove.toFixed(2)}`);
console.log(`    |R2|=${(R2.ang / DEG).toFixed(1)}° 轴≈${vcross(d2, vsub(W1, E0)).map((x) => (Math.abs(x) > 0.1 ? Math.sign(x) * x : 0).toFixed(2))}`);
if (wristAbove < 1) { console.error('自检失败: 腕未高于肘'); process.exit(1); }

// 内部旋转矩阵 → 文件系关键帧度数（内部=(−fx,−fy,fz) ⇒ 文件=(−ix,−iy,iz)）
function toFileEuler(m) {
  const [ix, iy, iz] = matToEul(m);
  return [+( -ix / DEG).toFixed(2), +(-iy / DEG).toFixed(2), +(iz / DEG).toFixed(2)];
}
// R1 = 单位阵 → rightArm 不写关键帧（保持 rest，且 state 动画须剔除 rightArm）
const armRot = {
  rightArm: toFileEuler(R1.m),          // [0,0,0]，仅用于自检
  rightForeArm: toFileEuler(R2.m),
};
console.log('文件系关键帧: rightForeArm=' + JSON.stringify(armRot.rightForeArm) + '（rightArm 不写=rest）');

// ---------------------------------------------------------------- vanilla 渲染缩放同步（2026-10-05 修订）
// 模型改回原版 1:1 几何（box-UV 矩形 = floor(size×16)，几何缩放破坏 UV 采样 ⇒
// 10/05 用户实测"贴图整体放大、只留左上角"）；0.9375 由运行时 root 骨 scale 施加
// （SwordPlayerModelHandler 的 BoneUpdateEvent 钳制；root 绕 (0,0,0) 缩放 ⇒
// 动画 position 位移自动随 root 等比缩放，无需动画侧补偿）⇒ 动画保持物品动画原值（1.0）。
const VANILLA_RENDER_SCALE = 1.0;
const r5 = (x) => +x.toFixed(5);
function scaleKfValue(v) {
  if (Array.isArray(v)) return v.map((x) => r5(x * VANILLA_RENDER_SCALE));
  if (v && typeof v === 'object') {
    const o = {};
    for (const [k, val] of Object.entries(v)) {
      if ((k === 'post' || k === 'pre') && Array.isArray(val)) o[k] = val.map((x) => r5(x * VANILLA_RENDER_SCALE));
      else o[k] = val;
    }
    return o;
  }
  return v;
}
function scalePositions(bd) {
  if (bd && typeof bd === 'object' && bd.position) {
    for (const t of Object.keys(bd.position)) bd.position[t] = scaleKfValue(bd.position[t]);
  }
  return bd;
}

// ---------------------------------------------------------------- 10/05 朝向修正 A=Ry180：剑骨关键帧 A 转换（物品文件 → 嵌入文件）
// 自洽条件：静态共轭 A·R·Aᵀ ≡ 本转换（A=Ry180 时 rotation (fx,fy,fz)→(−fx,fy,−fz)、
// position (px,py,pz)→(−px,py,−pz)）；scale 原样（沿骨局部轴，帧一致旋转）。
// 身体臂关键帧（rightArm/rightForeArm）在身体坐标系、不在剑嵌入内，不转换。
const SWORD_ANIM_BONES = new Set(['main', 'left', 'right', 'shade']);
const convVec = (arr) => [-arr[0], arr[1], -arr[2]];
function convSwordBone(d) {
  const o = JSON.parse(JSON.stringify(d));
  for (const prop of ['rotation', 'position']) {
    if (!o[prop]) continue;
    for (const t of Object.keys(o[prop])) {
      const v = o[prop][t];
      if (Array.isArray(v)) o[prop][t] = convVec(v);
      else if (v && typeof v === 'object') {
        const nv = {};
        for (const [k, val] of Object.entries(v)) nv[k] = ((k === 'post' || k === 'pre') && Array.isArray(val)) ? convVec(val) : val;
        o[prop][t] = nv;
      }
    }
  }
  return o;
}

// ---------------------------------------------------------------- 组装动画
// 3rd_still：剑骨 A 转换（rotation/position 按 A=Ry180）+ 臂 0 点静态
function stillOut() {
  const bones = {};
  for (const [n, d] of Object.entries(stillBones)) bones[n] = scalePositions(SWORD_ANIM_BONES.has(n) ? convSwordBone(d) : JSON.parse(JSON.stringify(d)));
  bones.rightArm = { rotation: { '0': armRot.rightArm } };
  bones.rightForeArm = { rotation: { '0': armRot.rightForeArm } };
  return { format_version: itemStill.animations['3rd_still'].format_version || itemStill.format_version || '1.8.0', animations: { '3rd_still': { bones } } };
}
// 3rd_attack：剑骨 A 转换 + 臂在所有剑关键帧时刻钉死同一值（避免插值漂移）
function attackOut() {
  const bones = {};
  for (const [n, d] of Object.entries(attackBones)) bones[n] = scalePositions(SWORD_ANIM_BONES.has(n) ? convSwordBone(d) : JSON.parse(JSON.stringify(d)));
  // 取 3rd_attack 里所有出现过的时间点并集（剑骨），臂在每个点都放同一值
  const times = new Set(['0']);
  for (const d of Object.values(attackBones)) {
    for (const prop of Object.values(d)) {
      if (prop && typeof prop === 'object') for (const t of Object.keys(prop)) times.add(t);
    }
  }
  const timesArr = [...times].map(Number).sort((x, y) => x - y);
  for (const bn of ['rightArm', 'rightForeArm']) {
    const kfs = {};
    for (const t of timesArr) kfs[String(t)] = armRot[bn];
    bones[bn] = { rotation: kfs };
  }
  return { format_version: itemAttack.animations['3rd_attack'].format_version || itemAttack.format_version || '1.8.0', animations: { '3rd_attack': { bones } } };
}

// ---------------------------------------------------------------- 写盘（pack + src + build）
let fail = 0;
const DRY = process.argv.includes('--dry-run');
if (DRY) console.log('[dry-run] 跳过写盘');
const targets = DRY ? [] : [
  ['pack ', PACK + `/animations/${NS}/entity/player_wrist/`],
  ['src  ', `${SRC}/spark_animations/${NS}/entity/player_wrist/`],
  ['build', `${BUILD}/spark_animations/${NS}/entity/player_wrist/`],
];
for (const [name, out] of [['3rd_still', stillOut()], ['3rd_attack', attackOut()]]) {
  const json = JSON.stringify(out, null, 2) + '\n';
  for (const [label, dir] of targets) {
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(dir + name + '.json', json, 'utf8');
    console.log('  wrote', label, name + '.json');
  }
}

// ---------------------------------------------------------------- 自检：重模拟（Spark 内部约定）验证腕/肘落点
// 链：world(v) = M_item·M_fore·M_arm·v；M_bone = T(pivot)·R(animRot+restRot)·T(−pivot)
// 内部系：pivot (−x,y,z)；文件度数→内部弧度 (−rx,−ry,rz)
function simArm(fileRot) {
  const ma4 = (m) => [[m[0][0], m[0][1], m[0][2], 0], [m[1][0], m[1][1], m[1][2], 0], [m[2][0], m[2][1], m[2][2], 0], [0, 0, 0, 1]];
  const mul4 = (a, b) => a.map((row, i) => [0, 1, 2, 3].map((j) => row[0] * b[0][j] + row[1] * b[1][j] + row[2] * b[2][j] + row[3] * b[3][j]));
  const apply4 = (m, v) => [m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2] + m[0][3], m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2] + m[1][3], m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2] + m[2][3]];
  const boneM = (name, fileR) => {
    const pivot = [-bodyBones[name].pivot[0], bodyBones[name].pivot[1], bodyBones[name].pivot[2]];
    const anim = fileR ? [-fileR[0] * DEG, -fileR[1] * DEG, fileR[2] * DEG] : [0, 0, 0];
    const rest = bodyBones[name].rotation ? [-bodyBones[name].rotation[0] * DEG, -bodyBones[name].rotation[1] * DEG, bodyBones[name].rotation[2] * DEG] : [0, 0, 0];
    const R4 = ma4(rotZYX([anim[0] + rest[0], anim[1] + rest[1], anim[2] + rest[2]]));
    const T1 = [[1, 0, 0, pivot[0]], [0, 1, 0, pivot[1]], [0, 0, 1, pivot[2]], [0, 0, 0, 1]];
    const T2 = [[1, 0, 0, -pivot[0]], [0, 1, 0, -pivot[1]], [0, 0, 1, -pivot[2]], [0, 0, 0, 1]];
    return mul4(T1, mul4(R4, T2));
  };
  const Marm = boneM('rightArm', fileRot.rightArm);
  const Mfore = boneM('rightForeArm', fileRot.rightForeArm);
  const Mitem = boneM('rightItem', null);
  // 肘 = rightForeArm 的 pivot：自身矩阵固定自身 pivot → 只经父链 Marm
  // 腕 = rightItem 的 pivot：经 Mitem·Mfore·Marm
  return {
    elbow: apply4(Marm, [E0[0], E0[1], E0[2], 1]),
    wrist: apply4(mul4(Mitem, mul4(Mfore, Marm)), [W0[0], W0[1], W0[2], 1]),
  };
}
const chk = simArm(armRot);
const dWrist = vlen(vsub(chk.wrist, W1));
const dElbow = vlen(vsub(chk.elbow, E1));
console.log('自检: 模拟腕=', chk.wrist.map((x) => x.toFixed(2)), ' 目标', JSON.stringify(W1), ` Δ=${dWrist.toFixed(3)}px（关键帧 0.01° 舍入）`);
console.log('      模拟肘=', chk.elbow.map((x) => x.toFixed(2)), ' IK 肘', E1.map((x) => x.toFixed(2)), ` Δ=${dElbow.toFixed(3)}px`);
if (dWrist > 0.15) { console.error('[FAIL] 腕落点偏差过大'); fail++; }
if (dElbow > 0.15) { console.error('[FAIL] 肘落点偏差过大'); fail++; }
if (chk.wrist[1] < chk.elbow[1] + 0.5) { console.error('[FAIL] 模拟后腕未高于肘'); fail++; }
console.log(fail === 0 ? 'SELF-CHECK OK' : `SELF-CHECK FAILED (${fail})`);
process.exit(fail === 0 ? 0 : 1);
