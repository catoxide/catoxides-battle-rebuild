// gizmoMath.test.js - W3 gizmo 变换数学验证（headless three，无 WebGL）
// 复刻 viewport buildModel 的层级公式（Group 即可，与 ui/viewport.js 同源约定）+
// gizmo 的 delta 公式，验证：
//   1. 骨骼移动 = 整棵子树刚性平移（子树外不动）
//   2. cube 移动 = 只动该 cube
//   3. 旋转链（M≠I）下 pivot 帧 vs 内容帧的区分正确（世界位移恒等于目标向量）
'use strict';
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const { pathToFileURL } = require('url');

const Codec = require('../core/sparkCodec');
const M = require('../core/model');
const ZOMBIE = path.resolve(__dirname, '..', '..', '..', '..', 'contentpacks-source', 'modular-zombie-3', 'spark_models', 'entity', 'zombie3pack', 'modular_zombie_3.json');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

(async () => {
    const THREE = await import(pathToFileURL(path.resolve(__dirname, '..', '..', 'src', 'lib', 'three.module.js')).href);
    const DEG = Math.PI / 180;
    const F = (v) => new THREE.Vector3(-v.x, v.y, v.z);

    // 复刻 ui/viewport.js buildModel 的层级（仅 Group：骨骼 outer/inner + cube pivot 标记）
    function buildHierarchy(geo) {
        const root = new THREE.Group();
        const boneGroups = new Map();
        const cubeMarkers = new Map();
        for (const b of geo.bones) {
            const p = b.pivot || [0, 0, 0];
            const outer = new THREE.Group();
            outer.position.set(-p[0], p[1], p[2]);
            const r = b.rotation || [0, 0, 0];
            outer.rotation.set(-r[0] * DEG, -r[1] * DEG, r[2] * DEG); // mirrorEuler 约定（BB 参照）
            const s = b.scale || [1, 1, 1];
            outer.scale.set(s[0], s[1], s[2]);
            const inner = new THREE.Group();
            inner.position.set(p[0], -p[1], -p[2]);
            outer.add(inner);
            root.add(outer);
            boneGroups.set(b.name, { outer, inner });
            (b.cubes || []).forEach((c, ci) => {
                const cp = c.pivot || [0, 0, 0];
                const mk = new THREE.Group();
                mk.position.set(-cp[0], cp[1], cp[2]);
                inner.add(mk);
                cubeMarkers.set(`${b.name}:${ci}`, mk);
            });
        }
        for (const b of geo.bones) {
            const g = boneGroups.get(b.name);
            if (b.parent && boneGroups.has(b.parent)) {
                root.remove(g.outer);
                boneGroups.get(b.parent).inner.add(g.outer);
            }
        }
        root.updateMatrixWorld(true);
        return { root, boneGroups, cubeMarkers };
    }

    // gizmo.js 的 delta 公式（与 ui/gizmo.js 保持同步；返回纯数组 [x,y,z]）：
    //   骨骼移动 = 统一 δ（所选骨【父帧】），子树所有 pivot/cube origin+pivot/locator += δ
    //   cube 移动 = δ（所属骨【自身内容帧】，含 M_B），只动该 cube 的 origin + pivot
    function parentFrameOf(h, geo, boneName) {
        const bone = geo.bones.find((b) => b.name === boneName);
        if (!bone || !bone.parent || !h.boneGroups.has(bone.parent)) return h.root;
        return h.boneGroups.get(bone.parent).inner;
    }
    function toFrameBedrock(frameGroup, dw) {
        const lin = new THREE.Matrix3().setFromMatrix4(frameGroup.matrixWorld).invert();
        const v = dw.clone().applyMatrix3(lin);
        return [-v.x, v.y, v.z];
    }
    const deltaForPivot = (h, geo, boneName, dw) => toFrameBedrock(parentFrameOf(h, geo, boneName), dw);
    const deltaForContent = (h, geo, boneName, dw) => toFrameBedrock(h.boneGroups.get(boneName).inner, dw);
    /** 应用骨骼移动（统一 δ）：子树 pivot/cube origin+pivot 全 += δ */
    function applyBoneMove(geo, boneName, delta) {
        for (const n of subtreeOf(geo, boneName)) {
            const b = geo.bones.find((x) => x.name === n);
            for (let i = 0; i < 3; i++) b.pivot[i] += delta[i];
            for (const c of b.cubes || []) for (let i = 0; i < 3; i++) { c.origin[i] += delta[i]; c.pivot[i] += delta[i]; }
        }
    }
    function subtreeOf(geo, name) {
        const { byName } = M.buildTree(geo);
        const out = [];
        const visit = (n) => { if (!n) return; out.push(n.name); for (const c of n.children) visit(c); };
        visit(byName.get(name));
        return out;
    }
    const worldPos = (o) => o.getWorldPosition(new THREE.Vector3());

    console.log('=== gizmo 变换数学（headless three） ===');

    test('骨骼移动 = 子树刚性平移（zombie left_arm，世界 +Y 3；子树外不动）', () => {
        const geo = Codec.importModel(fs.readFileSync(ZOMBIE, 'utf-8')).data;
        const h = buildHierarchy(geo);
        const before = new Map();
        for (const [k, m] of h.cubeMarkers) before.set(k, worldPos(m).clone());
        const dw = new THREE.Vector3(0, 3, 0);
        const delta = deltaForPivot(h, geo, 'left_arm', dw);
        applyBoneMove(geo, 'left_arm', delta);
        const h2 = buildHierarchy(geo);
        for (const [k, m] of h2.cubeMarkers) {
            const p = worldPos(m);
            const b = before.get(k);
            if (k.startsWith('left_arm:')) {
                assert.ok(p.distanceTo(new THREE.Vector3(b.x, b.y + 3, b.z)) < 1e-4, `${k} 未 +3Y: [${p.x.toFixed(2)},${p.y.toFixed(2)},${p.z.toFixed(2)}]`);
            } else {
                assert.ok(p.distanceTo(b) < 1e-4, `${k} 不应动: Δ=${p.sub(b).toArray().map((x) => x.toFixed(3))}`);
            }
        }
        // 数据侧：left_arm pivot/cube 都 +3Y（bedrock）
        const la = geo.bones.find((x) => x.name === 'left_arm');
        assert.deepStrictEqual(la.pivot, [5, 25, 0]);
        assert.deepStrictEqual(la.cubes[0].origin, [4, 15, -2]);
    });

    test('根骨移动 = 全模型刚性平移（body，bedrock +X 2 → 世界 -X 2）', () => {
        const geo = Codec.importModel(fs.readFileSync(ZOMBIE, 'utf-8')).data;
        const h = buildHierarchy(geo);
        const before = new Map();
        for (const [k, m] of h.cubeMarkers) before.set(k, worldPos(m).clone());
        const dw = F(new THREE.Vector3(2, 0, 0)); // bedrock +X 2 的世界位移
        const delta = deltaForPivot(h, geo, 'body', dw);
        applyBoneMove(geo, 'body', delta);
        const h2 = buildHierarchy(geo);
        for (const [k, m] of h2.cubeMarkers) {
            const p = worldPos(m);
            const b = before.get(k);
            assert.ok(p.distanceTo(new THREE.Vector3(b.x - 2, b.y, b.z)) < 1e-4, `${k} 未 -2X 世界位移`);
        }
        // bedrock 侧：所有 pivot/cube origin 的 x 都 +2
        for (const b of geo.bones) {
            assert.strictEqual(b.pivot[0], (b === geo.bones[0] ? 0 : b.name === 'left_arm' ? 5 : b.name === 'right_arm' ? -5 : b.name === 'left_leg' ? 1.9 : b.name === 'right_leg' ? -1.9 : 0) + 2, `${b.name} pivot.x`);
        }
    });

    test('cube 移动 = 只动该 cube（body cube[0]，世界 +Z 3；origin 与 pivot 同移，BB 语义）', () => {
        const geo = Codec.importModel(fs.readFileSync(ZOMBIE, 'utf-8')).data;
        const h = buildHierarchy(geo);
        const before = new Map();
        for (const [k, m] of h.cubeMarkers) before.set(k, worldPos(m).clone());
        const dw = new THREE.Vector3(0, 0, 3);
        const delta = deltaForContent(h, geo, 'body', dw);
        const b = geo.bones.find((x) => x.name === 'body');
        for (let i = 0; i < 3; i++) { b.cubes[0].origin[i] += delta[i]; b.cubes[0].pivot[i] += delta[i]; }
        const h2 = buildHierarchy(geo);
        for (const [k, m] of h2.cubeMarkers) {
            const p = worldPos(m);
            const bd = before.get(k);
            if (k === 'body:0') assert.ok(p.distanceTo(new THREE.Vector3(bd.x, bd.y, bd.z + 3)) < 1e-4, 'body:0 应 +3Z');
            else assert.ok(p.distanceTo(bd) < 1e-4, `${k} 不应动`);
        }
        assert.deepStrictEqual(b.cubes[0].origin, [-4, 12, 1]);
        assert.deepStrictEqual(b.cubes[0].pivot, [0, 0, 3]);
    });

    test('旋转骨上的 cube 移动：δ 必须在骨自身内容帧（合成：A 旋转90°X，cube 在 A 上）', () => {
        const geo = M.createGeometry({
            identifier: 't',
            bones: [
                M.createBone({ name: 'A', pivot: [0, 0, 0], rotation: [90, 0, 0], cubes: [M.createCube({ origin: [0, 0, 0], size: [2, 2, 2], uv: [0, 0] })] }),
            ],
        });
        const h = buildHierarchy(geo);
        const before = worldPos(h.cubeMarkers.get('A:0')).clone();
        const dw = new THREE.Vector3(0, 1, 0); // 世界 +Y
        const delta = deltaForContent(h, geo, 'A', dw);
        const b = geo.bones.find((x) => x.name === 'A');
        for (let i = 0; i < 3; i++) { b.cubes[0].origin[i] += delta[i]; b.cubes[0].pivot[i] += delta[i]; }
        const h2 = buildHierarchy(geo);
        const after = worldPos(h2.cubeMarkers.get('A:0'));
        assert.ok(after.distanceTo(before.clone().add(dw)) < 1e-4, `cube 应移动 dw: 实际 ${after.sub(before).toArray().map((x) => x.toFixed(4))}`);
        // 本用例能区分两种帧：A 自身帧 = Rx(−90°)（场景），父帧（root）= I。
        // 误用父帧会得 δ=(0,1,0)，世界位移 = Rx(−90°)·(0,1,0) = (0,0,−1) ≠ dw。
    });

    test('旋转链（M≠I）：子骨移动仍为世界刚性（合成：A 旋转90°X，B 为 A 之子旋转90°Y）', () => {
        const geo = M.createGeometry({
            identifier: 't',
            bones: [
                M.createBone({ name: 'A', pivot: [0, 0, 0], rotation: [90, 0, 0], cubes: [M.createCube({ origin: [1, 0, 0], size: [4, 4, 4], uv: [0, 0] })] }),
                M.createBone({ name: 'B', parent: 'A', pivot: [0, 0, 0], rotation: [0, 90, 0], cubes: [M.createCube({ origin: [0, 0, 1], size: [2, 2, 2], uv: [0, 0] })] }),
            ],
        });
        const h = buildHierarchy(geo);
        const beforeA = worldPos(h.cubeMarkers.get('A:0')).clone();
        const beforeB = worldPos(h.cubeMarkers.get('B:0')).clone();
        const beforePivot = worldPos(h.boneGroups.get('B').outer).clone();
        const dw = new THREE.Vector3(1, 0, 0);
        const delta = deltaForPivot(h, geo, 'B', dw);
        applyBoneMove(geo, 'B', delta);
        const h2 = buildHierarchy(geo);
        const afterA = worldPos(h2.cubeMarkers.get('A:0'));
        const afterB = worldPos(h2.cubeMarkers.get('B:0'));
        assert.ok(afterA.distanceTo(beforeA) < 1e-4, `A 不应动: ${afterA.sub(beforeA).toArray()}`);
        assert.ok(afterB.distanceTo(beforeB.clone().add(dw)) < 1e-4, `B 应移动 dw: 实际 ${afterB.sub(beforeB).toArray().map((x) => x.toFixed(4))}`);
        // 旋转链下统一 δ 仍精确刚性：pivot 世界位移也 = dw
        const pivotBefore = beforePivot;
        const pivotAfter = worldPos(h2.boneGroups.get('B').outer);
        assert.ok(pivotAfter.distanceTo(pivotBefore.clone().add(dw)) < 1e-4, `B pivot 应移动 dw: 实际 ${pivotAfter.sub(pivotBefore).toArray().map((x) => x.toFixed(4))}`);
    });

    // ⑥ gizmo.js 的轴参数（射线×轴直线）：回归测试
    const { rayAxisParam } = await import(pathToFileURL(path.resolve(__dirname, '..', 'ui', 'gizmo.js')).href);
    test('rayAxisParam：射线过 P+3n 的投影点 → t=3（初版公式恒 0 的回归点）', () => {
        const P = new THREE.Vector3(-5, 22, 0);
        const n = new THREE.Vector3(0, 1, 0);
        // 复刻 viewport 默认轨道相机（fit()：theta 0.9 / phi 1.05 / radius = maxDim×2.6 ≈ 83）
        const target = new THREE.Vector3(0, 16, 0), radius = 83, theta = 0.9, phi = 1.05;
        const cam = new THREE.PerspectiveCamera(45, 1.1, 0.01, 5000);
        cam.position.set(
            target.x + radius * Math.sin(phi) * Math.sin(theta),
            target.y + radius * Math.cos(phi),
            target.z + radius * Math.sin(phi) * Math.cos(theta),
        );
        cam.lookAt(target);
        cam.updateMatrixWorld();
        for (const t0 of [0, 1, 3, -2, 7.5]) {
            const X = P.clone().addScaledVector(n, t0);
            const d = X.clone().sub(cam.position).normalize(); // 指针落在 X 的投影像素上
            const t = rayAxisParam(cam.position, d, P, n);
            assert.ok(Math.abs(t - t0) < 1e-6, `过 P+${t0}n 应得 t=${t0}，实际 ${t}`);
        }
        // 斜视角（非垂直于轴）仍精确：相机绕 P 转 30°
        cam.position.applyAxisAngle(new THREE.Vector3(0, 1, 0), Math.PI / 6);
        cam.lookAt(P);
        const d2 = P.clone().addScaledVector(n, 4).sub(cam.position).normalize();
        assert.ok(Math.abs(rayAxisParam(cam.position, d2, P, n) - 4) < 1e-6);
    });

    console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
    if (failed > 0) process.exit(1);
})();
