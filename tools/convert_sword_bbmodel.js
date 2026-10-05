#!/usr/bin/env node
/**
 * convert_sword_bbmodel.js
 *
 * 将 Blockbench 5.0 项目文件 (sword.bbmodel, bedrock_animation_mode=entity)
 * 转换为 Spark-Core 资源：
 *
 *   src/main/resources/spark_models/item/catoxidesbattlerebuild/sword.json
 *   src/main/resources/spark_animations/item/catoxidesbattlerebuild/sword/<name>.json
 *
 * 转换约定（均已对照 Spark-Core 源码验证）：
 *   - 坐标系：bbmodel 模型空间（bedrock 约定：bone pivot 与 cube origin 同处
 *     父级变换后的空间；本模型静态链无旋转，故直接取绝对值）。
 *   - 动画：Blockbench 在 bedrock_animation_mode 下存的就是 bedrock 空间值。
 *
 * X 翻转约定（--flip-x，必须加——对照 Spark-Core 加载源码推导）：
 *   Spark 读模型 JSON 时会做固定变换（ModelModule.kt / OBoneAnimation.kt）：
 *     模型:  bone pivot ×(-1,1,1)/16；cube origin 取 -(x+size.x)/16；
 *            cube/bone 旋转 ×(-1,-1,1) → 弧度
 *     动画:  position ×(-1/16, 1/16, 1/16)；rotation ×(-1,-1,1) → 弧度
 *   即"直通"写入的 bbmodel 数值会被 Spark 渲染成 X 镜像（左右手反）。
 *   为使渲染结果 = bbmodel 原始朝向，JSON 里须预补偿：
 *     位置/pivot/动画 position:        (x,y,z)  -> (-x, y, z)
 *     cube origin（最小角）:           (x,y,z)  -> (-(x+size.x), y, z)
 *     旋转/动画 rotation:              (rx,ry,rz) -> (-rx, -ry, rz)
 *     scale / UV: 不变
 *   验证：旧版可用 swords 包右臂 cube 旋转 JSON=[-90,0,0] → Spark 加载后
 *   [90,0,0] = bbmodel 原值；其右臂 pivot 在 JSON -X 侧 → 渲染回 +X。✓
 *   - lerp：Spark 只支持 linear / catmullrom（InterpolationType 枚举确认）。
 *     bezier 关键帧按每轴三次贝塞尔采样烘焙为密集 linear 帧（每段 16 采样，
 *     含端点；每轴时间曲线独立，按轴采样后合并成单时间轴）。
 *   - camera animator 跳过（模型中没有 camera 骨）。
 *   - loop 映射：loop→"true", once→"once", hold→"hold_on_last_frame"。
 *
 * 用法：node tools/convert_sword_bbmodel.js [bbmodel路径] --flip-x
 *   --flip-x  预补偿 Spark 加载时的 X 镜像（手持物品必须加，见上方说明）
 */
'use strict';
const fs = require('fs');
const path = require('path');

const REPO = path.resolve(__dirname, '..');
const BBMODEL_PATH = process.argv[2] || 'D:/Download/sword.bbmodel';
const NS = 'catoxidesbattlerebuild';
const MODEL_ID = 'sword';
const BEZIER_SAMPLES = 16; // 每个贝塞尔段每轴的采样数（含端点）
const ROUND = 4;
const FLIP_X = process.argv.includes('--flip-x'); // 预补偿 Spark 加载的 X 镜像
if (!FLIP_X) console.log('WARN: 未加 --flip-x，输出为直通值（渲染后左右会反）');

// X 翻转预补偿（见头部注释推导）
const flipPos = (v) => (FLIP_X ? [-v[0], v[1], v[2]] : v);
const flipRot = (v) => (FLIP_X ? [-v[0], -v[1], v[2]] : v);
// 动画关键帧：position 翻 x；rotation 翻 x、y；scale 不变
const flipChanVal = (ch, a, v) => {
  if (!FLIP_X) return v;
  if (ch === 'position') return a === 0 ? -v : v;
  if (ch === 'rotation') return a <= 1 ? -v : v;
  return v;
};

const r = (v) => {
  if (v === 0) return 0;
  const n = Number(v);
  const p = Math.pow(10, ROUND);
  return Math.round(n * p) / p;
};
const num = (v) => (typeof v === 'string' ? parseFloat(v) : v);
const r3 = (v) => [r(v[0]), r(v[1]), r(v[2])];
const r2 = (v) => [r(v[0]), r(v[1])];

