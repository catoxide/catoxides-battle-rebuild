#!/usr/bin/env node
/**
 * install_base_state_v3.js
 *
 * 把 Spark 内置 base_state.json（20 个状态动画，专为 Spark 默认玩家模型设计，
 * 骨名 root/body/waist/head/leftArm/rightArm/leftLeg/rightLeg/rightItem 与
 * player_wrist 的身体骨完全一致）原样安装到 player_wrist 模型的动画集，
 * 让 Spark 内置 PlayerBaseAnimStateMachine 在叠加层模型上自动播放默认
 * 站立/走路/跑步/蹲/游/飞/跳/落 等全套状态动画（=「把默认动画叠上去」）。
 *
 * 同时清理旧的手写 state.*.json 与过期 sword_arm_*.json（被 base_state 取代）。
 * 剑动画 3rd_still.json / 3rd_attack.json 保留（MAIN 组叠加在 STATE 组之上，
 * 只驱动剑骨 main/left/right/shade，与状态动画的 body 骨不冲突）。
 *
 * 三处同步：pack(run/client/spark_modules) / src(main/resources) / build(resources/main，游戏实际读取)。
 */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
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
console.log('MODID(NS):', NS);

const SRC = ROOT + '/tools/_spark_default/animations/minecraft/entity/player/base_state.json';
const DIRS = [
  ['pack ', ROOT + `/run/client/spark_modules/${NS}/animations/${NS}/entity/player_wrist`],
  ['src  ', ROOT + `/src/main/resources/spark_animations/${NS}/entity/player_wrist`],
  ['build', ROOT + `/build/resources/main/spark_animations/${NS}/entity/player_wrist`],
];
const REMOVE = [
  'state.land.idle.json', 'state.land.move.json', 'state.jump.json', 'state.fall.json',
  'sword_arm_idle.json', 'sword_arm_attack.json',
];

const srcBuf = fs.readFileSync(SRC);
const srcMd5 = crypto.createHash('md5').update(srcBuf).digest('hex').toUpperCase();
console.log('源 base_state.json MD5 =', srcMd5, ' 大小 =', srcBuf.length);

let fail = 0;
for (const [tag, dir] of DIRS) {
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'base_state.json'), srcBuf);
  for (const r of REMOVE) {
    const p = path.join(dir, r);
    if (fs.existsSync(p)) fs.unlinkSync(p);
  }
  const back = crypto.createHash('md5').update(fs.readFileSync(path.join(dir, 'base_state.json'))).digest('hex').toUpperCase();
  const list = fs.readdirSync(dir).sort();
  console.log(`  [${tag}] base_state.json MD5=${back} ${back === srcMd5 ? '✓' : '✗ 不一致'}`);
  if (back !== srcMd5) fail++;
  console.log(`  [${tag}] 动画目录: ${list.join(', ')}`);
}
console.log(fail === 0 ? 'INSTALL OK：base_state 已三处同步，旧 state.*/sword_arm_* 已清理' : `INSTALL FAILED (${fail})`);
process.exit(fail === 0 ? 0 : 1);
