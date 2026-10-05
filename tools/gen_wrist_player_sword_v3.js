#!/usr/bin/env node
/**
 * gen_wrist_player_sword_v3.js
 *
 * 生成"叠加层"玩家剑模型 player_wrist.json（v3，2026-10-02 起身体=原版模型 1:1；
 * 2026-10-03 起整体烘焙 vanilla 渲染缩放 0.9375——PlayerRenderer.scale() 源码定案，
 * 见文件中部 VANILLA_RENDER_SCALE 注释）：
 *
 *   身体层 = 原版玩家模型（tools/player_vanilla.json）1:1 照抄（骨/几何/box-UV 全部原样）：
 *     用户要求「不更改玩家原本模型」——分段身体（steve.json）会让 base_state 的
 *     vanilla 骨语义失效（rightArm=整臂→只转上臂、body 并入 waist→腿不随倾身），
 *     动起来的模型和原版不一样。改用原版 10 骨后 STATE 动画直接驱动原版骨，
 *     身体与原版逐帧一致；box-UV 原样 ⇒ 贴图采样与原版渲染完全相同（无需 face-UV 重映射）。
 *     （ModelModule 反编译核对：模型 description 的 texture_width 会注入每个 cube，
 *       故 96 宽合图下原版 box-UV 像素坐标仍精确落在左 64px 身体区。）
 *
 *   剑层   = D:\Download\sword.geo.json 完整骨树，经刚体变换 M' = A·p + B 嵌入 rightItem（手腕）：
 *     · A = I 恒等（10/05 17:53 用户实测定案：物品静止剑轴（物品内部系）= (0,0.5,−0.866)
 *       本身即「前+上30°」——内部系 +Z=后（10/05 双数据点钉死：旧版剑轴 (1,0,0) 渲染=玩家右
 *       ⇒ +X右；A=Ry180 版剑轴 (0,0.5,0.866) 渲染=正后 ⇒ +Z后）。
 *       A=Ry180 会把它多转 180° ⇒ 剑指后（17:52 实测症状）。自洽条件（静态与动画同用 A 共轭）：
 *       A=I ⇒ 动画文件 rotation/position 原样拷贝（gen_wrist_sword_anim_v3 执行）。
 *     · anchor = 物品静止姿态 pommel（handle cube4 中心）→ 手腕（B = wrist − A·pommelFile）；
 *       旧锚（main 文件 pivot）在任何 A 下都会偏离手里，弃用
 *     · 骨 pivot / cube pivot：Tpt = A·p + B
 *     · cube origin：Torigin = A·o + B + min(0, A·s)（逐分量）——
 *       Spark box = [origin, origin+size]（OVertexSet 逐行核对），origin 必须取旋转包围盒最小角
 *     · cube size：Tsize = |A·s| = (sx, sy, sz)（A=I ⇒ 恒等）
 *     · cube rotation：Trot = A·R·Aᵀ（共轭，正交阵 A⁻¹=Aᵀ；A=I ⇒ 原样）
 *     · face-UV：corner-matching remap（从 Spark OPolygon/OUVUnion 反编译推导）：
 *         per-face 分配（非镜像）：W0←(u+w,v) W1←(u,v) W2←(u,v+h) W3←(u+w,v+h)
 *         面法线对应 old→new：A·n（A=I ⇒ 恒等 north→north 等）
 *         角点对应（单位角点 (cx,cy,cz)）：A·c（A=I ⇒ 恒等）
 *         恒等/翻转面：解出带符号 (u,v,w,h) 直接写（区域不动、像素不动）
 *         90°面：A=I 下每个面映到自身（w/h 不变）⇒ 0 个；转置代码保留，job=0 时空转
 *       注：Spark 不解析 uv_rotation（OUVUnion.FaceUV 仅 uv/uv_size，全源码无 uv_rotation）→ 忽略
 *
 *   贴图   = 静态复合 96×64：
 *     BODY_TEX 64×64 @(0,0) + SWORD_TEX 32×32 @(64,0)（原样）
 *     + 90°面转置区域 @(64,32)-(96,64)（像素搬运；A=I 下 0 job，区域为空）
 *
 * 动画（方案B 叠加层，另见 gen_wrist_state_anim_v3.js）：
 *   STATE(1) = state.land.idle/move/jump/fall（base_state 原样驱动原版骨；
 *              rightArm 按单组驱动规则剔除——持剑臂留给 step 2 的抬臂 MAIN 组）
 *   MAIN(4)  = 3rd_still / 3rd_attack（用户剑物品动画，驱动剑骨 main/left/right/shade）
 *
 * 输出（pack + src + build/resources/main 各一份，build 为游戏实际读取位置）：
 *   spark_models/entity/<NS>/player_wrist.json
 *   assets/<NS>/textures/entity/player_wrist.png
 */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';

// ---------------------------------------------------------------- 输入（身体=Spark 默认模型结构 + slim 3 宽臂，唯一基准）
// 2026-10-02 用户实机反馈：Spark 默认 player.json 手臂 4 宽，与玩家默认 slim 3 宽皮肤不符，
// 切换叠加层时身体"整体膨胀~1.5px"。改用 player_slim.json（Spark 结构 + slim 3 宽臂，
// 臂 pivot/cube/UV 与 vanilla PlayerModel slim 一致）→ 身体与默认角色同宽同高，无膨胀。
const BODY_GEO = ROOT + '/tools/player_slim.json';
const BODY_TEX = ROOT + '/tools/_steve.png';                      // 身体贴图 64×64
const SWORD_GEO = 'D:/Download/sword.geo.json';                   // 剑模型
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
const SWORD_TEX = ROOT + '/src/main/resources/assets/' + NS + '/textures/item/sword.png'; // 32×32
console.log('MODID(NS):', NS, 'len', NS.length);
console.log('BODY_GEO:', BODY_GEO);
console.log('BODY_TEX:', BODY_TEX);

const PACK = (() => {
  const d = fs.readdirSync(ROOT + '/run/client/spark_modules').filter((x) => !x.endsWith('.zip'))[0];
  return ROOT + '/run/client/spark_modules/' + d;
})();
const SRC = ROOT + '/src/main/resources';
const BUILD = ROOT + '/build/resources/main';