const j = JSON.parse(fs.readFileSync(BBMODEL_PATH, 'utf8'));

// ---------------------------------------------------------------- bones ----
const groups = new Map(j.groups.map((g) => [g.uuid, g]));
const bones = []; // 顺序：父先于子
const cubeByBone = new Map(); // boneName -> element[]

function walkOutliner(children, parentName) {
  for (const c of children) {
    if (typeof c === 'string') {
      const el = j.elements.find((e) => e.uuid === c);
      if (!el) {
        console.error('WARN: outliner 叶子找不到 element:', c);
        continue;
      }
      if (el.type !== 'cube') {
        console.log('  skip 非 cube 元素:', el.type, el.uuid.slice(0, 8));
        continue;
      }
      if (!parentName) {
        console.error('ERROR: 顶层 cube 没有所属骨骼:', el.uuid.slice(0, 8));
        continue;
      }
      if (!cubeByBone.has(parentName)) cubeByBone.set(parentName, []);
      cubeByBone.get(parentName).push(el);
      continue;
    }
    const g = groups.get(c.uuid);
    if (!g) {
      console.error('WARN: outliner 组不在 groups[] 中:', c.uuid.slice(0, 8));
      continue;
    }
    bones.push({ name: g.name, parent: parentName, origin: g.origin, rotation: g.rotation || [0, 0, 0] });
    walkOutliner(c.children || [], g.name);
  }
}
walkOutliner(j.outliner, null);
console.log('骨骼链:', bones.map((b) => b.name + (b.parent ? '<' + b.parent : '(root)')).join(' '));

// ---------------------------------------------------------------- cubes ----
function faceUv(el, face) {
  const f = el.faces[face];
  if (!f) return null;
  const [u1, v1, u2, v2] = f.uv;
  // 规则对照 Blockbench 官方导出验证：
  //   侧面: uv=[u1,v1]  size=[u2-u1, v2-v1]
  //   上下: uv=[u2,v2]  size=[u1-u2, v1-v2]
  const uv = face === 'up' || face === 'down' ? [u2, v2] : [u1, v1];
  const size = face === 'up' || face === 'down' ? [u1 - u2, v1 - v2] : [u2 - u1, v2 - v1];
  const o = { uv: r2(uv), uv_size: r2(size) };
  if (f.rotation) o.uv_rotation = f.rotation;
  return o;
}

function cubeJson(el) {
  const size = [el.to[0] - el.from[0], el.to[1] - el.from[1], el.to[2] - el.from[2]];
  // cube origin 是最小角：X 翻转后 x -> -(x + size.x)
  const origin = FLIP_X ? [-(el.from[0] + size[0]), el.from[1], el.from[2]] : el.from;
  const c = {
    origin: r3(origin),
    size: r3(size),
    uv: {
      north: faceUv(el, 'north'), east: faceUv(el, 'east'), south: faceUv(el, 'south'),
      west: faceUv(el, 'west'), up: faceUv(el, 'up'), down: faceUv(el, 'down'),
    },
  };
  const rot = flipRot(el.rotation || [0, 0, 0]);
  if (el.origin && rot.some((v) => v !== 0)) {
    c.pivot = r3(flipPos(el.origin));
    c.rotation = rot;
  }
  return c;
}

// --------------------------------------------------------------- model -----
const vb = j.visible_box || [4, 2, 0];
const model = {
  format_version: '1.12.0',
  'minecraft:geometry': [
    {
      description: {
        identifier: 'geometry.' + MODEL_ID,
        texture_width: j.resolution ? j.resolution.width : 32,
        texture_height: j.resolution ? j.resolution.height : 32,
        visible_bounds_width: vb[0],
        visible_bounds_height: vb[1],
        visible_bounds_offset: [0, vb[2], 0],
      },
      bones: bones.map((b) => {
        const o = { name: b.name };
        if (b.parent) o.parent = b.parent;
        const pivot = flipPos(b.origin);
        o.pivot = r3(pivot);
        const brot = flipRot(b.rotation);
        if (brot.some((v) => v !== 0)) o.rotation = brot;
        o.cubes = (cubeByBone.get(b.name) || []).map(cubeJson);
        return o;
      }),
    },
  ],
};

// --------------------------------------------------------- animations ------
const LOOP_MAP = { loop: 'true', once: 'once', hold: 'hold_on_last_frame' };

function cubicBezier(t, p0, p1, p2, p3) {
  const u = 1 - t;
  return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
}

