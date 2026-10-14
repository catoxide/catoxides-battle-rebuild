// modelThumb.js - 模型/贴图缩略图渲染（ES module）
// 供 entityList 使用：实体(model.type=entity) → 3D 骨骼预览；物品(model.type=item) → 贴图
// 用法：
//   import { drawModelThumb, drawTextureThumb } from '../lib/modelThumb.js';
//   drawModelThumb(canvas, model)      // model = resolveModelFromId 返回的解析结果
//   drawTextureThumb(canvas, dataUrl)  // dataUrl = 'data:image/png;base64,...'
import * as THREE from './three.module.js';

/**
 * 把 spark 模型渲染为 3D 缩略图
 * @param {HTMLCanvasElement} canvas
 * @param {Object} model resolveModelFromId / parseModelFile 的返回（含 bones: [{name,parent,pivot,cubes:[{origin,size}]}], tree）
 */
export function drawModelThumb(canvas, model) {
    if (!canvas || !model || !model.bones || !model.bones.length) {
        clearCanvas(canvas);
        return;
    }
    const W = canvas.width, H = canvas.height;
    // 已渲染过则复用（避免每帧重建 WebGL 上下文）
    if (canvas._thumbReady && canvas._thumbModelId === model.identifier) {
        renderThumb(canvas, model);
        return;
    }

    // 初始化场景
    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x1e1e2e);

    // 计算模型包围盒 → 自动适配相机
    const bb = computeBoundingBox(model);
    const center = bb.center.clone();
    const size = bb.size.length() || 1;
    const camDist = Math.max(size * 2.2, 3);

    const camera = new THREE.PerspectiveCamera(45, W / H, 0.1, 1000);
    camera.position.set(camDist * 0.9, camDist * 0.7, camDist * 0.9);
    camera.lookAt(center);

    // 灯光
    const amb = new THREE.AmbientLight(0xffffff, 0.55);
    scene.add(amb);
    const dir = new THREE.DirectionalLight(0xffffff, 0.9);
    dir.position.set(camDist, camDist * 0.6, camDist * 0.4);
    scene.add(dir);
    const dir2 = new THREE.DirectionalLight(0x89b4fa, 0.35);
    dir2.position.set(-camDist * 0.5, camDist * 0.3, -camDist * 0.5);
    scene.add(dir2);

    // 根节点组（相对包围盒中心对齐，让模型居中）
    const root = new THREE.Group();
    scene.add(root);

    // 递归构建骨骼 → cube 层级
    const matCache = {};
    function materialFor(boneName) {
        if (!matCache[boneName]) {
            // 简单配色：按骨骼名 hash 给不同色，视觉可区分
            let h = 0;
            for (let i = 0; i < boneName.length; i++) h = (h * 31 + boneName.charCodeAt(i)) % 360;
            const color = new THREE.Color().setHSL(h / 360, 0.45, 0.55);
            matCache[boneName] = new THREE.MeshLambertMaterial({ color });
        }
        return matCache[boneName];
    }

    // 骨骼 → cube 层级（10/09 重写：修复子骨 pivot 双重偏移——"僵尸头往上飞"）
    //
    // Bedrock 语义（与模型编辑器视口/游戏内渲染一致，zombie_3 现网核验）：
    //   骨骼 pivot 与 cube origin 都在模型根空间（父骨无旋转时 = 父骨局部空间）。
    // 旧实现把子骨 group 以"原始 pivot"直接挂到父骨 group 下，而父骨 group 的原点在
    // 父骨 pivot 上 → 子骨 pivot 被二次偏移父骨 pivot。zombie_3：body pivot(0,12,0)，
    // head pivot(0,24,0) → head 被渲染到 y36–44（正确 y24–32），headwear 再叠一层 +36。
    // 修复：子骨 group 位置 = 子骨 pivot − 父骨 pivot（根空间 → 父骨局部空间）；
    // 沿链累加 = 伸缩求和，每根骨骼世界位置 = 自己的 pivot（无旋转时）。
    const groups = {};
    for (const bone of model.bones) {
        const g = new THREE.Group();
        const p = bone.pivot || [0, 0, 0];
        g.name = bone.name;
        g.userData.pivot = p;
        groups[bone.name] = g;
        // cubes（origin 在根空间，group 原点在骨 pivot → 偏移 = origin − pivot）
        for (const cube of bone.cubes || []) {
            const inf = cube.inflate || 0;
            const o = [
                (cube.origin || [0, 0, 0])[0] - inf,
                (cube.origin || [0, 0, 0])[1] - inf,
                (cube.origin || [0, 0, 0])[2] - inf,
            ];
            const s0 = (cube.size || [1, 1, 1])[0], s1 = (cube.size || [1, 1, 1])[1], s2 = (cube.size || [1, 1, 1])[2];
            const s = [s0 + 2 * inf, s1 + 2 * inf, s2 + 2 * inf];
            if (!s0 || !s1 || !s2) continue; // 零尺寸 cube（占位）跳过
            const geo = new THREE.BoxGeometry(s[0], s[1], s[2]);
            const mesh = new THREE.Mesh(geo, materialFor(bone.name));
            mesh.position.set(o[0] - p[0], o[1] - p[1], o[2] - p[2]);
            g.add(mesh);
        }
    }
    for (const bone of model.bones) {
        const g = groups[bone.name];
        if (!g) continue;
        const p = g.userData.pivot;
        if (bone.parent && groups[bone.parent]) {
            const pp = groups[bone.parent].userData.pivot;
            g.position.set(p[0] - pp[0], p[1] - pp[1], p[2] - pp[2]);
            groups[bone.parent].add(g);
        } else {
            g.position.set(p[0], p[1], p[2]);
            root.add(g);
        }
    }
    // 居中
    root.position.set(-center.x, -center.y, -center.z);

    // WebGL 渲染器（alpha 透明背景则配合 scene.background）
    let renderer = canvas._renderer;
    if (!renderer) {
        renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
        renderer.setSize(W, H, false);
        canvas._renderer = renderer;
    }

    canvas._thumbScene = scene;
    canvas._thumbCamera = camera;
    canvas._thumbRoot = root;
    canvas._thumbReady = true;
    canvas._thumbModelId = model.identifier;
    canvas._thumbBb = bb;
    canvas._thumbT0 = performance.now();

    // 记录用于旋转动画
    renderThumb(canvas, model);
    // 单帧旋转动画（约 1.2 秒转一圈，中途停止在美观角度）
    let frames = 0;
    const animate = () => {
        if (!canvas._thumbReady || canvas._thumbScene !== scene) return;
        frames++;
        root.rotation.y += 0.045;
        renderer.render(scene, camera);
        if (frames < 45) requestAnimationFrame(animate);
    };
    animate();
}