function readJSON(p) { return JSON.parse(fs.readFileSync(p, 'utf8')); }
const bodyGeo = readJSON(BODY_GEO)['minecraft:geometry'][0];
const swordGeo = readJSON(SWORD_GEO)['minecraft:geometry'][0];
const swordBoneMap = {};
for (const b of swordGeo.bones) swordBoneMap[b.name] = b;

// ---------------------------------------------------------------- 嵌入 M' = A · p + B（新A）
const bodyBoneMap = {};
for (const b of bodyGeo.bones) bodyBoneMap[b.name] = b;
if (!bodyBoneMap.rightItem) { console.error('自检失败: 身体模型缺 rightItem 手腕骨'); process.exit(1); }
const wrist = bodyBoneMap.rightItem.pivot.slice();          // 恒等姿态下 world(rightItem) = 文件 pivot
console.log('手腕 rightItem =', JSON.stringify(wrist));

// A = I：恒等嵌入（10/05 17:53 用户实测定案推翻 A=Ry180）
// 内部系（渲染系）实测 = +X右/+Y上/+Z后（模型面朝 −Z）——10/05 双数据点钉死：
//   旧版剑轴 (1,0,0) 渲染=玩家右 ⇒ +X右；新版 Ry180 剑轴 (0,0.5,0.866) 渲染=正后 ⇒ +Z后。
// 物品静止剑轴（物品内部系）= (0,0.5,−0.866) = 前+上30°（原版第三人称手持 look）⇒
// A=I 直接嵌入即得「柄在手里、剑指前上方」；Ry180 会多转 180° ⇒ 剑指后（用户所见）。
const A = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
const AT = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];               // Aᵀ = A⁻¹ = I
const mulA = (v) => [
  A[0][0]*v[0] + A[0][1]*v[1] + A[0][2]*v[2],
  A[1][0]*v[0] + A[1][1]*v[1] + A[1][2]*v[2],
  A[2][0]*v[0] + A[2][1]*v[1] + A[2][2]*v[2],
];
const DEG = Math.PI / 180;
// ---------------------------------------------------------------- 锚点 = 物品静止姿态 pommel（10/05 朝向修正）
// 旧锚（main 文件 pivot）在 A=Ry180 下把静止剑送到玩家身后 27px ⇒ 改锚 pommel（handle cube4 中心）。
// 物品内部系约定（Spark 解析，verify_pose_v3 已对实测校准）：
//   点/position (x,y,z)→(−x,y,z)；rotation 文件 (fx,fy,fz) → 内部矩阵 Rz(fz)·Ry(−fy)·Rx(−fx)
// 静止关键帧（3rd_still@0）从物品动画文件读取，不硬编码。
let B = [0, 0, 0];
let pommelFile = null;
{
  const st = readJSON(PACK + `/animations/${NS}/item/sword/3rd_still.json`).animations['3rd_still'].bones;
  const kfArr = (bd, prop) => { const v = bd && bd[prop] && bd[prop]['0']; return v ? (Array.isArray(v) ? v : v.post || v.pre) : null; };
  const M4I = () => [[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]];
  const M4mul = (a, b) => a.map((row, i) => [0,1,2,3].map((j) => row[0]*b[0][j] + row[1]*b[1][j] + row[2]*b[2][j] + row[3]*b[3][j]));
  const M4t = (v) => { const m = M4I(); m[0][3] = v[0]; m[1][3] = v[1]; m[2][3] = v[2]; return m; };
  const M3to4 = (m) => [[m[0][0],m[0][1],m[0][2],0],[m[1][0],m[1][1],m[1][2],0],[m[2][0],m[2][1],m[2][2],0],[0,0,0,1]];
  const M4apply = (m, v) => [m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2]+m[0][3], m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2]+m[1][3], m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]+m[2][3]];
  const bmap = {}; for (const b of swordGeo.bones) bmap[b.name] = b;
  const chainW = (name) => {
    const path = []; let cur = name;
    while (cur && cur !== 'bb_main') { path.unshift(cur); cur = bmap[cur].parent || 'bb_main'; }
    let W = M4I();
    for (const n of path) {
      const b = bmap[n];
      const piv = [-b.pivot[0], b.pivot[1], b.pivot[2]];
      const a = st[n] || {};
      const posRaw = kfArr(a, 'position');
      const pos = posRaw ? [-posRaw[0], posRaw[1], posRaw[2]] : [0, 0, 0];
      const rotRaw = kfArr(a, 'rotation');
      const R = rotRaw ? M3to4(eulToMat([-rotRaw[0], -rotRaw[1], rotRaw[2]])) : M4I();
      W = M4mul(W, M4mul(M4t([piv[0]+pos[0], piv[1]+pos[1], piv[2]+pos[2]]), M4mul(R, M4t([-piv[0], -piv[1], -piv[2]]))));
    }
    return W;
  };
  const pc = bmap.handle.cubes[4];                          // pommel（中心 = cube 中心；cube 自带旋转绕自身中心）
  const pommelInt = M4apply(chainW('handle'), [-pc.origin[0] - pc.size[0]/2, pc.origin[1] + pc.size[1]/2, pc.origin[2] + pc.size[2]/2]);
  pommelFile = [-pommelInt[0], pommelInt[1], pommelInt[2]];
  console.log('静止姿态 pommel =', JSON.stringify(pommelInt.map((x) => +x.toFixed(4))), '（内部系）  文件系 =', JSON.stringify(pommelFile.map((x) => +x.toFixed(4))));
  const aPom = mulA(pommelFile);
  B = [+(wrist[0] - aPom[0]).toFixed(4), +(wrist[1] - aPom[1]).toFixed(4), +(wrist[2] - aPom[2]).toFixed(4)];
  console.log('A·pommel =', JSON.stringify(aPom.map((x) => +x.toFixed(4))), '  平移 B =', JSON.stringify(B));
}
const Tpt = (p) => { const q = mulA(p); return [+(q[0]+B[0]).toFixed(4), +(q[1]+B[1]).toFixed(4), +(q[2]+B[2]).toFixed(4)]; };
// cube origin = 旋转包围盒最小角（Spark box=[origin,origin+size]，OVertexSet 核对）
const Torigin = (o, s) => {
  const q = mulA(s);
  const t = Tpt(o);
  return [+(t[0] + Math.min(0, q[0])).toFixed(4), +(t[1] + Math.min(0, q[1])).toFixed(4), +(t[2] + Math.min(0, q[2])).toFixed(4)];
};
// cube size = |A·s|：对 A=(x,y,z)→(−x,y,−z) 即 (sx, sy, sz)（恒等）
const Tsize = (v) => [+v[0], +v[1], +v[2]];
// cube/bone 自带 rotation：R' = A·R·Aᵀ（共轭）
function eulToMat(e) {
  const [x, y, z] = e.map((d) => d * DEG);
  const cx = Math.cos(x), sx = Math.sin(x), cy = Math.cos(y), sy = Math.sin(y), cz = Math.cos(z), sz = Math.sin(z);
  // 应用顺序 Z→Y→X（JOML rotateZYX = Rz(v.z)·Ry(v.y)·Rx(v.x)，10/05 源码验证；下方矩阵即此式）
  return [
    [cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx],
    [sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx],
    [-sy, cy * sx, cy * cx],
  ];
}
function matToEul(m) {
  const sy = -m[2][0];
  let y, x, z;
  if (Math.abs(sy) > 0.99999) {
    y = sy > 0 ? Math.PI / 2 : -Math.PI / 2;
    x = 0;
    z = Math.atan2(-m[0][1], m[1][1]);
  } else {
    y = Math.asin(sy);
    x = Math.atan2(m[2][1], m[2][2]);
    z = Math.atan2(m[1][0], m[0][0]);
  }
  return [+(x / DEG).toFixed(4), +(y / DEG).toFixed(4), +(z / DEG).toFixed(4)];
}
function matMul(a, b) {
  const r = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) for (let k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
  return r;
}
const Trot = (e) => matToEul(matMul(matMul(A, eulToMat(e)), AT));

