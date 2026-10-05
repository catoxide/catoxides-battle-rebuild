#!/usr/bin/env node
/**
 * gen_wrist_sword_arm_anim.js
 *
 * 生成"挥剑手臂图层"动画（方案B 的 MAIN 组叠加层）：驱动 player_wrist 的
 * rightArm（上臂/肩）+ rightForeArm（前臂/肘），让手臂抬起、前臂前伸，
 * 使锚在手腕(rightItem)的剑斜指前上方；挥击时剑尖走"先升后降"的弧。
 *
 * 姿态要求（用户）：
 *   1) 剑尖 y 高于剑柄（剑朝上）
 *   2) 剑尖在剑柄前方（剑朝前）
 *   3) 挥动中剑尖先升高再降低（弧线挥砍）
 *   4) 手臂最低点也朝上（手臂抬起，不垂到最低）
 *
 * 坐标系（模型空间）：+X=左, -X=右, +Y=上, +Z=前。右臂在 -X 侧。
 * 手臂默认下垂（手在下方）；要抬起+前伸，需绕 X 前抬、绕 Z 外展。
 * 符号：文件 X 正角=前抬（Spark 内部旋转=(-文件rx,-文件ry,文件rz)，内部负X=往前抬）；文件 Z 负角=外展。
 *
 * 输出（pack + src 各一份）：
 *   animations/<NS>/entity/player_wrist/sword_arm_idle.json   （待机：抬臂持剑，循环微摆）
 *   animations/<NS>/entity/player_wrist/sword_arm_attack.json （挥击：剑尖先升后降的弧，once）
 *
 * 注：具体角度是初版估计值，进游戏看效果后用 --tune 微调（见文件底部 TUNE 块）。
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

// ---------------------------------------------------------------- 姿态参数（可微调）
// 单位：度。
// ⚠️ 符号约定（由 OBoneAnimation.getAnimRotAt 源码确认）：内部旋转 = (-文件rx, -文件ry, 文件rz)。
//   右手臂在 -X 侧、下垂(-Y)：正的【内部】X 旋转 = 往后抬(-Z)；负的【内部】X 旋转 = 往前抬(+Z)。
//   内部 X = -文件 X → 要往前抬，文件 X 必须为【正角】（绝对值越大=手臂越前抬=剑尖越高）。
//   绕 Z：文件正角 = 内收(向身体)；外展(向 -X 外侧) = 文件【负角】。
// 总旋转 = ra[0]+rf[0]（都绕 X 前抬）。剑身静息指 +Z；总旋转=θ 时剑身斜指前上(仰角θ)。
//   要剑尖在柄前方(要求2)：需 θ<90°（cosθ>0 → 剑尖 z>柄 z）。
//   要剑尖先升后降(要求3)：需 θ 先增大(升到峰)后减小(降到谷)。
//   要手臂抬起(要求4)：需 ra[0]>0。
// 故：ra 保留较大(抬臂)，rf 取小(避免总旋转顶到 90° 让剑竖直、剑尖 z 优势消失)。
const TUNE = {
  // 待机：抬臂持剑（剑斜指前上方，总旋转≈55°）
  idle: {
    rightArm:     { rot: [45, 0, -14], pos: [0, 0, 0] },   // 肩：前抬(文件正)+外展(文件负)
    rightForeArm: { rot: [10, 0, 0],   pos: [0, 0, 0] },   // 肘：轻微屈肘(文件正)
    sway: 0.5,   // 待机微摆幅度（度，绕 X 正弦）
    swayPeriod: 2.0,
  },
  // 挥击：剑尖先升后降的弧（once）—— 用户要求"挥动中剑尖先变高再变低"
  attack: {
    duration: 0.45,
    // 关键帧：t -> { rightArm.rot(ra), rightForeArm.rot(rf) }
    // 总旋转=ra[0]+rf[0]：待机(55) -> 高举(80, 剑尖升到最高) -> 下劈(32, 剑尖降到最低) -> 收势(55)
    // 全程 θ<90° → 剑尖始终在柄前方；θ 先升后降 → 剑尖先升后降。
    frames: [
      { t: 0.00, ra: [45, 0, -14], rf: [10, 0, 0] },  // 起势：待机姿态（剑斜指前上，中高度）
      { t: 0.15, ra: [70, 0, -22], rf: [10, 0, 0] },  // 高举：剑尖升到最高（先变高）
      { t: 0.34, ra: [22, 0, -4],  rf: [10, 0, 0] },  // 下劈：剑尖降到最低（再变低）
      { t: 0.45, ra: [45, 0, -14], rf: [10, 0, 0] },  // 收势：回到待机
    ],
  },
};

// ---------------------------------------------------------------- 生成
function lerp(a, b, t) { return a + (b - a) * t; }
function lerpRot(a, b, t) { return [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)]; }

// 待机：基线姿态 + 绕 X 正弦微摆
function buildIdle() {
  const { rightArm, rightForeArm, sway, swayPeriod } = TUNE.idle;
  const n = 4; // 关键帧数（一个周期）
  const ra = {}, rf = {};
  for (let i = 0; i <= n; i++) {
    const t = (i / n) * swayPeriod;
    const key = t.toFixed(4);
    const s = Math.sin((i / n) * Math.PI * 2) * sway;
    ra[key] = [rightArm.rot[0] + s, rightArm.rot[1], rightArm.rot[2]];
    rf[key] = [rightForeArm.rot[0] + s * 0.5, rightForeArm.rot[1], rightForeArm.rot[2]];
  }
  return {
    format_version: '1.8.0',
    animations: {
      'sword_arm_idle': {
        loop: true,
        animation_length: swayPeriod,
        override_previous_animation: true,
        bones: {
          rightArm: { rotation: ra, position: { '0.0': rightArm.pos } },
          rightForeArm: { rotation: rf, position: { '0.0': rightForeArm.pos } },
        },
      },
    },
  };
}

// 挥击：关键帧插值（剑尖先升后降）
function buildAttack() {
  const { duration, frames } = TUNE.attack;
  const ra = {}, rf = {};
  // 在关键帧之间线性插值，每 0.02s 一个采样
  const step = 0.02;
  for (let t = 0; t <= duration + 1e-6; t += step) {
    const tk = Math.min(t, duration).toFixed(4);
    // 找包围关键帧
    let i = 0;
    while (i < frames.length - 1 && frames[i + 1].t < t) i++;
    const a = frames[i], b = frames[Math.min(i + 1, frames.length - 1)];
    const span = b.t - a.t || 1;
    const f = Math.max(0, Math.min(1, (t - a.t) / span));
    ra[tk] = lerpRot(a.ra, b.ra, f);
    rf[tk] = lerpRot(a.rf, b.rf, f);
  }
  return {
    format_version: '1.8.0',
    animations: {
      'sword_arm_attack': {
        loop: false,
        animation_length: duration,
        override_previous_animation: true,
        bones: {
          rightArm: { rotation: ra },
          rightForeArm: { rotation: rf },
        },
      },
    },
  };
}

const idle = buildIdle();
const attack = buildAttack();

for (const [name, obj] of [['sword_arm_idle', idle], ['sword_arm_attack', attack]]) {
  const text = JSON.stringify(obj, null, 2) + '\n';
  const rel = `entity/player_wrist/${name}.json`;
  for (const base2 of [PACK + `/animations/${NS}/`, SRC + `/spark_animations/${NS}/`]) {
    const p = base2 + rel;
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, text, 'utf8');
    console.log('  wrote', p);
  }
}

// ---------------------------------------------------------------- 自检
// 1) 挥击关键帧：剑尖先升后降 → 检查 rightArm 前抬量序列。
//    前抬量 = ra[0]（文件正角=前抬，绝对值越大=手臂越前抬=剑尖越高）。
//    要求"先变高再变低" = 前抬量先增大（升到峰）后减小（降到谷），且峰在中段、谷在后段。
const fr = TUNE.attack.frames;
const xs = fr.map((f) => f.ra[0]); // 前抬量（越大=剑越高）
const peakIdx = xs.indexOf(Math.max(...xs));
const troughIdx = xs.indexOf(Math.min(...xs));
console.log(`  挥击前抬量序列: ${xs.map((x) => x.toFixed(0)).join(' -> ')} (峰@${peakIdx}, 谷@${troughIdx})`);
if (!(peakIdx < troughIdx && xs[peakIdx] > xs[0] && xs[troughIdx] < xs[peakIdx])) {
  console.error('  [FAIL] 挥击未形成"先升后降"（需前抬量先升到峰、再降到谷，且峰在谷前）');
  process.exit(1);
}
// 2) 待机手臂抬起：rightArm X 文件正角 > 20（前抬）= 手臂抬起
if (TUNE.idle.rightArm.rot[0] < 20) {
  console.error('  [FAIL] 待机手臂未抬起（rightArm X 文件角应 > 20 才前抬）');
  process.exit(1);
}
console.log('SELF-CHECK OK（挥剑手臂图层：待机抬臂 + 挥击先降后升弧）');
