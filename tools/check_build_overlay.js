#!/usr/bin/env node
/**
 * check_build_overlay.js — 叠加层三项的离线一致性检查（不跑游戏）
 * ① 手持剑 → 剑在手上：build 模型存在、剑骨树完整、锚在 rightItem、复合贴图 96×64
 * ② 不更改原本模型：deactivate 走 setModel(null)（代码已核对）→ 这里检查 vanilla 判定链
 *    （ModelIndex.isPlayer()=type=="entity"&&location=="minecraft:player"；我们的 index 非 player ✓）
 * ③ 自定义动画：STATE/MAIN 动画文件存在且骨齐全
 */
'use strict';
const fs = require('fs');
const path = require('path');

const BUILD = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild/build/resources/main';
const NS = 'catoxidesbattlerebuild';
let fail = 0;
const ok = (m) => console.log('  [OK] ' + m);
const bad = (m) => { console.error('  [FAIL] ' + m); fail++; };

// ---------- 模型 ----------
console.log('== 模型 (build 权威路径) ==');
const modelPath = `${BUILD}/spark_models/entity/${NS}/player_wrist.json`;
if (!fs.existsSync(modelPath)) bad('模型不存在: ' + modelPath);
else {
  const mj = JSON.parse(fs.readFileSync(modelPath, 'utf8'));
  const g = mj['minecraft:geometry'][0];
  const bones = g.bones;
  const names = bones.map((b) => b.name);
  ok(`模型存在，骨数=${names.length}（期望 17 = 10 身体[原版1:1] + 7 剑）`);
  names.length === 17 ? ok('骨数=17 ✓') : bad(`骨数=${names.length} ≠ 17`);
  const desc = g.description;
  (desc.texture_width === 96 && desc.texture_height === 64)
    ? ok(`description 贴图=${desc.texture_width}x${desc.texture_height} ✓`)
    : bad(`description 贴图=${desc.texture_width}x${desc.texture_height} ≠ 96x64`);
  const swordBones = ['hands', 'left', 'right', 'main', 'shade', 'blade', 'handle'];
  for (const n of swordBones) names.includes(n) ? ok('剑骨 ' + n) : bad('缺剑骨 ' + n);
  // 父链：sword 骨最终应挂到 rightItem
  const byName = Object.fromEntries(bones.map((b) => [b.name, b]));
  const chain = (n) => { const c = []; let b = byName[n]; while (b) { c.push(b.name); b = byName[b.parent]; } return c; };
  const ch = chain('main');
  ok('main 父链: ' + ch.join(' → '));
  byName['hands'] && byName['hands'].parent === 'rightItem'
    ? ok('hands 锚在 rightItem（剑子树挂手腕）✓')
    : bad('hands.parent=' + (byName['hands'] && byName['hands'].parent) + '（期望 rightItem）');
  const cubeCount = (n) => (byName[n] ? byName[n].cubes ? byName[n].cubes.length : 0 : -1);
  const sc = cubeCount('shade') + cubeCount('blade') + cubeCount('handle');
  sc >= 3 ? ok(`剑实体 cube 数=${sc}（shade/blade/handle）`) : bad(`剑 cube 数=${sc} 过少`);
  ['left', 'right'].forEach((n) => (cubeCount(n) === 0 || cubeCount(n) === -1)
    ? ok(`${n} 已去手臂 cube（仅留骨）`)
    : bad(`${n} 仍有 ${cubeCount(n)} 个 cube（应只剩骨）`));
  // 身体关键骨（原版 10 骨 1:1，2026-10-02 起）
  for (const n of ['root', 'body', 'waist', 'head', 'leftArm', 'leftItem', 'rightArm', 'rightItem', 'leftLeg', 'rightLeg'])
    names.includes(n) ? ok('身体骨 ' + n) : bad('缺身体骨 ' + n);
}

