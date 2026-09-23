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

export function createStructurePreview(canvas) {
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
    scene.add(gFills, gJigsaws, gGhosts);

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
    }

    // ===== 场景构建（方块级体素 + 真实贴图） =====
    let pieceMeshes = new Map(); // pieceName → { fills:[], jigsaws:[] }
    const editMaps = new Map();  // pieceName → 编辑过的局部体素表（P3 点选/笔刷；无 = 用片段定义）
    let clip = null;             // 3D 切片 {axis:'x'|'y'|'z', dir:-1|1, threshold:number}（P3）
    let slice = null;            // P9 编辑切片 {axis:'x'|'y'|'z', index:number}（剖切面：隐藏面向相机一侧，仅 rot=0 单片模式）
    let sliceSide = 1;           // 当前剖切方向（相机所在侧），相机越过分界面时翻转并重建

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

    /** 预取一组片段用到的全部贴图（构建前 await） */
    async function ensureFacesFor(pieces) {
        const ids = new Set();
        for (const pc of pieces || []) {
            for (const bf of pc.boxFills || []) if (bf && bf.block) ids.add(bf.block);
            for (const fb of pc.fillBlocks || []) if (fb && fb.block) ids.add(fb.block);
        }
        await Promise.all([...ids].map((id) => fetchFaces(id).catch(() => null)));
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
        } else if (faces && (faces.top || faces.side || faces.bottom)) {
            const t = getTextures(blockId, faces);
            const side = t.side || t.top, top = t.top || t.side, bottom = t.bottom || side || t.top;
            const mk = (tex) => new THREE.MeshLambertMaterial({ map: tex || null, color: tex ? 0xffffff : blockColor(blockId) });
            arr = [mk(side), mk(side), mk(top), mk(bottom), mk(side), mk(side)];
        } else {
            const m = new THREE.MeshLambertMaterial({ color: blockColor(blockId) });
            arr = [m, m, m, m, m, m];
        }
        matSets.set(blockId, arr);
        arr.forEach((m, i) => sharedMats.set('blockmat:' + blockId + ':' + i, m));
        return arr;
    }

    function clearGroups() {
        for (const g of [gFills, gJigsaws, gGhosts]) {
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
        const world = Voxel.applyClip(Voxel.worldSurface(vox, off, rot, [w, h, d]), clip);
        const groups = new Map();
        for (const [x, y, z, bid] of world) {
            if (!groups.has(bid)) groups.set(bid, []);
            groups.get(bid).push([x, y, z]);
        }
        const m4 = new THREE.Matrix4();
        for (const [bid, positions] of groups) {
            if (!positions.length) continue;
            const faces = faceCache.get(bid) || null;
            const im = new THREE.InstancedMesh(unitBox, getMats(bid, faces), positions.length);
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
     *  重建期间若切片翻转（sliceFlipPending），完成后自动补一轮 */
    let rebuilding = false;
    let sliceFlipPending = false;
    async function rebuild() {
        if (!lastBuild || rebuilding) return;
        rebuilding = true;
        try {
            for (;;) {
                sliceFlipPending = false;
                const b = lastBuild;
                const opts = { hiddenPieces: b.hidden, reframe: false };
                if (b.kind === 'place') {
                    opts.pieces = b.pieces;
                    await setPlacements(b.placements, opts);
                } else {
                    await setBlueprint(b.bp, opts);
                }
                if (!sliceFlipPending) break;
            }
        } finally {
            rebuilding = false;
        }
    }

    /**
     * 并排模式：片段沿 X 轴排开（编辑单个片段用）
     * @param {Object} bp 蓝图对象
     * @param {{hiddenPieces?:string[], reframe?:boolean}} opts
     */
    async function setBlueprint(bp, opts = {}) {
        const pieces = (bp && bp.pieces) || [];
        await ensureFacesFor(pieces);
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
        if (!piece) return;
        await setBlueprint({ pieces: [piece] }, Object.assign({ reframe: true }, opts));
    }

    /**
     * 拼合模式：按生长结果放置（预览 jigsaw 拼合后的完整建筑）
     * @param {Array} placements growStructure 的 placements
     *   [{pieceName, offset:[x,y,z], rotation:0..3, via}]
     * @param {{pieces?:Array, hiddenPieces?:string[], reframe?:boolean}} opts
     */
    async function setPlacements(placements, opts = {}) {
        const list = placements || [];
        const byName = new Map();
        for (const p of opts.pieces || []) byName.set(p.name, p);
        await ensureFacesFor(list.map((pl) => byName.get(pl.pieceName)).filter(Boolean));
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

    /** 射线拾取体素（含命中的片段/局部坐标/世界法线） */
    const raycaster = new THREE.Raycaster();
    function pickVoxel(clientX, clientY) {
        const rect = canvas.getBoundingClientRect();
        if (!rect.width || !rect.height) return null;
        const nx = ((clientX - rect.left) / rect.width) * 2 - 1;
        const ny = -((clientY - rect.top) / rect.height) * 2 + 1;
        raycaster.setFromCamera(new THREE.Vector2(nx, ny), camera);
        const targets = gFills.children.filter((c) => c.isInstancedMesh);
        const hits = raycaster.intersectObjects(targets, false);
        for (const h of hits) {
            if (!h.instanceId || h.object.visible === false) continue;
            const wx = Math.floor(h.point.x), wy = Math.floor(h.point.y), wz = Math.floor(h.point.z);
            for (const pl of lastPlacements) {
                const local = worldToLocal(pl, wx, wy, wz);
                if (!local) continue;
                const vox = editMaps.get(pl.name) || Voxel.expandPiece(pl.piece);
                const bid = vox.get(local[0] + ',' + local[1] + ',' + local[2]);
                if (!bid) continue;
                const n = h.face ? h.face.normal : new THREE.Vector3(0, 1, 0);
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
        return rebuild();
    }

    /**
     * 笔刷：以命中面为基准，在面上画 size×size 的块
     * @param {Object} pick pickVoxel 的结果
     * @param {string} blockId
     * @param {number} size 1 | 3 | 5
     */
    function brushVoxel(pick, blockId, size) {
        if (!pick) return Promise.resolve();
        const pl = pick.placement;
        const [w, h, d] = pl.piece.size || [1, 1, 1];
        // 世界法线 → 局部法线（逆旋转 = 前向 (4-r)%4 的向量版）
        // 前向向量：r1 (vx,vz)→(-vz,vx)；r2 →(-vx,-vz)；r3 →(vz,-vx)
        const [nx, ny, nz] = pick.face;
        const invVec = (vx, vz) => {
            switch ((pl.rot + 3) & 3) {
                case 1: return [-vz, vx];   // 逆 r3
                case 2: return [vz, -vx];   // 逆 r1
                case 3: return [-vx, -vz];  // 逆 r2
                default: return [vx, vz];
            }
        };
        const [lnx, lnz] = invVec(nx, nz);
        // 切平面两个轴
        let t1, t2;
        if (ny !== 0) { t1 = [1, 0, 0]; t2 = [0, 0, 1]; }
        else if (nx !== 0) { t1 = [0, 1, 0]; t2 = [0, 0, 1]; }
        else { t1 = [1, 0, 0]; t2 = [0, 1, 0]; }
        // t 轴也要旋到局部
        const toLocal = (v) => {
            const [a, b] = invVec(v[0], v[2]);
            return [a, v[1], b];
        };
        t1 = toLocal(t1); t2 = toLocal(t2);
        const k = Math.floor((size - 1) / 2);
        const map = ensureEditMap(pl.name);
        const sAi = slice ? (slice.axis === 'x' ? 0 : slice.axis === 'y' ? 1 : 2) : -1;
        const apply = (lx, ly, lz) => {
            if (lx < 0 || ly < 0 || lz < 0 || lx >= w || ly >= h || lz >= d) return;
            // P9：切片启用时只在切片层内编辑（rot=0 单片模式，局部轴 = 世界轴）
            if (slice && !pl.rot && [lx, ly, lz][sAi] !== slice.index) return;
            const key = lx + ',' + ly + ',' + lz;
            if (!blockId || blockId === 'minecraft:air' || blockId === 'air') map.delete(key);
            else map.set(key, blockId);
        };
        if (size <= 1) {
            // 单格：命中面外的相邻格
            const [ax, ay, az] = pick.local;
            apply(ax + lnx, ay + ny, az + lnz);
        } else {
            for (let a = -k; a <= k; a++) for (let b = -k; b <= k; b++) {
                const ax = pick.local[0] + lnx + t1[0] * a + t2[0] * b;
                const ay = pick.local[1] + ny + t1[1] * a + t2[1] * b;
                const az = pick.local[2] + lnz + t1[2] * a + t2[2] * b;
                apply(ax, ay, az);
            }
        }
        return rebuild();
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
    let evDown = 0, evMove = 0;

    // HUD（画布左下角浮层）
    const hud = document.createElement('div');
    hud.className = 'struct-hud';
    hud.style.cssText = 'position:absolute;left:8px;bottom:8px;font:11px/1.5 ui-monospace,Consolas,monospace;'
        + 'color:#cdd6f4;background:rgba(30,30,46,.78);border:1px solid #45475a;'
        + 'padding:4px 8px;border-radius:6px;pointer-events:none;display:none;white-space:pre;z-index:5;';
    const wrap = canvas.parentElement;
    if (wrap) { wrap.style.position = 'relative'; wrap.appendChild(hud); }
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
        renderNow();
    }

    // pointer 事件：down 在画布，move/up 在 window（捕获阶段）
    canvas.addEventListener('pointerdown', (e) => {
        if (activePointer !== null) return;
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

    canvas.addEventListener('wheel', (e) => {
        e.preventDefault();
        const f = e.deltaY > 0 ? 1.1 : 0.9;
        orbit.radius = Math.max(2, Math.min(2000, orbit.radius * f));
        checkSliceFlip();
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
        renderer.render(scene, camera);
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
        checkSliceFlip(); // P9：自动旋转等移动相机时，越过分界面自动翻转剖切
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

    function dispose() {
        stop();
        clearGroups();
        for (const set of texSets.values()) {
            [set.top, set.side, set.bottom].forEach((t) => { if (t) t.dispose(); });
        }
        texSets.clear();
        for (const arr of matSets.values()) arr.forEach((m) => m.dispose());
        matSets.clear();
        sharedMats.clear();
        faceCache.clear();
        facePending.clear();
        editMaps.clear();
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
        let loadedTex = 0;
        for (const set of texSets.values()) {
            for (const t of [set.top, set.side, set.bottom]) {
                if (t && t.image && (t.image.width || 0) > 0) loadedTex++;
            }
        }
        return {
            pieces: pieceMeshes.size,
            instanceMeshes,
            voxels,
            texturedBlocks: textured,
            missingBlocks: missing,
            loadedTextures: loadedTex,
            renderCalls: renderer ? renderer.info.render.calls : 0,
            triangles: renderer ? renderer.info.render.triangles : 0,
            editedPieces: [...editMaps.keys()],
            clip,
            slice,
            sliceSide,
            ghosts: gGhosts.children.length,
            // 相机实际位置（拖拽验证用：应随 orbit 变化）
            camera: camera ? { x: +camera.position.x.toFixed(2), y: +camera.position.y.toFixed(2), z: +camera.position.z.toFixed(2) } : null,
            running,
            hud: hud ? { display: hud.style.display, text: hud.textContent } : null,
        };
    }

    /** 当前轨道状态（测试/调试用） */
    function getOrbit() {
        return { theta: orbit.theta, phi: orbit.phi, radius: orbit.radius,
                 target: { x: orbit.target.x, y: orbit.target.y, z: orbit.target.z } };
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
        resetView,
        setAutoRotate,
        start,
        stop,
        dispose,
        // 体素编辑（P3）
        pickVoxel,
        setVoxel,
        brushVoxel,
        setClip,
        getClip,
        setSlice, // P9 编辑切片（剖切面）
        getSlice,
        getPieceVoxelMap,
        clearEdits,
        getPlacements,
        setGhosts,
        debugStats,
    };
}