// ---------------------------------------------------------------- 面/角点模板与对应（Spark 约定，反编译核对）
// per-face UV 非镜像分配：W0←(u+w,v) W1←(u,v) W2←(u,v+h) W3←(u+w,v+h)（OPolygon init 分支）
// 每面 4 个模板角点（单位立方体，OVertexSet quad 顺序）
const FACE_CORNERS = {
  north: [[0, 1, 0], [1, 1, 0], [1, 0, 0], [0, 0, 0]],
  south: [[1, 1, 1], [0, 1, 1], [0, 0, 1], [1, 0, 1]],
  west:  [[0, 1, 1], [0, 1, 0], [0, 0, 0], [0, 0, 1]],
  east:  [[1, 1, 0], [1, 1, 1], [1, 0, 1], [1, 0, 0]],
  up:    [[0, 1, 1], [1, 1, 1], [1, 1, 0], [0, 1, 0]],
  down:  [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]],
};
// 面法线 old→new：n' = A·n（A=I ⇒ 恒等；下方自检会按 A 验证）
const FACE_PAIR = { north: 'north', south: 'south', west: 'west', east: 'east', up: 'up', down: 'down' };
// 角点对应：A=I ⇒ 恒等（min-corner 约定下推导；自检同样覆盖）
const mapCorner = (c) => [c[0], c[1], c[2]];
// CORR[f] = [j0..j3]：旧面 f 的 Wi → 新面 FACE_PAIR[f] 的 Wj
const CORR = {};
for (const f of Object.keys(FACE_CORNERS)) {
  CORR[f] = FACE_CORNERS[f].map((c) => {
    const nc = mapCorner(c);
    const j = FACE_CORNERS[FACE_PAIR[f]].findIndex((d) => d[0] === nc[0] && d[1] === nc[1] && d[2] === nc[2]);
    if (j < 0) { console.error('自检失败: 角点对应无解', f, JSON.stringify(c)); process.exit(1); }
    return j;
  });
}
// 面法线一致性：FACE_PAIR[f] 的法线必须 = A·n(f)
const FACE_NORMAL = { north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };
for (const f of Object.keys(FACE_PAIR)) {
  const want = mulA(FACE_NORMAL[f]);
  const got = FACE_NORMAL[FACE_PAIR[f]];
  if (want[0] !== got[0] || want[1] !== got[1] || want[2] !== got[2]) {
    console.error('自检失败: FACE_PAIR 与 A·n 不符', f, JSON.stringify(want), JSON.stringify(got)); process.exit(1);
  }
}
console.log('CORR =', JSON.stringify(CORR));

// ---------------------------------------------------------------- 骨 = 身体 1:1 + 剑嵌入
const bones = bodyGeo.bones.map((b) => JSON.parse(JSON.stringify(b)));   // 身体：原样照抄（含自定义 UV）

// 身体 = 原版模型 1:1（box-UV 原样，不做任何 UV 处理）——见文件头 2026-10-02 说明。

const SWORD_BONES = ['hands', 'left', 'right', 'main', 'shade', 'blade', 'handle'];
const STRIP_CUBES = new Set(['left', 'right']);   // 去手臂 cube，只留骨供动画驱动

// face-UV 解算：旧面 f 的矩形 (U,V,w,h) → 新面 FILE_UV
//   轴对齐（恒等/翻转）：16 候选解 (a,b,w',h')，区域不变、像素不动
//   90°：返回 {rot:true}，转置区域 + 像素搬运
function solveFaceUV(f, U, V, w, h) {
  const P = [[U + w, V], [U, V], [U, V + h], [U + w, V + h]];   // 旧面 W0..W3 的 UV 点
  const corr = CORR[f];
  const corrInv = [0, 1, 2, 3].map((j) => corr.indexOf(j));     // 槽 j 接收旧面 corrInv[j]
  for (const a of [U, U + w]) for (const b of [V, V + h])
  for (const wp of [w, -w]) for (const hp of [h, -h]) {
    const S = [[a + wp, b], [a, b], [a, b + hp], [a + wp, b + hp]];   // 新槽 W0..W3
    let ok = true;
    for (let j = 0; j < 4; j++) {
      if (Math.abs(S[j][0] - P[corrInv[j]][0]) > 1e-9 || Math.abs(S[j][1] - P[corrInv[j]][1]) > 1e-9) { ok = false; break; }
    }
    if (ok) return { kind: 'aligned', u: a, v: b, w: wp, h: hp };
  }
  return { kind: 'rot', U, V, w, h, corr };
}