/** 每帧渲染（供旋转动画循环调用） */
function renderThumb(canvas) {
    if (!canvas._renderer || !canvas._thumbScene || !canvas._thumbCamera) return;
    canvas._renderer.render(canvas._thumbScene, canvas._thumbCamera);
}

/**
 * 渲染贴图缩略图（物品/武器）
 * @param {HTMLCanvasElement} canvas
 * @param {string} dataUrl data:image 前缀的图片
 */
export function drawTextureThumb(canvas, dataUrl) {
    clearCanvas(canvas);
    if (!dataUrl) return;
    const img = new Image();
    img.onload = () => {
        // 若该 canvas 已被 WebGL 占用（3D 渲染过），getContext('2d') 返回 null——跳过
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        const W = canvas.width, H = canvas.height;
        // 保持比例，居中绘制
        const scale = Math.min((W - 8) / img.width, (H - 8) / img.height);
        const w = img.width * scale, h = img.height * scale;
        const x = (W - w) / 2, y = (H - h) / 2;
        ctx.clearRect(0, 0, W, H);
        ctx.fillStyle = '#2a2b3d';
        ctx.fillRect(0, 0, W, H);
        ctx.imageSmoothingEnabled = true;
        ctx.drawImage(img, x, y, w, h);
    };
    img.onerror = () => clearCanvas(canvas);
    img.src = dataUrl;
}

/** 计算模型包围盒 */
function computeBoundingBox(model) {
    const min = new THREE.Vector3(Infinity, Infinity, Infinity);
    const max = new THREE.Vector3(-Infinity, -Infinity, -Infinity);
    for (const bone of model.bones) {
        for (const cube of bone.cubes || []) {
            // origin/size 均在模型根空间（Bedrock 语义，与层级构建一致）；inflate 展开几何
            const inf = cube.inflate || 0;
            const o = cube.origin || [0, 0, 0];
            const s = cube.size || [1, 1, 1];
            for (const dx of [0, s[0] + 2 * inf]) for (const dy of [0, s[1] + 2 * inf]) for (const dz of [0, s[2] + 2 * inf]) {
                const x = o[0] - inf + dx;
                const y = o[1] - inf + dy;
                const z = o[2] - inf + dz;
                min.x = Math.min(min.x, x); min.y = Math.min(min.y, y); min.z = Math.min(min.z, z);
                max.x = Math.max(max.x, x); max.y = Math.max(max.y, y); max.z = Math.max(max.z, z);
            }
        }
    }
    if (!isFinite(min.x)) {
        return { min: new THREE.Vector3(-1, -1, -1), max: new THREE.Vector3(1, 1, 1), center: new THREE.Vector3(0, 0, 0), size: new THREE.Vector3(2, 2, 2) };
    }
    const center = min.clone().add(max).multiplyScalar(0.5);
    const size = max.clone().sub(min);
    return { min, max, center, size };
}

function clearCanvas(canvas) {
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
}
