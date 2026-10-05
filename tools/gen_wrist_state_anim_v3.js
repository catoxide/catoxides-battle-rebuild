#!/usr/bin/env node
/**
 * gen_wrist_state_anim_v3.js
 *
 * 从 tools/spark_player_model/base_state.json 提取状态动画，适配 v3 身体
 * （2026-10-02 起身体 = 原版玩家模型 1:1，tools/player_vanilla.json）：
 *   - 只保留身体模型里存在的骨（按 BODY_GEO 骨名过滤；原版 10 骨全在，
 *     无需 body→waist 并入——原版有 body 骨，倾身/bob 语义与原版一致）
 *   - rightArm 剔除（交接书 §3.3 单组驱动规则：第二步抬臂时 MAIN 组将驱动
 *     rightArm，届时 STATE 若再驱动 rightArm → 同骨双组、
 *     LERP weight=1 后迭代组完全覆盖，顺序依赖脆弱。故 STATE 组一律不含 rightArm；
 *     代价：右臂（持剑侧）保持 rest，剑随身不摆——第一步可接受）
 *   - 提取 state.land.idle / state.land.move / state.jump / state.fall
 *     （handler 的 STATE 组用；jump=上升一次性、fall=下降循环。
 *      不含 fall_fly——那是鞘翅滑翔姿态，root 转 95°+位移 (0,14,12)，误播会翻模）
 *
 * 输出（pack + src + build/resources/main 各一份，build 为游戏实际读取位置）：
 *   spark_animations/<NS>/entity/player_wrist/state.land.idle.json
 *   spark_animations/<NS>/entity/player_wrist/state.land.move.json
 *   spark_animations/<NS>/entity/player_wrist/state.jump.json
 *   spark_animations/<NS>/entity/player_wrist/state.fall.json
 *   spark_animations/<NS>/entity/player_wrist/base_state.json
 *     （2026-10-05：补全量 20 态。此前部署的 base_state.json 是 10/02 内置拷贝
 *      +10/03 position×0.9375 缩放的遗留产物，模型改回 1:1 后必须重出；
 *      同一套适配（骨过滤+剔 rightArm+S=1.0）保证与 4 个独立文件内容一致——
 *      AnimationModule 对同名动画 putAll 后文件胜，内容一致则与加载顺序无关）
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const BODY_GEO = ROOT + '/tools/player_vanilla.json';            // 与 v3 模型同一身体（原版 1:1，2026-10-02 起）
const BASE_STATE = ROOT + '/tools/spark_player_model/base_state.json';

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

const PACK = (() => {
  const d = fs.readdirSync(ROOT + '/run/client/spark_modules').filter((x) => !x.endsWith('.zip'))[0];
  return ROOT + '/run/client/spark_modules/' + d;
})();
const SRC = ROOT + '/src/main/resources';
const BUILD = ROOT + '/build/resources/main';

function readJSON(p) { return JSON.parse(fs.readFileSync(p, 'utf8')); }
const bodyBones = new Set(readJSON(BODY_GEO)['minecraft:geometry'][0].bones.map((b) => b.name));
const base = readJSON(BASE_STATE).animations;

const WANTED = ['state.land.idle', 'state.land.move', 'state.jump', 'state.fall'];
const MERGE_INTO = { body: 'waist' };   // 模型没有 body 骨 → position 并入 waist
const DROP = new Set(['rightArm']);     // §3.3 单组驱动规则：rightArm 只许一个组驱动（第二步 MAIN 抬臂组）

// 把 data 的 position/rotation 关键帧并入 target（按时间合并，冲突跳过）
function mergeBoneData(target, data) {
  for (const prop of Object.keys(data)) {
    if (prop !== 'position' && prop !== 'rotation') continue;
    if (!target[prop]) target[prop] = {};
    for (const [t, val] of Object.entries(data[prop])) {
      if (target[prop][t] === undefined) {
        target[prop][t] = JSON.parse(JSON.stringify(val));
      } else {
        console.log(`  注意: 关键帧 ${prop}[${t}] 冲突，跳过`);
      }
    }
  }
}

function adapt(anim) {
  const out = { ...anim };
  delete out.bones;
  const bones = {};
  const deferred = [];
  for (const [boneName, data] of Object.entries(anim.bones || {})) {
    if (DROP.has(boneName)) {
      console.log(`  剔除骨（§3.3 单组驱动规则）: ${boneName}`);
      continue;
    }
    if (bodyBones.has(boneName)) {
      bones[boneName] = JSON.parse(JSON.stringify(data));
    } else if (MERGE_INTO[boneName] && bodyBones.has(MERGE_INTO[boneName])) {
      // body → waist：先记下，等目标骨（waist）处理完再并入——
      // 冲突时目标骨自身关键帧优先，body 只补空档（jump/fall 里 body 与 waist
      // 同时刻都有 rotation，若先占位会反过来丢掉 waist 的关键帧）
      deferred.push([MERGE_INTO[boneName], boneName, data]);
    } else {
      console.log(`  跳过骨（模型中不存在）: ${boneName}`);
    }
  }
  for (const [targetName, srcName, data] of deferred) {
    const target = bones[targetName] || (bones[targetName] = {});
    mergeBoneData(target, data);
    console.log(`  ${srcName} -> 并入 ${targetName}`);
  }
  // 2026-10-05：模型改回原版 1:1 几何（box-UV 矩形 = floor(size×16)，几何缩放会破坏 UV 采样，
  // 见 gen_wrist_player_sword_v3.js 头部说明）；0.9375 由运行时 root 骨 scale 施加
  // （SwordPlayerModelHandler 的 BoneUpdateEvent 钳制）⇒ 动画保持原版 1:1 关键帧（S=1.0）。
  // 注意：root 骨的 position 关键帧（呼吸摆位等）照常输出——运行时只钳 scale 分量，pos/rot 保留。
  const S = 1.0;
  const r5 = (x) => +x.toFixed(5);
  const scaleKf = (v) => Array.isArray(v) ? v.map((x) => r5(x * S))
    : (v && typeof v === 'object' ? Object.fromEntries(Object.entries(v).map(([k, val]) =>
        (k === 'post' || k === 'pre') && Array.isArray(val) ? [k, val.map((x) => r5(x * S))] : [k, val])) : v);
  for (const bd of Object.values(bones)) {
    if (bd && bd.position) for (const t of Object.keys(bd.position)) bd.position[t] = scaleKf(bd.position[t]);
  }
  out.bones = bones;
  return out;
}

let fail = 0;
for (const name of WANTED) {
  if (!base[name]) { console.error('自检失败: base_state.json 缺 ' + name); fail++; continue; }
  const adapted = adapt(base[name]);
  const json = JSON.stringify({ format_version: base[name].format_version || '1.8.0', animations: { [name]: adapted } }, null, 2) + '\n';
  for (const [label, dir] of [
    ['pack ', PACK + `/animations/${NS}/entity/player_wrist/`],
    ['src  ', `${SRC}/spark_animations/${NS}/entity/player_wrist/`],
    ['build', `${BUILD}/spark_animations/${NS}/entity/player_wrist/`],
  ]) {
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(dir + name + '.json', json, 'utf8');
    console.log('  wrote', label, name + '.json');
  }
  // 回读校验：输出里所有骨名都在身体模型里
  const back = JSON.parse(fs.readFileSync(`${BUILD}/spark_animations/${NS}/entity/player_wrist/` + name + '.json', 'utf8')).animations[name];
  for (const bn of Object.keys(back.bones || {})) {
    if (!bodyBones.has(bn)) { console.error(`  [FAIL] 输出含模型中不存在的骨 ${bn}`); fail++; }
    if (DROP.has(bn)) { console.error(`  [FAIL] 输出含应剔除的骨 ${bn}（单组驱动规则）`); fail++; }
  }
}
// 2026-10-05：全量 20 态 base_state.json（与独立文件同一套适配：骨过滤 + 剔 rightArm + S=1.0）
{
  const all = {};
  for (const name of Object.keys(base)) {
    all[name] = adapt(base[name]);
  }
  const json = JSON.stringify({ format_version: (base[Object.keys(base)[0]] || {}).format_version || '1.8.0', animations: all }, null, 2) + '\n';
  for (const [label, dir] of [
    ['pack ', PACK + `/animations/${NS}/entity/player_wrist/`],
    ['src  ', `${SRC}/spark_animations/${NS}/entity/player_wrist/`],
    ['build', `${BUILD}/spark_animations/${NS}/entity/player_wrist/`],
  ]) {
    fs.writeFileSync(dir + 'base_state.json', json, 'utf8');
    console.log('  wrote', label, 'base_state.json');
  }
  const back = JSON.parse(fs.readFileSync(`${BUILD}/spark_animations/${NS}/entity/player_wrist/base_state.json`, 'utf8')).animations;
  if (Object.keys(back).length !== Object.keys(base).length) {
    console.error(`  [FAIL] base_state.json 动画数 ${Object.keys(back).length} != 源 ${Object.keys(base).length}`);
    fail++;
  }
  for (const [name, anim] of Object.entries(back)) {
    for (const bn of Object.keys(anim.bones || {})) {
      if (!bodyBones.has(bn)) { console.error(`  [FAIL] base_state ${name} 含模型中不存在的骨 ${bn}`); fail++; }
      if (DROP.has(bn)) { console.error(`  [FAIL] base_state ${name} 含应剔除的骨 ${bn}`); fail++; }
    }
  }
}
console.log(fail === 0 ? 'SELF-CHECK OK' : `SELF-CHECK FAILED (${fail})`);
process.exit(fail === 0 ? 0 : 1);
