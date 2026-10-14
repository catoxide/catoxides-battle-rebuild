// gizmo.js - 手写变换 gizmo（W3；W5.4 无模式化）：
//   选中对象上常驻全部手柄（3 轴箭头=移动 / 3 环=旋转 / 方块=均匀缩放），
//   手柄拖拽 = 轴向精调；对象本体拖拽 = PS 式手势（viewport 路由）：
//   左拖=自由移动 / 右拖=屏幕面旋转（自动选最正对相机的局部轴，15° 吸附）/
//   滚轮=均匀缩放（绕 pivot，400ms 合并一次 undo）。
// ESM。不依赖 examples/jsm（vendored three 无 TransformControls），全部数学手写。
//
// 数据语义（真实文件核实 + Blockbench 源码对照，见 MODEL_EDITOR_REWRITE_PLAN §3.1 + W3 推导）：
//   - W_B（骨骼 B 内容帧的世界变换）= W_parent(B) · M_B，M_B = T(p)·R·S·T(−p)（绕 pivot 的旋转/缩放）
//   - 骨骼的 pivot 在【父内容帧】坐标（bedrock）；cube 的 origin/pivot 在【所属骨内容帧】坐标
//   - 骨骼"移动" = 整棵子树刚性平移（BB changeNodeLocalPosition 同规则：pivots 与子级坐标同加 δ）：
//       δ = F · (W_parent(D)⁻¹ · Δ_world)   （D 父内容帧的 bedrock 位移，一次求解）
//     子树内每根骨：pivot += δ；每 cube：origin += δ 且 pivot += δ；每 locator：offset += δ
//     （恒等式 M(p+δ)·(u+δ) = M(p)·u + δ ⇒ 任意旋转链下精确刚性）
//   - cube"移动" = 只动该 cube（BB：cube 的 from/to/origin 同加，file pivot 随 cube 走）：
//       δ = F · (W_B⁻¹ · Δ_world)   （【B 自身内容帧】—— 含 M_B，旋转骨上不可用父帧近似）
//     cube.origin += δ 且 cube.pivot += δ
//   - 旋转 = euler 分量【从起点值重算】（环/箭头按对象局部轴朝向）
//   - 缩放 = 从起点值 × f（bone.scale 全分量；cube.size 绕 cube pivot 保持中心）
//   - F = diag(−1,1,1)：bedrock↔three 显示翻转（X 镜像），F²=I
//
// 吸附：移动 = 1px 网格（轴拖）/ 1px（自由拖）；旋转 = 15°。Alt 按住临时关闭。
import * as THREE from '../../src/lib/three.module.js';

const AXIS_COLORS = [0xef4444, 0x22c55e, 0x3b82f6]; // X 红 / Y 绿 / Z 蓝
const F = (v) => new THREE.Vector3(-v.x, v.y, v.z);
const round4 = (v) => Math.round(v * 10000) / 10000;

/**
 * 射线（o + s·d，d 单位长）与轴直线（P + t·n，n 单位长）的交点参数 t（异面直线最小二乘解）：
 *   t = (n·(o−P) − (d·n)·(d·(o−P))) / (1 − (d·n)²)
 * 推导：求 s,t 使 ‖o + s·d − (P + t·n)‖² 最小（对 s,t 求导 = 0 的线性方程组）。
 * 射线过 P + t₀n 时精确返回 t₀；轴正对相机（d∥n）时分母 → 0，返回 0（该视角拖拽无定义）。
 *
 * ⚠ 回归点：W3 初版实现把 s 解成 (P−o)·n/(d·n) 后取 (Q−P)·n —— 该式恒等于 0（构造性恒等），
 * 导致轴拖拽 dT 恒为 0、模型纹丝不动（2026-10-09 headless 验收实测抓到）。
 * @returns {number}
 */
export function rayAxisParam(o, d, P, n) {
    const a = d.dot(n);
    const denom = 1 - a * a;
    if (denom < 1e-6) return 0;
    const op = o.clone().sub(P);
    return (op.dot(n) - a * d.dot(op)) / denom;
}

