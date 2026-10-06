#!/usr/bin/env node
/**
 * apply_swing_v4.js — 挥砍重做（摆臂 + 剑绕腕），后处理脚本。
 *
 * 前置：gen_wrist_player_sword_v3.js（模型）+ gen_wrist_sword_anim_v3.js（动画）已生成 src 下的
 *       player_wrist.json / 3rd_still.json / 3rd_attack.json（A=I，静止=前上30°柄在手里）。
 *
 * 本脚本做三件事（幂等，可重跑）：
 *  1. 模型：在 rightItem 与 hands 之间插入 "swing" 骨（parent=rightItem, pivot=[0,0,0]=手腕），
 *     并把 hands 的 parent 从 rightItem 改到 swing。静止时 swing 恒等 ⇒ 静止姿态不变。
 *  2. 3rd_attack：
 *     - "right" 骨固定为静止姿势 (60,0,0)+pos(0,0,0)（不再自己摆）；
 *     - 新增 "swing" 骨：剑绕手腕转（剑柄锁死在手上，只有剑刃扫弧）；
 *     - "rightArm" 骨：手臂摆臂（后扬→前下劈）。
 *  3. 3rd_still：新增 "swing" 骨恒等 (0,0,0)；"rightArm" 保持 (0,0,0)。
 *
 * 旋转约定（与 Spark 一致）：文件 (rx,ry,rz) → 内部 (−rx,−ry,rz) → 矩阵 Rz·Ry·Rx。
 *   ⇒ 有效 X 转角 = −rx_file。
 *   右臂（肩 (5,21.5,0)，腕在其下 9px）：rx_file>0 ⇒ 手往后上（后扬）；rx_file<0 ⇒ 手往前下（劈）。
 *   剑绕腕（刃尖静止时在腕 上11.4/前18.8）：rx_file<0 ⇒ 刃尖往上（扬）；rx_file>0 ⇒ 刃尖往前下（劈）。
 *
 * 输出：写 src + pack(run) + build 三路，MD5 自校。
 */
'use strict';
const fs = require('fs');
const crypto = require('crypto');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const NS = 'catoxidesbattlerebuild';
const MODEL = `${ROOT}/src/main/resources/spark_models/entity/${NS}/player_wrist.json`;
const ANIM = `${ROOT}/src/main/resources/spark_animations/${NS}/entity/player_wrist/`;
const PKG_MODEL = `${ROOT}/run/client/spark_modules/catoxidesbattlerebuild/models/${NS}/entity/player_wrist.json`;
const PKG_ANIM = `${ROOT}/run/client/spark_modules/catoxidesbattlerebuild/animations/${NS}/entity/player_wrist/`;
const BLT_MODEL = `${ROOT}/build/resources/main/spark_models/entity/${NS}/player_wrist.json`;
const BLT_ANIM = `${ROOT}/build/resources/main/spark_animations/${NS}/entity/player_wrist/`;
const readJ = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const writeJ = (p, o) => fs.writeFileSync(p, JSON.stringify(o, null, 2) + '\n');
const md5 = (p) => crypto.createHash('md5').update(fs.readFileSync(p)).digest('hex');

// ---------------------------------------------------------------- 1. 模型
const mdoc = readJ(MODEL);
const bones = mdoc['minecraft:geometry'][0].bones;
// 幂等：移除已存在的 swing 骨
for (let i = bones.length - 1; i >= 0; i--) if (bones[i].name === 'swing') bones.splice(i, 1);
// hands 挂到 swing 下
const hands = bones.find((b) => b.name === 'hands');
hands.parent = 'swing';
// 在 rightItem 之后插入 swing 骨。pivot 必须是手腕（=rightItem 的 pivot (−5.5,12.5,0) 文件系），
// 而不是 [0,0,0]（那是 rightItem 帧原点≈世界原点，会让剑绕 X 轴转、柄脱手）。
const riIdx = bones.findIndex((b) => b.name === 'rightItem');
bones.splice(riIdx + 1, 0, { name: 'swing', parent: 'rightItem', pivot: [-5.5, 12.5, 0] });
writeJ(MODEL, mdoc);
// 同步 pack + build（整文件覆盖）
fs.copyFileSync(MODEL, PKG_MODEL);
fs.copyFileSync(MODEL, BLT_MODEL);

