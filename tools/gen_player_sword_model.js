#!/usr/bin/env node
/**
 * gen_player_sword_model.js
 *
 * 生成"玩家剑模型" player_sword.json：把剑物品模型 (item/sword.json) 刚体嵌入
 * 原版玩家模型 (player.json) 空间，并把皮肤手臂 rightArm 重挂到我们的手臂骨
 * (right) 之下形成绑定（皮肤手臂 1:1 跟随我们的动画）。
 *
 * 嵌入矩阵 M（内部空间 4x4 刚体，det=+1）——vanilla 持物链的共轭形式：
 *   vanilla 链（ItemInHandLayer，"vanilla 模型空间"帧）: world_v = A_java·v + b_java，
 *   A_java = [[−1,0,0],[0,0,−1],[0,−1,0]]，b_java = (0.0625, 1.375, 0.125)
 *     （肩点锚定: A_java·rightPivot + b_java = (−0.3125, 0.125, 0) = vanilla 右肩）。
 *   帧映射 vanilla模型空间 -> 玩家内部: x_int = V_lin·x_v + t_V，
 *   V_lin = diag(−1,−1,1)（X 左->右, Y 下->上），t_V = (0,1.5,0)。
 *   正确嵌入旋转 = 共轭 A = V_lin·A_java·V_lin⁻¹ = [[−1,0,0],[0,0,1],[0,1,0]]
 *     （注意：左乘 V_lin·A_java 会得到绕身体后轴差 180° 的错误扭转 —— 手朝后伸；
 *      共轭才是对的 —— 手朝前伸。用户确认的外观 = 手朝前。）
 *   平移 = 肩点锚定: b = shoulder_int − A·rightPivot_int = (0.6875, 0.125, −0.125)。
 *   A 轴对齐（e_x->−e_x, e_y->e_z, e_z->e_y）⇒ cube 变换精确（扁 cube 不 AABB 化）、
 *   scale 共轭精确（(0,1,1)/(−2.2,1,1) 保持）。
 *   历史：M₁（vanilla 链直译，左乘 V_lin）手朝后 + 前后镜像可见；
 *        M₂（mixin 链 L_ra(0)·G，≈M₁+8°）手朝后 + 一般旋转破坏 cube/scale 精度；
 *        M（本次）= 共轭 = M₁ 的扭转修正版，手朝前，全部变换精确。
 *
 * 文件级变换（物品文件 px → 玩家文件 px，D = diag(−1,1,1)）：
 *   A_f = D·A·D = [[−1,0,0],[0,0,1],[0,1,0]]，b_f = 16·D·b = (−11, 2, −2)
 *   点: p' = A_f·p + b_f；向量/size: v' = A_f·v
 *   旋转: R' = A·R·A⁻¹（内部空间共轭，再转回文件欧拉）
 *   scale: S' = (A·diag(S)·A⁻¹) 的对角（无单位，不走 D；A 轴对齐时精确）
 * 动画 keyframe 做同样变换（旋转共轭、位置/size 向量 A_f、scale 对角共轭），
 * 因此 t=0 时玩家模型世界姿态与 vanilla 链逐项一致（自检矩阵验证）。
 * t≠0 存在 state 动画 rightArm 摆动的近似漂移（厘米级，已接受）。
 *
 * 输出（pack + src 资源各一份）：
 *   models/<NS>/entity/player_sword.json
 *   animations/<NS>/entity/player_sword/3rd_still.json
 *   animations/<NS>/entity/player_sword/3rd_attack.json
 *   animations/<NS>/entity/player_sword/state.land.idle.json
 *   animations/<NS>/entity/player_sword/state.land.move.json
 * （状态动画从 base_state.json 原样复制并删除 rightArm 关键帧，避免破坏绑定）
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
// pack 模块目录程序化解析（避免拼写歧义）
const PACK = (() => {
  const d = fs.readdirSync(ROOT + '/run/client/spark_modules').filter((x) => !x.endsWith('.zip'))[0];
  return ROOT + '/run/client/spark_modules/' + d;
})();
const SRC = ROOT + '/src/main/resources';
const TOOLS = ROOT + '/tools/spark_player_model';

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

// ---------------------------------------------------------------- 向量/矩阵

const V = (x, y, z) => [x, y, z];
const vAdd = (a, b) => V(a[0] + b[0], a[1] + b[1], a[2] + b[2]);
const vSub = (a, b) => V(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
const vScale = (a, s) => V(a[0] * s, a[1] * s, a[2] * s);
const vDot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const vCross = (a, b) => V(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]);
const vLen = (a) => Math.hypot(a[0], a[1], a[2]);
const vNorm = (a) => vScale(a, 1 / vLen(a));

// 3x3 行主序 [row0, row1, row2]
const I3 = () => [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
function mMul(A, B) {
  const R = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  for (let i = 0; i < 3; i++)
    for (let j = 0; j < 3; j++)
      for (let k = 0; k < 3; k++) R[i][j] += A[i][k] * B[k][j];
  return R;
}
function mT(A) {
  return [[A[0][0], A[1][0], A[2][0]], [A[0][1], A[1][1], A[2][1]], [A[0][2], A[1][2], A[2][2]]];
}
function mApply(A, v) {
  return V(A[0][0] * v[0] + A[0][1] * v[1] + A[0][2] * v[2],
           A[1][0] * v[0] + A[1][1] * v[1] + A[1][2] * v[2],
           A[2][0] * v[0] + A[2][1] * v[1] + A[2][2] * v[2]);
}
function mDet(A) {
  return A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1])
       - A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0])
       + A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0]);
}
const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;

function rotX(a) { const c = Math.cos(a), s = Math.sin(a); return [[1, 0, 0], [0, c, -s], [0, s, c]]; }
function rotY(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, 0, s], [0, 1, 0], [-s, 0, c]]; }
function rotZ(a) { const c = Math.cos(a), s = Math.sin(a); return [[c, -s, 0], [s, c, 0], [0, 0, 1]]; }

// 4x4 齐次矩阵（用于骨骼链自检）
const I4 = () => [[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]];
function m4Mul(A, B) {
  const R = [[0, 0, 0, 0], [0, 0, 0, 0], [0, 0, 0, 0], [0, 0, 0, 0]];
  for (let i = 0; i < 4; i++)
    for (let j = 0; j < 4; j++)
      for (let k = 0; k < 4; k++) R[i][j] += A[i][k] * B[k][j];
  return R;
}
function m4Apply(A, p) {
  const x = p[0], y = p[1], z = p[2];
  return V(A[0][0]*x + A[0][1]*y + A[0][2]*z + A[0][3],
           A[1][0]*x + A[1][1]*y + A[1][2]*z + A[1][3],
           A[2][0]*x + A[2][1]*y + A[2][2]*z + A[2][3]);
}
// 旋转矩阵 + 平移 -> 4x4
function rigid4(R, t) {
  return [[R[0][0], R[0][1], R[0][2], t[0]],
          [R[1][0], R[1][1], R[1][2], t[1]],
          [R[2][0], R[2][1], R[2][2], t[2]],
          [0, 0, 0, 1]];
}

// ZYX 欧拉分解: M = Rz(a)·Ry(b)·Rx(g)，返回 [rx, ry, rz]（弧度，即 (g, b, a)）
function zyxDecomp(M) {
  const EPS = 1e-9;
  if (Math.abs(M[2][0]) < 1 - EPS) {
    const b = Math.asin(-M[2][0]);
    const a = Math.atan2(M[1][0], M[0][0]);
    const g = Math.atan2(M[2][1], M[2][2]);
    return [g, b, a];
  }
  if (M[2][0] < 0) { // b = +90
    const g = 0;
    const a = Math.atan2(-M[0][1], M[0][2]);
    return [g, Math.PI / 2, a];
  }
  // b = -90
  const g = 0;
  const a = Math.atan2(-M[0][1], M[1][1]);
  return [g, -Math.PI / 2, a];
}
// 4x4 矩阵 -> ZYX 欧拉（弧度），平移另取
function m4ToEuler(R4) {
  const R = [R4[0].slice(0, 3), R4[1].slice(0, 3), R4[2].slice(0, 3)];
  return zyxDecomp(R);
}

function round3(v) {
  return v.map((x) => {
    const r = Math.round(x * 10000) / 10000;
    return Object.is(r, -0) ? 0 : r;
  });
}

// ---------------------------------------------------------------- 输入

function readJSON(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

const playerBase = readJSON(TOOLS + '/player.json');
const swordItem = readJSON(PACK + '/models/' + NS + '/item/sword.json');
const animStill = readJSON(PACK + '/animations/' + NS + '/item/sword/3rd_still.json');
const animAttack = readJSON(PACK + '/animations/' + NS + '/item/sword/3rd_attack.json');
const baseState = readJSON(TOOLS + '/base_state.json');

const playerBones = playerBase['minecraft:geometry'][0].bones;
const swordBones = swordItem['minecraft:geometry'][0].bones;
const playerBoneMap = {};
for (const b of playerBones) playerBoneMap[b.name] = b;
const swordBoneMap = {};
for (const b of swordBones) swordBoneMap[b.name] = b;
const baseBones = playerBoneMap;

// ---------------------------------------------------------------- 采样/骨骼局部矩阵

// 采样一个动画在时刻 t 的某骨 keyframe（数值精确匹配，兼容 "0" / "0.0" 两种 key 写法；
// keyframe 值兼容裸数组 [x,y,z] 与对象 {pre, post, lerp_mode} 两种格式）
function sampleKey(animBones, boneName, track, t) {
  const b = animBones[boneName];
  if (!b || !b[track]) return null;
  for (const [k, kv] of Object.entries(b[track])) {
    if (Math.abs(parseFloat(k) - t) < 1e-9) {
      if (Array.isArray(kv)) return kv;
      return kv.post;
    }
  }
  return null;
}

// 骨骼局部矩阵（内部空间，4x4）：T(pivot)·T(localPos)·R·S·T(−pivot)
function boneLocal(pivot, localPos, eulerInt, scale) {
  // R = Rz(rz)·Ry(ry)·Rx(rx)，eulerInt = [rx, ry, rz] 弧度
  const [rx, ry, rz] = eulerInt;
  const R = mMul(rotZ(rz), mMul(rotY(ry), rotX(rx)));
  let M = rigid4(I3(), pivot);
  M = m4Mul(M, rigid4(I3(), localPos));
  M = m4Mul(M, rigid4(R, V(0, 0, 0)));
  if (scale) {
    M = m4Mul(M, [[scale[0],0,0,0],[0,scale[1],0,0],[0,0,scale[2],0],[0,0,0,1]]);
  }
  M = m4Mul(M, rigid4(I3(), vScale(pivot, -1)));
  return M;
}
// 文件欧拉 -> 内部欧拉弧度
const fileEulerToInt = (e) => [rad(-e[0]), rad(-e[1]), rad(e[2])];
// 文件 px（点/向量）-> 内部
const playerPivotInt = (fp) => [-fp[0] / 16, fp[1] / 16, fp[2] / 16];
const playerVecInt = (fv) => [-fv[0] / 16, fv[1] / 16, fv[2] / 16];

// ---------------------------------------------------------------- M：vanilla 持物链（内部空间）
// vanilla 链（ItemInHandLayer 世界渲染，"vanilla 模型空间"帧）:
//   world_v = A_java · v + b_java
// 帧映射 vanilla模型空间 -> 玩家内部: x_int = V_lin·x_v + t_V
//   V_lin = diag(−1,−1,1)（X: 左->右, Y: 下->上）, t_V = (0, 1.5, 0)
// 正确嵌入（物品帧 -> 玩家内部帧）的旋转 = 共轭 A = V_lin·A_java·V_lin⁻¹
//   （注意：不是左乘 V_lin·A_java —— 那会得到绕身体后轴差 180° 的错误扭转）
// 平移 = 肩点锚定: b = shoulder − A·P_rightPivot
// 结果 A 轴对齐（e_x->−e_x, e_y->e_z, e_z->e_y）: cube 变换精确、scale 变换精确、
// 且 t=0 时手臂朝下-前（手在身前）—— 与用户确认的外观一致。
const A_java = [[-1, 0, 0], [0, 0, -1], [0, -1, 0]];   // ItemInHandLayer 线性部
const V_lin = [[-1, 0, 0], [0, -1, 0], [0, 0, 1]];     // 帧映射线性部（自逆）
const t_V = [0, 1.5, 0];
const A_INT = mMul(V_lin, mMul(A_java, V_lin));        // V_lin·A_java·V_lin⁻¹
const shoulderInt = [0.3125, 1.375, 0];                // 玩家右肩（内部）
const prInt = playerPivotInt(swordBoneMap['right'].pivot);
const B_INT = vSub(shoulderInt, mApply(A_INT, prInt));
const M4 = rigid4(A_INT, B_INT);
console.log('\nM = vanilla 链共轭（内部空间）:');
console.log('  A_INT =', A_INT.map((r) => r.map((x) => +x.toFixed(6))));
console.log('  B_INT =', B_INT.map((x) => +x.toFixed(6)));
console.log('  t_V 校验: V_lin·A_java·V_lin 的平移无关, b 由肩点锚定');
const detA = mDet(A_INT);
console.log('  det(A) =', detA.toFixed(6), detA > 0 ? '(+1 ✓)' : '(!! 必须 +1)');
if (detA <= 0) { console.error('自检失败: M 不是旋转 (det 必须 +1)'); process.exit(1); }
// 轴对齐校验（cube/scale 精确变换的前提）
{
  let axisAligned = true;
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++)
    if (Math.abs(A_INT[i][j]) > 1e-12 && Math.abs(A_INT[i][j]) !== 1) axisAligned = false;
  console.log('  轴对齐 =', axisAligned, axisAligned ? '(cube/scale 变换精确 ✓)' : '(!! 非轴对齐: cube AABB 化 + scale 失真)');
  if (!axisAligned) { console.error('自检失败: A 非轴对齐'); process.exit(1); }
}

// 翻转诊断: 旧 M（mixin 链 ≈ 轴对齐但扭转错误）-> 新 M 的相对变换（期望 ≈ 绕身体后轴 180°）
const Aold = [[1, 0, 0], [0, 0, 1], [0, -1, 0]];
const Bold = [-0.0625, 0.125, 0.125];
const Minv4old = rigid4(mT(Aold), vScale(mApply(mT(Aold), Bold), -1));
const rel = m4Mul(Minv4old, M4);
const relE = m4ToEuler(rel);
console.log('  旧M⁻¹·新M = 欧拉(度) [' + deg(relE[0]).toFixed(2) + ', ' + deg(relE[1]).toFixed(2) + ', ' + deg(relE[2]).toFixed(2)
  + ']  t = [' + rel[0][3].toFixed(4) + ', ' + rel[1][3].toFixed(4) + ', ' + rel[2][3].toFixed(4) + ']  (≈ 前后 180° 扭转修正)');

// ---------------------------------------------------------------- 文件级变换（一般刚体 A_f, b_f）
// 物品文件 px -> 内部: x_int = D·p/16；内部 -> 玩家文件 px: p_file = 16·D·x_int
// => p' = (D·A·D)·p + 16·D·b
const D3 = [[-1, 0, 0], [0, 1, 0], [0, 0, 1]];
const vApplyD = (v) => V(-v[0], v[1], v[2]);
const A_f = mMul(D3, mMul(A_INT, D3));
const b_f = vScale(vApplyD(B_INT), 16);
console.log('  A_f =', A_f.map((r) => r.map((x) => +x.toFixed(6))));
console.log('  b_f =', b_f.map((x) => +x.toFixed(6)));

// 点: 文件 px
const Tpt = (p) => round3(vAdd(mApply(A_f, p), b_f));
// 向量: 文件 px（位置 delta / size）
const Tvec = (v) => round3(mApply(A_f, v));
// scale 变换（无单位，不走 D 翻转）: S' = A·diag(S)·A⁻¹ 的对角
// （A 轴对齐时精确；非轴对齐时取对角近似并告警）
function TvecScale(s) {
  const diagS = [[s[0], 0, 0], [0, s[1], 0], [0, 0, s[2]]];
  const M = mMul(A_INT, mMul(diagS, mT(A_INT)));
  const out = [M[0][0], M[1][1], M[2][2]];
  let off = 0;
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) if (i !== j) off = Math.max(off, Math.abs(M[i][j]));
  if (off > 1e-6) console.warn(`  [warn] scale 共轭有非对角项 ${off.toFixed(6)}（取对角近似）: [${s}] -> [${out.map((x) => +x.toFixed(4))}]`);
  return round3(out);
}

// 文件欧拉角 [rx, ry, rz]（度）-> 内部旋转矩阵
// 内部 = (−rx, −ry, rz) 弧度, rotateZYX = Rz·Ry·Rx
function fileRotToMat(e) {
  const [rx, ry, rz] = e;
  return mMul(rotZ(rad(rz)), mMul(rotY(rad(-ry)), rotX(rad(-rx))));
}
// 旋转: 文件欧拉 -> 文件欧拉（刚体共轭，内部空间 A·R·A⁻¹）
function Trot(e) {
  const R = fileRotToMat(e);
  const Rp = mMul(A_INT, mMul(R, mT(A_INT))); // A·R·A⁻¹（A 是旋转，A⁻¹=Aᵀ）
  const [gx, gy, gz] = zyxDecomp(Rp);
  // 内部 (rx,ry,rz) = (gx,gy,gz) -> 文件 (−rx, −ry, rz)
  return [deg(-gx), deg(-gy), deg(gz)];
}

// ---------------------------------------------------------------- 模型生成

// 变换一个 sword cube（文件 px）
function transformCube(c) {
  const out = {};
  out.origin = Tpt(c.origin);
  out.size = Tvec(c.size);
  // uv：face uv 的 u 整体 +64（合图 96×64，剑纹理在 (64,0)-(96,32)）
  if (c.uv) {
    out.uv = {};
    for (const [face, f] of Object.entries(c.uv)) {
      const nf = { uv: [f.uv[0] + 64, f.uv[1]] };
      if (f.uv_size) nf.uv_size = [f.uv_size[0], f.uv_size[1]];
      if (f.uv_rotation !== undefined) nf.uv_rotation = f.uv_rotation;
      out.uv[face] = nf;
    }
  } else if (c['uv'] === undefined && Array.isArray(c.box_uv)) {
    // player.json 风格 box uv —— 剑模型不用；保险处理
    out.box_uv = c.box_uv;
  }
  if (c.pivot) out.pivot = Tpt(c.pivot);
  if (c.rotation) out.rotation = round3(Trot(c.rotation));
  return out;
}

// ---------------------------------------------------------------- Java 皮肤逐面 UV
// Spark 的 box UV 模板（OPolygon.kt 的 uvSet 布局）与 Java 皮肤布局不一致：
// 躯干 south(背面) 会采样到 (44,20)-(52,32)（躯干 box 之外 = 手臂纹理）、
// west 面 4px 物理面横跨 16px 纹理（4x 拉伸）、north 面 v 偏移 +4 且 u 镜像、
// 头部各面错乱 —— 装备后身体皮肤"有花纹"即此。
// 因此 6 个身体 cube 改用显式逐面 UV（Java PlayerModel 64×64 精确坐标）。
// 符号规则（由 OPolygon.init 角点分配 + OVertexSet 顶点顺序推导，mirror=false）：
//   角点 [0]=(u1+u2,v1) [1]=(u1,v1) [2]=(u1,v1+v2) [3]=(u1+u2,v1+v2)
//   north(正面, min-z 面): [0]=min-x 角。皮肤正面"所见即所绘"：角色右(min-x)↔min-u
//     ⇒ 需 u1+u2 = min-u ⇒ uv 取区域右上角 (x+w,y)，uv_size = (−w, +h)
//   south(背面): 背面所见：角色右(min-x)↔max-u ⇒ uv=(x+w,y)，uv_size=(−w,+h)
//   west(角色右侧面): 前缘(min-z 角)↔min-u ⇒ uv=(x,y)，uv_size=(+w,+h)
//   east(角色左侧面): 前缘↔max-u ⇒ uv=(x,y)，uv_size=(+w,+h)
//   up: 自然朝向（俯视前缘=max-v）⇒ uv=(x,y)，uv_size=(+w,+h)（顶面极少可见）
//   down: 同剑模型约定 v 翻转 ⇒ uv=(x,y+h) 区域左下角，uv_size=(+w,−h)（底面极少可见；
//     注意负高时 uv 必须锚在区域下边缘，否则采样到错误区域 —— 剑模型 down 面即此约定）
// 区域坐标 = Java 皮肤标准布局（64×64）：
//   头 8³:  背(0,0) 前(0,8) 左(8,8) 右(16,8) 顶(8,16) 底(24,16)
//   躯干 8×12×4: 背(32,16) 前(20,16) 左(16,16) 右(28,16) 顶(20,12) 底(20,28)
//   右臂 4×12×4: 背(52,16) 前(44,16) 内(48,16) 外(40,16) 顶(44,12) 底(40,20)
//   左臂 4×12×4: 背(44,48) 前(36,48) 内(40,48) 外(32,48) 顶(36,44) 底(32,52)
//   右腿 4×12×4: 背(12,16) 前(4,16) 内(8,16) 外(0,16) 顶(4,12) 底(0,20)
//   左腿 4×12×4: 背(28,48) 前(20,48) 内(24,48) 外(16,48) 顶(20,44) 底(16,52)
// （行序 = 角色右→前→角色左→背；west 面=角色右侧，east 面=角色左侧）
// 64×64 皮肤布局（Mojang 4px_reference.png 模板 + Steve 实际皮肤逐像素双重确认，无重叠）：
//   每个部分 = [顶/底行(高 d)] 在 [侧面行(高 h)] 之上，顶行左=up 右=down
//   头 8³ (y0-15):  up(8,0) down(16,0) | west(0,8) north(8,8) east(16,8) south(24,8)
//   躯干 8×12×4 (y16-31): up(20,16) down(28,16) | west(16,20) north(20,20) east(28,20) south(32,20)
//   右臂 4×12×4 (y16-31): up(44,16) down(48,16) | west(40,20) north(44,20) east(48,20) south(52,20)
//   右腿 4×12×4 (y16-31): up(4,16) down(8,16)   | west(0,20) north(4,20) east(8,20) south(12,20)
//   左臂 4×12×4 (y44-59): up(36,44) down(40,44) | west(32,48) north(36,48) east(40,48) south(44,48)
//   左腿 4×12×4 (y44-59): up(20,44) down(24,44) | west(16,48) north(20,48) east(24,48) south(28,48)
// 符号规则（避免镜像，匹配 Java 渲染）：
//   north/south: uv 锚在区域右上角 (x+w,y)，uv_size=(-w,+h)
//   west/east:   uv 锚在区域左上角 (x,y)，  uv_size=(+w,+h)
//   up:          uv 锚在区域左上角 (x,y)，  uv_size=(+w,+h)
//   down:        uv 锚在区域左下角 (x,y+h)，uv_size=(+w,-h)（Java 1.0 起底面 180° 翻转）
const F = (u, v, w, h) => ({ uv: [u, v], uv_size: [w, h] });
const JAVA_FACE_UV = {
  waist: {
    north: F(28, 20, -8, 12), south: F(40, 20, -8, 12),
    west: F(16, 20, 4, 12), east: F(28, 20, 4, 12),
    up: F(20, 16, 8, 4), down: F(28, 20, 8, -4),
  },
  head: {
    north: F(16, 8, -8, 8), south: F(32, 8, -8, 8),
    west: F(0, 8, 8, 8), east: F(16, 8, 8, 8),
    up: F(8, 0, 8, 8), down: F(16, 8, 8, -8),
  },
  rightArm: {
    north: F(48, 20, -4, 12), south: F(56, 20, -4, 12),
    west: F(40, 20, 4, 12), east: F(48, 20, 4, 12),
    up: F(44, 16, 4, 4), down: F(48, 20, 4, -4),
  },
  leftArm: {
    north: F(40, 48, -4, 12), south: F(48, 48, -4, 12),
    west: F(32, 48, 4, 12), east: F(40, 48, 4, 12),
    up: F(36, 44, 4, 4), down: F(40, 48, 4, -4),
  },
  rightLeg: {
    north: F(8, 20, -4, 12), south: F(16, 20, -4, 12),
    west: F(0, 20, 4, 12), east: F(8, 20, 4, 12),
    up: F(4, 16, 4, 4), down: F(8, 20, 4, -4),
  },
  leftLeg: {
    north: F(24, 48, -4, 12), south: F(32, 48, -4, 12),
    west: F(16, 48, 4, 12), east: F(24, 48, 4, 12),
    up: F(20, 44, 4, 4), down: F(24, 48, 4, -4),
  },
};

// 深拷贝 player.json 骨（body 部分保持原样；6 个身体 cube 的 box uv 换成 Java 逐面 uv）
function copyBone(b) {
  const out = { name: b.name };
  if (b.parent) out.parent = b.parent;
  out.pivot = b.pivot.slice();
  if (b.cubes) out.cubes = b.cubes.map((c) => {
    const n = { origin: c.origin.slice(), size: c.size.slice() };
    if (JAVA_FACE_UV[b.name] && c.uv) {
      // box uv [u,v] 丢弃，改用 Java 皮肤逐面 uv（修复装备后皮肤"花纹"）
      n.uv = JSON.parse(JSON.stringify(JAVA_FACE_UV[b.name]));
    } else {
      if (c.uv) n.uv = c.uv.slice();
      if (c.box_uv) n.box_uv = c.box_uv.slice();
      if (c.pivot) n.pivot = c.pivot.slice();
      if (c.rotation) n.rotation = c.rotation.slice();
    }
    return n;
  });
  return out;
}

const keepNames = ['root', 'body', 'waist', 'head', 'leftArm', 'leftLeg', 'rightLeg'];

const outBones = [];
// 1. 玩家基础骨（顺序：parent 在前）
for (const n of keepNames) {
  const b = copyBone(baseBones[n]);
  outBones.push(b);
}
// 2. 我们的手臂骨 right：pivot = 物品 right 骨 pivot 的变换（vanilla 链锚定 = 恰好肩关节 [−5,22,0]）
const swordRight = swordBoneMap['right'];
const rightPivot = Tpt(swordRight.pivot);
console.log('\nright 骨 pivot 变换:', swordRight.pivot, '->', rightPivot, '（应恰好 = 肩关节 [−5,22,0]）');
if (rightPivot[0] !== -5 || rightPivot[1] !== 22 || rightPivot[2] !== 0) {
  console.error('自检失败: right 骨 pivot 不是肩关节 [−5,22,0]:', rightPivot);
  process.exit(1);
}
outBones.push({ name: 'right', parent: 'waist', pivot: rightPivot });
// 3. 皮肤手臂 rightArm 重挂到 right 之下（绑定；cube 与 UV 原样）
const rightArm = copyBone(baseBones['rightArm']);
rightArm.parent = 'right';
outBones.push(rightArm);
// 4. 剑的其余骨骼
function addSwordBone(name) {
  const b = swordBoneMap[name];
  const ob = { name: b.name, pivot: Tpt(b.pivot) };
  if (b.parent === 'hands') ob.parent = 'right';
  else if (b.parent === 'right') ob.parent = 'right';
  else ob.parent = b.parent;
  if (b.cubes && b.cubes.length) ob.cubes = b.cubes.map(transformCube);
  outBones.push(ob);
}
// left（空骨，动画引用；原 parent hands -> 改挂 right）
addSwordBone('left');
addSwordBone('main');
addSwordBone('shade');
addSwordBone('blade');
addSwordBone('handle');

const newModel = {
  format_version: '1.12.0',
  'minecraft:geometry': [{
    description: {
      identifier: 'geometry.player_sword',
      texture_width: 96,
      texture_height: 64,
      visible_bounds_width: 3,
      visible_bounds_height: 3.5,
      visible_bounds_offset: [0, 1.25, 0],
    },
    bones: outBones,
  }],
};

// ---------------------------------------------------------------- 动画生成

// 变换一个动画文件的 bones 部分
function transformAnimBones(bones) {
  const out = {};
  for (const [boneName, anim] of Object.entries(bones)) {
    const ob = {};
    for (const [track, keys] of Object.entries(anim)) {
      const ok = {};
      for (const [t, kv] of Object.entries(keys)) {
        const nk = { lerp_mode: kv.lerp_mode };
        if (track === 'rotation') nk.post = round3(Trot(kv.post));
        else if (track === 'position') nk.post = round3(Tvec(kv.post));
        else if (track === 'scale') nk.post = TvecScale(kv.post);
        else nk.post = kv.post;
        ok[t] = nk;
      }
      ob[track] = ok;
    }
    out[boneName] = ob;
  }
  return out;
}

function transformAnimFile(file) {
  const out = { animations: {} };
  for (const [name, anim] of Object.entries(file.animations)) {
    const oa = {
      loop: anim.loop,
      animation_length: anim.animation_length,
      bones: transformAnimBones(anim.bones),
    };
    out.animations[name] = oa;
  }
  return out;
}

const stillOut = transformAnimFile(animStill);
const attackOut = transformAnimFile(animAttack);

// 状态动画：原样复制 state.land.idle / state.land.move，删除 rightArm（保持绑定 1:1）
// 注意：waist/head/legs 骨未做任何几何变换（copyBone 原样），所以 keyframe 不需要共轭
function extractStateAnim(name) {
  const anim = baseState.animations[name];
  if (!anim) throw new Error('base_state.json 缺少动画 ' + name);
  const bones = {};
  for (const [bn, v] of Object.entries(anim.bones)) {
    if (bn === 'rightArm') continue; // 删除：皮肤手臂必须 1:1 跟随我们的 right 骨
    bones[bn] = v;
  }
  return { animations: { [name]: { loop: anim.loop, animation_length: anim.animation_length, bones } } };
}
const stateIdleOut = extractStateAnim('state.land.idle');
const stateMoveOut = extractStateAnim('state.land.move');

// ---------------------------------------------------------------- 自检

console.log('\n=== 自检 ===');
let maxErr = 0;

// 链条: 从 right 骨开始（含 right 本身）到 main 的 4x4（物品侧，内部空间）
function chainItem(animBones, t) {
  const r = sampleKey(animBones, 'right', 'rotation', t) || [0, 0, 0];
  const rp = sampleKey(animBones, 'right', 'position', t) || [0, 0, 0];
  const m = sampleKey(animBones, 'main', 'rotation', t) || [0, 0, 0];
  const mp = sampleKey(animBones, 'main', 'position', t) || [0, 0, 0];
  const rightPivotInt = playerPivotInt(swordRight.pivot);
  const mainPivotInt = playerPivotInt(swordBoneMap['main'].pivot);
  const rpInt = playerVecInt(rp);
  const mpInt = playerVecInt(mp);
  let M = boneLocal(rightPivotInt, rpInt, fileEulerToInt(r));
  M = m4Mul(M, boneLocal(mainPivotInt, mpInt, fileEulerToInt(m)));
  return M;
}

// 玩家链: 用变换后的 pivot/rotation/position（内部空间）
function chainPlayer(animBonesPlayer, t) {
  const r = sampleKey(animBonesPlayer, 'right', 'rotation', t) || [0, 0, 0];
  const rp = sampleKey(animBonesPlayer, 'right', 'position', t) || [0, 0, 0];
  const m = sampleKey(animBonesPlayer, 'main', 'rotation', t) || [0, 0, 0];
  const mp = sampleKey(animBonesPlayer, 'main', 'position', t) || [0, 0, 0];
  // 玩家模型里的 pivot（文件 px）= 变换后的值（与写盘一致，含 round3）
  const rightPivotFile = Tpt(swordRight.pivot);
  const mainPivotFile = Tpt(swordBoneMap['main'].pivot);
  let M = boneLocal(playerPivotInt(rightPivotFile), playerVecInt(rp), fileEulerToInt(r));
  M = m4Mul(M, boneLocal(playerPivotInt(mainPivotFile), playerVecInt(mp), fileEulerToInt(m)));
  return M;
}

// 1) 共轭恒等式: M·C_item(t) == C_player(t)·M（全部 keyframe 时刻）
console.log('--- 1) 共轭恒等式 M·C_item == C_player·M ---');
function checkChain(animItem, animPlayer, label) {
  const animBonesItem = animItem.animations[Object.keys(animItem.animations)[0]].bones;
  const animBonesPlayer = animPlayer.animations[Object.keys(animPlayer.animations)[0]].bones;
  const times = new Set(Object.keys(animBonesItem.right.rotation || {}));
  Object.keys(animBonesItem.right.position || {}).forEach((t) => times.add(t));
  let localMax = 0;
  for (const t of times) {
    const Citem = chainItem(animBonesItem, parseFloat(t));
    const Cplayer = chainPlayer(animBonesPlayer, parseFloat(t));
    const L = m4Mul(M4, Citem);
    const R = m4Mul(Cplayer, M4);
    let err = 0;
    for (let i = 0; i < 4; i++)
      for (let j = 0; j < 4; j++) err = Math.max(err, Math.abs(L[i][j] - R[i][j]));
    localMax = Math.max(localMax, err);
    console.log(`    [debug] t=${t}: err=${err.toExponential(3)}`);
  }
  console.log(`  ${label}: max|误差| = ${localMax.toExponential(3)}  (t=${[...times].join(',')})`);
  maxErr = Math.max(maxErr, localMax);
}
checkChain(animStill, stillOut, '3rd_still');
checkChain(animAttack, attackOut, '3rd_attack');

// 2) 肩点锚定 + 手朝前（用户确认的外观）+ scale 保持
console.log('--- 2) 肩点锚定 / 手朝前 / scale 保持 ---');
{
  // 2a) 肩点: M·P_rightPivot == shoulder（构造上精确）
  const anchor = vAdd(mApply(A_INT, prInt), B_INT);
  const anchorErr = vSub(anchor, shoulderInt);
  const anchorMax = Math.max(...anchorErr.map((x) => Math.abs(x)));
  console.log('  肩点误差 =', anchorMax.toExponential(3), '(内部块)');
  maxErr = Math.max(maxErr, anchorMax);

  // 2b) 手朝前: t=0 时腕端（right 骨上的手臂末端，物品帧 (0.375,0.125,0.5)）
  //     经过 right 骨 t=0 keyframe + M 后必须在玩家前方 (z<0) 且低于肩 (y<1.375)
  //     （腕在 right 骨上，不含 main 子链 —— main 的位置偏移与腕无关）
  const animBonesStill = animStill.animations['3rd_still'].bones;
  const r0 = sampleKey(animBonesStill, 'right', 'rotation', 0) || [0, 0, 0];
  const rp0 = sampleKey(animBonesStill, 'right', 'position', 0) || [0, 0, 0];
  const Lright0 = boneLocal(playerPivotInt(swordRight.pivot), playerVecInt(rp0), fileEulerToInt(r0));
  const wristItem = [0.375, 0.125, 0.5]; // 右 pivot + 手臂长轴 0.75（−z 方向）
  const wristPlayer = m4Apply(m4Mul(M4, Lright0), wristItem);
  console.log(`  t=0 腕端(玩家内部) = [${wristPlayer[0].toFixed(4)}, ${wristPlayer[1].toFixed(4)}, ${wristPlayer[2].toFixed(4)}]`);
  if (!(wristPlayer[2] < 0)) { console.error('自检失败: 手腕在身后 (z>0) —— 应该朝前!'); process.exit(1); }
  if (!(wristPlayer[1] < shoulderInt[1])) { console.error('自检失败: 手腕高于肩!'); process.exit(1); }
  console.log('  手腕在身前且低于肩 ✓');

  // 2c) scale 保持: (0,1,1) 与 (−2.2,1,1) 变换后不变（shade 静止隐藏 / 攻击翻转效果）
  const s1 = TvecScale([0, 1, 1]);
  const s2 = TvecScale([-2.2, 1, 1]);
  console.log('  scale (0,1,1) ->', s1, '  (−2.2,1,1) ->', s2);
  if (Math.abs(s1[0]) > 1e-9 || Math.abs(s1[1] - 1) > 1e-9 || Math.abs(s1[2] - 1) > 1e-9) {
    console.error('自检失败: scale (0,1,1) 未被保持（shade 静止时会泄露）'); process.exit(1);
  }
  if (Math.abs(s2[0] + 2.2) > 1e-6) { console.error('自检失败: 负 scale 符号丢失'); process.exit(1); }
}

// 4) 静态子链 + 旋转 cube 自检（t=0，3rd_still）
console.log('--- 4) 静态子链 + 旋转 cube ---');
function chainErr(Ci, Cp) {
  const L = m4Mul(M4, Ci);
  const R = m4Mul(Cp, M4);
  let e = 0;
  for (let i = 0; i < 4; i++)
    for (let j = 0; j < 4; j++) e = Math.max(e, Math.abs(L[i][j] - R[i][j]));
  return e;
}
function staticChainCheck() {
  const bones = ['blade', 'handle', 'shade'];
  let localMax = 0;
  const baseE = chainErr(
    chainItem(animStill.animations['3rd_still'].bones, 0),
    chainPlayer(stillOut.animations['3rd_still'].bones, 0));
  console.log(`  [debug] 基链 right+main 误差 = ${baseE.toExponential(3)}`);
  for (const bn of bones) {
    const b = swordBoneMap[bn];
    let Ci = chainItem(animStill.animations['3rd_still'].bones, 0);
    Ci = m4Mul(Ci, boneLocal(
      playerPivotInt(b.pivot), V(0, 0, 0), fileEulerToInt([0, 0, 0])));
    let Cp = chainPlayer(stillOut.animations['3rd_still'].bones, 0);
    const bp = Tpt(b.pivot);
    Cp = m4Mul(Cp, boneLocal(
      playerPivotInt(bp), V(0, 0, 0), fileEulerToInt([0, 0, 0])));
    const eBone = chainErr(Ci, Cp);
    const cube = (b.cubes || []).find((c) => c.rotation);
    let eCube = null;
    if (cube) {
      const cpI = playerPivotInt(cube.pivot);
      const cpP = Tpt(cube.pivot);
      Ci = m4Mul(Ci, boneLocal(cpI, V(0, 0, 0), fileEulerToInt(cube.rotation)));
      Cp = m4Mul(Cp, boneLocal(playerPivotInt(cpP), V(0, 0, 0), fileEulerToInt(Trot(cube.rotation))));
      eCube = chainErr(Ci, Cp);
    }
    console.log(`  [debug] +骨 ${bn}: 误差 = ${eBone.toExponential(3)}${cube ? `  +cube(rot ${cube.rotation}): ${eCube.toExponential(3)}` : ''}`);
    localMax = Math.max(localMax, eBone, eCube || 0);
  }
  console.log(`  静态子链+旋转cube: max|误差| = ${localMax.toExponential(3)}`);
  maxErr = Math.max(maxErr, localMax);
}
staticChainCheck();

// 5) 锚点: 物品 right pivot -> 肩关节附近
console.log('--- 5) 锚点 ---');
{
  const prInt = playerPivotInt(swordRight.pivot);
  const prMapped = m4Apply(M4, prInt);
  const d = vLen(vSub(prMapped, [0.3125, 1.375, 0]));
  console.log('  P_r(0.375,0.125,1.25) ->', prMapped.map((x) => +x.toFixed(4)),
    '（肩关节 [0.3125, 1.375, 0]，距离', d.toFixed(4), '格）');
  if (d > 0.25) { console.error('自检失败: 锚点偏离肩关节过远'); process.exit(1); }
}

// 6) 骨骼名完整性: 动画引用的骨骼必须存在于新模型
const modelBoneNames = new Set(outBones.map((b) => b.name));
for (const [label, file] of [['3rd_still', stillOut], ['3rd_attack', attackOut], ['state.land.idle', stateIdleOut], ['state.land.move', stateMoveOut]]) {
  for (const anim of Object.values(file.animations)) {
    for (const bn of Object.keys(anim.bones)) {
      if (!modelBoneNames.has(bn)) throw new Error(`自检失败: ${label} 引用了不存在的骨骼 ${bn}`);
    }
  }
}
console.log('  骨骼引用完整性: OK（' + [...modelBoneNames].join(', ') + '）');

// 7) 身体 cube 逐面 UV 自检：6 个身体 cube 必须用 faceUV（非 box uv），
// 各面区域 = Java 皮肤标准布局且在 64×64 皮肤区（u/v ∈ [0,64]）内、互不重叠
console.log('--- 7) 身体 cube 逐面 UV ---');
{
  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  // Java 皮肤标准布局：每个 body 部分的 6 面区域 [x, y, w, h]
  const JAVA_REGIONS = {
    waist:    { north: [20, 20, 8, 12], south: [32, 20, 8, 12], west: [16, 20, 4, 12], east: [28, 20, 4, 12], up: [20, 16, 8, 4], down: [28, 16, 8, 4] },
    head:     { north: [8, 8, 8, 8], south: [24, 8, 8, 8], west: [0, 8, 8, 8], east: [16, 8, 8, 8], up: [8, 0, 8, 8], down: [16, 0, 8, 8] },
    rightArm: { north: [44, 20, 4, 12], south: [52, 20, 4, 12], west: [40, 20, 4, 12], east: [48, 20, 4, 12], up: [44, 16, 4, 4], down: [48, 16, 4, 4] },
    leftArm:  { north: [36, 48, 4, 12], south: [44, 48, 4, 12], west: [32, 48, 4, 12], east: [40, 48, 4, 12], up: [36, 44, 4, 4], down: [40, 44, 4, 4] },
    rightLeg: { north: [4, 20, 4, 12], south: [12, 20, 4, 12], west: [0, 20, 4, 12], east: [8, 20, 4, 12], up: [4, 16, 4, 4], down: [8, 16, 4, 4] },
    leftLeg:  { north: [20, 48, 4, 12], south: [28, 48, 4, 12], west: [16, 48, 4, 12], east: [24, 48, 4, 12], up: [20, 44, 4, 4], down: [24, 44, 4, 4] },
  };
  // 区域矩形 [x, y, w, h]（uv_size 带符号时还原为无符号包围盒）
  const bboxOf = (f) => {
    const [u, v] = f.uv, [w, h] = f.uv_size;
    return [Math.min(u, u + w), Math.min(v, v + h), Math.abs(w), Math.abs(h)];
  };
  const rects = [];
  for (const bn of Object.keys(JAVA_REGIONS)) {
    const bone = outBones.find((b) => b.name === bn);
    if (!bone) throw new Error(`自检失败: 模型缺少骨骼 ${bn}`);
    if (!bone.cubes || bone.cubes.length !== 1) throw new Error(`自检失败: ${bn} 应有 1 个 cube`);
    const cube = bone.cubes[0];
    if (Array.isArray(cube.uv)) throw new Error(`自检失败: ${bn} 仍是 box uv ${JSON.stringify(cube.uv)}（花纹 bug）`);
    if (cube.box_uv) throw new Error(`自检失败: ${bn} 残留 box_uv`);
    if (!cube.uv || typeof cube.uv === 'object') {
      // faceUV 对象
      for (const face of FACES) {
        const f = cube.uv[face];
        if (!f || !f.uv || !f.uv_size) throw new Error(`自检失败: ${bn}.${face} 缺 faceUV`);
        const bb = bboxOf(f);
        for (const v of [bb[0], bb[1], bb[0] + bb[2], bb[1] + bb[3]]) {
          if (v < 0 || v > 64) throw new Error(`自检失败: ${bn}.${face} 越界 64×64: uv=${f.uv} size=${f.uv_size}`);
        }
        const want = JAVA_REGIONS[bn][face];
        if (bb[0] !== want[0] || bb[1] !== want[1] || bb[2] !== want[2] || bb[3] !== want[3]) {
          throw new Error(`自检失败: ${bn}.${face} 区域 ${bb} ≠ Java 标准 ${want}`);
        }
        rects.push(bb);
      }
    } else {
      throw new Error(`自检失败: ${bn} uv 类型异常`);
    }
  }
  // 互不重叠（同面积允许贴边）
  for (let i = 0; i < rects.length; i++) {
    for (let j = i + 1; j < rects.length; j++) {
      const a = rects[i], b = rects[j];
      const ox = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
      const oy = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
      if (ox > 0 && oy > 0) throw new Error(`自检失败: 区域重叠 ${a} ∩ ${b}`);
    }
  }
  console.log(`  6 个身体 cube 均为 faceUV，36 面区域 = Java 标准布局、64×64 内、无重叠 ✓`);
}

// 阈值说明：输出 pivot/角度舍入 1e-4（px/度），引起的矩阵误差 ~1e-5；
// 真正的变换错误是 O(0.01) 量级，2e-5 可清晰区分。
if (maxErr > 2e-5) {
  console.error('自检失败: 误差超限', maxErr);
  process.exit(1);
}
console.log('自检通过 ✓\n');

// ---------------------------------------------------------------- 输出

function writeBoth(relPack, relSrc, content) {
  const p1 = path.join(PACK, relPack);
  const p2 = path.join(SRC, relSrc);
  for (const p of [p1, p2]) {
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, JSON.stringify(content, null, 2) + '\n', 'utf8');
    console.log('写入', p);
  }
}

writeBoth(
  `models/${NS}/entity/player_sword.json`,
  `spark_models/entity/${NS}/player_sword.json`,
  newModel
);
writeBoth(
  `animations/${NS}/entity/player_sword/3rd_still.json`,
  `spark_animations/entity/${NS}/player_sword/3rd_still.json`,
  stillOut
);
writeBoth(
  `animations/${NS}/entity/player_sword/3rd_attack.json`,
  `spark_animations/entity/${NS}/player_sword/3rd_attack.json`,
  attackOut
);
writeBoth(
  `animations/${NS}/entity/player_sword/state.land.idle.json`,
  `spark_animations/entity/${NS}/player_sword/state.land.idle.json`,
  stateIdleOut
);
writeBoth(
  `animations/${NS}/entity/player_sword/state.land.move.json`,
  `spark_animations/entity/${NS}/player_sword/state.land.move.json`,
  stateMoveOut
);

console.log('\n完成。');