const faceUVJobs = [];   // 90° 面的像素搬运任务
function transformSwordBone(srcBone, parentName) {
  const b = { name: srcBone.name, parent: parentName, pivot: Tpt(srcBone.pivot) };
  if (srcBone.rotation) b.rotation = Trot(srcBone.rotation);
  if (STRIP_CUBES.has(srcBone.name)) {
    b.cubes = [];
  } else if (srcBone.cubes && srcBone.cubes.length) {
    b.cubes = srcBone.cubes.map((c, ci) => {
      const out = { origin: Torigin(c.origin, c.size), size: Tsize(c.size) };
      if (c.uv && typeof c.uv === 'object') {
        out.uv = {};
        for (const [f, face] of Object.entries(c.uv)) {
          const U = face.uv[0], V = face.uv[1], w = face.uv_size[0], h = face.uv_size[1];
          const sol = solveFaceUV(f, U, V, w, h);
          if (sol.kind === 'aligned') {
            out.uv[FACE_PAIR[f]] = { uv: [+(U64(sol.u)), r4(sol.v)], uv_size: [r4(sol.w), r4(sol.h)] };
          } else {
            // 90° 面：登记搬运任务（区域稍后 pack），uv 先占位，pack 后回填
            out.uv[FACE_PAIR[f]] = { uv: [0, 0], uv_size: [r4(Math.abs(h)), r4(Math.abs(w))], __job: faceUVJobs.length };
            faceUVJobs.push({ bone: srcBone.name, cube: ci, face: f, newFace: FACE_PAIR[f], sol });
          }
        }
      } else if (Array.isArray(c.uv)) {
        out.uv = [+(c.uv[0] + 64), c.uv[1]];              // 旧式 uv: [u,v]（本剑源未用，left/right 已剥）
      }
      if (c.pivot) out.pivot = Tpt(c.pivot);
      if (c.rotation) out.rotation = Trot(c.rotation);
      return out;
    });
  }
  return b;
}
const r4 = (x) => +x.toFixed(4);
const U64 = (u) => +(u + 64).toFixed(4);   // 合图里剑纹理区在 (64,0) 起

const SWORD_PARENT = { hands: 'rightItem', left: 'hands', right: 'hands', main: 'right', shade: 'main', blade: 'main', handle: 'main' };
for (const name of SWORD_BONES) {
  const src = swordBoneMap[name];
  if (!src) { console.error('自检失败: sword.geo.json 缺 ' + name); process.exit(1); }
  bones.push(transformSwordBone(src, SWORD_PARENT[name]));
}

// ---------------------------------------------------------------- PNG 编解码（无依赖）
function crc32(buf) {
  let c, table = crc32.table;
  if (!table) {
    table = crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xFF];
  return (crc ^ -1) >>> 0;
}
function decodePNG(p) {
  const b = fs.readFileSync(p);
  if (b.readUInt32BE(0) !== 0x89504E47) throw new Error('not png: ' + p);
  const w = b.readUInt32BE(16), h = b.readUInt32BE(20);
  const ct = b[25];                       // 6=RGBA, 2=RGB
  const bpp = ct === 6 ? 4 : 3;
  let off = 8; const idat = [];
  while (off < b.length) {
    const len = b.readUInt32BE(off);
    const type = b.toString('ascii', off + 4, off + 8);
    if (type === 'IDAT') idat.push(b.slice(off + 8, off + 8 + len));
    off += 12 + len;
    if (type === 'IEND') break;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const img = Buffer.alloc(w * h * 4);
  let pos = 0;
  const paeth = (a, bb, c) => {
    const p = a + bb - c, pa = Math.abs(p - a), pb = Math.abs(p - bb), pc = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : pb <= pc ? bb : c;
  };
  for (let y = 0; y < h; y++) {
    const f = raw[pos++];
    for (let x = 0; x < w; x++) {
      for (let k = 0; k < 3; k++) {
        const cur = raw[pos + x * bpp + k];
        const a = x > 0 ? img[(y * w + x - 1) * 4 + k] : 0;
        const bb = y > 0 ? img[(y - 1) * w * x * 4 + k] : 0;
        const c = (x > 0 && y > 0) ? img[(y - 1) * w * (x - 1) * 4 + k] : 0;
        let v;
        if (f === 0) v = cur;
        else if (f === 1) v = cur + a;
        else if (f === 2) v = cur + bb;
        else if (f === 3) v = cur + Math.floor((a + bb) / 2);
        else v = cur + paeth(a, bb, c);
        img[(y * w + x) * 4 + k] = v & 0xFF;
      }
      img[(y * w + x) * 4 + 3] = ct === 6 ? raw[pos + x * bpp + 3] : 255;
    }
    pos += w * bpp;
  }
  return { w, h, img };
}
function encodePNG(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;                       // filter: none
    raw.set(rgba.subarray(y * w * 4, (y + 1) * w * 4), y * (w * 4 + 1) + 1);
  }
  const idat = zlib.deflateSync(raw);
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const t = Buffer.from(type, 'ascii');
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
    return Buffer.concat([len, t, data, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6;                         // 8-bit RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0)),
  ]);
}
// 双线性采样（src 为 32×32 源剑纹理；x,y 为 UV px 坐标）
function sampleBilinear(img, x, y) {
  const W = img.w, H = img.h;
  if (x < 0) x = 0; if (x > W - 0.001) x = W - 0.001;
  if (y < 0) y = 0; if (y > H - 0.001) y = H - 0.001;
  const x0 = Math.floor(x), y0 = Math.floor(y);
  const fx = x - x0, fy = y - y0;
  const x1 = Math.min(x0 + 1, W - 1), y1 = Math.min(y0 + 1, H - 1);
  const out = [0, 0, 0, 255];
  for (let k = 0; k < 4; k++) {
    const c00 = img.img[(y0 * W + x0) * 4 + k], c10 = img.img[(y0 * W + x1) * 4 + k];
    const c01 = img.img[(y1 * W + x0) * 4 + k], c11 = img.img[(y1 * W + x1) * 4 + k];
    out[k] = Math.round(c00 * (1 - fx) * (1 - fy) + c10 * fx * (1 - fy) + c01 * (1 - fx) * fy + c11 * fx * fy);
  }
  return out;
}

