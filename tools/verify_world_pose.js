#!/usr/bin/env node
/**
 * verify_world_pose.js — 最终验收：offset=(−0.4375,−0.25,−0.625) 下，
 * 剑模型各部位在世界系（玩家实体相对）的位置。
 *
 * world(v) = 肩 + R_A·(t1 + offset + v) = 肩 + R_A·(v − P_r)
 *   肩 = (−0.3125, 0.125, 0)（玩家模型系，y+ 向下，+Z=玩家前，+X=玩家左）
 *   R_A·(x,y,z) = (−x, −z, −y)
 *   t1+offset = −P_r（构造使然），P_r = right 骨 pivot(t=0) = (0.375,0.125,1.25)
 * 世界系（实体系，y 向上）：肩世界 = 肩模型 + (0, 1.5, 0)… 这里直接用实体系：
 *   实体系 = 玩家模型系旋转后：模型(−x, y, z) → 实体(x, 1.5−y, z)…
 * 为避免再引入转换，直接在"玩家模型系"（y 向下）输出 + 标注方向。
 */
'use strict';

const SHOULDER = [-0.3125, 0.125, 0];      // 玩家右肩关节（玩家模型系）
const PR = [0.375, 0.125, 1.25];           // 物品模型 right 骨 pivot (t=0)
const RA = (v) => [-v[0], -v[2], -v[1]];   // Rx(−90)·Ry(180)
// t=0 模型空间各点（verify_arm_ends.js / compute_hand_offset.js 输出）
const P = {
  '肩端(端B)': [0.250, 0.188, 1.142],
  '腕端(端A)': [0.250, -0.462, 0.767],
  '剑柄头(main)': [0.380, -0.410, 0.908],
  '剑尖(blade)': [0.359, 0.277, -0.286],
};
console.log('offset=(−0.4375,−0.25,−0.625) 下各部位位置（玩家模型系：+X=左, +Y=下, +Z=前；肩关节在' + SHOULDER + '）:');
for (const [name, v] of Object.entries(P)) {
  const d = [v[0]-PR[0], v[1]-PR[1], v[2]-PR[2]];
  const w = RA(d);
  const world = [SHOULDER[0]+w[0], SHOULDER[1]+w[1], SHOULDER[2]+w[2]];
  const relShoulder = [w[0], w[1], w[2]];
  console.log(`  ${name.padEnd(12)} 世界=(${world.map(n=>n.toFixed(3)).join(', ')})  相对肩=(${relShoulder.map(n=>(n>=0?'+':'')+n.toFixed(3)).join(', ')})`);
}
// 对照：玩家自己的腕部（右臂底端 = 肩下方 10px）
console.log('  玩家腕部(对照)  世界=(-0.312, +0.750, 0.000)  相对肩=(+0.000, +0.625(下), +0.000)');
console.log('解读（模型系 y 向下）：+Y=下方, +Z=玩家前方, +X=玩家左侧');
console.log('期望姿态(3rd_still 拄剑休息)：手臂前下方伸出，剑柄头在身前腰腹高度，剑尖朝下接近地面');
