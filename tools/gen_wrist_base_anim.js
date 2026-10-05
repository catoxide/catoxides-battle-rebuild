#!/usr/bin/env node
/**
 * gen_wrist_base_anim.js
 *
 * 从默认模块 base_state.json 提取 state.land.idle / state.land.move 两个状态动画，
 * 删掉 rightArm 关键帧（右臂改由挥剑图层 MAIN 驱动，避免基础摆臂覆盖挥剑），
 * 存成 player_wrist 的独立动画文件。
 *
 * 方案B 分层：
 *   STATE(1) = 本文件（身体/腿/头/左臂，base_state 驱动）
 *   MAIN(4)  = 挥剑手臂图层（rightArm+rightForeArm 挥击，另见 gen_wrist_sword_arm_anim.js）
 *
 * 输出（pack + src 各一份）：
 *   animations/<NS>/entity/player_wrist/state.land.idle.json
 *   animations/<NS>/entity/player_wrist/state.land.move.json
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

// 源：默认模块 base_state（之前解到 tools/_spark_default）
const baseStatePath = ROOT + '/tools/_spark_default/animations/minecraft/entity/player/base_state.json';
if (!fs.existsSync(baseStatePath)) { console.error('  [FAIL] 找不到 base_state.json'); process.exit(1); }
console.log('  源 base_state:', baseStatePath);

const base = JSON.parse(fs.readFileSync(baseStatePath, 'utf8'));
const anims = base.animations || {};

const WANTED = ['state.land.idle', 'state.land.move'];
let fail = 0;
for (const name of WANTED) {
  const a = anims[name];
  if (!a) { console.error(`  [FAIL] base_state 缺动画 ${name}`); fail++; continue; }
  const bones = a.bones || {};
  if (bones.rightArm) {
    console.log(`  ${name}: 删除 rightArm 关键帧`);
    delete bones.rightArm;
  }
  const out = {
    format_version: '1.8.0',
    animations: { [name]: a },
  };
  const text = JSON.stringify(out, null, 2) + '\n';
  const rel = `entity/player_wrist/${name}.json`;
  for (const base2 of [PACK + `/animations/${NS}/`, SRC + `/spark_animations/${NS}/`]) {
    const p = base2 + rel;
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, text, 'utf8');
    console.log('  wrote', p);
  }
}
process.exit(fail === 0 ? 0 : 1);