// ---------------------------------------------------------------- 复合贴图 96×64
const TW = 96, TH = 64;
const bodyImg = decodePNG(BODY_TEX);
const swordImg = decodePNG(SWORD_TEX);
if (bodyImg.w > 64 || bodyImg.h > TH) { console.error('自检失败: 身体贴图超过 64×64 区域', bodyImg.w, bodyImg.h); process.exit(1); }
if (swordImg.w > TW - 64 || swordImg.h > TH) { console.error('自检失败: 剑贴图超过 32×64 区域', swordImg.w, swordImg.h); process.exit(1); }
const comp = Buffer.alloc(TW * TH * 4);             // 透明底
for (let y = 0; y < bodyImg.h; y++) {                                   // 身体 @(0,0)，逐行（64 宽 → 96 宽，不能整块拷）
  comp.set(bodyImg.img.subarray(y * bodyImg.w * 4, (y + 1) * bodyImg.w * 4), y * TW * 4);
}
// 左腿 UV 对齐 vanilla 1.21.1：left_leg = texOffs(0,16).mirror()（反编译 HumanoidModel.createMesh）
// —— 左腿采样右腿区 (0,16)-(16,32) 的镜像；模型左腿用经典布局 (16,48) 区 ⇒ 此处把右腿区
// 镜像拷到 (16,48)-(32,64)，使模型采样与原版渲染逐像素一致（(16,48) 区无其他用途，安全）。
for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
  const si = ((16 + y) * bodyImg.w + (15 - x)) * 4;
  const di = ((48 + y) * TW + (16 + x)) * 4;
  comp[di] = bodyImg.img[si]; comp[di + 1] = bodyImg.img[si + 1];
  comp[di + 2] = bodyImg.img[si + 2]; comp[di + 3] = bodyImg.img[si + 3];
}
for (let y = 0; y < swordImg.h; y++) {
  for (let x = 0; x < swordImg.w; x++) {
    const si = (y * swordImg.w + x) * 4, di = (y * TW + 64 + x) * 4;  // 剑 @(64,0)
    comp[di] = swordImg.img[si]; comp[di + 1] = swordImg.img[si + 1];
    comp[di + 2] = swordImg.img[si + 2]; comp[di + 3] = swordImg.img[si + 3];
  }
}

