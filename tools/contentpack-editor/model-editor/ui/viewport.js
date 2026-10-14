// viewport.js - three.js 视口（W2）：cube 渲染（UV/mirror/inflate）+ 轨道相机 + 选择
// ESM 模块（app.js 动态 import，与 src/views/entityList.js 加载 modelThumb 同模式）。
//
// 渲染约定（与 Blockbench 参照实现一致，左旋→右旋显示变换）：
//   - 全局 X 翻转：three_x = -bedrock_x（模型正面 bedrock +z 朝向默认相机）
//   - 骨骼层级（pivot 中心变换，T(p)·R·S·T(-p) 的翻转等价形式）：
//       boneOuter.position = (-p.x, p.y, p.z)
//       boneOuter.rotation = (-rx, -ry, rz)  （弧度；镜像反射共轭：x/y 分量取反）
//       boneOuter.scale    = (sx, sy, sz)    （保持正数，几何体已预翻转）
//       boneInner.position = (p.x, -p.y, -p.z)   [= -boneOuter.position]
//   - cube：几何体 = 声明尺寸 + inflate 展开 + X 翻转，中心在 cube pivot：
//       from3 = (cp.x - o.x - s.x, o.y - cp.y, o.z - cp.z)
//       to3   = (cp.x - o.x,       o.y + s.y - cp.y, o.z + s.z - cp.z)
//       mesh.position = (-cp.x, cp.y, cp.z)，mesh.rotation = (-rcx, -rcy, rcz)
//   - UV：core/bedrockUV.js（声明尺寸布局；inflate 只展几何不展 UV）
//   - cube 原点 = 骨骼父空间（现网已核：zombie_3 head pivot(0,24,0) + cube y24-32 = 模型总高 32）
import * as THREE from '../../src/lib/three.module.js';

const UV = self.BedrockUV; // core/bedrockUV.js（经典 script 挂 self，node 侧 module.exports）

const DEG = Math.PI / 180;

/** dataURL → THREE.Texture（像素画 NearestFilter；失败 resolve null） */
export function makeTextureFromDataUrl(dataUrl) {
    return new Promise((resolve) => {
        const img = new Image();
        img.onload = () => {
            const tex = new THREE.Texture(img);
            tex.needsUpdate = true;
            tex.magFilter = THREE.NearestFilter;
            tex.minFilter = THREE.NearestFilter;
            tex.generateMipmaps = false;
            tex.colorSpace = THREE.SRGBColorSpace;
            resolve(tex);
        };
        img.onerror = () => resolve(null);
        img.src = dataUrl;
    });
}

/** W5.1：像素编辑器 work canvas（1×1px）→ THREE 贴图（NearestFilter；每笔重建，旧贴图由 setTexture 释放） */
export function makeCanvasTexture(canvas) {
    const tex = new THREE.CanvasTexture(canvas);
    tex.magFilter = THREE.NearestFilter;
    tex.minFilter = THREE.NearestFilter;
    tex.generateMipmaps = false;
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.needsUpdate = true;
    return tex;
}

/** 骨骼/ cube 旋转（bedrock 角度制）→ three 欧拉（X 镜像共轭） */
function mirrorEuler(rad, rx, ry, rz) {
    return new THREE.Euler(-rad * rx, -rad * ry, rad * rz);
}

