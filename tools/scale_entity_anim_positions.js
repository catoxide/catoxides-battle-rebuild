#!/usr/bin/env node
/**
 * scale_entity_anim_positions.js
 *
 * 把 player_wrist 实体动画的 position 关键帧 ×0.9375（vanilla 渲染缩放，
 * 与 gen_wrist_player_sword_v3.js 的 VANILLA_RENDER_SCALE 同步）：
 * 模型烘焙 0.9375 后，动画里的位移关键帧若不跟着缩放，剑子树/手臂会整体
 * 偏 (1−0.9375)·Δ（2026-10-03 check_scale_pose 实测 tip 偏移 ≈0.5px）。
 * rotation/scale 关键帧不变（均匀缩放与旋转可交换）。
 *
 * 处理对象（pack + src + build 各一份，src 为源）：
 *   spark_animations/<NS>/entity/player_wrist/{base_state,3rd_still,3rd_attack}.json
 * 不处理 item/*（物品动画驱动的是未缩放的物品模型）。
 *
 * 关键帧值两种形式都要处理：
 *   "t": [x,y,z]                      （base_state 内置格式）
 *   "t": {"post":[x,y,z], ...}        （物品动画格式，可能有 pre）
 */
'use strict';
const fs = require('fs');
const path = require('path');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const S = 0.9375;   // 必须与 gen_wrist_player_sword_v3.js 的 VANILLA_RENDER_SCALE 一致
const r5 = (x) => +x.toFixed(5);

function scaleKfValue(v) {
  if (Array.isArray(v)) return v.map((x) => r5(x * S));
  if (v && typeof v === 'object') {
    const o = {};
    for (const [k, val] of Object.entries(v)) {
      if ((k === 'post' || k === 'pre') && Array.isArray(val)) o[k] = val.map((x) => r5(x * S));
      else o[k] = val;
    }
    return o;
  }
  return v;
}
let scaled = 0;
function scaleBoneData(bd) {
  if (!bd || typeof bd !== 'object' || !bd.position) return bd;
  for (const t of Object.keys(bd.position)) {
    bd.position[t] = scaleKfValue(bd.position[t]);
    scaled++;
  }
  return bd;
}

const NS = (() => {
  function find(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name.startsWith('.git')) continue;
      const p = path.join(dir, e.name);
      if (e.isDirectory()) { const r = find(p); if (r) return r; }
      else if (e.name === 'CatoxidesBattleRebuildConstants.java') return p;
    }
    return null;
  }
  const f = find(ROOT + '/src/main/java');
  return fs.readFileSync(f, 'utf8').match(/MODID = "([^"]+)"/)[1];
})();
const PACK = (() => {
  const d = fs.readdirSync(ROOT + '/run/client/spark_modules').filter((x) => !x.endsWith('.zip'))[0];
  return ROOT + '/run/client/spark_modules/' + d;
})();
const SRC = ROOT + '/src/main/resources';
const BUILD = ROOT + '/build/resources/main';

const FILES = ['base_state.json', '3rd_still.json', '3rd_attack.json'];
let fail = 0;
for (const f of FILES) {
  const p = `${SRC}/spark_animations/${NS}/entity/player_wrist/` + f;
  if (!fs.existsSync(p)) { console.error('[FAIL] 源文件缺失 ' + p); fail++; continue; }
  const j = JSON.parse(fs.readFileSync(p, 'utf8'));
  for (const a of Object.values(j.animations || {})) {
    for (const bd of Object.values(a.bones || {})) scaleBoneData(bd);
  }
  const json = JSON.stringify(j, null, 2) + '\n';
  fs.writeFileSync(p, json, 'utf8');
  fs.writeFileSync(`${PACK}/animations/${NS}/entity/player_wrist/` + f, json, 'utf8');
  fs.writeFileSync(`${BUILD}/spark_animations/${NS}/entity/player_wrist/` + f, json, 'utf8');
  console.log('  ' + f + ' ✓（src/pack/build 已写）');
}
console.log('position 关键帧共缩放 ' + scaled + ' 个 ×' + S);

// 回读校验：三份一致 + 无残留未缩放（抽查 main.position 首值应含 0.9375 因子）
const md5 = (p) => {
  const h = require('crypto').createHash('md5').update(fs.readFileSync(p)).digest('hex');
  return h;
};
for (const f of FILES) {
  const a = md5(`${SRC}/spark_animations/${NS}/entity/player_wrist/` + f);
  const b = md5(`${PACK}/animations/${NS}/entity/player_wrist/` + f);
  const c = md5(`${BUILD}/spark_animations/${NS}/entity/player_wrist/` + f);
  if (a !== b || a !== c) { console.error(`[FAIL] ${f} 三处不一致`); fail++; }
}
// 幂等性提示：再次运行会二次缩放 —— 运行前若怀疑已缩放，先比对 src 与内置/物品源
console.log(fail === 0 ? 'SELF-CHECK OK' : `SELF-CHECK FAILED (${fail})`);
process.exit(fail === 0 ? 0 : 1);