export function createGizmo(vp, api) {
    // api: { getSelection(), getGeometry(), onChange(label), selectCube?(boneName, cubeIndex),
    //        // W7 动画模式（auto-key 姿态编辑）：
    //        animPoseMode?() -> boolean（动画模式：几何=虚拟姿态，禁 cube 编辑，拖拽不重建模型）
    //        onGestureStart?(info, h)（手势开始：auto-key 建帧）
    //        onAnimPoseChanged?(info, h)（动画模式拖拽中：写关键帧 + 刷新姿态显示）
    //        onGestureEnd?(drag, changed)（手势结束：动画模式提交 undo） }
    let gizmoGroup = null;
    let handleMeshes = [];
    let drag = null;
    let startVals = null; // 拖拽起点值（累计式重算，避免漂移）
    let wheelCommitTimer = 0; // W5.4 滚轮缩放的 undo 合并（400ms 静默 = 一次提交）

    // ---------- 选择信息 ----------
    function selInfo() {
        const sel = api.getSelection();
        const geo = api.getGeometry();
        if (!sel || !geo) return null;
        if (sel.type === 'bone') {
            const g = vp.boneGroups.get(sel.boneName);
            const bone = geo.bones.find((b) => b.name === sel.boneName);
            if (!g || !bone) return null;
            return { kind: 'bone', sel, bone, anchor: g.outer.getWorldPosition(new THREE.Vector3()) };
        }
        const bone = geo.bones.find((b) => b.name === sel.boneName);
        const cube = bone && bone.cubes[sel.cubeIndex];
        const mesh = vp.cubeMeshes.find((m) => m.userData.boneName === sel.boneName && m.userData.cubeIndex === sel.cubeIndex);
        if (!bone || !cube || !mesh) return null;
        return { kind: 'cube', sel, bone, cube, mesh, anchor: mesh.getWorldPosition(new THREE.Vector3()) };
    }

    /** 骨骼 B 的父内容帧（three 组）；根骨 = 模型根组 */
    function parentFrameOf(boneName) {
        const geo = api.getGeometry();
        const bone = geo.bones.find((b) => b.name === boneName);
        if (!bone || bone.parent == null || !vp.boneGroups.has(bone.parent)) return vp.modelGroupRef();
        return vp.boneGroups.get(bone.parent).inner;
    }

    /**
     * 世界位移 Δ_w（three）→ 指定"帧"的 bedrock 位移（纯数组 [x,y,z]）：δ = F · (frameWorld⁻¹ · Δ_w)。
     * 注意：返回数组而非 Vector3（调用方按 δ[i] 索引）。
     */
    function worldDeltaToFrameBedrock(frameGroup, deltaWorld) {
        const lin = new THREE.Matrix3().setFromMatrix4(frameGroup.matrixWorld).invert();
        const v = deltaWorld.clone().applyMatrix3(lin);
        return [-v.x, v.y, v.z];
    }
    /** 骨骼移动：pivot 在【父内容帧】求解（pivot 世界 = W_parent · F·p）。 */
    const deltaForPivot = (boneName, dw) => worldDeltaToFrameBedrock(parentFrameOf(boneName), dw);
    /** cube 移动：坐标在【所属骨自身内容帧 W_B】求解（含 M_B；旋转骨上不可用父帧近似）。 */
    const deltaForBoneContent = (boneName, dw) => worldDeltaToFrameBedrock(vp.boneGroups.get(boneName).inner, dw);

    /** 子树骨骼名（含自身，DFS 保序） */
    function subtreeOf(geo, name) {
        const { byName } = self.Model.buildTree(geo);
        const out = [];
        const visit = (n) => { if (!n) return; out.push(n.name); for (const c of n.children) visit(c); };
        visit(byName.get(name));
        return out;
    }

    function localAxisWorld(info, axis) {
        // 对象局部 bedrock 轴 i 的世界方向（箭头/环朝向）
        const e = new THREE.Vector3(axis === 0 ? 1 : 0, axis === 1 ? 1 : 0, axis === 2 ? 1 : 0);
        const q = new THREE.Quaternion();
        if (info.kind === 'bone') vp.boneGroups.get(info.bone.name).inner.getWorldQuaternion(q);
        else info.mesh.getWorldQuaternion(q);
        return F(e).applyQuaternion(q).normalize();
    }

    // ---------- 手柄构建 ----------
    function handleMaterial(color) {
        return new THREE.MeshBasicMaterial({ color, depthTest: false, side: THREE.DoubleSide, transparent: true, opacity: 0.92 });
    }

    /** W5.4：选中对象常驻全部手柄（箭头=轴移动 / 环=轴旋转 / 方块=均匀缩放） */
    function rebuild() {
        if (gizmoGroup) {
            vp.scene.remove(gizmoGroup);
            gizmoGroup.traverse((o) => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
            gizmoGroup = null;
        }
        handleMeshes = [];
        let info = selInfo();
        // W7 动画分页：cube 结构编辑禁用 → 选中 cube 时不建手柄（骨骼手柄 = auto-key）
        if (info && info.kind === 'cube' && api.animPoseMode && api.animPoseMode()) info = null;
        if (!info) return;

        gizmoGroup = new THREE.Group();
        gizmoGroup.renderOrder = 999;
        vp.scene.add(gizmoGroup);
        const P = info.anchor;

        // 3 轴箭头（移动精调）
        for (let i = 0; i < 3; i++) {
            const d = localAxisWorld(info, i);
            const g = new THREE.Group();
            g.position.copy(P);
            g.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), d);
            const shaft = new THREE.Mesh(new THREE.CylinderGeometry(0.35, 0.35, 8, 8), handleMaterial(AXIS_COLORS[i]));
            shaft.position.y = 4;
            const head = new THREE.Mesh(new THREE.ConeGeometry(1.2, 2.6, 12), handleMaterial(AXIS_COLORS[i]));
            head.position.y = 9.3;
            shaft.userData = { gizmo: 'move', axis: i };
            head.userData = { gizmo: 'move', axis: i };
            g.add(shaft, head);
            gizmoGroup.add(g);
            handleMeshes.push(shaft, head);
        }
        // 3 环（旋转精调，15° 吸附）
        for (let i = 0; i < 3; i++) {
            const n = localAxisWorld(info, i);
            const ring = new THREE.Mesh(new THREE.RingGeometry(7, 8.6, 40), handleMaterial(AXIS_COLORS[i]));
            ring.position.copy(P);
            ring.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), n);
            ring.userData = { gizmo: 'rotate', axis: i };
            gizmoGroup.add(ring);
            handleMeshes.push(ring);
        }
        // 缩放方块（对角方向）
        const diag = new THREE.Vector3(0, 0, 0);
        for (let i = 0; i < 3; i++) diag.add(localAxisWorld(info, i));
        diag.normalize().multiplyScalar(9);
        const box = new THREE.Mesh(new THREE.BoxGeometry(2.6, 2.6, 2.6), handleMaterial(0xfacc15));
        box.position.copy(P).add(diag);
        box.userData = { gizmo: 'scale' };
        gizmoGroup.add(box);
        handleMeshes.push(box);
    }

    // ---------- 拖拽 ----------
    function captureStart(info) {
        const geo = api.getGeometry();
        const s = {
            rotation: info.bone.rotation.slice(),
            scale: info.bone.scale.slice(),
            cube: info.kind === 'cube'
                ? { origin: info.cube.origin.slice(), size: info.cube.size.slice(), pivot: info.cube.pivot.slice() }
                : null,
            cubeRotation: info.kind === 'cube' ? info.cube.rotation.slice() : null,
            // 子树所有骨骼的起点（骨骼移动时逐骨累计重算）：
            // pivot 数组 + 每个 cube 的 [origin, pivot] + 每个 locator 的 offset
            subtree: {},
        };
        for (const n of subtreeOf(geo, info.bone.name)) {
            const b = geo.bones.find((x) => x.name === n);
            s.subtree[n] = {
                pivot: b.pivot.slice(),
                cubes: (b.cubes || []).map((c) => [c.origin.slice(), c.pivot.slice()]),
                locators: (b.locators || []).map((l) => l.offset.slice()),
            };
        }
        return s;
    }

    function rayNdc(e) {
        const rect = vp.canvas.getBoundingClientRect();
        return new THREE.Vector2(
            ((e.clientX - rect.left) / rect.width) * 2 - 1,
            -((e.clientY - rect.top) / rect.height) * 2 + 1,
        );
    }

    function axisParam(e, P, n) {
        const rc = new THREE.Raycaster();
        rc.setFromCamera(rayNdc(e), vp.camera);
        return rayAxisParam(rc.ray.origin, rc.ray.direction, P, n);
    }

    function planePoint(e, P, n) {
        // 指针射线与平面（点 P 法线 n）交点
        const rc = new THREE.Raycaster();
        rc.setFromCamera(rayNdc(e), vp.camera);
        const denom = rc.ray.direction.dot(n);
        if (Math.abs(denom) < 1e-6) return P.clone();
        const s = P.clone().sub(rc.ray.origin).dot(n) / denom;
        return rc.ray.origin.clone().addScaledVector(rc.ray.direction, s);
    }

    function angleOnPlane(e, P, n) {
        const Q = planePoint(e, P, n).sub(P);
        const up = Math.abs(n.y) < 0.99 ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(1, 0, 0);
        const u = new THREE.Vector3().crossVectors(n, up).normalize();
        const v = new THREE.Vector3().crossVectors(n, u);
        return Math.atan2(Q.dot(v), Q.dot(u));
    }

    function screenDist(e, P) {
        const rect = vp.canvas.getBoundingClientRect();
        const p = P.clone().project(vp.camera);
        const px = (p.x * 0.5 + 0.5) * rect.width + rect.left;
        const py = (-p.y * 0.5 + 0.5) * rect.height + rect.top;
        return Math.hypot(e.clientX - px, e.clientY - py);
    }

    // 应用（全部从 startVals 累计重算，避免漂移）
    //
    // 骨骼移动语义（与 Blockbench changeNodeLocalPosition 一致，任意旋转链下精确刚性）：
    //   δ = F · W_parent(D)⁻¹ · Δ_world（D 父帧的 bedrock 位移，一次求解）
    //   子树内每根骨：pivot += δ；每 cube：origin += δ 且 pivot += δ；每 locator：offset += δ
    //   （恒等式 M(p+δ)·(u+δ) = M(p)·u + δ ⇒ 统一 δ 即得刚性平移）
    // cube 移动（BB：from/to/origin 同加，file pivot 随 cube 刚性平移）：
    //   δ = F · W_B⁻¹ · Δ_world（B 自身内容帧，含 M_B）；cube.origin += δ 且 cube.pivot += δ
    function applyMoveAxis(info, axis, t) {
        const d = localAxisWorld(info, axis).multiplyScalar(t); // 世界位移（three）
        if (info.kind === 'bone') {
            const delta = deltaForPivot(info.bone.name, d);
            const geo = api.getGeometry();
            for (const n of subtreeOf(geo, info.bone.name)) {
                const b = geo.bones.find((x) => x.name === n);
                const st = startVals.subtree[n];
                for (let i = 0; i < 3; i++) b.pivot[i] = round4(st.pivot[i] + delta[i]);
                for (let ci = 0; ci < b.cubes.length; ci++) {
                    for (let i = 0; i < 3; i++) {
                        b.cubes[ci].origin[i] = round4(st.cubes[ci][0][i] + delta[i]);
                        b.cubes[ci].pivot[i] = round4(st.cubes[ci][1][i] + delta[i]);
                    }
                }
                for (let li = 0; li < (b.locators || []).length; li++) {
                    for (let i = 0; i < 3; i++) b.locators[li].offset[i] = round4(st.locators[li][i] + delta[i]);
                }
            }
        } else {
            const delta = deltaForBoneContent(info.bone.name, d);
            const st = startVals.cube;
            for (let i = 0; i < 3; i++) {
                info.cube.origin[i] = round4(st.origin[i] + delta[i]);
                info.cube.pivot[i] = round4(st.pivot[i] + delta[i]);
            }
        }
    }

    function applyMoveFree(info, deltaWorld) {
        const delta = deltaForBoneContent(info.bone.name, deltaWorld);
        const st = startVals.cube;
        for (let i = 0; i < 3; i++) {
            info.cube.origin[i] = round4(st.origin[i] + delta[i]);
            info.cube.pivot[i] = round4(st.pivot[i] + delta[i]);
        }
    }

    function applyRotate(info, axis, dDegTotal) {
        if (info.kind === 'bone') info.bone.rotation[axis] = round4(startVals.rotation[axis] + dDegTotal);
        else info.cube.rotation[axis] = round4(startVals.cubeRotation[axis] + dDegTotal);
    }

    function applyScale(info, fTotal) {
        if (info.kind === 'bone') {
            for (let i = 0; i < 3; i++) info.bone.scale[i] = round4(startVals.scale[i] * fTotal);
        } else {
            const cp = info.cube.pivot;
            for (let i = 0; i < 3; i++) {
                info.cube.size[i] = Math.max(0.001, round4(startVals.cube.size[i] * fTotal));
                info.cube.origin[i] = round4(cp[i] - fTotal * (cp[i] - startVals.cube.origin[i]));
            }
        }
    }

    // ---------- 事件 ----------
    /** 左键 pointerdown：只接管【手柄】（对象本体拖拽由 viewport 走 startCubeGesture） */
    function onPointerDown(e) {
        if (e.button !== 0) return false;
        const info = selInfo();
        if (!info) return false;
        const ndc = rayNdc(e);
        const rc = new THREE.Raycaster();
        rc.setFromCamera(ndc, vp.camera);
        const hits = rc.intersectObjects(handleMeshes, false);
        if (hits.length) {
            beginDrag(e, info, hits[0].object.userData);
            return true;
        }
        return false;
    }

    /** W5.4 PS 式对象手势（viewport 路由调用）：
     *  kind='move'   左拖 cube 本体 = 自由面移动（1px 网格，Alt 关）
     *  kind='rotate' 右拖 cube 本体 = 屏幕面旋转（最正对相机的局部轴，15° 吸附，Alt 关）
     *  命中即操作：未选中的 cube 先自动选中（PS 式"作用于光标下对象"）。
     *  返回 true = 已接管（viewport 不再启动相机手势） */
    function startCubeGesture(e, kind) {
        if (api.animPoseMode && api.animPoseMode()) return false; // W7 动画模式：禁 cube 编辑
        if (e.button !== 0 && e.button !== 2) return false;
        const mesh = vp.pickCubeAt(e);
        if (!mesh) return false;
        const boneName = mesh.userData.boneName, cubeIndex = mesh.userData.cubeIndex;
        const cur = api.getSelection();
        const isSel = !!(cur && cur.type === 'cube' && cur.boneName === boneName && cur.cubeIndex === cubeIndex);
        if (!isSel && typeof api.selectCube === 'function') api.selectCube(boneName, cubeIndex);
        const info = selInfo();
        if (!info || info.kind !== 'cube') return false;
        beginDrag(e, info, kind === 'rotate' ? { gizmo: 'rotateFree' } : { gizmo: 'move', axis: -1 });
        return true;
    }

    /** W5.4：滚轮悬停 cube = 均匀缩放（绕 pivot；400ms 静默合并一次 undo） */
    function wheelScaleAt(e) {
        if (api.animPoseMode && api.animPoseMode()) return false; // W7 动画模式：禁 cube 编辑
        const mesh = vp.pickCubeAt(e);
        if (!mesh) return false;
        const boneName = mesh.userData.boneName, cubeIndex = mesh.userData.cubeIndex;
        const cur = api.getSelection();
        const isSel = !!(cur && cur.type === 'cube' && cur.boneName === boneName && cur.cubeIndex === cubeIndex);
        if (!isSel && typeof api.selectCube === 'function') api.selectCube(boneName, cubeIndex);
        const info = selInfo();
        if (!info) return false;
        const f = Math.exp(-(e.deltaY || 0) * 0.0015);
        if (info.kind === 'bone') {
            for (let i = 0; i < 3; i++) info.bone.scale[i] = round4(Math.min(50, Math.max(0.02, info.bone.scale[i] * f)));
        } else {
            const cp = info.cube.pivot;
            for (let i = 0; i < 3; i++) {
                const ns = Math.min(1000, Math.max(0.001, info.cube.size[i] * f));
                const k = ns / info.cube.size[i];
                info.cube.origin[i] = round4(cp[i] - k * (cp[i] - info.cube.origin[i]));
                info.cube.size[i] = round4(ns);
            }
        }
        vp.refresh();
        rebuild();
        // undo 合并：连续滚轮 = 一次"滚轮缩放"
        if (wheelCommitTimer) clearTimeout(wheelCommitTimer);
        wheelCommitTimer = setTimeout(() => {
            wheelCommitTimer = 0;
            const s = info.sel;
            const what = s.type === 'bone' ? `骨骼 ${s.boneName}` : `cube[${s.cubeIndex}] @ ${s.boneName}`;
            api.onChange('滚轮缩放 ' + what);
        }, 400);
        return true;
    }

    /** W5.4 右拖自由旋转的轴选择：最正对相机的局部轴（|轴·视线| 最大）——
     *  绕它转时对象在屏幕上的表现 = 拖拽的圆平面自转，与鼠标圆周运动一致 */
    function pickRotateAxis(info) {
        const camDir = new THREE.Vector3();
        vp.camera.getWorldDirection(camDir);
        let best = 0, bestDot = -1;
        for (let i = 0; i < 3; i++) {
            const d = Math.abs(localAxisWorld(info, i).dot(camDir));
            if (d > bestDot) { bestDot = d; best = i; }
        }
        return best;
    }

    function beginDrag(e, info, h) {
        startVals = captureStart(info);
        const P = info.anchor.clone();
        const camDir = new THREE.Vector3();
        vp.camera.getWorldDirection(camDir);
        const base = { info, h, anchor0: P, changed: false };
        if (h.gizmo === 'move' && h.axis >= 0) {
            base.n = localAxisWorld(info, h.axis);
            base.t0 = axisParam(e, P, base.n);
        } else if (h.gizmo === 'rotate') {
            base.n = localAxisWorld(info, h.axis);
            base.angle0 = angleOnPlane(e, P, base.n);
        } else if (h.gizmo === 'scale') {
            base.dist0 = Math.max(1, screenDist(e, P));
        } else if (h.gizmo === 'rotateFree') {
            // W5.4 右拖：屏幕面（法线 = 视线）旋转，轴 = 最正对相机的局部轴
            base.freeAxis = pickRotateAxis(info);
            base.angle0 = angleOnPlane(e, P, camDir);
        } else if (h.gizmo === 'move') {
            // 自由移动：相机面
            base.camDir = camDir;
            base.p0 = planePoint(e, P, camDir);
        }
        drag = base;
        try { if (api.onGestureStart) api.onGestureStart(info, h); } catch (e) { console.warn('[gizmo] onGestureStart', e); }
        window.addEventListener('pointermove', onMove);
        window.addEventListener('pointerup', onUp, { once: true });
    }

    function onMove(e) {
        if (!drag) return;
        const { info, h } = drag;
        const snap = !e.altKey;
        let changed = false;
        if (h.gizmo === 'move' && h.axis >= 0) {
            const t = axisParam(e, drag.anchor0, drag.n);
            let dT = t - drag.t0;
            if (snap) dT = Math.round(dT);
            if (dT !== 0) { applyMoveAxis(info, h.axis, dT); changed = true; }
        } else if (h.gizmo === 'move' && h.axis === -1) {
            const Q = planePoint(e, drag.anchor0, drag.camDir);
            const dw = Q.sub(drag.p0);
            if (dw.lengthSq() > 1e-8) {
                if (snap) {
                    // 自由拖吸附：世界 delta → 骨自身内容帧取整 → 转回世界
                    const d = deltaForBoneContent(info.bone.name, dw);
                    const dd = new THREE.Vector3(Math.round(d[0]), Math.round(d[1]), Math.round(d[2]));
                    const lin = new THREE.Matrix3().setFromMatrix4(vp.boneGroups.get(info.bone.name).inner.matrixWorld);
                    applyMoveFree(info, F(dd).applyMatrix3(lin));
                } else {
                    applyMoveFree(info, dw);
                }
                changed = true;
            }
        } else if (h.gizmo === 'rotate') {
            const a = angleOnPlane(e, drag.anchor0, drag.n);
            let dDeg = THREE.MathUtils.radToDeg(a - drag.angle0);
            if (snap) dDeg = Math.round(dDeg / 15) * 15;
            if (dDeg !== 0) { applyRotate(info, h.axis, dDeg); changed = true; }
        } else if (h.gizmo === 'rotateFree') {
            // W5.4 右拖：视线平面角度 → 自由轴（beginDrag 时选定）
            const camDir = new THREE.Vector3();
            vp.camera.getWorldDirection(camDir);
            const a = angleOnPlane(e, drag.anchor0, camDir);
            let dDeg = THREE.MathUtils.radToDeg(a - drag.angle0);
            if (snap) dDeg = Math.round(dDeg / 15) * 15;
            if (dDeg !== 0) { applyRotate(info, drag.freeAxis, dDeg); changed = true; }
        } else if (h.gizmo === 'scale') {
            const f = Math.min(50, Math.max(0.02, screenDist(e, drag.anchor0) / drag.dist0));
            applyScale(info, f);
            changed = true;
        }
        if (changed) {
            drag.changed = true;
            if (api.animPoseMode && api.animPoseMode()) {
                // W7 动画模式：不改模型几何；写关键帧 + 姿态显示刷新（由 app 回调完成）
                try { if (api.onAnimPoseChanged) api.onAnimPoseChanged(info, h); } catch (e) { console.warn('[gizmo] onAnimPoseChanged', e); }
            } else {
                vp.refresh();
                rebuild();
            }
        }
    }

    function onUp() {
        window.removeEventListener('pointermove', onMove);
        if (drag && drag.changed) {
            const s = drag.info.sel;
            const what = s.type === 'bone' ? `骨骼 ${s.boneName}` : `cube[${s.cubeIndex}] @ ${s.boneName}`;
            const verb = (drag.h.gizmo === 'move' || drag.h.gizmo === 'rotateFree')
                ? (drag.h.gizmo === 'move' ? '移动' : '旋转')
                : (drag.h.gizmo === 'rotate' ? '旋转' : '缩放');
            if (api.animPoseMode && api.animPoseMode()) {
                // W7 动画模式：auto-key 提交（统一 {model, animations} 快照）
                try { if (api.onGestureEnd) api.onGestureEnd(drag, true, `${verb} ${what}`); }
                catch (e) { console.warn('[gizmo] onGestureEnd', e); }
            } else {
                api.onChange(`${verb} ${what}`);
            }
        }
        drag = null;
        startVals = null;
    }

    // ---------- 对外 ----------
    rebuild();
    return {
        /** W5.4 已无模式（PS 式手势 + 常驻手柄）；保留 no-op 兼容旧调用 */
        setMode() {},
        rebuild,
        onPointerDown,
        /** W5.4：PS 式对象手势（左拖=移动 / 右拖=旋转；未选中先选中） */
        startCubeGesture,
        /** W5.4：滚轮悬停 cube = 均匀缩放（绕 pivot） */
        wheelScaleAt,
        /** 调试钩子：当前拖拽状态（headless 验收 / W4 排障用） */
        getDragState() {
            if (!drag) return null;
            return { t0: drag.t0, axis: drag.h.axis, gizmo: drag.h.gizmo, changed: drag.changed };
        },
        dispose() {
            window.removeEventListener('pointermove', onMove);
            window.removeEventListener('pointerup', onUp);
            if (gizmoGroup) vp.scene.remove(gizmoGroup);
        },
    };
}
