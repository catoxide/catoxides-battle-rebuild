// bbmodel.js - Blockbench .bbmodel（v5 原生项目格式）↔ 数据模型 互操作
// 纯函数，node 可测。
//
// ==================== 坐标约定（2026-10 调研实锤，全部有源码/实据溯源） ====================
//
// 【帧关系】
//   v5 文件空间 ≡ Blockbench 内部空间 ≡ 本编辑器 three.js 渲染空间。
//   （.bbmodel 是 Blockbench 原生工程格式：bbmodel.js codec 的 parse/compile 对坐标零变换，
//    OutlinerElement.fromSave / Group.extend 直接合并文件值；
//    坐标变换只发生在导出 minecraft:geometry 时 —— bedrock.js：x 反号、旋转 x/y 反号。）
//   编辑器数据空间（spark，见 sparkCodec.js / zombie_3 实档）= v5 空间 X 轴镜像：
//     位置 (x,y,z) → (−x, y, z)        fl(v)
//     旋转 (rx,ry,rz) → (−rx, −ry, rz)  flR(r)
//   （= bedrock.js parseBone/compileGroup 的 `*[0]*=-1; *[1]*=-1` 规则，镜像为对合。）
//
// 【绝对坐标】v5 中 group.origin、element.from/to、element.origin（旋转锚点）、
//   locator.position 全部是模型根空间绝对值；three.js 层级用差值构建，但存储值是绝对值：
//     group.js:657-661  bone.position = group.origin − parent.origin（有父时）
//     outliner.js:298-319  element mesh.position = element.origin − parent.origin
//     cube.js:1196-1210   几何 = (from − origin) .. (to − origin)，mesh 在 origin（绝对锚点）
//   编辑器 spark 数据同为绝对（zombie_3 实档：腿 cube origin y=0=地面，非相对髋部 y=12）。
//   两种层级表示恒等（pivot 形式 T(o_i)R_i T(−o_i) 链 与 差值形式 T(o_i−o_{i−1})R_i 链，
//   靠 T(−o_i)·T(o_{i+1}) = T(o_{i+1}−o_i) 逐级 telescoping，已代数验证）→
//   导入/导出逐值直接映射，无需链式累加。
//
// 【UV】
//   - v5 face.uv = 4 值对角角点 [a,b,c,d]（Blockbench 内部表示，cube.js:11-26：
//     uv_size = [c−a, d−b]，可为负 = 翻转）；face.rotation = uv 旋转角。
//   - 编辑器 per-face {uv:[x,y], uv_size:[w,h]}，渲染期 up/down 面做 180° 角点交换
//     （bedrockUV.js:99-103；与 Blockbench updateUV 对 minecraft:geometry up/down
//     "origin 存对角"约定的归一化等价，cross-check 见 bedrockUV.js 头注释）。
//     映射（对合）：
//       north/south/east/west：uv=[a,b], uv_size=[c−a, d−b]
//       up/down              ：uv=[c,d], uv_size=[a−c, b−d]
//   - box_uv（simple）cube：权威值 = uv_offset + mirror；faces 4v 是 Blockbench
//     updateUV 的 face_list 副产物（标准布局表 + mirror 翻转 + 东西交换，
//     与 bedrockUV.faceRects 逐面对账一致）。导入时优先还原 simple（6 面匹配布局表），
//     否则 per-face。
//
// 【mirror】v5 中 cube 级 mirror_uv 是渲染权威（Group.update 不向子级传播 group 级标志；
//   Blockbench UI 切换组 mirror 时经 toggleElementProperty 物化到子 cube）。
//   编辑器无 cube 级 mirror（视口用 bone 级）：导入按 minecraft:geometry 物化规则
//   effMirror = (el.mirror_uv ?? group.mirror_uv)；effMirror ≠ bone.mirror 的 simple cube
//   退化为 per-face 精确表示。导出：group.mirror_uv = bone.mirror，simple cube 物化
//   el.mirror_uv = bone.mirror（与 Blockbench 物化态一致）。
//
// 【inflate】渲染期效果，文件坐标为未膨胀值；v5 组级 inflate 作为 cube 缺省
//   （bedrock.js parseCube：`s.inflate ?? bone_data?.inflate`）。
//
// 【其他字段】visibility/selected/color/scope/render_order/locked/isOpen/shade =
//   Blockbench 编辑器状态（游戏不读），导入忽略；export=false 的组子树跳过；
//   bedrock_binding 非空时保留到 bone（spark 导出透传，TACZ 绑定需要）。
// ============================================================================

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.BBModel）
(function (globalScope) {
    const isNode = typeof module === 'object' && module.exports;
    const Model = isNode ? require('./model') : globalScope.Model;
    const BedrockUV = isNode ? require('./bedrockUV') : globalScope.BedrockUV;

    const FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
    const EPS = 1e-4;

    // ---------- 小工具 ----------

    const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
    const sub3 = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
    const add3 = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
    const scale3 = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
    const flip = (v) => [-v[0], v[1], v[2]];                 // X 镜像（点，对合）
    const flipRot = (r) => [-r[0], -r[1], r[2]];             // X 镜像（欧拉角，对合）
    const num3 = (v, d) => (Array.isArray(v) && v.length === 3 && v.every(isNum)) ? v.slice() : d.slice();
    const close3 = (a, b) => Math.abs(a[0] - b[0]) < EPS && Math.abs(a[1] - b[1]) < EPS && Math.abs(a[2] - b[2]) < EPS;
    const round6 = (v) => Math.round(v * 1e6) / 1e6;

    let uuidCounter = 0;
    function genUuid() {
        if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
        uuidCounter++;
        const h = (n) => (uuidCounter * 2654435761 + n * 40503).toString(16).slice(-8);
        return `${h(1)}-${h(2)}-4${h(3).slice(1)}-a${h(4).slice(1)}-${h(5)}${h(6)}`;
    }

    function toObject(source, label) {
        if (typeof source === 'string') {
            try {
                return JSON.parse(String(source).replace(/^\uFEFF/, ''));
            } catch (e) {
                throw new Error(`${label} JSON 解析失败: ${e.message}`);
            }
        }
        if (!source || typeof source !== 'object') throw new Error(`${label} 不是 JSON 对象`);
        return source;
    }

    // ---------- UV ----------

    /**
     * simple uv [u0,v0] + size + mirror → 6 面 4 值角点（v5 faces.uv 写法，
     * 即 Blockbench updateUV face_list + uv_offset 的展开，含 mirror 翻转+东西交换）。
     */
    function boxFace4v(u0, v0, size, mirror) {
        const rects = BedrockUV.faceRects({ size, uv: [u0, v0], mirror: !!mirror });
        const out = {};
        for (const f of FACES) {
            const r = rects[f];
            out[f] = [round6(r[0]), round6(r[1]), round6(r[0] + r[2]), round6(r[1] + r[3])];
        }
        return out;
    }

    function same4v(a, b) {
        if (!a || !b) return false;
        for (let i = 0; i < 4; i++) if (Math.abs(a[i] - b[i]) > EPS) return false;
        return true;
    }

    /**
     * 从 v5 每面 4 值角点还原 simple uv：是否存在 (u0,v0,mirror) 使 6 面全部
     * 匹配 boxFace4v 布局表（含 mirror 变体）。
     * @returns {{u0:number, v0:number, mirror:boolean}|null}
     */
    function recoverSimpleUV(faces, size, preferMirror) {
        const sx = size[0], sy = size[1], sz = size[2];
        // 各面在布局表中的 (from_u, from_v) 相对 (u0,v0) 的偏移。
        // mirror 表 = 每面 from+=size、size*=−1 后东西交换（Blockbench updateUV mirror 分支），
        // 偏移随之变化，不能复用未 mirror 的偏移。
        const tableFromFalse = {
            east: [0, sz], north: [sz, sz], west: [sz + sx, sz],
            south: [2 * sz + sx, sz], up: [sz + sx, sz], down: [sz + 2 * sx, 0],
        };
        const tableFromTrue = {
            east: [2 * sz + sx, sz], north: [2 * sx, sz], west: [sz, sz],
            south: [2 * sz + 2 * sx, sz], up: [sz, sz], down: [sz + sx, 0],
        };
        const present = FACES.filter((f) => faces[f] && faces[f].texture !== null && faces[f].texture !== false);
        if (present.length !== 6) return null; // box_uv 要求 6 面齐全
        const raw = {};
        for (const f of FACES) {
            const v = faces[f].uv;
            if (!Array.isArray(v) || v.length < 4 || !v.every(isNum)) return null;
            raw[f] = [v[0], v[1]];
        }
        const mirrorOrder = [!!preferMirror, !preferMirror];
        for (const M of mirrorOrder) {
            const off = M ? tableFromTrue : tableFromFalse;
            for (const anchor of FACES) {
                const cand = { u0: raw[anchor][0] - off[anchor][0], v0: raw[anchor][1] - off[anchor][1] };
                const expect = boxFace4v(cand.u0, cand.v0, size, M);
                if (FACES.every((f) => same4v(faces[f].uv, expect[f]))) {
                    return { u0: round6(cand.u0), v0: round6(cand.v0), mirror: M };
                }
            }
        }
        return null;
    }

    /**
     * v5 faces（6 面对角 4 值角点）→ 编辑器 uv 表示。
     * @returns {{uv:(number[]|Object), bakedMirror:boolean}}
     *   uv = [u0,v0]（simple）或 per-face 映射；bakedMirror = simple 还原出的 cube 自身 mirror。
     */
    function facesToUv(el, size, groupMirror, warnings, where) {
        const faces = el.faces;
        if (faces && typeof faces === 'object') {
            const norm = {};
            let allOk = true;
            for (const f of FACES) {
                const face = faces[f];
                if (!face) { norm[f] = null; continue; }
                const v = face.uv;
                if (!Array.isArray(v) || v.length < 4 || !v.every(isNum)) {
                    warnings.push(`${where}: ${f} 面 uv 非法（需 4 值角点），按无 UV 处理`);
                    norm[f] = null;
                    continue;
                }
                norm[f] = {
                    uv: [v[0], v[1], v[2], v[3]],
                    rotation: isNum(face.rotation) ? face.rotation : 0,
                    present: face.texture !== null && face.texture !== false,
                };
            }
            const simple = recoverSimpleUV(
                FACES.reduce((acc, f) => { acc[f] = norm[f] ? { uv: norm[f].uv, texture: norm[f].present } : null; return acc; }, {}),
                size,
                el.mirror_uv !== undefined ? el.mirror_uv === true : groupMirror,
            );
            if (simple) {
                return { uv: [simple.u0, simple.v0], bakedMirror: simple.mirror };
            }
            // per-face：编辑器约定（ns/ew uv=[a,b]+size；up/down uv=[c,d]+size=[a−c,b−d]）
            const perFace = {};
            for (const f of FACES) {
                const n = norm[f];
                if (!n || !n.present) continue;
                const [a, b, c, d] = n.uv;
                if (f === 'up' || f === 'down') {
                    perFace[f] = { uv: [c, d], uv_size: [a - c, b - d] };
                } else {
                    perFace[f] = { uv: [a, b], uv_size: [c - a, d - b] };
                }
                if (n.rotation) perFace[f].uv_rotation = n.rotation;
            }
            if (!Object.keys(perFace).length) {
                warnings.push(`${where}: 所有面无 UV`);
            }
            return { uv: perFace, bakedMirror: groupMirror };
        }
        if (Array.isArray(el.uv_offset) && el.uv_offset.length >= 2 && el.uv_offset.slice(0, 2).every(isNum)) {
            return { uv: [el.uv_offset[0], el.uv_offset[1]], bakedMirror: el.mirror_uv === true };
        }
        warnings.push(`${where}: 无 faces 也无 uv_offset，cube 无 UV`);
        return { uv: {}, bakedMirror: groupMirror };
    }

    // ---------- 导入 ----------

    /**
     * 导入 .bbmodel（v5；兼容无 outliner 的扁平旧形态）→ 编辑器几何。
     * @param {string|Object} source
     * @param {Object} [opts] {name?: 覆盖模型名}
     * @returns {{geometry: Object, warnings: string[]}}
     */
    function importBBModel(source, opts) {
        const obj = toObject(source, 'bbmodel');
        const warnings = [];

        // v4.x 及更早：elements 叫 cubes（models[] 包装一并兼容）
        let model = obj;
        if (!model.elements && Array.isArray(model.cubes)) model = Object.assign({}, model, { elements: model.cubes });
        if (!model.elements && Array.isArray(model.models) && model.models.length) model = model.models[0];
        if (!model || !Array.isArray(model.elements)) throw new Error('bbmodel 缺 elements 数组');

        const meta = model.meta || {};
        const res = model.resolution || {};
        const geometry = {
            identifier: 'geometry.' + (model.model_identifier || model.name || (opts && opts.name) || 'model'),
            formatVersion: '1.12.0',
            textureWidth: isNum(res.width) ? res.width : 64,
            textureHeight: isNum(res.height) ? res.height : 64,
            visibleBounds: null,
            bones: [],
        };

        const elByUuid = {};
        for (const el of model.elements) if (el && el.uuid) elByUuid[el.uuid] = el;
        const grByUuid = {};
        const rawGroups = Array.isArray(model.groups) ? model.groups : [];
        for (const g of rawGroups) if (g && g.uuid) grByUuid[g.uuid] = g;
        // 无 groups 数组的内联组（v3 旧形态）：从 outliner 节点里捞
        if (!rawGroups.length) {
            (function scan(list) {
                for (const it of list || []) {
                    if (it && typeof it === 'object' && it.uuid && !grByUuid[it.uuid] && it.name != null) {
                        grByUuid[it.uuid] = it;
                    }
                    if (it && typeof it === 'object') scan(Array.isArray(it.children) ? it.children : (Array.isArray(it.content) ? it.content : null));
                }
            })(Array.isArray(model.outliner) ? model.outliner : null);
            for (const g of Object.values(grByUuid)) rawGroups.push(g);
        }

        const bones = geometry.bones;
        const boneByName = {};
        function addBone(name, parent, g) {
            const bone = {
                name,
                parent: parent || null,
                pivot: flip(num3(g ? g.origin : null, [0, 0, 0])),
                rotation: flipRot(num3(g ? g.rotation : null, [0, 0, 0])),
                scale: [1, 1, 1],
                mirror: g ? g.mirror_uv === true : false,
                cubes: [],
                locators: [],
            };
            if (g && g.bedrock_binding) bone.binding = g.bedrock_binding;
            bones.push(bone);
            boneByName[name] = bone;
            return bone;
        }

        function placeElement(el, bone, warnings2) {
            if (!el || !el.uuid) return;
            const where = `元素 "${el.name || el.uuid}"`;
            const type = el.type || 'cube';
            if (type === 'locator' || type === 'null_object') {
                bone.locators.push({
                    name: el.name || 'locator',
                    offset: flip(num3(el.position, [0, 0, 0])),
                    rotation: flipRot(num3(el.rotation, [0, 0, 0])),
                });
                return;
            }
            if (type !== 'cube' && type !== 'box') {
                warnings2.push(`${where}: 不支持的元素类型 "${type}"，已跳过`);
                return;
            }
            // from/to 归一（兼容 position/size 旧写法；负尺寸归一）
            let from = num3(el.from, [0, 0, 0]);
            let to;
            if (Array.isArray(el.to) && el.to.length === 3 && el.to.every(isNum)) to = el.to.slice();
            else if (Array.isArray(el.size) && el.size.length === 3 && el.size.every(isNum)) to = add3(from, el.size);
            else if (Array.isArray(el.position)) { from = el.position.slice(); to = null; }
            if (!to) {
                warnings2.push(`${where}: 缺 from/to 且无 size，已跳过`);
                return;
            }
            const size = sub3(to, from);
            for (let i = 0; i < 3; i++) {
                if (size[i] < 0) {
                    warnings2.push(`${where}: 负尺寸（from>to），已归一`);
                    [from[i], to[i]] = [to[i], from[i]];
                    size[i] = -size[i];
                }
            }
            if (size.every((v) => Math.abs(v) < EPS)) {
                warnings2.push(`${where}: 零尺寸，已跳过`);
                return;
            }
            const groupMirror = bone.mirror;
            const effMirror = (typeof el.mirror_uv === 'boolean') ? el.mirror_uv : groupMirror;
            const uvInfo = facesToUv(el, size, groupMirror, warnings2, where);
            const hasRot = Array.isArray(el.rotation) && el.rotation.some((v) => isNum(v) && Math.abs(v) > EPS);
            const cube = {
                origin: flip(from).map(round6),
                size: size.map(round6),
                uv: uvInfo.uv,
                inflate: isNum(el.inflate) ? el.inflate : (isNum(bone._groupInflate) ? bone._groupInflate : 0),
                pivot: hasRot ? flip(num3(el.origin, [0, 0, 0])).map(round6) : [0, 0, 0],
                rotation: hasRot ? flipRot(num3(el.rotation, [0, 0, 0])).map(round6) : [0, 0, 0],
            };
            // simple uv 且 cube 自身 mirror ≠ bone mirror → 编辑器无 cube 级 mirror，退化为 per-face 精确表示
            if (Array.isArray(cube.uv) && uvInfo.bakedMirror !== groupMirror) {
                warnings2.push(`${where}: cube mirror 与骨骼不一致，改用 per-face UV 表示`);
                const rects = BedrockUV.faceRects({ size, uv: cube.uv, mirror: effMirror });
                const perFace = {};
                for (const f of FACES) {
                    const r = rects[f];
                    // 与 facesToUv 同一映射：v5 4v=(a,b,c,d) → up/down {uv:[c,d], uv_size:[a−c,b−d]}
                    if (f === 'up' || f === 'down') perFace[f] = { uv: [r[0] + r[2], r[1] + r[3]], uv_size: [-r[2], -r[3]] };
                    else perFace[f] = { uv: [r[0], r[1]], uv_size: [r[2], r[3]] };
                }
                cube.uv = perFace;
            }
            bone.cubes.push(cube);
        }

        function processGroup(g, parentBone) {
            if (!g || !g.name) return;
            if (g.export === false) {
                warnings.push(`组 "${g.name}": export=false，子树跳过`);
                return;
            }
            const bone = addBone(g.name, parentBone ? parentBone.name : null, g);
            bone._groupInflate = isNum(g.inflate) ? g.inflate : 0;
            // 子级：优先 outliner 中该组的 children；无 outliner 时为空
            const children = groupChildren.get(g.uuid);
            for (const item of children || []) {
                if (typeof item === 'string') {
                    const el = elByUuid[item];
                    if (el) placeElement(el, bone, warnings);
                } else if (item && item.uuid) {
                    processGroup(grByUuid[item.uuid], bone);
                }
            }
        }

        // outliner 遍历（v5：组 → {uuid,isOpen,children} 对象；元素 → uuid 字符串）
        const groupChildren = new Map();
        if (Array.isArray(model.outliner)) {
            (function scan(list, parentUuid) {
                for (const item of list || []) {
                    if (typeof item === 'string') continue;
                    if (!item || !item.uuid) continue;
                    const kids = Array.isArray(item.children) ? item.children : (Array.isArray(item.content) ? item.content : []);
                    groupChildren.set(item.uuid, kids);
                    if (parentUuid) {
                        // 记录子组关系（processGroup 用 groupChildren 取子项）
                    }
                    scan(kids, item.uuid);
                }
            })(model.outliner, null);
            const usedGroups = new Set();
            const usedEls = new Set();
            (function mark(list) {
                for (const item of list || []) {
                    if (typeof item === 'string') { if (elByUuid[item]) usedEls.add(item); continue; }
                    if (item && item.uuid) {
                        if (grByUuid[item.uuid]) usedGroups.add(item.uuid);
                        mark(Array.isArray(item.children) ? item.children : (Array.isArray(item.content) ? item.content : null));
                    }
                }
            })(model.outliner);
            for (const item of model.outliner) {
                if (typeof item === 'string') {
                    const el = elByUuid[item];
                    if (!el) continue;
                    let rootBone = bones.find((b) => b.name === '__root');
                    if (!rootBone) rootBone = addBone('__root', null, null);
                    placeElement(el, rootBone, warnings);
                } else if (item && item.uuid) {
                    processGroup(grByUuid[item.uuid], null);
                }
            }
            // 未被 outliner 引用的组/元素 → 兜底挂根
            for (const g of rawGroups) {
                if (!g.uuid || usedGroups.has(g.uuid)) continue;
                warnings.push(`组 "${g.name || g.uuid}" 不在 outliner 中，已挂到根`);
                processGroup(g, null);
            }
            for (const el of model.elements) {
                if (!el || !el.uuid || usedEls.has(el.uuid)) continue;
                warnings.push(`元素 "${el.name || el.uuid}" 不在 outliner 中，已挂到根`);
                let rootBone = bones.find((b) => b.name === '__root');
                if (!rootBone) rootBone = addBone('__root', null, null);
                placeElement(el, rootBone, warnings);
            }
        } else {
            warnings.push('无 outliner 字段：组/元素均按顶层扁平处理（层级信息丢失）');
            for (const g of rawGroups) processGroup(g, null);
            for (const el of model.elements) {
                let rootBone = bones.find((b) => b.name === '__root');
                if (!rootBone) rootBone = addBone('__root', null, null);
                placeElement(el, rootBone, warnings);
            }
        }

        // 清理临时字段
        for (const b of bones) delete b._groupInflate;

        const { errors } = Model.validateGeometry(geometry);
        if (errors.length) throw new Error(`bbmodel 导入后结构非法: ${errors.join('; ')}`);
        return { geometry, warnings };
    }

    // ---------- 导出 ----------

    /**
     * 编辑器几何 → .bbmodel v5 文本（可直接在 Blockbench 5.x 打开）。
     * @param {Object} geometry 规范形几何
     * @param {Object} [opts] {name?: 覆盖文件模型名}
     * @returns {string}
     */
    function exportBBModel(geometry, opts) {
        const { errors } = Model.validateGeometry(geometry);
        if (errors.length) throw new Error(`无法导出 bbmodel（先修结构）: ${errors.join('; ')}`);
        const baseName = (opts && opts.name) || String(geometry.identifier || '').replace(/^geometry\./, '') || 'model';

        const boneMap = {};
        for (const b of geometry.bones) boneMap[b.name] = b;

        const elements = [];
        const groups = [];
        const uuidOf = {}; // boneName → group uuid

        // 先统一分配组 uuid（bones 数组为预序，父在子前；若边处理边分配，
        // 父骨引用尚未处理的子骨时会拿到 undefined）
        for (const b of geometry.bones) uuidOf[b.name] = genUuid();

        for (const b of geometry.bones) {
            const g = { name: b.name, uuid: uuidOf[b.name] };
            g.origin = flip(b.pivot).map(round6);
            if (!Model.vec3Equals(b.rotation, [0, 0, 0])) g.rotation = flipRot(b.rotation).map(round6);
            g.export = true;
            g.visibility = true;
            g.locked = false;
            g.isOpen = false;
            g.mirror_uv = b.mirror === true;
            if (b.binding) g.bedrock_binding = b.binding;
            groups.push(g);
            g._children = [];

            (b.cubes || []).forEach((c, i) => {
                const elUuid = genUuid();
                const from = flip(c.origin).map(round6);
                const el = {
                    name: `${b.name}_${i + 1}`,
                    type: 'cube',
                    from,
                    to: add3(from, c.size).map(round6),
                    uuid: elUuid,
                    export: true,
                    visibility: true,
                };
                if (!Model.vec3Equals(c.rotation, [0, 0, 0])) {
                    el.origin = flip(c.pivot).map(round6);
                    el.rotation = flipRot(c.rotation).map(round6);
                }
                if (c.inflate) el.inflate = c.inflate;
                if (b.mirror === true && Array.isArray(c.uv)) el.mirror_uv = true; // 物化（Blockbench 状态一致）
                const faces = {};
                if (Array.isArray(c.uv)) {
                    const uv4 = boxFace4v(c.uv[0], c.uv[1], c.size, b.mirror === true);
                    for (const f of FACES) faces[f] = { uv: uv4[f], texture: 0 };
                } else {
                    for (const f of FACES) {
                        const face = c.uv && c.uv[f];
                        if (!face) { faces[f] = { uv: [0, 0, 0, 0], texture: null }; continue; }
                        const x = face.uv[0], y = face.uv[1];
                        const w = face.uv_size ? face.uv_size[0] : 0;
                        const h = face.uv_size ? face.uv_size[1] : 0;
                        // up/down 编辑器角点 (x,y)=(c,d) → v5 角点顺序 (a,b,c,d)
                        const uv4 = (f === 'up' || f === 'down') ? [x + w, y + h, x, y] : [x, y, x + w, y + h];
                        const entry = { uv: uv4, texture: 0 };
                        if (face.uv_rotation) entry.rotation = face.uv_rotation;
                        faces[f] = entry;
                    }
                }
                el.faces = faces;
                elements.push(el);
                g._children.push(elUuid);
            });
            (b.locators || []).forEach((l) => {
                const elUuid = genUuid();
                const le = {
                    name: l.name,
                    type: 'locator',
                    position: flip(l.offset).map(round6),
                    export: true,
                    uuid: elUuid,
                };
                if (l.rotation && !Model.vec3Equals(l.rotation, [0, 0, 0])) le.rotation = flipRot(l.rotation).map(round6);
                elements.push(le);
                g._children.push(elUuid);
            });

            // 子骨（保持文件序）
            for (const c of geometry.bones) {
                if (c.parent === b.name) g._children.push(uuidOf[c.name]);
            }
        }

        // outliner 树（v5 语义：组 → {uuid,isOpen,children} 对象；元素 → uuid 字符串。
        // 子组若写成字符串引用，parse 时组会丢子级 —— 必须写成对象）
        const nameByGroupUuid = {};
        for (const g of groups) nameByGroupUuid[g.uuid] = g.name;
        const nodeCache = {};
        const buildNode = (boneName) => {
            if (nodeCache[boneName]) return nodeCache[boneName];
            const g = groups.find((x) => x.name === boneName);
            const node = { uuid: g.uuid, isOpen: false, children: g._children };
            nodeCache[boneName] = node;
            return node;
        };
        for (const g of groups) {
            const ch = g._children;
            for (let i = 0; i < ch.length; i++) {
                if (typeof ch[i] === 'string' && nameByGroupUuid[ch[i]]) {
                    ch[i] = buildNode(nameByGroupUuid[ch[i]]);
                }
            }
        }
        const outliner = [];
        for (const b of geometry.bones) {
            if (!b.parent || !boneMap[b.parent]) outliner.push(buildNode(b.name));
        }

        for (const g of groups) delete g._children;

        const allSimple = geometry.bones.every((b) => (b.cubes || []).every((c) => Array.isArray(c.uv)));
        const model = {
            name: baseName,
            model_identifier: baseName,
            meta: {
                format_version: '5.0',
                model_format: 'bedrock',
                box_uv: allSimple,
            },
            resolution: { width: geometry.textureWidth, height: geometry.textureHeight },
            elements,
            groups,
            outliner,
            textures: [{ name: baseName, width: geometry.textureWidth, height: geometry.textureHeight, uuid: genUuid() }],
        };
        return JSON.stringify(model, null, 2) + '\n';
    }

    // ---------- 嗅探 ----------

    /**
     * 嗅探模型文件类型。
     * @returns {'bbmodel'|'spark'|'unknown'}
     */
    function sniffModelFormat(text) {
        let obj;
        try {
            obj = JSON.parse(String(text).replace(/^\uFEFF/, ''));
        } catch (e) {
            return 'unknown';
        }
        if (!obj || typeof obj !== 'object') return 'unknown';
        if (obj['minecraft:geometry']) return 'spark';
        const meta = obj.meta || {};
        if (Array.isArray(obj.elements) || Array.isArray(obj.cubes) || Array.isArray(obj.models)
            || Array.isArray(obj.outliner) || meta.format_version || meta.model_format || meta.format) {
            return 'bbmodel';
        }
        return 'unknown';
    }

    const BBModelAPI = {
        importBBModel,
        exportBBModel,
        sniffModelFormat,
        // 测试/调试用内部导出
        _internals: { flip, flipRot, boxFace4v, recoverSimpleUV, facesToUv, genUuid },
    };
    if (isNode) module.exports = BBModelAPI;
    if (globalScope) globalScope.BBModel = BBModelAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