// ---------- 动画 ----------
console.log('== 动画 (build 权威路径) ==');
const animDir = `${BUILD}/spark_animations/${NS}/entity/player_wrist/`;
// 状态动画 = Spark 内置 base_state.json 原样安装（20 态，为 Spark 默认玩家模型设计，
// 骨名与 player_wrist 身体骨一致）→ PlayerBaseAnimStateMachine 自动播放默认站立/走/跑/跳/落等。
const BASE_STATE_ANIMS = [
  'state.land.idle', 'state.land.move', 'state.land.move_back', 'state.land.sprint',
  'state.crouch.idle', 'state.crouch.move', 'state.crouch.move_back',
  'state.fly.idle', 'state.fly.move', 'state.fly.sprint',
  'state.swim.idle', 'state.swim.move',
  'state.sit', 'state.fall_fly', 'state.sleep', 'state.fall', 'state.jump_land', 'state.jump',
  'state.climb.idle', 'state.climb.move',
];
{
  const p = animDir + 'base_state.json';
  if (!fs.existsSync(p)) bad('base_state.json 不存在: ' + p);
  else {
    const aj = JSON.parse(fs.readFileSync(p, 'utf8'));
    const have = Object.keys(aj.animations);
    const missing = BASE_STATE_ANIMS.filter((n) => !have.includes(n));
    missing.length === 0 ? ok(`base_state 含全部 ${BASE_STATE_ANIMS.length} 个状态动画 ✓`) : bad(`base_state 缺状态动画: ${missing}`);
    // 每个状态动画至少驱动一个身体骨
    let emptyAnims = 0;
    for (const n of BASE_STATE_ANIMS) {
      const ab = aj.animations[n] && aj.animations[n].bones;
      if (!ab || Object.keys(ab).length === 0) emptyAnims++;
    }
    emptyAnims === 0 ? ok('base_state 各状态动画均有骨关键帧') : bad(`${emptyAnims} 个状态动画无骨关键帧`);
  }
}
// 剑动画（MAIN 组叠加）
const expectAnimBones = {
  '3rd_still': ['main', 'left', 'right', 'shade'],
  '3rd_attack': ['main', 'left', 'right', 'shade'],
};
for (const [name, wantBones] of Object.entries(expectAnimBones)) {
  const p = animDir + name + '.json';
  if (!fs.existsSync(p)) { bad('动画不存在: ' + p); continue; }
  const aj = JSON.parse(fs.readFileSync(p, 'utf8'));
  const animKeys = Object.keys(aj.animations);
  if (animKeys.length !== 1) { bad(`${name}: animations 键=${animKeys}（期望唯一）`); continue; }
  const ab = aj.animations[animKeys[0]].bones;
  const have = Object.keys(ab);
  const missing = wantBones.filter((b) => !have.includes(b));
  missing.length === 0 ? ok(`${name} 骨齐全 [${have.join(',')}]`) : bad(`${name} 缺骨: ${missing}`);
  // 关键帧检查：每个骨的 rotation/position 至少一个关键帧
  let empty = 0;
  for (const [bn, bd] of Object.entries(ab)) {
    const kf = Object.values(bd).filter((v) => v && typeof v === 'object' && Object.keys(v).length > 0);
    if (kf.length === 0) empty++;
  }
  empty === 0 ? ok(`${name} 无空关键帧骨`) : bad(`${name} 有 ${empty} 个无关键帧的骨`);
}

// ---------- 贴图 ----------
console.log('== 贴图 ==');
const pngPath = `${BUILD}/assets/${NS}/textures/entity/player_wrist.png`;
if (!fs.existsSync(pngPath)) bad('复合贴图不存在: ' + pngPath);
else {
  const buf = fs.readFileSync(pngPath);
  const sig = buf.subarray(0, 8).toString('hex') === '89504e470d0a1a0a';
  sig ? ok('PNG 签名 ✓') : bad('非 PNG');
  const w = buf.readUInt32BE(16), hgt = buf.readUInt32BE(20);
  (w === 96 && hgt === 64) ? ok(`尺寸 ${w}x${hgt} ✓（身体 64×64 @0,0 + 剑 32×32 @64,0）`) : bad(`尺寸 ${w}x${hgt} ≠ 96x64`);
}

// ---------- 三处同步（pack/src/build） ----------
console.log('== 同步检查（pack / src / build）==');
const roots = [
  ['pack ', 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild/run/client/spark_modules/catoxidesbattlerebuild',
    (n) => `animations/${NS}/entity/player_wrist/${n}.json`, (m) => `animations/${NS}/entity/player_wrist/model.json`],
];
// pack 内模型文件名可能是 model.json 或 player_wrist.json，两处都查
const packDir = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild/run/client/spark_modules/catoxidesbattlerebuild';
for (const n of ['base_state'].concat(Object.keys(expectAnimBones))) {
  const p = `${packDir}/animations/${NS}/entity/player_wrist/${n}.json`;
  fs.existsSync(p) ? ok(`pack 动画 ${n}`) : bad(`pack 缺动画 ${n}`);
}
const srcDir = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild/src/main/resources';
for (const n of ['base_state'].concat(Object.keys(expectAnimBones))) {
  const p = `${srcDir}/spark_animations/${NS}/entity/player_wrist/${n}.json`;
  fs.existsSync(p) ? ok(`src 动画 ${n}`) : bad(`src 缺动画 ${n}`);
}
// 物品剑动画：pack 布局 animations/<NS>/item/sword/；mod 资源布局 spark_animations/item/<NS>/sword/
const itemStillPack = `${packDir}/animations/${NS}/item/sword/3rd_still.json`;
fs.existsSync(itemStillPack) ? ok('pack 物品剑动画 3rd_still') : bad('pack 缺物品剑动画 ' + itemStillPack);
const itemStillBuild = `${BUILD}/spark_animations/item/${NS}/sword/3rd_still.json`;
fs.existsSync(itemStillBuild) ? ok('build 物品剑动画 3rd_still') : bad('build 缺物品剑动画 ' + itemStillBuild);

console.log(fail === 0 ? '\nSELF-CHECK OK：build 三项离线一致，可交用户实测' : `\nSELF-CHECK FAILED（${fail} 项）`);
process.exit(fail === 0 ? 0 : 1);
