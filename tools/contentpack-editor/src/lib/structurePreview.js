// structurePreview.js - 建筑结构 3D 预览（ES module）
//
// 能力：
//   - 轨道相机：左键拖拽绕目标旋转（球坐标 theta/phi），滚轮缩放
//   - 右键 / Shift+左键 拖拽 = 平移（沿地面移动视点目标）
//   - 默认/重置 = 固定角落视角（45° 方位 + 30° 仰角，自动取景）
//   - 图层开关：填充方块 / jigsaw 标记（朝向桩线）/ 地面网格
//   - 片段级显隐（按片段名，两种模式通用）
//   - 自动旋转开关
//   - 两种视图模式：
//       setBlueprint(bp, {hiddenPieces})      并排模式：各片段独立排开（编辑单个片段用）
//       setPlacements(placements, {pieces, hiddenPieces})  拼合模式：
//           按 core/structureGrowth.js 的生长结果，把片段按 jigsaw 连接拼成完整建筑
//
// 用法：
//   import { createStructurePreview } from '../lib/structurePreview.js';
//   const pv = createStructurePreview(canvas);
//   pv.setPlacements(grow.placements, { pieces: bp.pieces });
//   pv.setLayer('grid', false);
//   pv.setPieceVisible('main_hall', false);
//   pv.start();  // 进入 3D 页签时
//   pv.stop();   // 离开时
//
// 交互用 Pointer Events + pointer capture：拖到窗口外不丢失，右键拖拽不弹菜单。

import * as THREE from './three.module.js';

const BG = 0x1e1e2e;        // 与 styles.css --bg 一致
const ACCENT = 0x89b4fa;    // jigsaw 标记
const GRID_A = 0x45475a;
const GRID_B = 0x313244;

/** 片段名 → 稳定颜色（与 modelThumb 同算法，视觉一致） */
function pieceColor(name) {
    let h = 0;
    for (let i = 0; i < String(name).length; i++) h = (h * 31 + String(name).charCodeAt(i)) % 360;
    return new THREE.Color().setHSL(h / 360, 0.5, 0.55);
}

// ===== 旋转数学（与 src/core/structureGrowth.js 保持一致；渲染进程不能 require CJS，故本地实现） =====
const YAW_ROT90 = { east: 'south', south: 'west', west: 'north', north: 'east' };
const FACE_OPPOSITE = { up: 'down', down: 'up', east: 'west', west: 'east', north: 'south', south: 'north' };

function splitOrientation(ori) {
    const i = ori.indexOf('_');
    if (i < 0) return { face: ori, yaw: null };
    const a = ori.slice(0, i), b = ori.slice(i + 1);
    if (a === 'east' || a === 'west' || a === 'north' || a === 'south') return { face: a, yaw: 'up' };
    return { face: a, yaw: b };
}

/** 模板旋转 r 下的 orientation（与 core/structureGrowth.js 一致）：
 *  顶/底面 → yaw 旋转；水平面 → 面旋转，yaw 恒 up */
function rotatedOrientation(ori, r) {
    const { face, yaw } = splitOrientation(ori);
    if (!yaw) return ori;
    let f = face, y = yaw;
    for (let i = 0; i < (r & 3); i++) {
        if (f === 'up' || f === 'down') y = YAW_ROT90[y] || y;
        else f = YAW_ROT90[f] || f;
    }
    return f + '_' + y;
}

/** 模板旋转 r 下的局部 (x,z)（y 不变）；标准网格旋转约定 */
function rotPos(x, z, w, d, r) {
    switch (r & 3) {
        case 0: return [x, z];
        case 1: return [d - 1 - z, x];
        case 2: return [w - 1 - x, d - 1 - z];
        default: return [z, w - 1 - x];
    }
}

/** orientation → 主方向向量（桩线方向；北 = -Z） */
function orientationVec(o) {
    const { face } = splitOrientation(o);
    switch (face) {
        case 'up': return new THREE.Vector3(0, 1, 0);
        case 'down': return new THREE.Vector3(0, -1, 0);
        case 'east': return new THREE.Vector3(1, 0, 0);
        case 'west': return new THREE.Vector3(-1, 0, 0);
        case 'north': return new THREE.Vector3(0, 0, -1);
        default: return new THREE.Vector3(0, 0, 1);
    }
}

/**
 * @param {HTMLCanvasElement} canvas
 * @param {{onChange?: () => void}} hooks onChange：体素编辑表变化（setVoxel/clearEdits）回调
 */