/**
 * 把一个 channel 的关键帧序列转换为 Spark 格式的时间映射。
 * bbmodel 关键帧: {channel, data_points:[{x,y,z}], time, interpolation,
 *                  bezier_right_time/value, bezier_left_time/value (每轴各一份)}
 */
function convertChannel(kfs, ch) {
  const sorted = [...kfs].sort((a, b) => num(a.time) - num(b.time));
  const merged = new Map(); // timeStr -> {post:[x,y,z]}
  const order = []; // 时间顺序

  function emit(time, val) {
    const t = r(time);
    const key = String(t);
    if (!merged.has(key)) {
      merged.set(key, { post: [0, 0, 0] });
      order.push(key);
    }
    const slot = merged.get(key);
    for (let a = 0; a < 3; a++) if (val[a] !== undefined) slot.post[a] = r(flipChanVal(ch, a, val[a]));
  }

  for (let i = 0; i < sorted.length; i++) {
    const k = sorted[i];
    const next = sorted[i + 1];
    const kTime = num(k.time);
    const kVal = [num(k.data_points[0].x), num(k.data_points[0].y), num(k.data_points[0].z)];

    if (k.interpolation === 'bezier') {
      // 先落本锚点
      emit(kTime, kVal);
      // 烘焙 [k -> next] 段（如果存在且两端都有把手数据）
      if (next && k.bezier_right_time) {
        const nTime = num(next.time);
        const nVal = [num(next.data_points[0].x), num(next.data_points[0].y), num(next.data_points[0].z)];
        const L = nTime - kTime;
        // 把手语义（对照 Blockbench 官方导出采样验证）：
        //   时间把手 = 段长 L 的比例（rt*L / lt*L），值把手 = 绝对偏移
        //   下一关键帧非 bezier 时其左侧把手视为 0（P2 与 P3 重合）
        const hasLeft = !!(next.bezier_left_time && next.bezier_left_value);
        // 每轴独立的 (time, value) 贝塞尔曲线
        const axisPts = []; // [axis][s] = {t, v}
        for (let a = 0; a < 3; a++) {
          const pts = [];
          for (let s = 0; s < BEZIER_SAMPLES; s++) {
            const t = s / (BEZIER_SAMPLES - 1);
            const p1t = hasLeft ? num(next.bezier_left_time[a]) * L : 0;
            const p1v = hasLeft ? num(next.bezier_left_value[a]) : 0;
            pts.push({
              t: cubicBezier(t, kTime,
                kTime + num(k.bezier_right_time[a]) * L,
                nTime + p1t,
                nTime),
              v: cubicBezier(t, kVal[a],
                kVal[a] + num(k.bezier_right_value[a]),
                nVal[a] + p1v,
                nVal[a]),
            });
          }
          axisPts.push(pts);
        }
        // 合并：按时间收集所有轴的所有采样点，逐点取各轴在该时间的线性内插值
        const all = [];
        axisPts.forEach((pts, a) => pts.forEach((p) => all.push({ t: p.t, v: p.v, a })));
        all.sort((p, q) => p.t - q.t);
        for (const p of all) {
          const vals = [];
          for (let a = 0; a < 3; a++) {
            const pts = axisPts[a];
            let v = pts[0].v;
            for (let s = 1; s < pts.length; s++) {
              if (Math.abs(p.t - pts[s].t) < 1e-9) { v = pts[s].v; break; }
              if (p.t > pts[s - 1].t && p.t <= pts[s].t) {
                const f = (p.t - pts[s - 1].t) / (pts[s].t - pts[s - 1].t);
                v = pts[s - 1].v + f * (pts[s].v - pts[s - 1].v);
                break;
              }
            }
            vals.push(v);
          }
          emit(p.t, vals);
        }
      }
      continue;
    }

    if (k.interpolation !== 'linear' && k.interpolation !== 'catmullrom') {
      throw new Error(`不支持的插值类型: ${k.interpolation} (动画内)`);
    }
    // 落锚点（lerp_mode 在后面的 lerpMap 中统一处理）
    emit(kTime, kVal);
  }

  // 生成 (time -> lerp_mode) 映射：锚点取 bbmodel 中该关键帧的 interpolation（段属性）。
  // bezier 锚点：其后紧跟烘焙出的 linear 密集帧，锚点本身用 linear。
  const lerpMap = new Map();
  for (let i = 0; i < sorted.length - 1; i++) {
    const ip = sorted[i].interpolation;
    lerpMap.set(String(r(num(sorted[i].time))), ip === 'bezier' ? 'linear' : ip);
  }
  // 烘焙段内部的点用 linear
  for (const key of order) {
    if (!lerpMap.has(key)) lerpMap.set(key, 'linear');
  }

  // 返回 Map：保持时间升序的插入顺序（JS 普通对象会把整数样键 "0"/"3" 数值重排，
  // 破坏 Gson 解析后的 LinkedHashMap 区间顺序）
  const out = new Map();
  for (const key of order) out.set(key, { post: merged.get(key).post, lerp_mode: lerpMap.get(key) });
  return out;
}