export function createViewport(container, opts = {}) {
    // ---------- 渲染器 / 场景 / 相机 ----------
    const renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(window.devicePixelRatio || 1);
    renderer.domElement.style.display = 'block';
    renderer.domElement.style.width = '100%';
    renderer.domElement.style.height = '100%';
    container.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x1e1e2e);

    const camera = new THREE.PerspectiveCamera(45, 1, 0.01, 5000);

    // 灯光（与主窗口缩略图同风格）
    scene.add(new THREE.AmbientLight(0xffffff, 0.55));
    const dir = new THREE.DirectionalLight(0xffffff, 0.9);
    dir.position.set(1, 1.2, 0.8);
    scene.add(dir);
    const dir2 = new THREE.DirectionalLight(0x89b4fa, 0.35);
    dir2.position.set(-0.8, 0.4, -0.6);
    scene.add(dir2);

    // 网格参考（关闭时）
    const grid = new THREE.GridHelper(64, 32, 0x44445a, 0x2a2a3d);
    grid.visible = true;
    scene.add(grid);

    // ---------- 模型根 ----------
    const root = new THREE.Group();
    scene.add(root);
    let model = null;            // 当前 Geometry（规范形）
    let texture = null;
    let modelGroup = null;
    const boneGroups = new Map(); // boneName → {outer, inner}
    const locatorGroups = new Map(); // 2026-10-14：locator 名 → {group, marker, markerMat, boneName, locator}（接口/虚拟骨）
    const cubeMeshes = [];

    // ---------- 轨道相机（W5.4 PS 式鼠标：空白处左拖=旋转视角 / 右键拖=平移 / 滚轮=缩放） ----------
    const orbit = { theta: 0.9, phi: 1.05, radius: 80, target: new THREE.Vector3() };
    let dragging = null; // {mode: 'rotate'|'pan', x, y}

    function updateCamera() {
        const { theta, phi, radius, target } = orbit;
        camera.position.set(
            target.x + radius * Math.sin(phi) * Math.sin(theta),
            target.y + radius * Math.cos(phi),
            target.z + radius * Math.sin(phi) * Math.cos(theta),
        );
        camera.lookAt(target);
    }

    const el = () => renderer.domElement;
    function onPointerDown(e) {
        // ① gizmo 手柄优先（左键命中箭头/环/方块 → 轴向精调，不转相机）
        if (opts.onGizmoPointerDown && opts.onGizmoPointerDown(e)) return;
        if (e.button === 0) {
            // ② W5.4：左拖 cube 本体 = 移动该 cube（gizmo 接管，未选中先选中）
            if (opts.onCubeDragStart && opts.onCubeDragStart(e, 'move')) return;
            // ③ 空白 = 旋转视角
            dragging = { mode: 'rotate', x: e.clientX, y: e.clientY, moved: false };
        } else if (e.button === 2 || e.button === 1) {
            // ④ W5.4：右拖 cube 本体 = 旋转该 cube；空白/中键 = 平移视角
            if (e.button === 2 && opts.onCubeDragStart && opts.onCubeDragStart(e, 'rotate')) return;
            dragging = { mode: 'pan', x: e.clientX, y: e.clientY };
        }
    }
    function onPointerMove(e) {
        if (!dragging) return;
        const dx = e.clientX - dragging.x, dy = e.clientY - dragging.y;
        dragging.x = e.clientX; dragging.y = e.clientY;
        dragging.moved = true;
        if (dragging.mode === 'rotate') {
            orbit.theta -= dx * 0.008;
            orbit.phi = Math.min(Math.PI - 0.05, Math.max(0.05, orbit.phi - dy * 0.008));
        } else {
            // 平移：沿相机右/上轴
            const dist = orbit.radius;
            const right = new THREE.Vector3().setFromMatrixColumn(camera.matrix, 0);
            const up = new THREE.Vector3().setFromMatrixColumn(camera.matrix, 1);
            orbit.target.addScaledVector(right, -dx * dist * 0.0015);
            orbit.target.addScaledVector(up, dy * dist * 0.0015);
        }
        updateCamera();
    }
    function onPointerUp(e) {
        const wasClick = dragging && dragging.mode === 'rotate' && !dragging.moved && e.button === 0;
        dragging = null;
        if (wasClick) pick(e);
    }
    function onWheel(e) {
        e.preventDefault();
        // W5.4：滚轮悬停 cube = 均匀缩放（绕 pivot）；Shift+滚轮 = 强制缩放视角（逃生门）
        if (!e.shiftKey && opts.onWheelScale && opts.onWheelScale(e)) return;
        orbit.radius = Math.min(2000, Math.max(2, orbit.radius * Math.exp(e.deltaY * 0.001)));
        updateCamera();
    }
    el().addEventListener('pointerdown', onPointerDown);
    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
    el().addEventListener('wheel', onWheel, { passive: false });
    el().addEventListener('contextmenu', (e) => e.preventDefault());

    // ---------- 选择 ----------
    let selection = null; // {boneName, cubeIndex}
    let selectionHelper = null;
    const raycaster = new THREE.Raycaster();
    const onSelect = opts.onSelect || null;

    function pick(e) {
        const rect = el().getBoundingClientRect();
        const ndc = new THREE.Vector2(
            ((e.clientX - rect.left) / rect.width) * 2 - 1,
            -((e.clientY - rect.top) / rect.height) * 2 + 1,
        );
        raycaster.setFromCamera(ndc, camera);
        const hits = raycaster.intersectObjects(cubeMeshes.filter((m) => m.visible), false); // W4.9：隐藏 cube 不可拾取
        setSelection(hits.length ? hits[0].object.userData : null);
    }

    function setSelection(sel, { silent = false } = {}) {
        selection = sel;
        if (selectionHelper) {
            modelGroup.remove(selectionHelper);
            selectionHelper.geometry.dispose();
            selectionHelper = null;
        }
        if (sel) {
            const mesh = cubeMeshes.find((m) => m.userData.boneName === sel.boneName && m.userData.cubeIndex === sel.cubeIndex);
            if (mesh) {
                selectionHelper = new THREE.LineSegments(
                    new THREE.EdgesGeometry(mesh.geometry),
                    new THREE.LineBasicMaterial({ color: 0xfacc15 }),
                );
                mesh.add(selectionHelper);
            }
        }
        if (!silent && onSelect) onSelect(sel);
    }

    // ---------- 模型构建 ----------
    let mainH = null; // 主模型层级句柄 { group, boneGroups, meshes, mat, dispose }
    let attachment = null; // W3.5 附加预览 { h, boneName }

    // ---------- W4.9 按 cube 临时隐藏（大纲 👁；会话级不落盘；buildModel 重建后自动恢复） ----------
    let hiddenCubes = null; // Set<"boneName#cubeIndex">；null/空 = 无隐藏
    function applyHidden() {
        for (const m of cubeMeshes) m.visible = !(hiddenCubes && hiddenCubes.has(m.userData.boneName + '#' + m.userData.cubeIndex));
    }
    function setHiddenCubes(keys) {
        hiddenCubes = (keys && keys.size) ? keys : null;
        applyHidden();
    }

    /** 共享材质（贴图可换；无贴图时灰色占位） */
    function makeMaterial(tex) {
        // W4.9：MC 式 cutout（alpha<0.5 丢弃）——头壳/空 UV 区域等透明贴图像素（0,0,0,0）
        // 此前被渲染成不透明黑色；现与游戏端 Spark 渲染器（cutout 层）一致
        return new THREE.MeshLambertMaterial({ map: tex || null, color: tex ? 0xffffff : 0x94a3b8, alphaTest: 0.5 });
    }

    /**
     * 构建 geo 的骨骼/cube 层级挂到 parent 下（X 翻转约定，见文件头）。
     * interactive=false（W3.5 附加预览）：不进 cubeMeshes（不可拾取/不参与选择）。
     * 返回 { group, boneGroups, meshes, mat, dispose, setTexture }。
     */
    function buildHierarchy(geo, parent, { interactive, tex }) {
        const group = new THREE.Group();
        parent.add(group);
        let mat = makeMaterial(tex);
        const bgs = new Map();
        const meshes = [];

        // 骨骼组（按文件序；parent 缺失/环上骨 → 挂根，与 core buildTree 容错一致）
        for (const b of geo.bones) {
            const p = b.pivot || [0, 0, 0];
            const outer = new THREE.Group();
            outer.position.set(-p[0], p[1], p[2]);
            const rot = b.rotation || [0, 0, 0];
            outer.rotation.copy(mirrorEuler(DEG, rot[0], rot[1], rot[2]));
            const sc = b.scale || [1, 1, 1];
            outer.scale.set(sc[0], sc[1], sc[2]);
            const inner = new THREE.Group();
            inner.position.set(p[0], -p[1], -p[2]);
            outer.add(inner);
            group.add(outer); // 先全部挂组根，下面按 parent 重挂
            bgs.set(b.name, { outer, inner, bone: b });
        }
        for (const [name, g] of bgs) {
            const pname = g.bone.parent;
            if (pname && bgs.has(pname)) bgs.get(pname).inner.add(g.outer);
        }

        // 2026-10-14：locator（接口/虚拟骨）= bone.inner 下的组。
        // 语义定案（现网 zombie 数据 + Java WeaponPhysicsItemAnimatable 验证）：
        //   offset = cube origin 同帧（所属骨 inner 帧；静止 = 根空间绝对位），
        //   游戏端经 getWorldBonePivot(offset) 随骨动画变换跟随（绕骨 pivot 转）。
        // 紫色八面体标记（不可拾取）；同名 locator 冲突 → 键加骨名前缀。
        const locs = new Map();
        const locMat = new THREE.MeshBasicMaterial({ color: 0xa78bfa, transparent: true, opacity: 0.9 });
        const locGeo = new THREE.OctahedronGeometry(1.4);
        for (const b of geo.bones) {
            const g = bgs.get(b.name);
            for (const loc of b.locators || []) {
                if (!loc || !loc.name) continue;
                const o = loc.offset || [0, 0, 0];
                const lr = loc.rotation || [0, 0, 0];
                const lg = new THREE.Group();
                lg.position.set(-o[0], o[1], o[2]);
                lg.rotation.copy(mirrorEuler(DEG, lr[0], lr[1], lr[2]));
                const marker = new THREE.Mesh(locGeo, locMat.clone());
                marker.raycast = () => {}; // 永不命中拾取
                lg.add(marker);
                g.inner.add(lg);
                let key = loc.name;
                if (locs.has(key)) key = b.name + '/' + loc.name;
                locs.set(key, { group: lg, marker, markerMat: marker.material, boneName: b.name, locator: loc });
            }
        }

        // cubes
        for (const b of geo.bones) {
            const g = bgs.get(b.name);
            const mirror = b.mirror === true;
            for (let ci = 0; ci < (b.cubes || []).length; ci++) {
                const c = b.cubes[ci];
                const s = c.size;
                if (!s[0] || !s[1] || !s[2]) continue; // 零尺寸 cube（占位）跳过
                // inflate：几何展开，UV 不动
                const inf = c.inflate || 0;
                const o = [c.origin[0] - inf, c.origin[1] - inf, c.origin[2] - inf];
                const si = [s[0] + 2 * inf, s[1] + 2 * inf, s[2] + 2 * inf];
                const cp = c.pivot || [0, 0, 0];
                const from3 = [cp[0] - o[0] - si[0], o[1] - cp[1], o[2] - cp[2]];
                const to3 = [cp[0] - o[0], o[1] + si[1] - cp[1], o[2] + si[2] - cp[2]];
                // 退化维 +0.001（Blockbench 同处理）
                for (let i = 0; i < 3; i++) if (to3[i] === from3[i]) to3[i] = from3[i] + 0.001;

                const g3 = new THREE.BufferGeometry();
                g3.setAttribute('position', new THREE.BufferAttribute(UV.cubeVertexPositions(...from3, ...to3), 3));
                // UV：用声明尺寸（inflate 不展 UV）；mirror = bone 级（现网无 cube 级）
                const uvCube = { size: s.slice(), uv: c.uv, mirror };
                g3.setAttribute('uv', new THREE.BufferAttribute(UV.cubeFaceUVs(uvCube, geo.textureWidth, geo.textureHeight), 2));
                g3.setIndex(UV.cubeIndices());
                g3.computeVertexNormals();

                const mesh = new THREE.Mesh(g3, mat);
                mesh.position.set(-cp[0], cp[1], cp[2]);
                const cr = c.rotation || [0, 0, 0];
                mesh.rotation.copy(mirrorEuler(DEG, cr[0], cr[1], cr[2]));
                mesh.userData = { boneName: b.name, cubeIndex: ci, attached: !interactive };
                g.inner.add(mesh);
                meshes.push(mesh);
            }
        }

        // 骨骼可视化（10/09：用户反馈"看不到骨骼"——骨骼本身不渲染，只画 cube；
        // 给每根骨骼的 pivot 画一个小球。不进 cubeMeshes → 不参与拾取/选择。
        // 不随贴图变色（固定色），选中骨骼时由选择高亮体现）。
        const boneMat = new THREE.MeshBasicMaterial({ color: 0x38bdf8, transparent: true, opacity: 0.85 });
        const boneSphereGeo = new THREE.SphereGeometry(1.1, 10, 8);
        for (const b of geo.bones) {
            const g = bgs.get(b.name);
            const dot = new THREE.Mesh(boneSphereGeo, boneMat);
            dot.position.set(0, 0, 0); // outer 原点 = 骨骼 pivot（外层 translate(pivot) 分解）
            dot.raycast = () => {}; // 永不命中拾取
            g.outer.add(dot);
        }

        function setTexture2(tex2) {
            const old = mat;
            mat = makeMaterial(tex2);
            for (const m of meshes) m.material = mat;
            if (old) old.dispose();
        }
        function dispose() {
            parent.remove(group);
            for (const m of meshes) m.geometry.dispose();
            group.traverse((o) => { if (o.geometry && o !== group) o.geometry.dispose(); });
            if (mat) mat.dispose();
            boneMat.dispose();
        }

        return { group, boneGroups: bgs, locatorGroups: locs, meshes, mat, setTexture: setTexture2, dispose };
    }

    /**
     * 2026-10-14 插入模型（接口吸附）：附加参数持久化（pendingAttach）—— 层级重建
     * （gizmo 拖拽 refresh / 进动画页 setModel）后自动重挂，不丢插入；公开 clearAttachment
     * 才真正移除（连参数一起清）。
     */
    let pendingAttach = null;
    function clearAttachment() {
        if (attachment) { attachment.h.dispose(); attachment = null; }
        pendingAttach = null;
    }

    function disposeMain() {
        if (attachment) { attachment.h.dispose(); attachment = null; } // pendingAttach 保留（buildModel 重挂）
        if (mainH) { mainH.dispose(); mainH = null; }
        if (selectionHelper) { selectionHelper = null; } // helper 随 mesh 一起被 traverse 释放
        modelGroup = null;
        boneGroups.clear();
        locatorGroups.clear();
        cubeMeshes.length = 0;
    }

    /**
     * 构建主模型层级（X 翻转约定，见文件头）。
     */
    function buildModel() {
        disposeMain();
        mainH = buildHierarchy(model, root, { interactive: true, tex: texture });
        modelGroup = mainH.group;
        for (const [k, v] of mainH.boneGroups) boneGroups.set(k, v);
        for (const [k, v] of mainH.locatorGroups) locatorGroups.set(k, v);
        for (const m of mainH.meshes) cubeMeshes.push(m);
        applyHidden(); // W4.9：重建（含 gizmo 拖拽 refresh）后恢复隐藏状态
        if (pendingAttach) reapplyAttach(pendingAttach); // 2026-10-14：重建后重挂插入/附加（不丢）
        // 重建保持选择（gizmo 拖拽中每帧重建，不能丢选择/回调闪烁）
        const prev = selection;
        selection = null;
        if (prev) {
            const still = cubeMeshes.find((m) => m.userData.boneName === prev.boneName && m.userData.cubeIndex === prev.cubeIndex);
            if (still) {
                selection = prev;
                selectionHelper = new THREE.LineSegments(
                    new THREE.EdgesGeometry(still.geometry),
                    new THREE.LineBasicMaterial({ color: 0xfacc15 }),
                );
                still.add(selectionHelper);
            }
        }
    }

    // ---------- W3.5 附加预览（联动模型挂骨骼/locator 联合预览）+ 2026-10-14 接口吸附 ----------
    /**
     * 附加一个模型到主模型的骨骼/locator（locatorName 优先；两者皆空 = 模型根空间）。
     * 挂 locator 组 / boneInner 下 → 自动跟随其变换（动画播放 pose 时同步跟随）。
     * offset = 附着点附加偏移（X 翻转约定）；插入吸附 = [0,0,0]。
     * 参数存 pendingAttach → 层级重建（gizmo 拖拽/进动画页）后自动重挂；真正移除 = clearAttachment。
     */
    function setAttachment({ geo, texture: attTex, boneName, locatorName, offset }) {
        clearAttachment();
        if (!modelGroup) return false;
        pendingAttach = {
            geo, texture: attTex,
            boneName: boneName || null, locatorName: locatorName || null,
            offset: (offset || [0, 0, 0]).slice(),
        };
        reapplyAttach(pendingAttach);
        return !!attachment;
    }
    function reapplyAttach(pa) {
        if (!modelGroup) return;
        let target = null;
        if (pa.locatorName) { const lg = locatorGroups.get(pa.locatorName); if (lg) target = lg.group; }
        if (!target) { const bg = pa.boneName ? boneGroups.get(pa.boneName) : null; target = (bg && bg.inner) || modelGroup; }
        const h = buildHierarchy(pa.geo, target, { interactive: false, tex: pa.texture });
        const o = pa.offset || [0, 0, 0];
        h.group.position.set(-o[0], o[1], o[2]); // X 翻转约定（bedrock→three）
        attachment = { h, boneName: pa.boneName, locatorName: pa.locatorName };
    }
    /** 2026-10-14：locator 标记高亮（name = null 全恢复） */
    function setLocatorHighlight(name) {
        for (const [k, v] of locatorGroups) {
            const sel = name != null && (k === name || k === v.boneName + '/' + name);
            v.marker.scale.setScalar(sel ? 1.9 : 1);
            v.markerMat.color.setHex(sel ? 0xfacc15 : 0xa78bfa);
        }
    }

    // ---------- 对外 API ----------
    function setModelGeometry(geo, opts = {}) {
        model = geo;
        buildModel();
        // W5.4 验证发现：undo/redo 走 adoptState → setModel，此前恒 fit() 重置相机，
        // 用户调好的视角在撤销时丢失。fit 只用于新模型加载（加载/新建传默认）。
        if (opts.fit !== false) fit();
    }

    /** W3：重建层级不重置相机（gizmo 拖拽中每帧调用） */
    function refresh() {
        if (!model) return;
        buildModel();
    }

    function setTexture(tex) {
        texture = tex || null;
        if (!modelGroup) return;
        const old = cubeMeshes.length ? cubeMeshes[0].material : null;
        const mat = makeMaterial(texture); // 10/09 修复：重构后 makeMaterial 需显式传贴图（漏传 = 永远灰色）
        for (const m of cubeMeshes) m.material = mat;
        // W5.1：像素编辑每笔换贴图 → 释放旧材质/贴图，防 GPU 内存累积
        if (old && old !== mat) {
            if (old.map && old.map !== texture) old.map.dispose();
            old.dispose();
        }
    }

    /** 对一组骨节点应用 pose（主模型/附加模型共用；bedrock 空间角度/值，X 镜像共轭） */
    function applyPoseTo(bgs, poseMap) {
        for (const [name, g] of bgs) {
            const b = g.bone;
            const p = b.pivot || [0, 0, 0];
            const pose = poseMap && poseMap.get(name);
            if (pose) {
                g.outer.position.set(
                    pose.position ? -pose.position[0] : -p[0],
                    pose.position ? pose.position[1] : p[1],
                    pose.position ? pose.position[2] : p[2],
                );
                const r = pose.rotation || [0, 0, 0];
                g.outer.rotation.copy(mirrorEuler(DEG, r[0], r[1], r[2]));
                const s = pose.scale || [1, 1, 1];
                g.outer.scale.set(s[0], s[1], s[2]);
            } else {
                const rot = b.rotation || [0, 0, 0];
                g.outer.rotation.copy(mirrorEuler(DEG, rot[0], rot[1], rot[2]));
            }
        }
    }
    /** W4 预留：应用 pose（主模型） */
    function setPose(poseMap) {
        if (!modelGroup) return;
        applyPoseTo(boneGroups, poseMap);
    }
    /** 2026-10-14：附加模型（插入）应用 pose（与主模型同一时刻 evaluate → 同名状态同步） */
    function setAttachmentPose(poseMap) {
        if (!attachment) return;
        applyPoseTo(attachment.h.boneGroups, poseMap);
    }

    /** 相机框住模型（几何包围盒） */
    function fit() {
        if (!modelGroup) return;
        const box = new THREE.Box3().setFromObject(modelGroup);
        if (box.isEmpty()) return;
        const center = box.getCenter(new THREE.Vector3());
        const size = box.getSize(new THREE.Vector3());
        const maxDim = Math.max(size.x, size.y, size.z) || 1;
        orbit.target.copy(center);
        orbit.radius = maxDim * 2.6;
        orbit.theta = 0.9;
        orbit.phi = 1.05;
        updateCamera();
        grid.position.y = box.min.y;
    }

    function resize() {
        const w = container.clientWidth || 1;
        const h = container.clientHeight || 1;
        renderer.setSize(w, h, false);
        camera.aspect = w / h;
        camera.updateProjectionMatrix();
        updateCamera();
    }

    // ---------- 渲染循环 ----------
    // 持续 rAF：每帧更新 matrixWorld（gizmo 射线拾取依赖）+ 绘制。
    // W4 预览播放直接复用（setPose 改节点后下一帧自动生效）。
    let rafId = 0;
    let lastW = 0, lastH = 0;
    function renderLoop() {
        rafId = requestAnimationFrame(renderLoop);
        // 10/09 双保险：Chromium 对后台/被遮挡窗口节流渲染管线——RO 回调与 rAF 都会暂停，
        // 窗口回到前台后 RO 未必补发。每帧比对容器尺寸（O(1)），变化即同步 buffer。
        const w = container.clientWidth, h = container.clientHeight;
        if ((w || 0) !== lastW || (h || 0) !== lastH) {
            lastW = w; lastH = h;
            resize();
        }
        renderer.render(scene, camera);
    }

    // 初始尺寸 + 持续跟踪容器尺寸
    // 10/09 修复（真实 exe 视口空白）：原实现只在创建时首次 rAF resize 一次；
    // 窗口恢复/布局变化后 canvas 渲染 buffer 滞留旧尺寸（如 375×187 vs CSS 756×722），
    // 模型被画进左上角一小块 → 视口看起来是空的。ResizeObserver 持续同步。
    updateCamera();
    requestAnimationFrame(() => resize());
    let ro = null;
    if (typeof ResizeObserver !== 'undefined') {
        ro = new ResizeObserver(() => resize());
        ro.observe(container);
    }
    const onWinResize = () => resize();
    window.addEventListener('resize', onWinResize);
    renderLoop();

    /** 命中 cube mesh（gizmo 自由移动用；返回 mesh 或 null） */
    function pickCubeAt(e) {
        const rect = el().getBoundingClientRect();
        const ndc = new THREE.Vector2(
            ((e.clientX - rect.left) / rect.width) * 2 - 1,
            -((e.clientY - rect.top) / rect.height) * 2 + 1,
        );
        raycaster.setFromCamera(ndc, camera);
        const hits = raycaster.intersectObjects(cubeMeshes.filter((m) => m.visible), false); // W4.9：隐藏 cube 不可拾取
        return hits.length ? hits[0].object : null;
    }

    return {
        renderer,
        scene,
        camera,
        container,
        canvas: el(),
        setModel: setModelGeometry,
        refresh,
        setTexture,
        setPose,
        fit,
        resize,
        setSelection,
        getSelection: () => selection,
        pickCubeAt,
        /** W3 gizmo 用：骨骼组（Map name→{outer,inner,bone}）+ 模型根组 + 当前几何 + cube mesh 列表 */
        boneGroups,
        cubeMeshes,
        modelGroupRef: () => modelGroup,
        geometry: () => model,
        /** W3.5 附加预览：挂仓库模型到骨骼/locator/根（session 级）；clearAttachment 移除 */
        setAttachment,
        clearAttachment,
        setAttachmentPose,
        setLocatorHighlight,
        locatorGroups,
        /** W4.9：按 cube 临时隐藏（Set<"boneName#cubeIndex">）；传空 Set/null = 全部显示 */
        setHiddenCubes,
        dispose() {
            el().removeEventListener('pointerdown', onPointerDown);
            window.removeEventListener('pointermove', onPointerMove);
            window.removeEventListener('pointerup', onPointerUp);
            window.removeEventListener('resize', onWinResize);
            el().removeEventListener('wheel', onWheel);
            if (ro) { ro.disconnect(); ro = null; }
            cancelAnimationFrame(rafId);
            disposeMain();
            renderer.dispose();
            if (el().parentNode) el().parentNode.removeChild(el());
        },
    };
}