export function createStructurePreview(canvas, hooks = {}) {
    const W0 = canvas.width || 800, H0 = canvas.height || 600;

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(BG);
    const camera = new THREE.PerspectiveCamera(45, W0 / H0, 0.1, 8000);

    // 灯光
    const amb = new THREE.AmbientLight(0xffffff, 0.55);
    scene.add(amb);
    const dir = new THREE.DirectionalLight(0xffffff, 0.9);
    dir.position.set(60, 90, 40);
    scene.add(dir);
    const dir2 = new THREE.DirectionalLight(0x89b4fa, 0.3);
    dir2.position.set(-40, 30, -50);
    scene.add(dir2);

    // 地面网格
    const grid = new THREE.GridHelper(400, 100, GRID_A, GRID_B);
    scene.add(grid);

    // 图层根组
    const gFills = new THREE.Group();
    const gJigsaws = new THREE.Group();
    const gGhosts = new THREE.Group(); // P5 死端幽灵盒
    const gGhostVox = new THREE.Group(); // P16 jigsaw 虚影拼合（半透明体素，独立于 gFills → 不可拾取）
    scene.add(gFills, gJigsaws, gGhosts, gGhostVox);

    // 轨道相机状态（球坐标：target + radius/phi/theta）
    const orbit = { target: new THREE.Vector3(0, 4, 0), radius: 40, theta: Math.PI / 4, phi: Math.PI / 3 };

    function applyCamera() {
        const sp = Math.sin(orbit.phi), cp = Math.cos(orbit.phi);
        camera.position.set(
            orbit.target.x + orbit.radius * sp * Math.sin(orbit.theta),
            orbit.target.y + orbit.radius * cp,
            orbit.target.z + orbit.radius * sp * Math.cos(orbit.theta)
        );
        camera.lookAt(orbit.target);
        updateViewCube(); // 轨道变化 → 立方体逆旋转同步（θ/φ 解析，无 Quaternion 依赖）
    }

    let renderer = canvas._structRenderer;
    if (!renderer) {
        renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false });
        canvas._structRenderer = renderer;
    }
    function sizeCanvas() {
        const w = canvas.clientWidth || W0, h = canvas.clientHeight || H0;
        if (canvas.width !== w || canvas.height !== h) {
            canvas.width = w; canvas.height = h;
            renderer.setSize(w, h, false);
            camera.aspect = w / h;
            camera.updateProjectionMatrix();
        }
        updateViewCube(); // 视图立方体右上角位置随画布尺寸/aspect 重算（幂等，开销极小）
    }

    // ===== 场景构建（方块级体素 + 真实贴图） =====
    // P14 调试：重建/换片时序追踪（定位切片段不生效）
    const pvTrace = [];
    function trace(s) {
        pvTrace.push(s + ' @' + Date.now());
        if (pvTrace.length > 300) pvTrace.shift();
        if (typeof window !== 'undefined') window.__pvTrace = pvTrace;
    }
    let pieceMeshes = new Map(); // pieceName → { fills:[], jigsaws:[] }
    const editMaps = new Map();  // pieceName → 编辑过的局部体素表（P3 点选/笔刷；无 = 用片段定义）
    let clip = null;             // 3D 切片 {axis:'x'|'y'|'z', dir:-1|1, threshold:number}（P3）
    let slice = null;            // P9 编辑切片 {axis:'x'|'y'|'z', index:number}（剖切面：隐藏面向相机一侧，仅 rot=0 单片模式）
    let sliceSide = 1;           // 当前剖切方向（相机所在侧），相机越过分界面时翻转并重建
    // P16 世界坐标切片（主 3D 预览：实体+虚影同切，任意 rot）：{axis, index}，index = 世界格坐标
    let worldSlice = null;       // null 关闭
    let worldSliceSide = 1;      // +1 = 相机在 +axis 侧（隐 coord>index）

    /** 共享单位立方体（所有体素 InstancedMesh 复用；clearGroups 不 dispose） */
    const unitBox = new THREE.BoxGeometry(1, 1, 1);

    // --- 贴图系统（IPC 取 dataURL → THREE 纹理缓存 → 材质缓存；无资源回退色板） ---
    const sharedMats = new Map();  // key → material（复用 + clearGroups 不释放）
    const faceCache = new Map();   // blockId → {top,side,bottom}(dataURL) | null
    const facePending = new Map(); // blockId → Promise
    const texSets = new Map();     // blockId → {top,side,bottom: Texture|null}
    const matSets = new Map();     // blockId → materials[6]
    const texLoader = new THREE.TextureLoader();

    /** 无贴图方块的稳定回退色（按 blockId hash，饱和度低，不与片段色混淆） */
    function blockColor(id) {
        let h = 0;
        for (let i = 0; i < String(id).length; i++) h = (h * 31 + String(id).charCodeAt(i)) % 360;
        return new THREE.Color().setHSL(h / 360, 0.32, 0.58);
    }

    async function fetchFaces(blockId) {
        if (faceCache.has(blockId)) return faceCache.get(blockId);
        if (facePending.has(blockId)) return facePending.get(blockId);
        const p = (async () => {
            let faces = null;
            if (typeof window !== 'undefined' && window.api && window.api.mcBlockFaces) {
                try {
                    const res = await window.api.mcBlockFaces(blockId);
                    if (res && res.ok) faces = res.data;
                } catch {}
            }
            faceCache.set(blockId, faces);
            return faces;
        })();
        facePending.set(blockId, p);
        p.finally(() => facePending.delete(blockId));
        return p;
    }

    /** 预取一组方块的外观（优先）与回退贴图（外观失败才需要 blockFaces） */
    async function ensureAssetsFor(ids) {
        const set = new Set(ids || []);
        await ensureAppearanceFor(set);
        const needFaces = [...set].filter((id) =>
            id && id !== 'minecraft:air' && !id.startsWith('#') && !faceCache.has(id)
            && !(specCache.get(id) && specCache.get(id).ok));
        if (needFaces.length) await Promise.all(needFaces.map((id) => fetchFaces(id).catch(() => null)));
    }

    /** 预取一组片段用到的全部贴图（构建前 await） */
    async function ensureFacesFor(pieces) {
        const ids = new Set();
        for (const pc of pieces || []) {
            for (const bf of pc.boxFills || []) if (bf && bf.block) ids.add(bf.block);
            for (const fb of pc.fillBlocks || []) if (fb && fb.block) ids.add(fb.block);
        }
        await ensureAssetsFor(ids);
    }

    /** 预取编辑表里新增方块的贴图（笔刷/点选刷出的方块不在片段定义里，
     *  不预取就只有回退色，且首帧构建后材质定型 → 贴图"不热载入"） */
    async function ensureFacesForEdited() {
        const ids = new Set();
        for (const map of editMaps.values()) {
            for (const bid of map.values()) {
                if (bid && bid !== 'minecraft:air' && bid.indexOf('#') !== 0) ids.add(bid);
            }
        }
        await ensureAssetsFor(ids);
    }

    function getTextures(blockId, faces) {
        if (texSets.has(blockId)) return texSets.get(blockId);
        const t = (u) => {
            if (!u) return null;
            const tex = texLoader.load(u);
            tex.magFilter = THREE.NearestFilter; // MC 像素风
            return tex;
        };
        const set = {
            top: t(faces && faces.top),
            side: t(faces && faces.side),
            bottom: t(faces && faces.bottom),
        };
        texSets.set(blockId, set);
        return set;
    }

    /** blockId → 6 面材质（BoxGeometry 组序：px,nx,py,ny,pz,nz = 右,左,上,下,前,后） */
    function getMats(blockId, faces) {
        if (matSets.has(blockId)) return matSets.get(blockId);
        let arr;
        // 占位符 #*：淡线框感半透明（P3 空气占位符）
        if (blockId && blockId.indexOf('#') === 0) {
            const m = new THREE.MeshBasicMaterial({ color: 0x89b4fa, transparent: true, opacity: 0.16, depthWrite: false });
            arr = [m, m, m, m, m, m];
            matSets.set(blockId, arr);
            arr.forEach((m, i) => sharedMats.set('blockmat:' + blockId + ':' + i, m));
        } else if (faces && (faces.top || faces.side || faces.bottom)) {
            const t = getTextures(blockId, faces);
            const side = t.side || t.top, top = t.top || t.side, bottom = t.bottom || side || t.top;
            const mk = (tex) => new THREE.MeshLambertMaterial({ map: tex || null, color: tex ? 0xffffff : blockColor(blockId) });
            arr = [mk(side), mk(side), mk(top), mk(bottom), mk(side), mk(side)];
            matSets.set(blockId, arr);
            arr.forEach((m, i) => sharedMats.set('blockmat:' + blockId + ':' + i, m));
        } else {
            // 贴图尚未加载 → 回退色，【不缓存】：下次贴图到位后重建即升级为贴图材质
            // （缓存会导致"部分方块有贴图、部分只有色块"的半更新状态）
            const m = new THREE.MeshLambertMaterial({ color: blockColor(blockId) });
            arr = [m, m, m, m, m, m];
        }
        return arr;
    }

    // --- P14 外观系统（vanilla 标准链路：主进程按 blockstate→模型链→元素/UV/旋转/uvlock 烘焙，
    //     渲染端只做几何装配；同一 blockId[props] 的所有实例共享几何 → 仍用 InstancedMesh）---
    const specCache = new Map();    // bid → {ok,data} | null（dataURL 已在主进程解好）
    const specPending = new Map();  // bid → Promise
    const specGeos = new Map();     // bid → {geometry, materials[]}（会话级复用，clearGroups 不释放）
    const texByUrl = new Map();     // dataURL → THREE.Texture
    const texMats = new Map();      // dataURL → Set(material)（alpha 检测完成后统一升级 cutout）

    async function fetchAppearance(bid) {
        if (specCache.has(bid)) return specCache.get(bid);
        if (specPending.has(bid)) return specPending.get(bid);
        const p = (async () => {
            let res = null;
            if (typeof window !== 'undefined' && window.api && window.api.mcBlockAppearance) {
                try {
                    const r = await window.api.mcBlockAppearance(bid);
                    if (r) res = r;
                } catch { res = null; }
            }
            specCache.set(bid, res && res.ok ? res : null);
            return specCache.get(bid);
        })();
        specPending.set(bid, p);
        p.finally(() => specPending.delete(bid));
        return p;
    }

    /** 预取一组方块的外观（优先于贴图：外观成功则无需 blockFaces 回退贴图） */
    async function ensureAppearanceFor(ids) {
        const todo = [...new Set(ids || [])].filter((id) =>
            id && id !== 'minecraft:air' && !id.startsWith('#') && !specCache.has(id));
        if (!todo.length) return;
        await Promise.all(todo.map((id) => fetchAppearance(id).catch(() => null)));
    }

    /** 16×16 贴图 alpha 采样（玻璃/门/栅栏等 cutout；加载完成时调一次） */
    function textureHasAlpha(tex) {
        const img = tex && tex.image;
        if (!img || !img.width || !img.height) return false;
        try {
            const c = document.createElement('canvas');
            c.width = img.width; c.height = img.height;
            const ctx = c.getContext('2d');
            ctx.drawImage(img, 0, 0);
            const d = ctx.getImageData(0, 0, c.width, c.height).data;
            for (let i = 3; i < d.length; i += 4) if (d[i] < 250) return true;
        } catch { return false; }
        return false;
    }

    /** bid → {geometry, materials}（每个烘焙面一个材质组；贴图缺失→回退色板） */
    function specMeshAssets(bid) {
        if (specGeos.has(bid)) return specGeos.get(bid);
        const spec = specCache.get(bid);
        if (!spec || !spec.data || !spec.data.faces || !spec.data.faces.length) return null;
        const faces = spec.data.faces;
        const pos = new Float32Array(faces.length * 12);
        const uv = new Float32Array(faces.length * 8);
        const idx = new Uint16Array(faces.length * 6);
        const matMap = new Map();
        const getMat = (f) => {
            const url = f.texUrl || null;
            const k = (url || '∅') + '|' + f.shade.toFixed(3);
            if (matMap.has(k)) return matMap.get(k);
            // 灰度 = 游戏面亮度（顶 1.0/底 0.5/南北 0.8/东西 0.6）；贴图 = 色 × 灰度
            const mat = new THREE.MeshLambertMaterial({ color: new THREE.Color(f.shade, f.shade, f.shade), side: THREE.DoubleSide });
            if (url) {
                let tex = texByUrl.get(url);
                if (!tex) {
                    texMats.set(url, new Set());
                    tex = texLoader.load(url, (t) => {
                        if (textureHasAlpha(t)) { // MC cutout（玻璃/门/栅栏等）
                            for (const m of texMats.get(url) || []) { m.alphaTest = 0.5; m.needsUpdate = true; }
                        }
                    });
                    tex.magFilter = THREE.NearestFilter;
                    tex.minFilter = THREE.NearestFilter;
                    tex.wrapS = tex.wrapT = THREE.ClampToEdgeWrapping;
                    texByUrl.set(url, tex);
                }
                mat.map = tex;
                (texMats.get(url) || new Set()).add(mat);
                if (tex.image && tex.image.width && textureHasAlpha(tex)) { mat.alphaTest = 0.5; } // 图已加载完
            } else {
                mat.color = blockColor(bid).multiplyScalar(f.shade);
            }
            matMap.set(k, mat);
            sharedMats.set('specmat:' + bid + ':' + k, mat);
            return mat;
        };
        let v = 0;
        const materials = faces.map((f, fi) => {
            pos.set(f.pos, fi * 12);
            uv.set(f.uv, fi * 8);
            idx.set([v, v + 1, v + 2, v, v + 2, v + 3], fi * 6);
            v += 4;
            return getMat(f);
        });
        // P17 修正：烘焙顶点在 0..1 空间（from/16..to/16），而实例矩阵按 unitBox 约定
        // （几何中心在原点）再加 +0.5 → 不居中的话整块偏移 +0.5（用户实测"所有方块偏移"）。
        // 这里把几何平移到 -0.5..0.5，与 unitBox 同约定。
        for (let i = 0; i < pos.length; i += 3) {
            pos[i] -= 0.5; pos[i + 1] -= 0.5; pos[i + 2] -= 0.5;
        }
        const geo = new THREE.BufferGeometry();
        geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
        geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
        geo.setIndex(new THREE.BufferAttribute(idx, 1));
        faces.forEach((f, fi) => geo.addGroup(fi * 6, 6, fi));
        const out = { geometry: geo, materials };
        specGeos.set(bid, out);
        return out;
    }

    function clearGroups() {
        for (const g of [gFills, gJigsaws, gGhosts, gGhostVox]) {
            while (g.children.length) {
                const c = g.children.pop();
                if (c.isInstancedMesh) {
                    c.dispose(); // 仅释放 instanceMatrix；unitBox/材质共享
                } else if (c.geometry && c.geometry !== unitBox) {
                    c.geometry.dispose();
                }
                if (c.material && !sharedMats.has(c.material)) {
                    if (Array.isArray(c.material)) c.material.forEach((m) => { if (!sharedMats.has(m)) m.dispose(); });
                    else c.material.dispose();
                }
            }
        }
        pieceMeshes = new Map();
    }

    /** 取/建某片段的 mesh 桶 */
    function bucket(pieceName) {
        if (!pieceMeshes.has(pieceName)) pieceMeshes.set(pieceName, { fills: [], jigsaws: [] });
        return pieceMeshes.get(pieceName);
    }

    /**
     * 构建一个片段的 mesh：淡边界框 + 体素（按 blockId 实例化，真实贴图）+ jigsaw 标记
     * @param {Object} piece 蓝图片段
     * @param {number[]} off 世界偏移 [x,y,z]
     * @param {number} rot 0..3（90° 步长）
     * @param {THREE.Color} color 片段色（仅边界框用）
     */
    function buildPieceMeshes(piece, off, rot, color) {
        const [w, h, d] = piece.size || [1, 1, 1];
        const entry = bucket(piece.name);

        // 淡边界框（保留：显示片段范围；体素是主体）
        const bb = new THREE.LineSegments(
            new THREE.EdgesGeometry(new THREE.BoxGeometry(w, h, d)),
            new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.25 })
        );
        bb.position.set(off[0] + w / 2, off[1] + h / 2, off[2] + d / 2);
        gFills.add(bb);
        entry.fills.push(bb);

        // 体素：展开（或编辑表）→ P9 剖切过滤（仅 rot=0，即单片模式）→ 表面 → 世界坐标 → 切片 → 按 blockId 实例化
        let vox = editMaps.get(piece.name) || Voxel.expandPiece(piece);
        if (slice && !rot) vox = Voxel.cutOf(vox, slice, sliceSide);
        let world = Voxel.applyClip(Voxel.worldSurface(vox, off, rot, [w, h, d]), clip);
        if (worldSlice) world = worldSliceCut(world, worldSlice, worldSliceSide); // P16 世界切片（任意 rot，实体+虚影同切）
        const groups = new Map();
        for (const [x, y, z, bid] of world) {
            if (!groups.has(bid)) groups.set(bid, []);
            groups.get(bid).push([x, y, z]);
        }
        const m4 = new THREE.Matrix4();
        for (const [bid, positions] of groups) {
            if (!positions.length) continue;
            // P14：优先外观链路（按 blockstate 烘焙的真实模型几何）；失败回退简单立方体
            const spec = specCache.get(bid);
            const assets = spec && spec.ok ? specMeshAssets(bid) : null;
            const geo = assets ? assets.geometry : unitBox;
            const mats = assets ? assets.materials : getMats(bid, faceCache.get(bid) || null);
            const im = new THREE.InstancedMesh(geo, mats, positions.length);
            for (let i = 0; i < positions.length; i++) {
                m4.makeTranslation(positions[i][0] + 0.5, positions[i][1] + 0.5, positions[i][2] + 0.5);
                im.setMatrixAt(i, m4);
            }
            im.instanceMatrix.needsUpdate = true;
            im.userData.blockId = bid;
            gFills.add(im);
            entry.fills.push(im);
        }

        // jigsaw 标记
        const jigsawMat = new THREE.LineBasicMaterial({ color: ACCENT });
        const jigsawFill = new THREE.MeshLambertMaterial({ color: ACCENT, transparent: true, opacity: 0.3 });
        for (const j of piece.jigsaws || []) {
            const p = rotPos(j.x, j.z, w, d, rot);
            const cx = off[0] + p[0] + 0.5, cy = off[1] + j.y + 0.5, cz = off[2] + p[1] + 0.5;
            const mk = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1.15, 1.15, 1.15)), jigsawMat);
            mk.position.set(cx, cy, cz);
            gJigsaws.add(mk);
            entry.jigsaws.push(mk);
            const dot = new THREE.Mesh(new THREE.BoxGeometry(0.3, 0.3, 0.3), jigsawFill);
            dot.position.set(cx, cy, cz);
            gJigsaws.add(dot);
            entry.jigsaws.push(dot);
            const v = orientationVec(rotatedOrientation(j.orientation, rot));
            const stub = new THREE.Line(
                new THREE.BufferGeometry().setFromPoints([
                    new THREE.Vector3(cx, cy, cz),
                    new THREE.Vector3(cx + v.x * 2.2, cy + v.y * 2.2, cz + v.z * 2.2),
                ]),
                jigsawMat
            );
            gJigsaws.add(stub);
            entry.jigsaws.push(stub);
        }
        // jigsawMat/jigsawFill 每次构建新建 → clearGroups 时释放
        entry.jigsawMats = [jigsawMat, jigsawFill];
    }

    function applyHidden(hidden) {
        for (const [name, entry] of pieceMeshes) {
            const vis = !hidden.has(name);
            entry.fills.forEach((m) => { m.visible = vis; });
            entry.jigsaws.forEach((m) => { m.visible = vis; });
        }
    }

    /** 相机取景：目标 = 包围盒中心，半径按跨度自适应 */
    function frameBox(min, max) {
        const center = new THREE.Vector3((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2);
        const span = Math.max(max[0] - min[0], max[1] - min[1], max[2] - min[2], 1);
        orbit.target.copy(center);
        orbit.radius = Math.max(span * 0.75, 10);
        applyCamera();
    }

    // ===== 构建记录（编辑/切片后无相机重置重建） =====
    let lastBuild = null; // {kind, bp, placements, built, pieces, hidden}
    let lastPlacements = []; // [{piece, name, off, rot}]
    function saveBuild(kind, bp, placements, built, pieces, hidden) {
        lastBuild = { kind, bp, placements, built, pieces: pieces || [], hidden: hidden || [] };
        lastPlacements = built;
    }
    /** 用最后一次构建参数重建（编辑/切片后调用；不重置相机）；rebuilding 防重入；
     *  重建期间若切片翻转（sliceFlipPending）或又有新编辑（editsDirty，P12 笔触），完成后自动补一轮。
     *  P14：构建目标 = pendingBuild（请求时同步置位）优先于 lastBuild ——
     *  并发请求（切片段 vs 切片重置/编辑）时循环始终追最新的请求，过期构建被丢弃。 */
    let rebuilding = false;
    let sliceFlipPending = false;
    let editsDirty = false; // P12：重建期间又有新编辑（笔触连续写格）→ 完成后自动补一轮，不丢格
    let pendingBuild = null; // {kind, bp, placements, opts} —— 已请求未完成的构建（同步置位，重建循环取最新）
    async function rebuild() {
        if (!pendingBuild && !lastBuild) { trace('rebuild skip (无目标)'); return; }
        if (rebuilding) { editsDirty = true; trace('rebuild 并入进行中的重建（editsDirty=1）'); return; }
        rebuilding = true;
        try {
            for (;;) {
                sliceFlipPending = false;
                editsDirty = false;
                const b = pendingBuild || lastBuild;
                if (!b) break;
                const isPending = (b === pendingBuild);
                const kind = b.kind;
                const bp = b.bp;
                const placements = b.placements;
                const piecesArr = b.pieces || [];
                const hidden = b.hidden || [];
                // 公开 API 的 reframe 默认 true（旧行为 opts.reframe !== false）；编辑重建不重置相机
                const reframe = isPending ? !((b.opts || {}).reframe === false) : false;
                trace('rebuild iter [' + (kind === 'place' ? 'place' : (bp && bp.pieces ? bp.pieces.map((p) => p.name).join('|') : '?')) + (isPending ? '（新请求）' : '') + ']');
                await ensureFacesForEdited(); // 先取新刷方块的贴图，再构建
                // 等待期间若又来了更新请求 → 不构建旧的，循环重读最新目标
                if (pendingBuild && pendingBuild !== b) continue;
                if (kind === 'place') {
                    await buildPlacements(placements, { pieces: piecesArr, hiddenPieces: hidden, reframe });
                } else {
                    await buildBlueprint(bp, { hiddenPieces: hidden, reframe });
                }
                rebuildGhosts(); // P16：虚影层随整重建同步（世界切片/新片段）
                if (pendingBuild === b) pendingBuild = null; // 本目标构建完成 → 清除请求
                // 重建后强制渲染一帧：rAF 循环停摆（窗口失焦/被节流）时也立即看到编辑结果
                renderOnce();
                if (!pendingBuild && !sliceFlipPending && !editsDirty) { trace('rebuild 结束'); break; }
            }
        } finally {
            rebuilding = false;
        }
    }

    /** 请求一次构建（同步置 pendingBuild → 重建循环执行）；setPiece/直接 setPlacements 走这里 */
    function requestBuild(kind, bp, placements, pieces, opts) {
        pendingBuild = { kind, bp, placements, pieces: pieces || [], hidden: (opts && opts.hiddenPieces) || [], opts: opts || {} };
        return rebuild();
    }

    /**
     * 并排模式（公开 API）：片段沿 X 轴排开（编辑单个片段用）。
     * 请求一次并排构建（走 pendingBuild 通道，并发安全）
     * @param {Object} bp 蓝图对象
     * @param {{hiddenPieces?:string[], reframe?:boolean}} opts
     */
    async function setBlueprint(bp, opts = {}) {
        return requestBuild('bp', bp, null, (bp && bp.pieces) || [], opts);
    }
    // P14：构建序号 —— 并发构建时最后声明者生效，过期构建丢弃（重建循环单飞，此为双保险）
    let buildSeq = 0;
    async function buildBlueprint(bp, opts = {}) {
        const seq = ++buildSeq;
        const pieces = (bp && bp.pieces) || [];
        trace('buildBlueprint start [' + (pieces.map((p) => p.name).join('|') || '(空)') + '] seq' + seq);
        await ensureFacesFor(pieces);
        if (seq !== buildSeq) { trace('buildBlueprint 过期丢弃 [' + (pieces.map((p) => p.name).join('|') || '(空)') + '] seq' + seq); return; }
        trace('buildBlueprint faces-ok [' + (pieces.map((p) => p.name).join('|') || '(空)') + ']');
        clearGroups();
        const hidden = new Set(opts.hiddenPieces || []);

        const built = [];
        let cursorX = 0, maxH = 1, totalW = 0;
        for (const p of pieces) {
            const [w, h, d] = p.size || [1, 1, 1];
            buildPieceMeshes(p, [cursorX, 0, 0], 0, pieceColor(p.name));
            built.push({ piece: p, name: p.name, off: [cursorX, 0, 0], rot: 0 });
            cursorX += w + 12;
            maxH = Math.max(maxH, h);
        }
        totalW = Math.max(cursorX - 12, 1);
        trace('buildBlueprint built [' + (pieces.map((p) => p.name).join('|') || '(空)') + ']');
        saveBuild('bp', bp, null, built, pieces, [...hidden]);
        applyHidden(hidden);
        if (opts.reframe !== false) {
            orbit.theta = Math.PI / 4;
            orbit.phi = Math.PI / 3;
            frameBox([0, 0, 0], [totalW, maxH, Math.max(...pieces.map((p) => (p.size || [1, 1, 1])[2]), 1)]);
        }
        if (!running) renderOnce();
    }

    /**
     * P9 单片编辑模式：只渲染一个片段（放原点、rot=0、居中取景），
     * 供笔刷/点选/切片对该片段独立编辑。
     * @param {Object} piece 蓝图片段
     * @param {{reframe?:boolean}} opts
     */
    async function setPiece(piece, opts = {}) {
        if (!piece) { trace('setPiece 跳过（piece 为空）'); return; }
        trace('setPiece 请求 ' + piece.name);
        clearHover(); // P12：换片段 → 旧悬停高亮失效（函数声明已提升，此处可安全调用）
        // P14：走请求-重建通道（pendingBuild 同步置位）→ 与并发重建不竞态
        await requestBuild('bp', { pieces: [piece] }, null, [piece], Object.assign({ reframe: true }, opts));
    }

    /**
     * 拼合模式（公开 API）：按生长结果放置（预览 jigsaw 拼合后的完整建筑）。
     * 请求一次拼合构建（走 pendingBuild 通道，并发安全）
     * @param {Array} placements growStructure 的 placements
     *   [{pieceName, offset:[x,y,z], rotation:0..3, via}]
     * @param {{pieces?:Array, hiddenPieces?:string[], reframe?:boolean}} opts
     */
    async function setPlacements(placements, opts = {}) {
        return requestBuild('place', null, placements || [], (opts && opts.pieces) || [], opts || {});
    }
    async function buildPlacements(placements, opts = {}) {
        const seq = ++buildSeq;
        const list = placements || [];
        const byName = new Map();
        for (const p of opts.pieces || []) byName.set(p.name, p);
        await ensureFacesFor(list.map((pl) => byName.get(pl.pieceName)).filter(Boolean));
        if (seq !== buildSeq) { trace('buildPlacements 过期丢弃 seq' + seq); return; }
        clearGroups();
        const hidden = new Set(opts.hiddenPieces || []);

        const built = [];
        let min = [Infinity, Infinity, Infinity];
        let max = [-Infinity, -Infinity, -Infinity];
        for (const pl of list) {
            const piece = byName.get(pl.pieceName);
            if (!piece) continue;
            const off = pl.offset || [0, 0, 0];
            const rot = pl.rotation || 0;
            buildPieceMeshes(piece, off, rot, pieceColor(pl.pieceName));
            built.push({ piece, name: pl.pieceName, off, rot });
            const [w, h, d] = piece.size;
            min = [Math.min(min[0], off[0]), Math.min(min[1], off[1]), Math.min(min[2], off[2])];
            max = [Math.max(max[0], off[0] + w), Math.max(max[1], off[1] + h), Math.max(max[2], off[2] + d)];
        }
        saveBuild('place', null, list, built, opts.pieces || [], [...hidden]);
        applyHidden(hidden);
        if (opts.reframe !== false) {
            if (list.length) {
                orbit.theta = Math.PI / 4;
                orbit.phi = Math.PI / 3;
                frameBox(min, max);
            } else {
                frameBox([0, 0, 0], [10, 10, 10]);
            }
        }
        if (!running) renderOnce();
    }

    /**
     * P5 死端幽灵盒：红色半透明框标出"本应生长/放置失败"的位置
     * @param {Array<{x,y,z,w,h,d}>} ghosts 生长 deadEnds 中带的 ghost 字段
     */
    function setGhosts(ghosts) {
        while (gGhosts.children.length) {
            const c = gGhosts.children.pop();
            for (const ch of [...c.children]) {
                if (ch.geometry) ch.geometry.dispose();
                if (ch.material) ch.material.dispose();
                c.remove(ch);
            }
            if (c.geometry && c.geometry !== unitBox) c.geometry.dispose();
            if (c.material) c.material.dispose();
        }
        for (const gh of ghosts || []) {
            if (!gh || !isFinite(gh.x)) continue;
            const w = gh.w || 1, h = gh.h || 1, d = gh.d || 1;
            const geo = (w === 1 && h === 1 && d === 1) ? unitBox
                : new THREE.BoxGeometry(w, h, d);
            const mat = new THREE.MeshBasicMaterial({
                color: 0xff4d4d, transparent: true, opacity: 0.32, depthWrite: false,
            });
            const mesh = new THREE.Mesh(geo, mat);
            mesh.position.set(gh.x + w / 2, gh.y + h / 2, gh.z + d / 2);
            const edges = new THREE.LineSegments(
                new THREE.EdgesGeometry(geo),
                new THREE.LineBasicMaterial({ color: 0xff2d2d, transparent: true, opacity: 0.9 })
            );
            mesh.add(edges);
            gGhosts.add(mesh);
        }
        if (!running) renderOnce();
    }

    // ===== P16：jigsaw 虚影拼合（半透明体素层）+ 世界坐标切片 =====

    /** 世界坐标切片过滤（cutOf 的世界版）：切片层恒显示，隐藏面向相机一侧 */
    function worldSliceCut(world, ws, side) {
        if (!ws) return world;
        const ai = ws.axis === 'x' ? 0 : ws.axis === 'y' ? 1 : 2;
        const idx = Math.trunc(ws.index || 0);
        const s = (side || 1) >= 0 ? 1 : -1;
        return world.filter((v) => {
            const c = v[ai];
            if (c === idx) return true;
            return s === 1 ? c < idx : c > idx;
        });
    }

    /** 相机在 +axis 侧（世界切片平面之外）= +1（与 currentCamSide 类似，但相对世界平面） */
    function worldSliceCamSide(axis) {
        const ai = axis === 'x' ? 'x' : axis === 'y' ? 'y' : 'z';
        return (camera.position[ai] - worldSlice.index) >= 0 ? 1 : -1;
    }

    /** 相机越过分界面 → 翻转剖切方向并重建（无翻转时零成本；拖拽/循环中调用） */
    function checkWorldSliceFlip() {
        if (!worldSlice) return;
        const s = worldSliceCamSide(worldSlice.axis);
        if (s !== worldSliceSide) {
            worldSliceSide = s;
            if (!rebuilding) rebuild();
        }
    }

    /**
     * P16 虚影拼合层：[{pieceName, offset:[x,y,z], rotation:0..3}] 半透明渲染。
     * 独立组 gGhostVox（不在 pickVoxel 目标内 → 不可拾取）；真实贴图克隆材质
     * （transparent + opacity + depthWrite:false）；受世界切片过滤（切片切虚影）。
     * @param {Array} ghosts 虚影放置（1:1 附着候选，来自 structureGrowth.jigsawCandidates/poolPick1to1）
     * @param {{opacity?:number}} opts 0.05..1，默认 0.35
     */
    function setGhostPlacements(ghosts, opts = {}) {
        ghostPlacements = (ghosts || []).slice();
        if (opts && isFinite(opts.opacity)) ghostOpacity = Math.max(0.05, Math.min(1, opts.opacity));
        trace('setGhostPlacements [' + ghostPlacements.length + '] op=' + ghostOpacity);
        rebuildGhosts();
    }
    function getGhostPlacements() { return ghostPlacements.map((g) => Object.assign({}, g)); }
    let ghostPlacements = [];   // [{pieceName, offset, rotation}]
    let ghostOpacity = 0.35;

    /** 清空并重建虚影层（轻量：不触碰实体构建/相机取景） */
    function rebuildGhosts() {
        for (let i = gGhostVox.children.length - 1; i >= 0; i--) {
            const c = gGhostVox.children[i];
            if (c.isInstancedMesh) c.dispose();
            if (c.material && !sharedMats.has(c.material)) {
                (Array.isArray(c.material) ? c.material : [c.material]).forEach((m) => { if (!sharedMats.has(m)) m.dispose(); });
            }
            gGhostVox.remove(c);
        }
        if (!ghostPlacements.length) { if (!running) renderOnce(); return; }
        const pieces = new Map();
        for (const p of (lastBuild && lastBuild.pieces) || []) pieces.set(p.name, p);
        const m4 = new THREE.Matrix4();
        for (const gh of ghostPlacements) {
            const piece = pieces.get(gh.pieceName);
            if (!piece) continue;
            const [w, h, d] = piece.size || [1, 1, 1];
            const vox = Voxel.expandPiece(piece); // 虚影 = 定义几何（不带编辑表）
            let world = Voxel.worldSurface(vox, gh.offset || [0, 0, 0], gh.rotation || 0, [w, h, d]);
            if (worldSlice) world = worldSliceCut(world, worldSlice, worldSliceSide); // 切片切虚影
            const groups = new Map();
            for (const [x, y, z, bid] of world) {
                if (!groups.has(bid)) groups.set(bid, []);
                groups.get(bid).push([x, y, z]);
            }
            for (const [bid, positions] of groups) {
                if (!positions.length) continue;
                const spec = specCache.get(bid);
                const assets = spec && spec.ok ? specMeshAssets(bid) : null;
                const geo = assets ? assets.geometry : unitBox;
                // 克隆材质 → 半透明（共享材质不可改）
                const base = assets ? assets.materials : getMats(bid, faceCache.get(bid) || null);
                const mats = (Array.isArray(base) ? base : [base]).map((m) => {
                    const c = m.clone();
                    c.transparent = true;
                    c.opacity = ghostOpacity;
                    c.depthWrite = false;
                    return c;
                });
                const im = new THREE.InstancedMesh(geo, Array.isArray(base) ? mats : mats[0], positions.length);
                im.renderOrder = 10;
                for (let i = 0; i < positions.length; i++) {
                    m4.makeTranslation(positions[i][0] + 0.5, positions[i][1] + 0.5, positions[i][2] + 0.5);
                    im.setMatrixAt(i, m4);
                }
                im.instanceMatrix.needsUpdate = true;
                im.userData.blockId = bid;
                im.userData.ghost = true;
                gGhostVox.add(im);
            }
        }
        if (!running) renderOnce();
    }

    /**
     * P16 世界坐标切片：null 关闭；否则 {axis:'x'|'y'|'z', index:世界格坐标}
     * 实体+虚影同切（切片层恒显示、隐藏面向相机一侧）；相机越面自动翻转。
     */
    function setWorldSlice(c) {
        const next = (c && c.axis && isFinite(c.index))
            ? { axis: c.axis, index: Math.trunc(c.index) } : null;
        const same = (next === null && worldSlice === null)
            || (next && worldSlice && next.axis === worldSlice.axis && next.index === worldSlice.index);
        if (same) return;
        trace('setWorldSlice ' + (next ? next.axis + '=' + next.index : 'null'));
        worldSlice = next;
        if (next) worldSliceSide = worldSliceCamSide(next.axis);
        return rebuild(); // 实体也要切 → 走整构建（UI 侧防抖）
    }
    function getWorldSlice() { return worldSlice ? Object.assign({}, worldSlice, { side: worldSliceSide }) : null; }

    // ===== 显隐控制 =====
    let layers = { fills: true, jigsaws: true, grid: true };
    /** @param {'fills'|'jigsaws'|'grid'} layer */
    function setLayer(layer, visible) {
        layers[layer] = visible;
        if (layer === 'fills') gFills.visible = visible;
        else if (layer === 'jigsaws') gJigsaws.visible = visible;
        else if (layer === 'grid') grid.visible = visible;
        if (!running) renderOnce();
    }
    function setPieceVisible(pieceName, visible) {
        const entry = pieceMeshes.get(pieceName);
        if (!entry) return;
        entry.fills.forEach((m) => { m.visible = visible; });
        entry.jigsaws.forEach((m) => { m.visible = visible; });
        if (!running) renderOnce();
    }
    function getLayers() { return Object.assign({}, layers); }
    function getHiddenPieces() {
        const out = [];
        for (const [name, entry] of pieceMeshes) {
            if (entry.fills.length && !entry.fills[0].visible) out.push(name);
        }
        return out;
    }

    // ===== 体素编辑（点选 / 笔刷 / 3D 切片） =====
    /** 世界体素 → 某放置片段的局部坐标（逆旋转），越界返回 null */
    function worldToLocal(pl, wx, wy, wz) {
        const [w, h, d] = pl.piece.size || [1, 1, 1];
        const x0 = wx - pl.off[0], y0 = wy - pl.off[1], z0 = wz - pl.off[2];
        if (y0 < 0 || y0 >= h) return null;
        let x, z;
        switch (pl.rot & 3) {
            case 0: x = x0; z = z0; break;
            case 1: x = z0; z = d - 1 - x0; break;        // 逆：r1 前向 (x,z)→(d-1-z, x)
            case 2: x = w - 1 - x0; z = d - 1 - z0; break;
            default: x = w - 1 - z0; z = x0; break;       // 逆：r3 前向 (x,z)→(z, w-1-x)
        }
        if (x < 0 || x >= w || z < 0 || z >= d) return null;
        return [x, y0, z];
    }

    /** 射线拾取体素（含命中的片段/局部坐标/世界法线）。
     *  2026-09-24 修复（用户报告"右键方向反了 / Y 面不生效"）：
     *  - 命中点恰好落在网格边界上，Math.floor 的浮点结果不稳定（±ε 决定落在哪一侧）：
     *    一旦落进面【前方】的空气格，该格查不到体素 → continue → 落穿到更远处
     *    （常是背后方块的面向相机的面）→ 编辑/放置落到结构深处 = "背向"。
     *    修复：floor 前沿命中面法线（朝外）微移 EPS → 恒命中命中面背后的实体格。
     *  - 切片模式：平面拾取（射线 ∩ 切片板）→ 目标恒为切片层那格（空气格也可选中，
     *    "只编辑最外面的那个面"；空气也可左键编辑）。切片模式下射线不命中切片板 → null，
     *    不回退 3D 拾取（否则又穿到切面背后去了）。 */
    const raycaster = new THREE.Raycaster();
    const PICK_EPS = 0.001;
    function pickVoxel(clientX, clientY) {
        const rect = canvas.getBoundingClientRect();
        if (!rect.width || !rect.height) return null;
        const nx = ((clientX - rect.left) / rect.width) * 2 - 1;
        const ny = -((clientY - rect.top) / rect.height) * 2 + 1;
        raycaster.setFromCamera(new THREE.Vector2(nx, ny), camera);

        // —— 切片模式（单片 rot=0 才启用剖切渲染）：平面拾取 ——
        if (slice && lastPlacements.length === 1 && !lastPlacements[0].rot) {
            const pl = lastPlacements[0];
            const [w, h, d] = pl.piece.size || [1, 1, 1];
            const ai = slice.axis === 'x' ? 0 : slice.axis === 'y' ? 1 : 2;
            const k = Math.trunc(slice.index || 0);
            const dim = ai === 0 ? w : ai === 1 ? h : d;
            if (k >= 0 && k < dim) {
                const off = pl.off;
                const camC = camera.position[ai === 0 ? 'x' : ai === 1 ? 'y' : 'z'];
                // 切片板（局部 c=k 那一层）的相机侧边界面（世界坐标沿轴）
                const nA = (camC - (off[ai] + k + 0.5)) >= 0 ? 1 : -1;
                const planeC = off[ai] + k + (nA === 1 ? 1 : 0);
                const D = raycaster.ray.direction;
                const dA = D[ai === 0 ? 'x' : ai === 1 ? 'y' : 'z'];
                if (Math.abs(dA) > 1e-8) {
                    const O = camera.position;
                    const t = (planeC - O[ai === 0 ? 'x' : ai === 1 ? 'y' : 'z']) / dA;
                    if (t > 0) {
                        const px = O.x + D.x * t, py = O.y + D.y * t, pz = O.z + D.z * t;
                        const cell = [
                            Math.floor(px - (ai === 0 ? nA * PICK_EPS : 0)),
                            Math.floor(py - (ai === 1 ? nA * PICK_EPS : 0)),
                            Math.floor(pz - (ai === 2 ? nA * PICK_EPS : 0)),
                        ];
                        const local = [cell[0] - off[0], cell[1] - off[1], cell[2] - off[2]];
                        if (local[0] >= 0 && local[0] < w && local[1] >= 0 && local[1] < h && local[2] >= 0 && local[2] < d) {
                            const vox = editMaps.get(pl.name) || Voxel.expandPiece(pl.piece);
                            const bid = vox.get(local[0] + ',' + local[1] + ',' + local[2]) || null;
                            const face = [0, 0, 0];
                            face[ai] = nA;
                            return { piece: pl.name, local, world: cell, blockId: bid, face, placement: pl };
                        }
                    }
                }
                return null; // 射线平行切片板 / 未穿入 / 出界 → 切片模式只认切面
            }
        }

        // —— 3D 模式：面拾取（命中面背后的实体格，普通 3D 实体方块逻辑）——
        const targets = gFills.children.filter((c) => c.isInstancedMesh);
        const hits = raycaster.intersectObjects(targets, false);
        for (const h of hits) {
            if (!h.instanceId || h.object.visible === false) continue;
            const n = h.face ? h.face.normal : new THREE.Vector3(0, 1, 0);
            // 沿法线微移后再 floor：命中点必落回命中面背后的格（消除边界浮点落穿）
            const wx = Math.floor(h.point.x - n.x * PICK_EPS);
            const wy = Math.floor(h.point.y - n.y * PICK_EPS);
            const wz = Math.floor(h.point.z - n.z * PICK_EPS);
            for (const pl of lastPlacements) {
                const local = worldToLocal(pl, wx, wy, wz);
                if (!local) continue;
                const vox = editMaps.get(pl.name) || Voxel.expandPiece(pl.piece);
                const bid = vox.get(local[0] + ',' + local[1] + ',' + local[2]);
                if (!bid) continue;
                return {
                    piece: pl.name,
                    local: local,
                    world: [wx, wy, wz],
                    blockId: bid,
                    face: [Math.round(n.x), Math.round(n.y), Math.round(n.z)],
                    placement: pl,
                };
            }
        }
        return null;
    }

    function ensureEditMap(pieceName) {
        if (!editMaps.has(pieceName)) {
            const pl = lastPlacements.find((p) => p.name === pieceName);
            editMaps.set(pieceName, Voxel.expandPiece(pl ? pl.piece : { name: pieceName, size: [1, 1, 1] }));
        }
        return editMaps.get(pieceName);
    }

    /** 改单体素（blockId 可为 'minecraft:air' 删除，或 '#empty' 占位符） */
    function setVoxel(pieceName, local, blockId) {
        const map = ensureEditMap(pieceName);
        const key = local[0] + ',' + local[1] + ',' + local[2];
        if (!blockId || blockId === 'minecraft:air' || blockId === 'air') map.delete(key);
        else map.set(key, blockId);
        if (hooks.onChange) hooks.onChange(); // P14：未写回修改（dirty）通知
        return rebuild();
    }
    /** P15 框选填充：[min..max] 闭区间批量写格（一次重建，避免逐格 rebuild） */
    function fillBox(pieceName, min, max, blockId) {
        const map = ensureEditMap(pieceName);
        const pl = lastPlacements.find((p) => p.name === pieceName);
        const [w, h, d] = (pl && pl.piece && pl.piece.size) || [1, 1, 1];
        let n = 0;
        for (let x = Math.max(0, min[0]); x <= Math.min(w - 1, max[0]); x++)
            for (let y = Math.max(0, min[1]); y <= Math.min(h - 1, max[1]); y++)
                for (let z = Math.max(0, min[2]); z <= Math.min(d - 1, max[2]); z++) {
                    const key = x + ',' + y + ',' + z;
                    if (!blockId || blockId === 'minecraft:air' || blockId === 'air') map.delete(key);
                    else map.set(key, blockId);
                    n++;
                }
        if (hooks.onChange) hooks.onChange();
        trace('fillBox ' + pieceName + ' [' + min.join(',') + ']..[' + max.join(',') + '] = ' + n + ' 格');
        return rebuild();
    }
    // P15 框选角点预览盒（世界坐标；单片编辑模式 off/rot 取当前 placement）
    let gSelBox = null;
    const selBoxMat = new THREE.LineBasicMaterial({ color: 0xf9e2af, transparent: true, opacity: 0.95 });
    function setSelectionBox(a, b) {
        if (!a || !b) { if (gSelBox) gSelBox.visible = false; if (!running) renderOnce(); return; }
        if (!gSelBox) {
            gSelBox = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 1, 1)), selBoxMat);
            scene.add(gSelBox);
        }
        const pl = lastPlacements[0] || { off: [0, 0, 0] };
        const min = [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])];
        const max = [Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])];
        gSelBox.scale.set(max[0] - min[0] + 1.02, max[1] - min[1] + 1.02, max[2] - min[2] + 1.02);
        gSelBox.position.set(
            pl.off[0] + (min[0] + max[0]) / 2 + 0.5,
            pl.off[1] + (min[1] + max[1]) / 2 + 0.5,
            pl.off[2] + (min[2] + max[2]) / 2 + 0.5
        );
        gSelBox.visible = true;
        if (!running) renderOnce();
    }

    /**
     * P12 笔刷落点（一次一格）：
     *  - 'replace'（左键）：命中格本身 —— 直接替换你指着的方块（切片模式=切面那格）
     *  - 'place'（右键）：命中面的相邻格 —— MC 传统放置逻辑
     * @returns {number[][]} 局部坐标列表（0 或 1 格）
     */
    /** 'place' 目标格（不做边界检查；边界检查在 brushTargetCells）
     *  2026-09-24 修复：逆旋转之前 case 整体错位（switch 在 (rot+3)&3 上）→
     *  rot≠0 时相邻格方向算错。正确逆（rotPos 线性部分的逆）：
     *  r1 前向 (x,z)→(-z,x) ⇒ 逆 (a,b)→(b,-a)；r2 (a,b)→(-a,-b)；r3 前向 (x,z)→(z,-x) ⇒ 逆 (a,b)→(-b,a) */
    function brushPlaceCell(pick) {
        if (!pick) return null;
        const pl = pick.placement;
        const [nx, ny, nz] = pick.face;
        const invVec = (vx, vz) => {
            switch (pl.rot & 3) {
                case 1: return [vz, -vx];
                case 2: return [-vx, -vz];
                case 3: return [-vz, vx];
                default: return [vx, vz];
            }
        };
        const [lnx, lnz] = invVec(nx, nz);
        const [ax, ay, az] = pick.local;
        return [ax + lnx, ay + ny, az + lnz];
    }
    function brushTargetCells(pick, action) {
        if (!pick) return [];
        const pl = pick.placement;
        const [w, h, d] = pl.piece.size || [1, 1, 1];
        const inB = (lx, ly, lz) => lx >= 0 && ly >= 0 && lz >= 0 && lx < w && ly < h && lz < d;
        if (action === 'replace') {
            const [lx, ly, lz] = pick.local;
            return inB(lx, ly, lz) ? [[lx, ly, lz]] : [];
        }
        // 'place'：命中面的面向侧相邻格（MC 放置逻辑）
        // P14：切片模式下只允许编辑切面本身 —— 相邻格若出切面（Y 面右键等）→ 不放置（悬停红标）
        const t = brushPlaceCell(pick);
        if (!t || !inB(t[0], t[1], t[2])) return [];
        if (slice) {
            const ai = slice.axis === 'x' ? 0 : slice.axis === 'y' ? 1 : 2;
            if (t[ai] !== slice.index) return [];
        }
        return [t];
    }

    /**
     * P12 笔刷：左键 'replace'=命中格；右键 'place'=命中面相邻格（一次一格，无大笔刷）
     * @param {Object} pick pickVoxel 的结果
     * @param {string} blockId 'minecraft:air' 删除；'#empty' 占位；其他=放置该方块
     * @param {string} action 'replace' | 'place'（默认 'replace'）
     */
    function brushVoxel(pick, blockId, action) {
        if (!pick) return Promise.resolve();
        const map = ensureEditMap(pick.placement.name);
        for (const [lx, ly, lz] of brushTargetCells(pick, action || 'replace')) {
            const key = lx + ',' + ly + ',' + lz;
            if (!blockId || blockId === 'minecraft:air' || blockId === 'air') map.delete(key);
            else map.set(key, blockId);
        }
        if (hooks.onChange) hooks.onChange(); // P14：未写回修改（dirty）通知
        return rebuild();
    }

    // ===== P12 悬停高亮：实时显示左键落点（命中格，白线框）与右键落点（相邻格，工具色半透明）=====
    const gHover = new THREE.Group();
    scene.add(gHover);
    const hoverBoxGeo = new THREE.BoxGeometry(1.02, 1.02, 1.02);
    const hoverHitMat = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.95 });
    const hoverPlaceMat = new THREE.MeshBasicMaterial({ color: 0xa6e3a1, transparent: true, opacity: 0.4, depthWrite: false });
    const hoverHit = new THREE.LineSegments(new THREE.EdgesGeometry(hoverBoxGeo), hoverHitMat);
    const hoverPlace = new THREE.Mesh(hoverBoxGeo, hoverPlaceMat);
    hoverHit.visible = false;
    hoverPlace.visible = false;
    gHover.add(hoverHit, hoverPlace);
    let hoverEnabled = false;
    let hoverInfo = null;      // 调试/E2E：{hit:[x,y,z], place:[x,y,z]|null, placeOut?:[x,y,z]}
    let hoverToolColor = 0xa6e3a1;
    let lastHoverKey = '';
    let hoverEvt = null, hoverTimer = null, hoverTs = 0;
    function setHoverEnabled(on) {
        hoverEnabled = !!on;
        if (!hoverEnabled) clearHover();
    }
    function setHoverColor(hex) {
        hoverToolColor = hex;
        hoverPlaceMat.color.setHex(hex);
    }
    /** 局部坐标 → 世界中心（与 buildPieceMeshes/rotPos 同算法；worldToLocal 的逆） */
    function localToWorld(pl, local) {
        const [w, h, d] = pl.piece.size || [1, 1, 1];
        const [rx, rz] = rotPos(local[0], local[2], w, d, pl.rot || 0);
        return [pl.off[0] + rx + 0.5, pl.off[1] + local[1] + 0.5, pl.off[2] + rz + 0.5];
    }
    function updateHover(clientX, clientY) {
        if (!hoverEnabled) return;
        const pick = pickVoxel(clientX, clientY);
        if (!pick) { clearHover(); return; }
        const key = pick.piece + '|' + pick.local.join(',') + '|' + pick.face.join(',');
        if (key === lastHoverKey) return;
        lastHoverKey = key;
        const hw = localToWorld(pick.placement, pick.local);
        hoverHit.position.set(hw[0], hw[1], hw[2]);
        hoverHit.visible = true;
        const placeCells = brushTargetCells(pick, 'place');
        if (placeCells.length) {
            const wp = localToWorld(pick.placement, placeCells[0]);
            hoverPlace.position.set(wp[0], wp[1], wp[2]);
            hoverPlace.visible = true;
            hoverPlaceMat.color.setHex(hoverToolColor);
            hoverInfo = { hit: pick.local.slice(), place: placeCells[0].slice() };
        } else {
            // 2026-09-24：'place' 目标出界（超片段尺寸）→ 在"应放置处"显示红色标记，
            // 不再静默无效果（用户报告"Y 方向右键不生效"多为顶/底面出界）
            const out = brushPlaceCell(pick);
            if (out) {
                const wp = localToWorld(pick.placement, out);
                hoverPlace.position.set(wp[0], wp[1], wp[2]);
                hoverPlace.visible = true;
                hoverPlaceMat.color.setHex(0xf38ba8);
                hoverInfo = { hit: pick.local.slice(), place: null, placeOut: out.slice() };
            } else {
                hoverPlace.visible = false;
                hoverInfo = { hit: pick.local.slice(), place: null };
            }
        }
    }
    function clearHover() {
        hoverHit.visible = false;
        hoverPlace.visible = false;
        hoverPlaceMat.color.setHex(hoverToolColor);
        hoverInfo = null;
        lastHoverKey = '';
    }
    // pointermove 节流（~30ms）：pickVoxel 要对全部实例做射线测试，逐帧跑会卡
    function scheduleHover(x, y) {
        const now = performance.now();
        if (now - hoverTs >= 30) {
            hoverTs = now;
            updateHover(x, y);
            renderNow();
        } else {
            hoverEvt = { x, y };
            if (hoverTimer == null) {
                hoverTimer = setTimeout(() => {
                    hoverTimer = null;
                    hoverTs = performance.now();
                    if (hoverEvt) { updateHover(hoverEvt.x, hoverEvt.y); renderNow(); hoverEvt = null; }
                }, 32);
            }
        }
    }
    canvas.addEventListener('pointermove', (e) => {
        if (dragging || !hoverEnabled) return;
        scheduleHover(e.clientX, e.clientY);
    });
    canvas.addEventListener('pointerleave', () => { if (hoverEnabled) { clearHover(); renderNow(); } });

    /** P12 WASD 平移：相机相对的地面平移（fwd +1=W/-1=S；right +1=D/-1=A；角度不变） */
    function panBy(fwd, right) {
        const th = orbit.theta;
        const sp = Math.sin(orbit.phi);
        const fx = -sp * Math.sin(th), fz = -sp * Math.cos(th); // 相机前向的水平分量
        const fl = Math.hypot(fx, fz) || 1;
        const rx = Math.cos(th), rz = -Math.sin(th);            // 相机右向（与 applyDrag 一致）
        const s = Math.max(0.5, orbit.radius * 0.0016);
        orbit.target.x += (fx / fl) * fwd * s + rx * right * s;
        orbit.target.z += (fz / fl) * fwd * s + rz * right * s;
        orbit.target.y = Math.max(0, Math.min(200, orbit.target.y));
        renderNow();
    }

    /** 3D 切片：null 关闭；否则 {axis, dir(1=隐 axis>threshold, -1=隐 axis<threshold), threshold} */
    function setClip(c) {
        clip = c || null;
        return rebuild();
    }
    function getClip() { return clip; }

    /**
     * P9 编辑切片（剖切面）：null 关闭；否则 {axis:'x'|'y'|'z', index:number}
     * 隐藏面向相机一侧、展示另一侧 + 切片层本身；笔刷被限制在切片层内。
     */
    function setSlice(c) {
        const next = (c && c.axis && isFinite(c.index))
            ? { axis: c.axis, index: Math.trunc(c.index) } : null;
        // P14 修复：slice 未变化 → 不重建（原实现无条件 rebuild → 与并发 setPiece 竞态，
        // 用旧 lastBuild 覆盖新片段场景，表现为"切片段后还是同一个模型"）
        const same = (next === null && slice === null)
            || (next && slice && next.axis === slice.axis && next.index === slice.index);
        if (same) return;
        trace('setSlice ' + (next ? next.axis + '=' + next.index : 'null') + '（原 ' + (slice ? slice.axis + '=' + slice.index : 'null') + '）');
        slice = next;
        if (next) sliceSide = currentCamSide(next.axis);
        return rebuild();
    }
    function getSlice() { return slice ? Object.assign({}, slice) : null; }
    /** 相机在 +axis 侧 = +1，-axis 侧 = -1（0 时按 +1） */
    function currentCamSide(axis) {
        const ai = axis === 'x' ? 'x' : axis === 'y' ? 'y' : 'z';
        return (camera.position[ai] - orbit.target[ai]) >= 0 ? 1 : -1;
    }
    /** 拖拽/循环中调用：相机越过分界面 → 翻转剖切方向并重建（无翻转时零成本） */
    function checkSliceFlip() {
        if (!slice) return;
        const s = currentCamSide(slice.axis);
        if (s !== sliceSide) {
            sliceSide = s;
            sliceFlipPending = true;
            if (!rebuilding) rebuild();
        }
    }

    /** 某片段的当前体素表（编辑表或定义展开） */
    function getPieceVoxelMap(pieceName) {
        const pl = lastPlacements.find((p) => p.name === pieceName);
        if (!pl) return new Map();
        return editMaps.get(pieceName) || Voxel.expandPiece(pl.piece);
    }
    function clearEdits(pieceName) {
        if (pieceName) editMaps.delete(pieceName);
        else editMaps.clear();
        if (hooks.onChange) hooks.onChange(); // P14：dirty 通知（写回后清零）
        return rebuild();
    }
    function getPlacements() { return lastPlacements; }

    // ===== 交互 =====
    // 真实鼠标拖拽修复（2026-07 轮）：
    //   - pointermove/up 改挂 window【捕获阶段】——指针移出画布/捕获丢失也不中断
    //   - 不再依赖 setPointerCapture（部分 Windows/Electron 组合下行为不稳）
    //   - 经典 mouse 事件兜底（pointer 事件异常时仍可拖拽）
    //   - HUD 实时显示轨道参数 + 事件计数（诊断：拖拽时 → 计数不涨 = 事件未到达）
    let dragging = false, panning = false, lastX = 0, lastY = 0, activePointer = null;
    // 交互模型（2026-09-24 定稿）——不复合：
    //   移动工具：左键拖拽=旋转、右键/Shift拖拽=平移、滚轮=缩放（纯相机）
    //   笔刷/擦除/占位：左键=画（单击替换命中格 / 拖拽沿路径连续替换）、
    //                   右键单击=命中面相邻格放置、中键单击=编辑菜单；左键不带动相机
    let orbitOnLeft = true;   // 移动工具=true；笔刷类=false（由 pieceEditor.setTool 切换）
    function setOrbitOnLeft(on) { orbitOnLeft = !!on; }
    function dragAllowed(e) {
        const isPan = (e.buttons & 2) || e.shiftKey || e.button === 2;
        return isPan || (e.button === 0 && !e.shiftKey && orbitOnLeft);
    }
    let evDown = 0, evMove = 0;

    // HUD（画布左下角浮层）
    const hud = document.createElement('div');
    hud.className = 'struct-hud';
    hud.style.cssText = 'position:absolute;left:8px;bottom:8px;font:11px/1.5 ui-monospace,Consolas,monospace;'
        + 'color:#cdd6f4;background:rgba(30,30,46,.78);border:1px solid #45475a;'
        + 'padding:4px 8px;border-radius:6px;pointer-events:none;display:none;white-space:pre;z-index:5;';
    const wrap = canvas.parentElement;
    if (wrap) { wrap.style.position = 'relative'; wrap.appendChild(hud); }

    // P14 方向罗盘 → 3D 视图立方体（MC 约定：北=-Z 南=+Z 西=-X 东=+X 上=+Y 下=-Y）。
    // 直接用主场景坐标系：cubeMesh 是主相机的子节点（零重复业务逻辑，不存在"小场景与主场景
    // 坐标系不一致"的可能），渲染在画布右上角，depthTest 关闭永远置顶。
    // P15 修正：子节点若局部旋转=单位矩阵会被锁死在相机系（转视角时永远不转）→ 局部矩阵写入
    // R_cam^T（相机世界旋转的逆，由轨道 θ/φ 解析推出，绕开 three Quaternion 依赖），立方体恒
    // 与世界轴对齐：东/西/南/北/上/下面永远指向世界对应方向。
    let cubeMesh = null;
    const cubeTexs = [];
    {
        // 面贴图：128×128 画布字（BoxGeometry 材质组序 +X,-X,+Y,-Y,+Z,-Z）
        const faceLabel = (ch, color) => {
            const c = document.createElement('canvas');
            c.width = 128; c.height = 128;
            const g = c.getContext('2d');
            g.fillStyle = 'rgba(46,46,66,0.88)';
            g.fillRect(0, 0, 128, 128);
            g.strokeStyle = '#585b70'; g.lineWidth = 6;
            g.strokeRect(3, 3, 122, 122);
            g.fillStyle = color;
            g.font = '700 72px "Segoe UI","Microsoft YaHei",sans-serif';
            g.textAlign = 'center'; g.textBaseline = 'middle';
            g.fillText(ch, 64, 70);
            const t = new THREE.CanvasTexture(c);
            t.magFilter = THREE.NearestFilter;
            t.minFilter = THREE.LinearFilter;
            cubeTexs.push(t);
            return t;
        };
        const mats = [
            new THREE.MeshBasicMaterial({ map: faceLabel('东', '#cdd6f4'), depthTest: false }), // +X
            new THREE.MeshBasicMaterial({ map: faceLabel('西', '#cdd6f4'), depthTest: false }), // -X
            new THREE.MeshBasicMaterial({ map: faceLabel('上', '#89b4fa'), depthTest: false }), // +Y
            new THREE.MeshBasicMaterial({ map: faceLabel('下', '#7f849c'), depthTest: false }), // -Y
            new THREE.MeshBasicMaterial({ map: faceLabel('南', '#cdd6f4'), depthTest: false }), // +Z
            new THREE.MeshBasicMaterial({ map: faceLabel('北', '#f38ba8'), depthTest: false }), // -Z
        ];
        cubeMesh = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), mats);
        cubeMesh.renderOrder = 999;
        cubeMesh.frustumCulled = false;
        cubeMesh.matrixAutoUpdate = false; // 局部矩阵由 updateViewCube() 整体写入（位置+缩放+逆旋转）
        scene.add(camera);        // 相机进场景图（其子节点才会被渲染）
        camera.add(cubeMesh);     // 子节点 + 逆旋转 → 恒与世界轴对齐（见 updateViewCube）
        updateViewCube();
    }
    /**
     * 视图立方体更新（P15 引入 / P17 修正）：画布右上角位置 + 逆旋转。
     * 立方体是相机的子节点：局部矩阵必须 = 相机世界旋转的【逆】，合成后
     * matrixWorld 旋转 = R_cam·R_cam^T = I → 立方体恒与世界轴对齐（东/西/南/北/上/下
     * 永远指向世界对应方向；面向相机的一面 = 相机所看方向）。
     *
     * 相机世界旋转 R_cam 的【列】= 相机世界轴（lookAt 约定，up=+Y，θ=方位角 φ=极角）：
     *   col0 x_cam = ( cosθ, 0, -sinθ)                      （相机右）
     *   col1 y_cam = (-cosφsinθ, sinφ, -cosφcosθ)           （相机上）
     *   col2 z_cam = ( sinφsinθ, cosφ, sinφcosθ)            （相机后 = eye-target 向）
     * 局部旋转 L = R_cam^T，其【列】= R_cam 的【行】：
     *   列0 = ( ct, -cph·st, sp·st )
     *   列1 = ( 0,  sp,       cph  )
     *   列2 = ( -st, -cph·ct, sp·ct )
     * （P17 前误把 R_cam 本身写成了局部旋转 → 世界旋转 = R_cam² → 倾斜角恒为相机 2 倍，
     *   默认 30° 仰角时立方体呈 60° 歪斜、顶面几乎成边。）
     * 调用时机：applyCamera()（轨道变化）、sizeCanvas()（像素/aspect 变化）。
     */
    function updateViewCube() {
        if (!cubeMesh) return;
        const h = canvas.clientHeight || H0;
        const d = 3;                                  // 相机前方距离
        const halfH = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)) * d;
        const halfW = halfH * camera.aspect;
        const px2world = (2 * halfH) / h;             // 画布高 = 2*halfH 世界单位
        const s = 58 * px2world, margin = 10 * px2world;
        const px = halfW - margin - s / 2, py = halfH - margin - s / 2;
        const st = Math.sin(orbit.theta), ct = Math.cos(orbit.theta);
        const sp = Math.sin(orbit.phi), cph = Math.cos(orbit.phi);
        // 4×4 列主序：第 i 列 = R_cam 第 i 行 × s（即 R_cam^T 的列）
        const e = cubeMesh.matrix.elements;
        e[0] = ct * s;         e[1] = -cph * st * s;  e[2] = sp * st * s;
        e[4] = 0;              e[5] = sp * s;         e[6] = cph * s;
        e[8] = -st * s;        e[9] = -cph * ct * s;  e[10] = sp * ct * s;
        e[12] = px;            e[13] = py;            e[14] = -d;            e[15] = 1;
        cubeMesh.matrixWorldNeedsUpdate = true;
    }
    function updateHud() {
        if (!hud) return;
        hud.style.display = 'block';
        const mode = panning ? '平移' : '旋转';
        hud.textContent =
            `θ ${orbit.theta.toFixed(2)}  φ ${orbit.phi.toFixed(2)}  r ${orbit.radius.toFixed(0)}${dragging ? '  · 拖拽中(' + mode + ')' : ''}\n`
            + `事件 ↓${evDown} →${evMove}`;
    }

    canvas.addEventListener('contextmenu', (e) => e.preventDefault());

    function applyDrag(dx, dy) {
        if (panning) {
            // 平移：沿相机 right / screenUp 移动 target
            const th = orbit.theta;
            const right = new THREE.Vector3(Math.cos(th), 0, -Math.sin(th));
            const sp = Math.sin(orbit.phi), cp = Math.cos(orbit.phi);
            const fwd = new THREE.Vector3(-sp * Math.sin(th), -cp, -sp * Math.cos(th));
            const up = new THREE.Vector3().crossVectors(right, fwd).normalize();
            const s = orbit.radius * 0.0016;
            orbit.target.addScaledVector(right, -dx * s);
            orbit.target.addScaledVector(up, dy * s);
            orbit.target.y = Math.max(0, Math.min(200, orbit.target.y));
        } else {
            orbit.theta -= dx * 0.006;
            orbit.phi -= dy * 0.006;
            orbit.phi = Math.max(0.05, Math.min(Math.PI / 2 - 0.02, orbit.phi));
        }
        checkSliceFlip();
        checkWorldSliceFlip();
        renderNow();
    }

    // pointer 事件：down 在画布，move/up 在 window（捕获阶段）
    canvas.addEventListener('pointerdown', (e) => {
        if (activePointer !== null) return;
        if (!dragAllowed(e)) return; // 笔刷/擦除工具下左键不启动拖拽（写入与视角移动解耦）
        activePointer = e.pointerId;
        dragging = true;
        panning = (e.buttons & 2) || e.shiftKey || e.button === 2; // 右键 或 Shift+左键
        lastX = e.clientX; lastY = e.clientY;
        evDown++;
        console.info('[3D] pointerdown button=' + e.button + ' at', Math.round(e.clientX), Math.round(e.clientY));
        updateHud();
    });

    window.addEventListener('pointermove', (e) => {
        if (!dragging || e.pointerId !== activePointer) return;
        evMove++;
        const dx = e.clientX - lastX, dy = e.clientY - lastY;
        lastX = e.clientX; lastY = e.clientY;
        if (dx || dy) { applyDrag(dx, dy); updateHud(); }
    }, true);

    function endDrag(e) {
        if (activePointer === null) return;
        if (e && typeof e.pointerId === 'number' && e.pointerId !== activePointer) return;
        dragging = false;
        panning = false;
        activePointer = null;
        updateHud();
    }
    window.addEventListener('pointerup', endDrag, true);
    window.addEventListener('pointercancel', endDrag, true);

    // 经典 mouse 事件兜底（Chromium 下与 pointer 事件并存，用 activePointer 去重）
    canvas.addEventListener('mousedown', (e) => {
        if (activePointer !== null) return; // pointer 事件已接管
        if (!dragAllowed(e)) return; // 与 pointerdown 同门控
        activePointer = '__mouse__';
        dragging = true;
        panning = (e.buttons & 2) || e.shiftKey;
        lastX = e.clientX; lastY = e.clientY;
        evDown++;
        console.info('[3D] mousedown button=' + e.button + ' at', Math.round(e.clientX), Math.round(e.clientY));
        updateHud();
    });
    window.addEventListener('mousemove', (e) => {
        if (!dragging || activePointer !== '__mouse__') return;
        evMove++;
        const dx = e.clientX - lastX, dy = e.clientY - lastY;
        lastX = e.clientX; lastY = e.clientY;
        if (dx || dy) { applyDrag(dx, dy); updateHud(); }
    }, true);
    window.addEventListener('mouseup', (e) => {
        if (activePointer === '__mouse__') endDrag(e);
    }, true);

    // P15 滚轮模式：'zoom'（主窗口默认）= 滚轮缩放；'slots'（片段编辑器）= 普通滚轮回调
    // hooks.onWheelSlot(delta)（切槽），仅 Ctrl+滚轮 保留缩放
    let wheelMode = 'zoom';
    function setWheelMode(m) { wheelMode = m === 'slots' ? 'slots' : 'zoom'; }
    canvas.addEventListener('wheel', (e) => {
        e.preventDefault();
        if (wheelMode === 'slots' && !e.ctrlKey) {
            if (hooks.onWheelSlot) hooks.onWheelSlot(e.deltaY > 0 ? 1 : -1);
            return;
        }
        const f = e.deltaY > 0 ? 1.1 : 0.9;
        orbit.radius = Math.max(2, Math.min(2000, orbit.radius * f));
        checkSliceFlip();
        checkWorldSliceFlip();
        updateHud();
        renderNow();
    }, { passive: false });

    /** 重置到固定角落视角（保留当前取景半径/目标） */
    function resetView() {
        orbit.theta = Math.PI / 4;
        orbit.phi = Math.PI / 3;
        applyCamera();
        renderNow();
    }

    // ===== 渲染循环 =====
    // 2026-09-23 修复（用户报告"HUD 有反馈但 3D 不动"）：
    // 页面 hidden（窗口最小化/被遮挡）时 Chromium 完全不触发 rAF →
    // 渲染循环一帧都不跑，而 running=true 又跳过所有按需渲染 → 画面永久冻结。
    // 对策：① 相机每帧跟随轨道参数；② 交互（拖拽/滚轮/重置）按需立即渲染；
    //       ③ 看门狗：循环停摆 >1s 时兜底渲染并尝试重启 rAF 链；
    //       ④ visibilitychange 恢复时重启循环。
    let running = false;
    let autoRotate = false;
    let lastFrameTime = 0;
    function setAutoRotate(on) { autoRotate = !!on; }

    function renderOnce() {
        sizeCanvas();
        applyCamera();
        renderer.render(scene, camera); // 视图立方体 = 相机子节点，随主场景一次渲染（无独立更新路径）
        lastFrameTime = performance.now();
    }
    let lastManualRender = 0;
    /** 交互触发的按需渲染（rAF 停摆时也保证画面即时响应） */
    function renderNow() {
        const t = performance.now();
        if (t - lastManualRender < 16) return;
        lastManualRender = t;
        renderOnce();
    }
    let hudFrame = 0;
    function loop() {
        if (!running) return;
        if (autoRotate) orbit.theta += 0.0035;
        applyCamera();
        checkSliceFlip();
        checkWorldSliceFlip(); // P9：自动旋转等移动相机时，越过分界面自动翻转剖切
        sizeCanvas();
        renderer.render(scene, camera);
        lastFrameTime = performance.now();
        if (++hudFrame % 30 === 0) updateHud();
        requestAnimationFrame(loop);
    }
    function start() {
        sizeCanvas();
        updateHud();
        if (!running) running = true;
        // rAF 链可能已死（页面曾长期 hidden）→ 只在停摆时补发，避免双链
        if (performance.now() - lastFrameTime > 500) requestAnimationFrame(loop);
    }
    // 看门狗：hidden 页面里 rAF 不触发、setInterval 仍会跑（节流到 ~1Hz 也够）
    setInterval(() => {
        if (running && performance.now() - lastFrameTime > 1000) {
            renderOnce();
            requestAnimationFrame(loop);
        }
    }, 500);
    if (typeof document !== 'undefined') {
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden && running && performance.now() - lastFrameTime > 300) {
                requestAnimationFrame(loop);
            }
        });
    }
    function stop() {
        running = false;
        if (hud) hud.style.display = 'none';
    }
    // P11：连续渲染循环开关。false = 按需渲染（仅交互/编辑时渲染，不占主线程，
    // 适合编辑器窗口：搜索输入框/右键菜单不再与 60fps WebGL 渲染抢帧）。
    // 按需模式下 running=false → 各 setXxx 的 `if (!running) renderOnce()` 转为强制渲染，
    // 加上 rebuild 末尾的 renderOnce()，编辑结果即时可见。
    function setContinuous(on) {
        if (on) {
            start();
        } else {
            running = false;
            if (hud) updateHud();
            renderOnce();
        }
    }

    function dispose() {
        stop();
        clearGroups();
        if (hoverTimer != null) { clearTimeout(hoverTimer); hoverTimer = null; } // P12
        hoverBoxGeo.dispose(); hoverHit.geometry.dispose(); hoverHitMat.dispose(); hoverPlaceMat.dispose();
        for (const set of texSets.values()) {
            [set.top, set.side, set.bottom].forEach((t) => { if (t) t.dispose(); });
        }
        texSets.clear();
        for (const arr of matSets.values()) arr.forEach((m) => m.dispose());
        matSets.clear();
        for (const t of texByUrl.values()) t.dispose();
        texByUrl.clear();
        texMats.clear();
        for (const g of specGeos.values()) g.geometry.dispose();
        specGeos.clear();
        sharedMats.clear();
        faceCache.clear();
        facePending.clear();
        specCache.clear();
        specPending.clear();
        editMaps.clear();
        for (const c of [...gGhostVox.children]) { // P16 虚影材质（克隆的，非共享）
            if (c.material && !sharedMats.has(c.material)) {
                (Array.isArray(c.material) ? c.material : [c.material]).forEach((m) => m.dispose());
            }
        }
        ghostPlacements = [];
        if (gSelBox) { gSelBox.geometry.dispose(); selBoxMat.dispose(); gSelBox = null; } // P15 框选预览盒
        for (const t of cubeTexs) t.dispose(); // P14 视图立方体面贴图
        cubeTexs.length = 0;
        if (cubeMesh) {
            cubeMesh.parent && cubeMesh.parent.remove(cubeMesh);
            cubeMesh.geometry.dispose();
            cubeMesh.material.forEach((m) => m.dispose());
            cubeMesh = null;
        }
        unitBox.dispose();
        if (renderer && renderer.forceContextLoss) renderer.forceContextLoss();
        canvas._structRenderer = null;
    }

    /** 渲染/贴图统计（E2E 与诊断用） */
    function debugStats() {
        let instanceMeshes = 0, voxels = 0;
        for (const c of gFills.children) {
            if (c.isInstancedMesh) { instanceMeshes++; voxels += c.count; }
        }
        let textured = 0, missing = 0;
        for (const v of faceCache.values()) v ? textured++ : missing++;
        let specBlocks = 0, specVoxels = 0;
        for (const c of gFills.children) {
            if (c.isInstancedMesh && c.geometry !== unitBox) { specBlocks++; specVoxels += c.count; }
        }
        let loadedTex = 0;
        for (const set of texSets.values()) {
            for (const t of [set.top, set.side, set.bottom]) {
                if (t && t.image && (t.image.width || 0) > 0) loadedTex++;
            }
        }
        return {
            pieces: pieceMeshes.size,
            pieceNames: [...pieceMeshes.keys()], // P14 调试：当前场景渲染的片名
            instanceMeshes,
            voxels,
            texturedBlocks: textured,
            missingBlocks: missing,
            loadedTextures: loadedTex,
            specBlocks, // P14：走外观链路（真实模型几何）的 blockId 数
            specVoxels,
            // 立方体 = 相机子节点且自身零旋转 → 朝向由 orbit 唯一决定（绕开本地 three 精简版的 Quaternion 依赖）
            compass: cubeMesh ? {
                cube: true,
                theta: +orbit.theta.toFixed(4),
                phi: +orbit.phi.toFixed(4),
                world: cubeMesh.matrixWorld.elements.slice(0, 16), // P17：世界矩阵（E2E 断言旋转部分≈I）
            } : null,
            renderCalls: renderer ? renderer.info.render.calls : 0,
            triangles: renderer ? renderer.info.render.triangles : 0,
            editedPieces: [...editMaps.keys()],
            clip,
            slice,
            sliceSide,
            worldSlice, // P16 世界坐标切片（含 side）
            ghostPlacements: ghostPlacements.length, // P16 虚影放置数
            ghostVoxels: gGhostVox.children.reduce((n, c) => n + (c.isInstancedMesh ? c.count : 0), 0), // P16 虚影体素数
            ghosts: gGhosts.children.length,
            // 相机实际位置（拖拽验证用：应随 orbit 变化）
            camera: camera ? { x: +camera.position.x.toFixed(2), y: +camera.position.y.toFixed(2), z: +camera.position.z.toFixed(2) } : null,
            hover: hoverInfo, // P12 悬停落点（命中格 + 相邻格）
            running,
            hud: hud ? { display: hud.style.display, text: hud.textContent } : null,
        };
    }

    /** 当前轨道状态（测试/调试用） */
    function getOrbit() {
        return { theta: orbit.theta, phi: orbit.phi, radius: orbit.radius,
                 target: { x: orbit.target.x, y: orbit.target.y, z: orbit.target.z } };
    }
    /** 直接设置轨道角度（测试/调试用；E2E 罗盘旋转一致性） */
    function setOrbit(theta, phi) {
        orbit.theta = theta;
        if (phi != null) orbit.phi = Math.max(0.05, Math.min(Math.PI - 0.05, phi));
        renderOnce();
    }

    /** 2026-09-24：世界坐标 → 画布 client 坐标（E2E 精确瞄准 / 未来 UI 用） */
    function projectToScreen(x, y, z) {
        // 注意：投影前必须先更新相机矩阵（按需渲染模式下相机可能已移动但未 render）
        scene.updateMatrixWorld(true);
        const v = new THREE.Vector3(x, y, z).project(camera);
        const rect = canvas.getBoundingClientRect();
        return { x: rect.left + (v.x * 0.5 + 0.5) * rect.width, y: rect.top + (-v.y * 0.5 + 0.5) * rect.height };
    }

    return {
        setBlueprint,
        setPlacements,
        setPiece, // P9 单片编辑模式
        rebuild,
        setLayer,
        setPieceVisible,
        getLayers,
        getHiddenPieces,
        getOrbit,
        setOrbit,
        resetView,
        setAutoRotate,
        setContinuous, // P11 编辑器窗口按需渲染
        setOrbitOnLeft, // P11 移动/笔刷工具解耦（P12 保留：不复合）
        setHoverEnabled, // P12 悬停高亮开关
        setHoverColor, // P12 悬停右键落点颜色（随工具）
        panBy, // P12 WASD 平移（相机相对地面平移，角度不变）
        setWheelMode, // P15 'zoom'|'slots'（编辑器：普通滚轮切槽，Ctrl+滚轮缩放）
        start,
        stop,
        dispose,
        // 体素编辑（P3/P12）
        pickVoxel,
        setVoxel,
        fillBox, // P15 框选批量填充
        setSelectionBox, // P15 框选角点预览盒（null 隐藏）
        brushVoxel, // P12: (pick, blockId, 'replace'|'place')
        brushTargetCells,
        brushPlaceCell, // 'place' 目标（不检查边界；悬停红标用）
        projectToScreen, // 世界坐标 → 画布 client 坐标
        setClip,
        getClip,
        setSlice, // P9 编辑切片（剖切面）
        getSlice,
        getPieceVoxelMap,
        clearEdits,
        getPlacements,
        setGhosts,
        setGhostPlacements, // P16 jigsaw 虚影拼合（半透明体素层）
        getGhostPlacements, // P16
        setWorldSlice, // P16 世界坐标切片（实体+虚影同切）
        getWorldSlice, // P16
        debugStats,
    };
}