// ---------------------------------------------------------------- 90° 面：转置区域 pack + 像素搬运
// 下剑区：合图 u∈[64,96), v∈[32,64)（32×32）
const ROT_ZONE = { x0: 64, y0: 32, w: 32, h: 32 };
function shelfPack(rects, zoneW, zoneH) {
  const idx = rects.map((_, i) => i).sort((a, b) => rects[b].h - rects[a].h);
  const shelves = [];   // {y, h, x}
  for (const i of idx) {
    const r = rects[i];
    let s = null;
    for (const sh of shelves) if (sh.h + 1e-6 >= r.h && sh.x + r.w <= zoneW + 1e-6) { s = sh; break; }
    if (!s) { s = { y: shelves.reduce((m, sh) => Math.max(m, sh.y + sh.h), 0), h: 0, x: 0 }; shelves.push(s); }
    r.x = s.x; r.y = s.y;
    s.x += r.w; s.h = Math.max(s.h, r.h);
  }
  const used = shelves.reduce((m, sh) => Math.max(m, sh.y + sh.h), 0);
  if (used > zoneH + 1e-6) { console.error('自检失败: 90° 转置区域装不进 32×32 下区（用 ' + used.toFixed(2) + 'px 高）'); process.exit(1); }
}
{
  const jobs = faceUVJobs;
  for (const j of jobs) {
    j.rect = { w: Math.abs(j.sol.h), h: Math.abs(j.sol.w) };   // 转置：宽=旧|h|，高=旧|w|
  }
  shelfPack(jobs.map((j) => j.rect), ROT_ZONE.w, ROT_ZONE.h);
  for (const j of jobs) {
    const { U, V, w, h, corr } = j.sol;
    const a = ROT_ZONE.x0 + j.rect.x, b = ROT_ZONE.y0 + j.rect.y;
    const wn = j.rect.w, hn = j.rect.h;                        // 新区域宽/高
    // 旧槽 UV 点 P_i 与 新槽 S_j 的对应（S_j 为新区域 (a,b,wn,hn) 的角）
    const P = [[U + w, V], [U, V], [U, V + h], [U + w, V + h]];
    const corrInv = [0, 1, 2, 3].map((q) => corr.indexOf(q));  // 槽 j ← 旧 P[corrInv[j]]
    const S = [[a + wn, b], [a, b], [a, b + hn], [a + wn, b + hn]];
    // 仿射映射：源UV = M·(新UV)；由 3 个角解 2x2，第 4 角校验
    // 新像素偏移 (i,j)（0..wn, 0..hn）→ 源点：src = A2·(i,j) + t
    // 取新区域局部坐标：s1=(0,0)→S1, s0=(wn,0)→S0, s2=(0,hn)→S2
    const p1 = P[corrInv[1]], p0 = P[corrInv[0]], p2 = P[corrInv[2]];
    const bx = (p0[0] - p1[0]) / wn, by = (p2[0] - p1[0]) / hn;   // 源x 对新 i/j 的偏导
    const cx = (p0[1] - p1[1]) / wn, cy = (p2[1] - p1[1]) / hn;   // 源y
    const p3 = P[corrInv[3]];
    if (Math.abs(p1[0] + bx * wn + by * hn - p3[0]) > 1e-6 || Math.abs(p1[1] + cx * wn + cy * hn - p3[1]) > 1e-6) {
      console.error('自检失败: 90° 面角点非仿射（不应发生）', j.bone, j.cube, j.face, JSON.stringify([p1, p0, p2, p3])); process.exit(1);
    }
    j.map = { a, b, wn, hn, ox: p1[0], oy: p1[1], bx, by, cx, cy };
    // 像素搬运（texel 中心采样）
    const iw = Math.max(0, Math.ceil(wn - 1e-6)), ih = Math.max(0, Math.ceil(hn - 1e-6));
    for (let jj = 0; jj < ih; jj++) {
      for (let ii = 0; ii < iw; ii++) {
        const sx = p1[0] + bx * (ii + 0.5) + by * (jj + 0.5);
        const sy = p1[1] + cx * (ii + 0.5) + cy * (jj + 0.5);
        const px = sampleBilinear(swordImg, sx, sy);
        const di = ((b + jj) * TW + (a + ii)) * 4;
        comp[di] = px[0]; comp[di + 1] = px[1]; comp[di + 2] = px[2]; comp[di + 3] = px[3];
      }
    }
  }
  // 回填模型 uv（转置区域坐标 +64）
  for (const j of jobs) {
    const bone = bones.find((x) => x.name === j.bone);
    const cube = bone.cubes[j.cube];
    cube.uv[j.newFace] = { uv: [r4(j.map.a), r4(j.map.b)], uv_size: [r4(j.map.wn), r4(j.map.hn)] };
    delete cube.uv[j.newFace].__job;
  }
  console.log('90° 转置区域：' + jobs.length + ' 个，shelf 高度 ' +
    jobs.reduce((m, j) => Math.max(m, j.map.b + j.map.hn - ROT_ZONE.y0), 0).toFixed(1) + 'px');
}
// ---------------------------------------------------------------- vanilla 渲染缩放 0.9375（源码定案，2026-10-03）
// 根因（PlayerRenderer.scale() L159-162，LivingEntityRenderer.render:102 调用）：
//   原版玩家实际渲染 = 32px×0.9375 = 30px = 1.875 块（脚踩地、头顶 1.875）；
//   Spark 管线（IEntityAnimatable.getWorldPositionMatrix = translate×rotateY，无 scale）
//   无此缩放 ⇒ 未缩放的 Spark 玩家模型 = 2.0 块 = 大 6.67%（用户长期看到的「更大」）。
// 10/05 修正：缩放**不能**烘进几何——spark_core 的 box-UV 矩形由
//   OPolygon.of: `size = cube.size.scale(16.0).floor()` 决定（UV 尺寸跟随几何尺寸！）：
//   几何 ×0.9375 后 8px cube→7.5px→floor=7px UV 矩形、锚点仍是 uv 左上角
//   ⇒ 贴图「整体放大、只留左上角」（用户 10/05 实测报告）。
// 现做法：模型保持原版 1:1 几何（box-UV 完整采样，10/02 验证过的正确外观）；
//   0.9375 由运行时施加在 root 骨上——SwordPlayerModelHandler 订阅 BoneUpdateEvent，
//   对本模型把 root 骨 scale 钳到 (0.9375,0.9375,0.9375)（root pivot=(0,0,0)，
//   等价于原版 poseStack.scale：绕脚缩放全部子骨，含动画 position 位移，UV 不受影响）。
//   ⇒ 本生成器 VANILLA_RENDER_SCALE 恒为 1.0（scaleBoneInPlace 保留为无害 no-op，
//     便于日后需要几何缩放时一键启用）。
const VANILLA_RENDER_SCALE = 1.0;
const r5 = (x) => +x.toFixed(5);
function scaleBoneInPlace(b) {
  if (b.pivot) b.pivot = b.pivot.map((v) => r5(v * VANILLA_RENDER_SCALE));
  for (const c of (b.cubes || [])) {
    c.origin = c.origin.map((v) => r5(v * VANILLA_RENDER_SCALE));
    c.size = c.size.map((v) => r5(v * VANILLA_RENDER_SCALE));
    if (c.pivot) c.pivot = c.pivot.map((v) => r5(v * VANILLA_RENDER_SCALE));
    if (c.inflate) c.inflate = r5(c.inflate * VANILLA_RENDER_SCALE);
  }
}
for (const b of bones) scaleBoneInPlace(b);

const compPNG = encodePNG(TW, TH, comp);

// ---------------------------------------------------------------- description
const desc = bodyGeo.description;
const model = {
  format_version: '1.12.0',
  'minecraft:geometry': [{
    description: {
      identifier: 'geometry.player_wrist',
      texture_width: 96,      // 64(身体) + 32(剑)
      texture_height: 64,
      visible_bounds_width: desc.visible_bounds_width,
      visible_bounds_height: desc.visible_bounds_height,
      visible_bounds_offset: desc.visible_bounds_offset,
    },
    bones,
  }],
};

// ---------------------------------------------------------------- 写文件
function out(rel, data, label) {
  fs.mkdirSync(path.dirname(rel), { recursive: true });
  fs.writeFileSync(rel, data, 'utf8');
  console.log('  wrote', label || rel);
}
const modelJSON = JSON.stringify(model, null, 2) + '\n';
out(PACK + `/models/${NS}/entity/player_wrist.json`, modelJSON, 'pack  player_wrist.json');
out(PACK + `/textures/${NS}/entity/player_wrist.png`, compPNG, 'pack  player_wrist.png (96×64)');
out(`${SRC}/spark_models/entity/${NS}/player_wrist.json`, modelJSON, 'src   player_wrist.json');
out(`${BUILD}/spark_models/entity/${NS}/player_wrist.json`, modelJSON, 'build player_wrist.json (游戏实际读取)');
out(`${SRC}/assets/${NS}/textures/entity/player_wrist.png`, compPNG, 'src   player_wrist.png (96×64)');
out(`${BUILD}/assets/${NS}/textures/entity/player_wrist.png`, compPNG, 'build player_wrist.png (游戏实际读取)');

