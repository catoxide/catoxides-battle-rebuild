#!/usr/bin/env node
/**
 * gen_wrist_player_model.js
 *
 * 生成"手腕玩家模型" player_wrist.json：在原版玩家模型 (player.json) 空间里，
 * 把手臂/腿各拆成两节（上臂+前臂+手腕、大腿+小腿+脚），提供手腕/脚踝分节
 * （steve.json 那种"条带处有凹凸"的观感），但用标准 64×64 玩家皮肤 UV
 * （手臂/腿的侧面行各拆上下两半，顶/底面复用原区域）。
 *
 * 方案B（叠加层）的基础模型：
 *   - 玩家本体 = 本模型（Spark 几何，isPlayer()=false → Spark 几何渲染）。
 *   - 身体/腿/头/左臂走基础动画 (base_state，STATE 组)。
 *   - 右臂 (rightArm/rightForeArm) 走独立挥剑图层 (MAIN 组)。
 *   - 剑 = 纯剑物品模型，锚到 rightItem（手腕）。
 *
 * 骨结构（pivot 与 cube origin 均在模型全局空间，pivot=旋转中心）：
 *   root
 *   └─ body [0,16,0]
 *      ├─ waist [0,12,0]  躯干 8×12×4 (y12-24)
 *      │  ├─ head [0,24,0] 头 8×8×8 (y24-32)
 *      │  ├─ leftArm [5,22,0]  左上臂 4×6×4 (y18-24)
 *      │  │  └─ leftForeArm [5,18,0] 左前臂 4×6×4 (y12-18)
 *      │  │     └─ leftItem [5,12,0] 左手腕(挂点, 无 cube)
 *      │  └─ rightArm [-5,22,0] 右上臂 4×6×4 (y18-24)
 *      │     └─ rightForeArm [-5,18,0] 右前臂 4×6×4 (y12-18)
 *      │        └─ rightItem [-5,12,0] 右手腕(挂点, 无 cube)
 *      ├─ leftLeg [1.9,12,0]  左大腿 4×6×4 (y6-12)
 *      │  └─ leftForeLeg [1.9,6,0] 左小腿 4×6×4 (y0-6)
 *      │     └─ leftFoot [1.9,0,0] 左脚(挂点, 无 cube)
 *      └─ rightLeg [-1.9,12,0] 右大腿 4×6×4 (y6-12)
 *         └─ rightForeLeg [-1.9,6,0] 右小腿 4×6×4 (y0-6)
 *            └─ rightFoot [-1.9,0,0] 右脚(挂点, 无 cube)
 *
 * 皮肤 UV（Java PlayerModel 64×64 精确坐标，符号规则见 gen_player_sword_model.js）：
 *   侧面行拆上下两半（h=12 → 上6+下6）；顶/底面复用原 4×4 区域（细肢顶底几乎不可见）。
 *   符号规则（mirror=false，匹配 Java 渲染）：
 *     north/south: uv 锚区域右上角 (x+w,y)，uv_size=(-w,+h)
 *     west/east:   uv 锚区域左上角 (x,y)，  uv_size=(+w,+h)
 *     up:          uv 锚区域左上角 (x,y)，  uv_size=(+w,+h)
 *     down:        uv 锚区域左下角 (x,y+h)，uv_size=(+w,-h)
 *
 * 输出（pack + src 资源各一份）：
 *   models/<NS>/entity/player_wrist.json
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

// MODID 从常量文件程序化读取（拼写易混淆，禁止手打）
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
console.log('MODID(NS):', NS, 'len', NS.length);

const F = (u, v, w, h) => ({ uv: [u, v], uv_size: [w, h] });

// ---------------------------------------------------------------- 逐面 UV
// 区域坐标 = Java 皮肤标准布局（64×64），侧面行 h=12 拆成 上6(y+0..6) / 下6(y+6..12)。
//   躯干 8×12×4 (y16-31): up(20,16) down(28,16) | west(16,20) north(20,20) east(28,20) south(32,20)
//   头 8³ (y0-15):        up(8,0)  down(16,0)  | west(0,8)  north(8,8)   east(16,8)  south(24,8)
//   右臂 4×12×4 (y16-31): up(44,16) down(48,16)| west(40,20) north(44,20) east(48,20) south(52,20)
//   右腿 4×12×4 (y16-31): up(4,16)  down(8,16) | west(0,20)  north(4,20)  east(8,20)  south(12,20)
//   左臂 4×12×4 (y44-59): up(36,44) down(40,44)| west(32,48) north(36,48) east(40,48) south(44,48)
//   左腿 4×12×4 (y44-59): up(20,44) down(24,44)| west(16,48) north(20,48) east(24,48) south(28,48)

// 躯干 / 头：与原版一致（不拆）
const UV_WAIST = {
  north: F(28, 20, -8, 12), south: F(40, 20, -8, 12),
  west: F(16, 20, 4, 12), east: F(28, 20, 4, 12),
  up: F(20, 16, 8, 4), down: F(28, 20, 8, -4),
};
const UV_HEAD = {
  north: F(16, 8, -8, 8), south: F(32, 8, -8, 8),
  west: F(0, 8, 8, 8), east: F(16, 8, 8, 8),
  up: F(8, 0, 8, 8), down: F(16, 8, 8, -8),
};

// 侧肢（4 宽）侧面行拆分：给定区域左上角 (x, y)，上段 (y..y+6) / 下段 (y+6..y+12)。
// 列序（角色右→前→角色左→背）= west(north内) ... 实际列：west, north, east, south，各 4 宽。
function limbSides(x, yTop, h) {
  return {
    west:  F(x,      yTop, 4, h),   // 锚左上 (+w,+h)
    north: F(x + 8,  yTop, -4, h),  // 锚右上 (-w,+h)  [north 列 x+4..x+8]
    east:  F(x + 8,  yTop, 4, h),   // 锚左上 (+w,+h)  [east 列 x+8..x+12]
    south: F(x + 16, yTop, -4, h),  // 锚右上 (-w,+h)  [south 列 x+12..x+16]
  };
}
// 顶/底面复用原区域（细肢顶底几乎不可见）：
//   up 锚左上 (x+4, y-4) 4×4；down 锚左下 (x+8, y) 4×4（负高约定）。
function limbUpDown(x, ySideTop) {
  const yUpRow = ySideTop - 4; // 顶行在侧面行之上
  return { up: F(x + 4, yUpRow, 4, 4), down: F(x + 8, ySideTop, 4, -4) };
}

const UV = {
  // 右臂区域 x=40, 侧面行 yTop=20；上段 y20..26，下段 y26..32
  rightArm:     Object.assign(limbSides(40, 20, 6),  limbUpDown(40, 20)),
  rightForeArm: Object.assign(limbSides(40, 26, 6),  { up: F(48, 20, 4, -4), down: F(48, 20, 4, -4) }),
  // 左臂区域 x=32, 侧面行 yTop=48；上段 y48..54，下段 y54..60
  leftArm:      Object.assign(limbSides(32, 48, 6),  limbUpDown(32, 48)),
  leftForeArm:  Object.assign(limbSides(32, 54, 6),  { up: F(40, 48, 4, -4), down: F(40, 48, 4, -4) }),
  // 右腿区域 x=0, 侧面行 yTop=20；上段 y20..26，下段 y26..32
  rightLeg:     Object.assign(limbSides(0, 20, 6),   limbUpDown(0, 20)),
  rightForeLeg: Object.assign(limbSides(0, 26, 6),   { up: F(8, 20, 4, -4), down: F(8, 20, 4, -4) }),
  // 左腿区域 x=16, 侧面行 yTop=48；上段 y48..54，下段 y54..60
  leftLeg:      Object.assign(limbSides(16, 48, 6),  limbUpDown(16, 48)),
  leftForeLeg:  Object.assign(limbSides(16, 54, 6),  { up: F(24, 48, 4, -4), down: F(24, 48, 4, -4) }),
};
UV.waist = UV_WAIST;
UV.head = UV_HEAD;

// ---------------------------------------------------------------- 骨结构
function bone(name, parent, pivot, cubeOrigin, cubeSize, uvName, extra) {
  const b = { name };
  if (parent) b.parent = parent;
  b.pivot = pivot;
  if (cubeOrigin) {
    b.cubes = [{
      origin: cubeOrigin, size: cubeSize,
      uv: JSON.parse(JSON.stringify(UV[uvName])),
    }];
  }
  if (extra) Object.assign(b, extra);
  return b;
}

const bones = [
  bone('root', null, [0, 0, 0]),
  bone('body', 'root', [0, 16, 0]),
  bone('waist', 'body', [0, 12, 0], [-4, 12, -2], [8, 12, 4], 'waist'),
  bone('head', 'waist', [0, 24, 0], [-4, 24, -4], [8, 8, 8], 'head'),
  // 左臂（上臂 + 前臂 + 手腕挂点）
  bone('leftArm', 'waist', [5, 22, 0], [4, 18, -2], [4, 6, 4], 'leftArm'),
  bone('leftForeArm', 'leftArm', [5, 18, 0], [4, 12, -2], [4, 6, 4], 'leftForeArm'),
  bone('leftItem', 'leftForeArm', [5, 12, 0]),
  // 右臂（上臂 + 前臂 + 手腕挂点）
  bone('rightArm', 'waist', [-5, 22, 0], [-8, 18, -2], [4, 6, 4], 'rightArm'),
  bone('rightForeArm', 'rightArm', [-5, 18, 0], [-8, 12, -2], [4, 6, 4], 'rightForeArm'),
  bone('rightItem', 'rightForeArm', [-5, 12, 0]),
  // 左腿（大腿 + 小腿 + 脚挂点）
  bone('leftLeg', 'body', [1.9, 12, 0], [0, 6, -2], [4, 6, 4], 'leftLeg'),
  bone('leftForeLeg', 'leftLeg', [1.9, 6, 0], [0, 0, -2], [4, 6, 4], 'leftForeLeg'),
  bone('leftFoot', 'leftForeLeg', [1.9, 0, 0]),
  // 右腿（大腿 + 小腿 + 脚挂点）
  bone('rightLeg', 'body', [-1.9, 12, 0], [-4, 6, -2], [4, 6, 4], 'rightLeg'),
  bone('rightForeLeg', 'rightLeg', [-1.9, 6, 0], [-4, 0, -2], [4, 6, 4], 'rightForeLeg'),
  bone('rightFoot', 'rightForeLeg', [-1.9, 0, 0]),
];

const model = {
  format_version: '1.12.0',
  'minecraft:geometry': [{
    description: {
      identifier: 'geometry.player_wrist',
      texture_width: 64,
      texture_height: 64,
      visible_bounds_width: 3,
      visible_bounds_height: 3.5,
      visible_bounds_offset: [0, 1.25, 0],
    },
    bones,
  }],
};

// ---------------------------------------------------------------- 写文件
function out(rel) {
  const p = rel;
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(model, null, 2) + '\n', 'utf8');
  console.log('  wrote', p);
}
// pack 路径: models/<NS>/entity/... ；src 资源路径: spark_models/entity/<NS>/...（与 player_sword 一致）
out(PACK + `/models/${NS}/entity/player_wrist.json`);
out(`${SRC}/spark_models/entity/${NS}/player_wrist.json`);

// ---------------------------------------------------------------- 自检
// 1) 每根有 cube 的骨：6 面 uv bbox 不越出 64×64，且侧肢上下段不互相重叠。
// 2) 手腕挂点 rightItem 存在且是 rightForeArm 子级（剑锚点）。
function bbox(f) {
  const [u, v] = f.uv, [w, h] = f.uv_size;
  const x0 = Math.min(u, u + w), x1 = Math.max(u, u + w);
  const y0 = Math.min(v, v + h), y1 = Math.max(v, v + h);
  return [x0, y0, x1, y1];
}
let fail = 0;
for (const b of bones) {
  if (!b.cubes) continue;
  for (const face of Object.values(b.cubes[0].uv)) {
    const [x0, y0, x1, y1] = bbox(face);
    if (x0 < 0 || y0 < 0 || x1 > 64 || y1 > 64) {
      console.error(`  [FAIL] ${b.name} 面越界 bbox=${JSON.stringify([x0, y0, x1, y1])}`);
      fail++;
    }
  }
}
const ri = bones.find((b) => b.name === 'rightItem');
if (!ri || ri.parent !== 'rightForeArm') { console.error('  [FAIL] rightItem 挂点缺失/父级错误'); fail++; }
const li = bones.find((b) => b.name === 'leftItem');
if (!li || li.parent !== 'leftForeArm') { console.error('  [FAIL] leftItem 挂点缺失/父级错误'); fail++; }

// 3) 右臂上下段侧面不重叠（上 y20..26 / 下 y26..32，列相同）
function sideCol(name) {
  const c = bones.find((b) => b.name === name).cubes[0].uv;
  return {
    west: bbox(c.west), north: bbox(c.north), east: bbox(c.east), south: bbox(c.south),
  };
}
function overlap(a, b) {
  return !(a[2] <= b[0] || b[2] <= a[0] || a[3] <= b[1] || b[3] <= a[1]);
}
const upR = sideCol('rightArm'), loR = sideCol('rightForeArm');
for (const k of ['west', 'north', 'east', 'south']) {
  if (overlap(upR[k], loR[k])) { console.error(`  [FAIL] 右臂 ${k} 上下段重叠 ${JSON.stringify([upR[k], loR[k]])}`); fail++; }
}
const upL = sideCol('leftArm'), loL = sideCol('leftForeArm');
for (const k of ['west', 'north', 'east', 'south']) {
  if (overlap(upL[k], loL[k])) { console.error(`  [FAIL] 左臂 ${k} 上下段重叠 ${JSON.stringify([upL[k], loL[k]])}`); fail++; }
}

console.log(fail === 0 ? 'SELF-CHECK OK' : `SELF-CHECK FAILED (${fail})`);
process.exit(fail === 0 ? 0 : 1);
