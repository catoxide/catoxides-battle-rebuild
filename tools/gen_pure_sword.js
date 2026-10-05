#!/usr/bin/env node
/**
 * gen_pure_sword.js
 *
 * 生成"纯剑"物品模型 sword.json（覆盖）：从现有剑模型里删掉 `right` 骨的手臂 cube
 * （保留 right 骨当控制器、main/shade/blade/handle 剑体照旧）。
 *
 * 方案B：玩家手臂由 player_wrist 的 rightArm/rightForeArm 提供（独立挥剑图层），
 * 剑 = 纯剑物品模型，经 in-hand 机制锚到 player_wrist 的 rightItem（手腕）。
 * 所以剑模型里不能再带手臂，否则会出现两只手臂。
 *
 * 输出（pack + src 资源各一份，覆盖 sword.json）：
 *   models/<NS>/item/sword.json
 *   src spark_models/item/<NS>/sword.json
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const PACK = (() => {
  const d = fs.readdirSync(ROOT + '/run/client/spark_modules').filter((x) => !x.endsWith('.zip'))[0];
  return ROOT + '/run/client/spark_modules/' + d;
})();
const SRC = ROOT + '/src/main/resources';

const _constFile = (() => {
  function find(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name.startsWith('.git')) continue;
      const p = path.join(dir, e.name);
      if (e.isDirectory()) { const r = find(p); if (r) return r; }
      else if (e.name === 'CatoxidesBattleRebuildConstants.java') return p;
    }
    return null;
  }
  return find(ROOT + '/src/main/java');
})();
const NS = fs.readFileSync(_constFile, 'utf8').match(/MODID = "([^"]+)"/)[1];
console.log('MODID(NS):', NS);

// 以 pack 里的 sword.json 为源（最新）
const src = PACK + `/models/${NS}/item/sword.json`;
const model = JSON.parse(fs.readFileSync(src, 'utf8'));
const geo = model['minecraft:geometry'][0];

let removed = 0;
for (const b of geo.bones) {
  if (b.name === 'right' && Array.isArray(b.cubes) && b.cubes.length) {
    console.log(`  移除 right 骨手臂 cube：${b.cubes.length} 个`);
    removed = b.cubes.length;
    b.cubes = [];
  }
  // 纯剑不需要左手控制器
  if (b.name === 'left') {
    console.log('  移除 left 控制器骨');
  }
}
// 删除 left 骨
geo.bones = geo.bones.filter((b) => b.name !== 'left');

if (removed === 0) { console.error('  [FAIL] 未找到 right 骨手臂 cube'); process.exit(1); }

const outText = JSON.stringify(model, null, 2) + '\n';
function out(p) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, outText, 'utf8');
  console.log('  wrote', p);
}
out(PACK + `/models/${NS}/item/sword.json`);
out(`${SRC}/spark_models/item/${NS}/sword.json`);

// 自检：right 骨无 cube，main/shade/blade/handle 仍在
const names = geo.bones.map((b) => b.name);
for (const need of ['hands', 'right', 'main', 'shade', 'blade', 'handle']) {
  if (!names.includes(need)) { console.error(`  [FAIL] 缺骨 ${need}`); process.exit(1); }
}
const right = geo.bones.find((b) => b.name === 'right');
if (right.cubes.length !== 0) { console.error('  [FAIL] right 骨仍有 cube'); process.exit(1); }
if (names.includes('left')) { console.error('  [FAIL] left 骨未移除'); process.exit(1); }
console.log('SELF-CHECK OK（纯剑：无手臂 cube，剑体完整）');