// ---------------------------------------------------------------- 自检
function bbox(f) {
  const [u, v] = f.uv, [w, h] = f.uv_size;
  return [Math.min(u, u + w), Math.min(v, v + h), Math.max(u, u + w), Math.max(v, v + h)];
}
let fail = 0;
// 1) 所有剑 cube face-uv 不越出剑区 96×64 的 (64,0)-(96,64)
for (const b of bones) {
  if (!SWORD_BONES.includes(b.name) || !b.cubes) continue;
  for (const c of b.cubes) {
    if (!c.uv || Array.isArray(c.uv)) continue;
    for (const [f, face] of Object.entries(c.uv)) {
      const [x0, y0, x1, y1] = bbox(face);
      if (x0 < 64 - 1e-6 || y0 < -1e-6 || x1 > TW + 1e-6 || y1 > TH + 1e-6) {
        console.error(`  [FAIL] ${b.name}.${f} 面越界 bbox=${JSON.stringify([x0, y0, x1, y1])}`); fail++;
      }
    }
  }
}
// 2) 身体骨 = player_slim 骨 1:1（slim 3 宽臂；0.9375 由运行时 root 骨 scale 施加，见头部说明）
if (bodyGeo.bones.length !== bones.length - SWORD_BONES.length) {
  console.error('  [FAIL] 身体骨数量不符'); fail++;
}
for (const sb of bodyGeo.bones) {
  const want = JSON.parse(JSON.stringify(sb));
  scaleBoneInPlace(want);
  const db = bones.find((x) => x.name === sb.name);
  if (!db || JSON.stringify(db) !== JSON.stringify(want)) {
    console.error(`  [FAIL] 身体骨 ${sb.name} 与 player_slim 不一致`); fail++;
  }
}
console.log('  身体 10 骨 = player_slim 1:1 ✓（slim 3 宽臂；0.9375 运行时 root 骨 scale）');
// 2b) 几何 sanity（1:1 模型空间）：脚=0、头顶=32px（=2.0 块）、帽层顶=32.5px；
//     渲染时经 root scale 0.9375 → 头顶 30px=1.875 块、帽顶 30.46875px=1.9043 块（=原版渲染值）
{
  let minY = Infinity, maxY = -Infinity, hatTop = -Infinity;
  for (const b of bones) {
    if (SWORD_BONES.includes(b.name) || !b.cubes) continue;
    for (const c of b.cubes) {
      minY = Math.min(minY, c.origin[1]);
      maxY = Math.max(maxY, c.origin[1] + c.size[1]);
      if (b.name === 'head') hatTop = Math.max(hatTop, c.origin[1] + c.size[1] + (c.inflate || 0));
    }
  }
  const wantTop = 32 * VANILLA_RENDER_SCALE;                    // 32（1:1）
  const wantHat = (32 + 0.5) * VANILLA_RENDER_SCALE;            // 32.5（1:1）
  if (minY > 1e-6 || Math.abs(maxY - wantTop) > 0.01 || Math.abs(hatTop - wantHat) > 0.01) {
    console.error(`  [FAIL] 几何不符: 脚=${minY} 头顶=${maxY.toFixed(4)}(应 ${wantTop}) 帽顶=${hatTop.toFixed(4)}(应 ${wantHat})`); fail++;
  } else {
    const RS = 0.9375;   // 运行时 root scale（渲染值换算用）
    console.log(`  几何 ✓ 脚=${minY} 头顶=${maxY.toFixed(4)}px(=${(maxY / 16).toFixed(4)}块) 帽顶=${hatTop.toFixed(4)}px(=${(hatTop / 16).toFixed(4)}块)；×${RS} 渲染=${(maxY * RS / 16).toFixed(4)}/${(hatTop * RS / 16).toFixed(4)}块（=原版 1.875/1.9043）`);
  }
}
// 3) 锚点 = 物品静止姿态 pommel → 手腕（10/05 朝向修正）：A·pommelFile + B == wrist
const mb = bones.find((b) => b.name === 'main');
if (!mb || mb.parent !== 'right') { console.error('  [FAIL] main 缺失或父级非 right'); fail++; }
else if (!pommelFile) { console.error('  [FAIL] pommel 锚点未计算'); fail++; }
else {
  const ws = wrist.map((v) => r5(v * VANILLA_RENDER_SCALE));
  const ap = mulA(pommelFile);
  const d = Math.hypot(ap[0] + B[0] - ws[0], ap[1] + B[1] - ws[1], ap[2] + B[2] - ws[2]);
  if (d > 0.01) { console.error(`  [FAIL] pommel 锚点不在手腕: d=${d.toFixed(4)}`); fail++; }
  else console.log(`  pommel 锚点 = 手腕 ${JSON.stringify(ws)} ✓（静止姿态 pommel→手腕；含动画 pos 的实际落点由 verify_pose_v3 验）`);
}
// 4) 剑子树 parent 链
for (const [n, p] of Object.entries(SWORD_PARENT)) {
  const b = bones.find((x) => x.name === n);
  if (!b || b.parent !== p) { console.error(`  [FAIL] ${n} 缺失或父级非 ${p}`); fail++; }
}
// 5) left/right 无 cube
for (const n of ['left', 'right']) {
  const b = bones.find((x) => x.name === n);
  if (b && b.cubes && b.cubes.length > 0) { console.error(`  [FAIL] ${n} 仍有 cube`); fail++; }
}
// 6) 剑 cube 数 = 源 main 子树
const srcCubes = ['shade', 'blade', 'handle'].reduce((s, n) => s + (swordBoneMap[n].cubes ? swordBoneMap[n].cubes.length : 0), 0);
const dstCubes = ['shade', 'blade', 'handle'].reduce((s, n) => { const b = bones.find((x) => x.name === n); return s + (b.cubes ? b.cubes.length : 0); }, 0);
if (srcCubes !== dstCubes) { console.error(`  [FAIL] 剑 cube 数不符 src=${srcCubes} dst=${dstCubes}`); fail++; }
else console.log(`  剑 cube 数 = ${dstCubes} ✓   总骨数 = ${bones.length}（身体 ${bodyGeo.bones.length} + 剑 ${SWORD_BONES.length}）`);
// 7) 旋转共轭抽查：每个带旋转的源 cube，其输出 rotation 必须 = Trot(源)
{
  for (const n of ['shade', 'blade', 'handle']) {
    const srcs = swordBoneMap[n].cubes || [];
    const dsts = (bones.find((x) => x.name === n).cubes || []);
    for (let i = 0; i < srcs.length; i++) {
      if (!srcs[i].rotation) continue;
      const want = Trot(srcs[i].rotation);
      const got = dsts[i].rotation;
      if (JSON.stringify(want) !== JSON.stringify(got)) {
        console.error(`  [FAIL] ${n} cube${i} 旋转共轭不符 src=${JSON.stringify(srcs[i].rotation)} want=${JSON.stringify(want)} got=${JSON.stringify(got)}`);
        fail++;
      }
    }
  }
  // 已知值抽查（按 A 通用）：对角 A=diag(ax,ay,az) 共轭 ⇒ x 旋转×(ay·az)、y 旋转×(az·ax)、z 旋转×(ax·ay)
  //   A=I ⇒ 恒等 [45,45,45]→[45,45,45]；A=Ry180=diag(−1,1,−1) ⇒ [45,45,45]→[−45,45,−45]
  const spot = Trot([45, 45, 45]);
  const kx = A[1][1] * A[2][2], ky = A[2][2] * A[0][0], kz = A[0][0] * A[1][1];
  const spotWant = [45 * kx, 45 * ky, 45 * kz];
  if (JSON.stringify(spot) !== JSON.stringify(spotWant)) {
    console.error(`  [FAIL] Trot 抽查 [45,45,45] -> ${JSON.stringify(spot)}（应为 ${JSON.stringify(spotWant)}）`); fail++;
  } else {
    console.log(`  Trot 共轭抽查 [45,45,45] -> ${JSON.stringify(spot)} ✓（按 A=${JSON.stringify([kx, ky, kz])}）`);
  }
}
// 8) 复合贴图回读校验尺寸
{
  const chk = decodePNG(BUILD + '/assets/' + NS + '/textures/entity/player_wrist.png');
  if (chk.w !== TW || chk.h !== TH) { console.error(`  [FAIL] 复合贴图尺寸 ${chk.w}x${chk.h}`); fail++; }
  else {
    let bodyPx = 0, swordPx = 0, rotPx = 0;
    for (let y = 0; y < TH; y++) for (let x = 0; x < TW; x++) {
      const a = chk.img[(y * TW + x) * 4 + 3];
      if (a > 10) { if (x < 64) bodyPx++; else if (y < 32) swordPx++; else rotPx++; }
    }
    let bodySrcPx = 0;
    for (let i = 3; i < bodyImg.img.length; i += 4) if (bodyImg.img[i] > 10) bodySrcPx++;
    if (bodyPx < bodySrcPx * 0.9) { console.error(`  [FAIL] 身体区像素缺失: ${bodyPx}px（源 ${bodySrcPx}px，怀疑身体复制损坏）`); fail++; }
    console.log(`  复合贴图 ${TW}×${TH} ✓（身体区 ${bodyPx}px / 剑区(上) ${swordPx}px / 90°转置区(下) ${rotPx}px）`);
  }
}
// 9) UV 角点像素级校验：每个新面的槽角像素 = 旧面对应槽角像素（Spark 分配约定）
{
  const chk = decodePNG(BUILD + '/assets/' + NS + '/textures/entity/player_wrist.png');
  const pxAt = (img, x, y) => img.img[Math.round(Math.max(0, Math.min(img.h - 1, y))) * img.w * 4 + Math.round(Math.max(0, Math.min(img.w - 1, x))) * 4];
  let checked = 0, badPx = 0;
  const TOL = 24;
  for (const b of bones) {
    if (!SWORD_BONES.includes(b.name) || !b.cubes) continue;
    const srcBone = swordBoneMap[b.name];
    for (let ci = 0; ci < b.cubes.length; ci++) {
      const c = b.cubes[ci], sc = srcBone.cubes[ci];
      if (!sc.uv || typeof sc.uv !== 'object' || !c.uv || Array.isArray(c.uv)) continue;
      for (const [f, sface] of Object.entries(sc.uv)) {
        const nf = FACE_PAIR[f];
        const face = c.uv[nf];
        if (!face) continue;
        const U = sface.uv[0], V = sface.uv[1], w = sface.uv_size[0], h = sface.uv_size[1];
        const P = [[U + w, V], [U, V], [U, V + h], [U + w, V + h]];      // 旧槽 W0..W3（源 32×32 坐标）
        const corrInv = [0, 1, 2, 3].map((q) => CORR[f].indexOf(q));
        const [u, v] = face.uv, [ww, hh] = face.uv_size;
        const S = [[u + ww, v], [u, v], [u, v + hh], [u + ww, v + hh]];  // 新槽（合图坐标）
        for (let j = 0; j < 4; j++) {
          const i = corrInv[j];
          const a = pxAt(chk, S[j][0], S[j][1]);
          const e = sampleBilinear(swordImg, P[i][0], P[i][1]);
          const dmax = Math.max(Math.abs(a[0] - e[0]), Math.abs(a[1] - e[1]), Math.abs(a[2] - e[2]));
          checked++;
          if (dmax > TOL) {
            badPx++;
            if (badPx <= 4) console.error(`  [FAIL] UV角点不符 ${b.name} cube${ci} ${f}→${nf} 槽${j} 合图=${JSON.stringify([...a])} 期望≈${JSON.stringify(e)} (Δ=${dmax})`);
          }
        }
      }
    }
  }
  if (badPx === 0) console.log(`  UV 角点像素校验 ✓（${checked} 角点全过）`);
  else { console.error(`  [FAIL] UV 角点不符 ${badPx}/${checked}`); fail++; }
}
console.log(fail === 0 ? 'SELF-CHECK OK' : `SELF-CHECK FAILED (${fail})`);
process.exit(fail === 0 ? 0 : 1);