// ---------------------------------------------------------------- 2. 3rd_attack
const adoc = readJ(ANIM + '3rd_attack.json');
const ab = adoc.animations['3rd_attack'].bones;
// right 固定为静止姿势
ab.right = {
  rotation: {
    '0': { post: [60, 0, 0], lerp_mode: 'linear' },
    '0.5': { post: [60, 0, 0], lerp_mode: 'linear' },
  },
  position: {
    '0': { post: [0, 0, 0], lerp_mode: 'linear' },
    '0.5': { post: [0, 0, 0], lerp_mode: 'linear' },
  },
};
// swing：剑绕腕（v6：绕 Y 轴=竖直轴，刃在 XZ 面左右大扫；文件系 ry→内部 −ry）
ab.swing = {
  rotation: {
    '0': { post: [0, 0, 0], lerp_mode: 'linear' },
    '0.1': { post: [0, -50, 0], lerp_mode: 'linear' },
    '0.3': { post: [0, 50, 0], lerp_mode: 'linear' },
    '0.5': { post: [0, 0, 0], lerp_mode: 'linear' },
  },
};
// rightArm：摆臂（v6：绕 Z 轴=手上下抡，±70°）
ab.rightArm = {
  rotation: {
    '0': { post: [0, 0, 0], lerp_mode: 'linear' },
    '0.1': { post: [0, 0, -70], lerp_mode: 'linear' },
    '0.3': { post: [0, 0, 70], lerp_mode: 'linear' },
    '0.5': { post: [0, 0, 0], lerp_mode: 'linear' },
  },
};
writeJ(ANIM + '3rd_attack.json', adoc);
fs.copyFileSync(ANIM + '3rd_attack.json', PKG_ANIM + '3rd_attack.json');
fs.copyFileSync(ANIM + '3rd_attack.json', BLT_ANIM + '3rd_attack.json');

// ---------------------------------------------------------------- 3. 3rd_still
const sdoc = readJ(ANIM + '3rd_still.json');
const sb = sdoc.animations['3rd_still'].bones;
sb.swing = {
  rotation: {
    '0': { post: [0, 0, 0], lerp_mode: 'linear' },
    '3': { post: [0, 0, 0], lerp_mode: 'linear' },
  },
};
sb.rightArm = { rotation: { '0': [0, 0, 0], '3': [0, 0, 0] } };
writeJ(ANIM + '3rd_still.json', sdoc);
fs.copyFileSync(ANIM + '3rd_still.json', PKG_ANIM + '3rd_still.json');
fs.copyFileSync(ANIM + '3rd_still.json', BLT_ANIM + '3rd_still.json');

// ---------------------------------------------------------------- MD5 自校
for (const [n, s, p, b] of [
  ['model', MODEL, PKG_MODEL, BLT_MODEL],
  ['attack', ANIM + '3rd_attack.json', PKG_ANIM + '3rd_attack.json', BLT_ANIM + '3rd_attack.json'],
  ['still', ANIM + '3rd_still.json', PKG_ANIM + '3rd_still.json', BLT_ANIM + '3rd_still.json'],
]) {
  const a = md5(s), b1 = md5(p), b2 = md5(b);
  console.log(`${n}: src=${a.slice(0, 8)} pack=${b1.slice(0, 8)} build=${b2.slice(0, 8)}  ${a === b1 && b1 === b2 ? '✓' : '✗ MISMATCH'}`);
}
console.log('apply_swing_v4: done');