// 说明：lerp_mode 在 Spark 中作用于"该关键帧 → 下一个关键帧"的段
// (OBoneAnimation.getPresentAnimValue 使用 kTarget.value.interpolation)。
// 因此锚点的 lerp_mode 取 bbmodel 中该关键帧的 interpolation（段属性）。

const modelBoneNames = new Set(bones.map((b) => b.name));
const animsOut = {};
const stats = [];

for (const a of j.animations) {
  let name = a.name;
  const dot = name.lastIndexOf('.');
  if (dot >= 0) name = name.slice(dot + 1); // "animation.model.still" -> "still"

  const loop = LOOP_MAP[a.loop];
  if (!loop) throw new Error('未知 loop 值: ' + a.loop);

  const bonesOut = {};
  for (const [uuid, animator] of Object.entries(a.animators)) {
    if (animator.type !== 'bone') continue; // camera 等
    const boneName = animator.name;
    if (!modelBoneNames.has(boneName)) {
      console.error('WARN: 动画骨骼不在模型中:', a.name, boneName);
      continue;
    }
    if (!animator.keyframes) continue;
    const byChannel = {};
    for (const kf of animator.keyframes) (byChannel[kf.channel] = byChannel[kf.channel] || []).push(kf);
    const boneAnim = {};
    for (const [ch, chKfs] of Object.entries(byChannel)) {
      const conv = convertChannel(chKfs, ch);
      if (conv.size) boneAnim[ch] = conv;
    }
    if (Object.keys(boneAnim).length) bonesOut[boneName] = boneAnim;
  }

  animsOut[name] = {
    animations: {
      [name]: {
        loop,
        animation_length: a.length,
        bones: bonesOut,
      },
    },
  };
  stats.push({ name, loop, length: a.length, bones: Object.keys(bonesOut).map((b) => b + '(' + Object.keys(bonesOut[b]).join('') + ')').join(' ') });
}

// ---------------------------------------------------------------- write ----
/**
 * 自定义序列化：Map 按插入顺序输出（关键帧时间轴必须时间升序），
 * 普通对象/数组按常规。缩进 2 空格，与项目现有 JSON 风格一致。
 */
function stringifyOrdered(value, indent, depth) {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  const pad = ' '.repeat(indent * depth);
  const padIn = ' '.repeat(indent * (depth + 1));
  const nl = depth === 0 ? '' : '\n';
  if (Array.isArray(value)) {
    if (!value.length) return '[]';
    return '[\n' + value.map((v) => padIn + stringifyOrdered(v, indent, depth + 1)).join(',\n') + '\n' + pad + ']';
  }
  if (value instanceof Map) {
    if (!value.size) return '{}';
    return '{\n' + [...value].map(([k, v]) => padIn + JSON.stringify(k) + ': ' + stringifyOrdered(v, indent, depth + 1)).join(',\n') + '\n' + pad + '}';
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value);
    if (!keys.length) return '{}';
    return '{\n' + keys.map((k) => padIn + JSON.stringify(k) + ': ' + stringifyOrdered(value[k], indent, depth + 1)).join(',\n') + '\n' + pad + '}';
  }
  throw new Error('unserializable value: ' + typeof value);
}

function writeJson(rel, obj) {
  const p = path.join(REPO, 'src/main/resources', rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, stringifyOrdered(obj, 2, 0) + '\n');
  console.log('  wrote', path.relative(REPO, p));
}

console.log('\n写模型:');
writeJson(`spark_models/item/${NS}/${MODEL_ID}.json`, model);
console.log('写动画:');
for (const [name, doc] of Object.entries(animsOut)) {
  writeJson(`spark_animations/item/${NS}/${MODEL_ID}/${name}.json`, doc);
}

console.log('\n动画摘要:');
for (const s of stats) console.log('  ' + s.name + ' [' + s.loop + ' ' + s.length + 's] ' + s.bones);
