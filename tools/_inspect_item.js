'use strict';
// 查看物品 sword.json 的骨骼结构（判断 TP 手持时物品模型自带手臂链与否）
const fs = require('fs');
const path = require('path');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
function findFile(dir, name, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name.startsWith('.') || e.name === 'node_modules') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) findFile(p, name, out);
    else if (e.name === name) out.push(p);
  }
  return out;
}
const consts = findFile(ROOT + '/src/main/java', 'CatoxidesBattleRebuildConstants.java')[0];
const NS = fs.readFileSync(consts, 'utf8').match(/MODID = "([^"]+)"/)[1];
const f = path.join(ROOT, 'run/client/spark_modules', NS, 'models', NS, 'item', 'sword.json');
let j = JSON.parse(fs.readFileSync(f, 'utf8'));
console.log('raw type:', typeof j, Array.isArray(j));
console.log('raw top keys:', Object.keys(j).join(', '));
const geo = j['minecraft:geometry'];
const g = Array.isArray(geo) ? geo[0] : geo;
console.log('item sword.json bones:');
for (const b of g.bones) {
  console.log(`  ${b.name}: parent=${b.parent || '-'} pivot=${JSON.stringify(b.pivot)} rot=${JSON.stringify(b.rotation)} cubes=${(b.cubes||[]).length}`);
}

// 对比玩家模型
function unwrap(x) {
  const gg = x['minecraft:geometry'];
  return Array.isArray(gg) ? gg[0] : (gg || x);
}
for (const label of ['player.json', 'player_sword.json']) {
  const p = label === 'player.json'
    ? ROOT + '/tools/spark_player_model/player.json'
    : path.join(ROOT, 'run/client/spark_modules', NS, 'models', NS, 'entity', 'player_sword.json');
  const m = unwrap(JSON.parse(fs.readFileSync(p, 'utf8')));
  console.log(`\n${label} bones:`);
  for (const b of m.bones) {
    console.log(`  ${b.name}: parent=${b.parent || '-'} pivot=${JSON.stringify(b.pivot)} rot=${JSON.stringify(b.rotation)} cubes=${(b.cubes||[]).length}`);
  }
}

