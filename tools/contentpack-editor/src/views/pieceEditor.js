// pieceEditor.js - P11/P12 片段 3D 编辑器窗口（独立窗口渲染进程）
// 布局：顶栏（片段选择/方块搜索/快捷按钮/撤销/写回）+ 左侧工具栏（工具/剖切/视角）+ 画布 + 状态栏
// 交互（P12 定稿，不复合）：
//   移动工具：左键拖拽=旋转 · 右键拖拽/Shift=平移 · WASD=平移 · 滚轮=缩放
//   笔刷/擦除/占位：左键单击=替换命中格 · 左键拖拽=笔触(连续替换) · 右键单击=命中面相邻格放置 ·
//                   中键单击=编辑菜单(可编辑 jigsaw 组件) · WASD=平移 · 角度锁死
//   悬停高亮：左键落点(命中格,白线框) + 右键落点(相邻格,工具色)
// 数据流：主窗口 push blueprint（pieceEditor:load）→ 本地编辑 → 写回（pieceEditor:saveEdits）
'use strict';
(function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));
    const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    const $ = (id) => document.getElementById(id);

    let state = null;      // { packDir, structureId, pieceName, blueprint }
    let pv = null;         // 预览器实例
    let tool = 'move';     // 默认"移动"（先摆视角）；笔刷/擦除/占位 = 写入模式（左键不转视角）
    let initialized = false;
    // P12 jigsaw 字段编辑：记录被改过的片段 + 原始快照（撤销用）
    let jigsawEdited = new Set();
    let jigsawBackup = new Map();
    // P12 方块实体 NBT 编辑：同上（blockNbt 列表）
    let blockNbtEdited = new Set();
    let blockNbtBackup = new Map();

    // ===== P12 方块实体 NBT（1.21.1 格式，对照 vanilla 结构模板实测数据）=====
    // 实测（ancient_city 模板）：chest = {"Items":[{"count":1,"Slot":13,"id":"..."}], "id":"minecraft:chest"}
    //   —— 注意：小写 count；每个 BE 带 id 字段（BE 类型 ID）；1.21.1 熔炉用 Items（非旧 Contents）
    const BE_TEMPLATES = {
        chest: '{"id": "minecraft:chest", "Items": []}',
        barrel: '{"id": "minecraft:barrel", "Items": []}',
        hopper: '{"id": "minecraft:hopper", "Items": []}',
        dispenser: '{"id": "minecraft:dispenser", "Items": []}',
        dropper: '{"id": "minecraft:dropper", "Items": []}',
        shulker_box: '{"id": "minecraft:shulker_box", "Items": []}',
        decorated_pot: '{"id": "minecraft:decorated_pot", "Items": []}',
        furnace: '{"id": "minecraft:furnace", "Items": [], "BurnTime": 0, "CookTime": 0, "CookTimeTotal": 0}',
        blast_furnace: '{"id": "minecraft:blast_furnace", "Items": [], "BurnTime": 0, "CookTime": 0, "CookTimeTotal": 0}',
        smoker: '{"id": "minecraft:smoker", "Items": [], "BurnTime": 0, "CookTime": 0, "CookTimeTotal": 0}',
    };
    // ===== P13 方块实体判定（"是不是方块实体，能看得出来"）=====
    // 方块实体是【代码注册】的（BlockEntityType），1.21.1 没有纯数据文件可枚举。两个数据来源交叉：
    //   ① 1180 个 vanilla 1.21.1 结构模板实测：携带 nbt 的方块（39 种，.scratch/be-blocks-1.21.1.json）
    //   ② 反编译源码 BlockEntityType.java 的完整注册表（165 种方块 → 41 个 BE 类型）
    // Mod 方块：文件/代码扫描都不可见 → 不在列表内，但 NBT 入口恒可用（下方 NBT 区对任何方块开放），
    //   且方块已带 NBT（如圈地读取捕获的）时一律按方块实体处理（isBe = 列表 ∪ 实际有 NBT）。
    const BE_KNOWN = new Set([
        // 存储/容器类
        'chest', 'trapped_chest', 'ender_chest', 'barrel', 'hopper', 'dropper', 'dispenser',
        'shulker_box', 'black_shulker_box', 'blue_shulker_box', 'brown_shulker_box', 'cyan_shulker_box',
        'gray_shulker_box', 'green_shulker_box', 'light_blue_shulker_box', 'light_gray_shulker_box',
        'lime_shulker_box', 'magenta_shulker_box', 'orange_shulker_box', 'pink_shulker_box',
        'purple_shulker_box', 'red_shulker_box', 'white_shulker_box', 'yellow_shulker_box',
        'decorated_pot', 'crafter', 'jukebox',
        // 熔炉系
        'furnace', 'blast_furnace', 'smoker',
        // 床（16 色）
        'red_bed', 'black_bed', 'blue_bed', 'brown_bed', 'cyan_bed', 'gray_bed', 'green_bed',
        'light_blue_bed', 'light_gray_bed', 'lime_bed', 'magenta_bed', 'orange_bed', 'pink_bed',
        'purple_bed', 'white_bed', 'yellow_bed',
        // 旗帜（16 色 × 挂/立）
        'white_banner', 'orange_banner', 'magenta_banner', 'light_blue_banner', 'yellow_banner',
        'lime_banner', 'pink_banner', 'gray_banner', 'light_gray_banner', 'cyan_banner',
        'purple_banner', 'blue_banner', 'brown_banner', 'green_banner', 'red_banner', 'black_banner',
        'white_wall_banner', 'orange_wall_banner', 'magenta_wall_banner', 'light_blue_wall_banner',
        'yellow_wall_banner', 'lime_wall_banner', 'pink_wall_banner', 'gray_wall_banner',
        'light_gray_wall_banner', 'cyan_wall_banner', 'purple_wall_banner', 'blue_wall_banner',
        'brown_wall_banner', 'green_wall_banner', 'red_wall_banner', 'black_wall_banner',
        // 牌子（11 材质 × 挂/立 + 悬挂牌）
        'oak_sign', 'spruce_sign', 'birch_sign', 'acacia_sign', 'cherry_sign', 'jungle_sign',
        'dark_oak_sign', 'crimson_sign', 'warped_sign', 'mangrove_sign', 'bamboo_sign',
        'oak_wall_sign', 'spruce_wall_sign', 'birch_wall_sign', 'acacia_wall_sign', 'cherry_wall_sign',
        'jungle_wall_sign', 'dark_oak_wall_sign', 'crimson_wall_sign', 'warped_wall_sign',
        'mangrove_wall_sign', 'bamboo_wall_sign',
        'oak_hanging_sign', 'spruce_hanging_sign', 'birch_hanging_sign', 'acacia_hanging_sign',
        'cherry_hanging_sign', 'jungle_hanging_sign', 'dark_oak_hanging_sign', 'crimson_hanging_sign',
        'warped_hanging_sign', 'mangrove_hanging_sign', 'bamboo_hanging_sign',
        'oak_wall_hanging_sign', 'spruce_wall_hanging_sign', 'birch_wall_hanging_sign', 'acacia_wall_hanging_sign',
        'cherry_wall_hanging_sign', 'jungle_wall_hanging_sign', 'dark_oak_wall_hanging_sign',
        'crimson_wall_hanging_sign', 'warped_wall_hanging_sign', 'mangrove_wall_hanging_sign', 'bamboo_wall_hanging_sign',
        // 头颅（14）
        'skeleton_skull', 'skeleton_wall_skull', 'creeper_head', 'creeper_wall_head',
        'dragon_head', 'dragon_wall_head', 'zombie_head', 'zombie_wall_head',
        'wither_skeleton_skull', 'wither_skeleton_wall_skull', 'player_head', 'player_wall_head',
        'piglin_head', 'piglin_wall_head',
        // 功能方块
        'brewing_stand', 'enchanting_table', 'end_portal', 'beacon', 'conduit', 'lectern',
        'bell', 'spawner', 'trial_spawner', 'vault', 'structure_block', 'end_gateway',
        'daylight_detector', 'comparator', 'sculk_sensor', 'calibrated_sculk_sensor',
        'sculk_catalyst', 'sculk_shrieker', 'chiseled_bookshelf',
        'command_block', 'chain_command_block', 'repeating_command_block',
        'campfire', 'soul_campfire', 'bee_nest', 'beehive',
        'suspicious_sand', 'suspicious_gravel',
        'moving_piston',
        'jigsaw',
    ]);
    const BE_TYPES = BE_KNOWN; // 兼容旧引用
    // 字段名 → NBT 类型（类型推断覆盖；对照 vanilla 数据：Slot/count 为 byte）
    const NBT_FIELD_TYPES = { Slot: 1, count: 1, Count: 1 };

    // ===== P13 方块状态属性（实例值）：block 串 "name[k=v,...]" 的解析/重建 =====
    // 定义（有哪些属性、允许值）来自 blockstate JSON（通用链路 blockStateAny）；
    // 实例值（这一格当前是什么朝向/含水/连接）存在方块串里，与 NBT 平级、随蓝图保存、随导出写回
    // （1.21.1 模板 palette 的 Properties compound，对照 1180 个 vanilla 模板实测）。
    function parsePropsInstance(blockIdStr) {
        const out = {};
        const m = String(blockIdStr || '').match(/\[([^\]]*)\]$/);
        if (!m) return out;
        for (const kv of m[1].split(',')) {
            const i = kv.indexOf('=');
            if (i > 0) {
                // 键归一化：剥 minecraft: 前缀（与导出端 parseBlockId 一致；游戏 StateDefinition 用裸属性名）
                out[kv.slice(0, i).trim().replace(/^minecraft:/, '')] = kv.slice(i + 1).trim();
            }
        }
        return out;
    }
    function buildBlockStr(base, props) {
        const entries = Object.entries(props || {});
        return entries.length ? base + '[' + entries.map(([k, v]) => k + '=' + v).join(',') + ']' : base;
    }

    // plain JSON → tagged NBT（类型推断；blueprint 存储格式 = tagged，long 的 v 用字符串）
    function inferTag(value, fieldName) {
        const forced = fieldName ? NBT_FIELD_TYPES[fieldName] : null;
        if (forced) return { $t: forced, v: Math.trunc(Number(value)) };
        if (typeof value === 'string') return { $t: 8, v: value };
        if (typeof value === 'boolean') return { $t: 1, v: value ? 1 : 0 };
        if (typeof value === 'number') {
            if (Number.isInteger(value)) return { $t: 3, v: value };
            return { $t: 6, v: value };
        }
        if (Array.isArray(value)) {
            // 空列表：元素类型按 compound（Items 等惯例）
            const elemType = value.length ? inferTag(value[0]).$t : 10;
            return { $t: 9, elemType, v: value.map((it) => inferTag(it)) };
        }
        if (value && typeof value === 'object') return inferCompound(value);
        throw new Error('无法推断 NBT 类型: ' + JSON.stringify(value));
    }
    function inferCompound(obj) {
        const v = {};
        for (const [k, val] of Object.entries(obj || {})) v[k] = inferTag(val, k);
        return { $t: 10, v };
    }
    // 用户输入解析：plain JSON（推断）或 tagged JSON（带 $t，原样采用）
    function parseNbtInput(text) {
        let obj;
        try { obj = JSON.parse(text); } catch (e) { return { ok: false, error: 'JSON 解析失败: ' + e.message }; }
        if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return { ok: false, error: '顶层必须是 JSON 对象（NBT compound）' };
        try {
            if (obj.$t !== undefined) {
                if (obj.$t !== 10) return { ok: false, error: 'tagged 格式顶层须为 $t:10（compound）' };
                return { ok: true, nbt: { $t: 10, v: obj.v && typeof obj.v === 'object' && !Array.isArray(obj.v) ? obj.v : {} } };
            }
            return { ok: true, nbt: inferCompound(obj) };
        } catch (e) {
            return { ok: false, error: e.message };
        }
    }
    // tagged → plain 展示（菜单文本域回显用，去掉 $t；long 显示为字符串）
    function taggedToPlainDisplay(tagged) {
        const conv = (t) => {
            if (!t || typeof t !== 'object' || !Number.isInteger(t.$t)) return t;
            let v = t.v;
            if (t.$t === 4) return String(v);
            if (t.$t === 9) return (v || []).map(conv);
            if (t.$t === 10) { const o = {}; for (const [k, val] of Object.entries(v || {})) o[k] = conv(val); return o; }
            return v;
        };
        return conv(tagged);
    }

    // 工具切换：按钮高亮 + 左键拖拽门控（移动=转视角；笔刷=画，不转）+ 悬停高亮（随工具配色）
    function setTool(name) {
        tool = name;
        document.querySelectorAll('.ed-tool[data-tool]').forEach((b) => b.classList.toggle('active', b.dataset.tool === name));
        if (!pv) return;
        pv.setOrbitOnLeft(name === 'move');
        if (name === 'move') {
            pv.setHoverEnabled(false);
        } else {
            pv.setHoverEnabled(true);
            pv.setHoverColor(name === 'erase' ? 0xf38ba8 : name === 'placeholder' ? 0x89b4fa : 0xa6e3a1);
        }
    }

    // ===== 状态栏 =====
    function status(msg, isErr) {
        const el = $('edStatus');
        if (el) { el.textContent = msg || ''; el.style.color = isErr ? 'var(--err)' : ''; }
    }

    function updateInfo() {
        const el = $('edInfo');
        if (!el) return;
        if (!pv || !pv.debugStats) { el.textContent = ''; return; }
        const st = pv.debugStats();
        el.textContent = st.editedPieces.length
            ? tf('structure.3d.edit.edited', { n: st.editedPieces.length, p: st.editedPieces.join(', ') })
            : t('structure.3d.edit.noEdit');
    }

    // ===== 主入口：主窗口推送蓝图 =====
    window.api.onPieceEditorLoad((payload) => {
        state = payload;
        if (!initialized) { init(); return; }
        // 再次打开（换结构/换片段）→ 重置本地编辑并加载新数据
        if (pv) pv.clearEdits();
        jigsawEdited.clear();
        jigsawBackup.clear();
        blockNbtEdited.clear();
        blockNbtBackup.clear();
        syncPieceSelect();
        selectPiece(state.pieceName);
        status(t('structure.piece3d.loaded') + ' — ' + (state.structureId || '') + (state.pieceName ? ' / ' + state.pieceName : ''));
    });

    async function init() {
        try {
            const mod = await import('../lib/structurePreview.js');
            pv = mod.createStructurePreview($('edCanvas'));
            window.__pieceEditor = {
                pv: () => pv,
                getState: () => state,
                inspect: inspectVoxel,
                openMenuAt: openMenuAtCell, // P12 E2E：指定格开中键菜单
            }; // E2E/调试钩子
            pv.start();
            // 编辑器窗口用按需渲染（交互/编辑时渲染，不跑 60fps 连续循环）：
            // 省主线程 → 搜索输入/右键菜单不卡；rebuild 末尾强制 renderOnce → 编辑即时可见
            pv.setContinuous(false);
            pv.setOrbitOnLeft(tool === 'move');
            syncPieceSelect();
            selectPiece(state.pieceName);
            wireUI();
            loadBlocks();
            initialized = true;
            status(t('structure.piece3d.ready'));
        } catch (e) {
            console.error('[PieceEditor]', e);
            status('3D 模块加载失败: ' + e.message, true);
        }
    }

    // ===== 片段选择 =====
    function syncPieceSelect() {
        const sel = $('edPiece');
        const pieces = (state && state.blueprint && state.blueprint.pieces) || [];
        const cur = sel.value;
        sel.innerHTML = '';
        for (const p of pieces) {
            const o = document.createElement('option');
            o.value = p.name;
            o.textContent = p.name + '  (' + (p.size || [0, 0, 0]).join('×') + ')';
            sel.appendChild(o);
        }
        if (pieces.some((p) => p.name === cur)) sel.value = cur;
        else if (state && state.pieceName && pieces.some((p) => p.name === state.pieceName)) sel.value = state.pieceName;
    }
    function currentPiece() {
        const sel = $('edPiece');
        const pieces = (state && state.blueprint && state.blueprint.pieces) || [];
        return pieces.find((p) => p.name === sel.value) || pieces[0] || null;
    }
    function selectPiece(name) {
        const piece = (state.blueprint.pieces || []).find((p) => p.name === name) || currentPiece();
        if (!piece || !pv) return;
        pv.setPiece(piece);
        // 换片段重置剖切
        pv.setSlice(null);
        $('edSliceAxis').value = 'off';
        $('edSliceI').value = '0';
        syncSliceControl();
        updateInfo();
        status(t('structure.piece3d.loaded') + ' — ' + piece.name + ' (' + (piece.size || [0, 0, 0]).join('×') + ')');
    }

    // ===== 剖切（切平面：隐藏面向相机一侧，笔刷限切片层） =====
    function syncSliceControl() {
        const piece = currentPiece();
        const axis = $('edSliceAxis').value;
        const slider = $('edSliceI');
        if (piece && axis !== 'off') {
            const [w, h, d] = piece.size || [1, 1, 1];
            const max = Math.max(0, (axis === 'x' ? w : axis === 'y' ? h : d) - 1);
            slider.max = String(max);
            if (+slider.value > max) slider.value = String(max);
        }
        $('edSliceLabel').textContent = slider.value;
    }
    function sliceChange() {
        if (!pv) return;
        syncSliceControl();
        const axis = $('edSliceAxis').value;
        const i = parseInt($('edSliceI').value, 10) || 0;
        if (axis === 'off') pv.setSlice(null);
        else pv.setSlice({ axis, index: i });
    }

    // ===== UI 绑定 =====
    function wireUI() {
        // 左侧工具按钮（移动 / 笔刷 / 擦除 / 占位）
        document.querySelectorAll('.ed-tool[data-tool]').forEach((btn) => {
            btn.addEventListener('click', () => setTool(btn.dataset.tool));
        });
        $('edResetView').addEventListener('click', () => { if (pv) pv.resetView(); });
        $('edSliceAxis').addEventListener('change', sliceChange);
        $('edSliceI').addEventListener('input', sliceChange);
        $('edPiece').addEventListener('change', () => selectPiece($('edPiece').value));

        // 撤销 / 写回
        $('edUndo').addEventListener('click', () => {
            if (!pv) return;
            // P12：撤销 jigsaw 字段编辑（还原原始快照）
            for (const [name, orig] of jigsawBackup) {
                const i = (state.blueprint.pieces || []).findIndex((p) => p.name === name);
                if (i >= 0) state.blueprint.pieces[i].jigsaws = JSON.parse(JSON.stringify(orig));
            }
            jigsawEdited.clear();
            jigsawBackup.clear();
            // P12：撤销方块实体 NBT 编辑
            for (const [name, orig] of blockNbtBackup) {
                const i = (state.blueprint.pieces || []).findIndex((p) => p.name === name);
                if (i >= 0) {
                    if (orig.length) state.blueprint.pieces[i].blockNbt = JSON.parse(JSON.stringify(orig));
                    else delete state.blueprint.pieces[i].blockNbt;
                }
            }
            blockNbtEdited.clear();
            blockNbtBackup.clear();
            pv.clearEdits().then(() => { updateInfo(); status(t('structure.3d.edit.undoDone'), false); });
        });
        $('edSave').addEventListener('click', () => saveEdits());

        // 画布（P12 定稿，不复合）：
        //   左键：笔刷工具 单击=替换命中格 / 拖拽=笔触(连续替换)；移动工具 拖拽=旋转
        //   右键：单击=命中面相邻格放置（仅单击）；拖拽=平移
        //   中键：单击=编辑菜单（移动/笔刷模式一致）
        const canvas = $('edCanvas');
        let down = null, rdown = null, mdown = null;
        let painting = false, lastPaintKey = '';
        const blockIdForTool = () => {
            const inp = $('edBlock');
            const blockId = (inp.value || '').trim() || 'minecraft:stone_bricks';
            return tool === 'erase' ? 'minecraft:air' : (tool === 'placeholder' ? '#empty' : blockId);
        };
        // 在 (cx,cy) 落一笔：action 'replace'=命中格 / 'place'=相邻格；同格去重（笔触不重复写）
        const paintAt = (cx, cy, action) => {
            if (!pv || tool === 'move') return;
            const pick = pv.pickVoxel(cx, cy);
            if (!pick) return;
            const key = pick.piece + '|' + pick.local.join(',') + '|' + action;
            if (key === lastPaintKey) return;
            lastPaintKey = key;
            pv.brushVoxel(pick, blockIdForTool(), action).then(() => updateInfo());
        };
        canvas.addEventListener('pointerdown', (e) => {
            if (e.button === 0) {
                down = { x: e.clientX, y: e.clientY };
                if (tool !== 'move') { painting = true; lastPaintKey = ''; paintAt(e.clientX, e.clientY, 'replace'); }
            } else if (e.button === 1) {
                mdown = { x: e.clientX, y: e.clientY };
                e.preventDefault(); // 防中键自动滚屏
            } else if (e.button === 2) {
                rdown = { x: e.clientX, y: e.clientY };
            }
        });
        canvas.addEventListener('pointermove', (e) => {
            if (painting && (e.buttons & 1)) paintAt(e.clientX, e.clientY, 'replace'); // 笔触
        });
        canvas.addEventListener('pointerup', (e) => {
            if (e.button === 0) { painting = false; down = null; }
            else if (e.button === 1) {
                if (!mdown) return;
                const moved = Math.hypot(e.clientX - mdown.x, e.clientY - mdown.y);
                mdown = null;
                if (moved > 4 || !pv) return;
                const pick = pv.pickVoxel(e.clientX, e.clientY);
                showCtxMenu(e.clientX, e.clientY, pick);
            } else if (e.button === 2) {
                if (!rdown) return;
                const moved = Math.hypot(e.clientX - rdown.x, e.clientY - rdown.y);
                rdown = null;
                if (moved > 4 || !pv || tool === 'move') return; // 右键拖拽=平移，不放置
                const pick = pv.pickVoxel(e.clientX, e.clientY);
                if (!pick) return;
                lastPaintKey = '';
                pv.brushVoxel(pick, blockIdForTool(), 'place').then(() => updateInfo());
            }
        });
        canvas.addEventListener('contextmenu', (e) => e.preventDefault()); // P12：菜单改中键，右键只放置
        canvas.addEventListener('mousedown', (e) => { if (e.button === 1) e.preventDefault(); }); // 中键自动滚屏兜底

        // WASD 平移（相机相对地面平移，角度不变；两种模式通用；输入框聚焦时不触发）
        const heldKeys = new Set();
        let panTimer = null;
        const isTyping = (e) => {
            const el = e.target;
            return el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT');
        };
        const panStep = () => {
            if (!heldKeys.size || !pv) { panTimer = null; return; }
            let fwd = 0, right = 0;
            if (heldKeys.has('w')) fwd++;
            if (heldKeys.has('s')) fwd--;
            if (heldKeys.has('d')) right++;
            if (heldKeys.has('a')) right--;
            if (fwd || right) pv.panBy(fwd, right);
        };
        window.addEventListener('keydown', (e) => {
            if (isTyping(e) || e.ctrlKey || e.metaKey || e.altKey) return;
            const k = (e.key || '').toLowerCase();
            if (!'wasd'.includes(k) || heldKeys.has(k)) return;
            heldKeys.add(k);
            if (!panTimer) panTimer = setInterval(panStep, 50);
            panStep();
        });
        window.addEventListener('keyup', (e) => { heldKeys.delete((e.key || '').toLowerCase()); });
        window.addEventListener('blur', () => { heldKeys.clear(); });

        // 菜单外点击 / Esc 关闭
        document.addEventListener('click', (e) => { if (!e.target.closest('#edCtx')) hideCtx(); });
        document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hideCtx(); });

        // 快捷方块按钮 + 搜索（选中要刷的方块 → 自动切到笔刷工具，直接可点写）
        $('edBtnJigsaw').addEventListener('click', () => { $('edBlock').value = 'minecraft:jigsaw'; setTool('paint'); hideBlockList(); });
        $('edBtnVoid').addEventListener('click', () => { $('edBlock').value = 'minecraft:structure_void'; setTool('paint'); hideBlockList(); });
        const inp = $('edBlock');
        const listEl = $('edBlockList');
        // 搜索防抖：连续输入合并到一次重渲染（几百个方块过滤+重建成 80 行 DOM，逐键重渲染会卡）
        let searchTimer = null;
        inp.addEventListener('input', () => {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(filterBlockList, 60);
        });
        inp.addEventListener('focus', filterBlockList);
        inp.addEventListener('blur', () => { setTimeout(hideBlockList, 150); });
        inp.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const first = listEl.querySelector('.block-search-item');
                if (first) { e.preventDefault(); inp.value = first.dataset.id; hideBlockList(); }
            } else if (e.key === 'Escape') { hideBlockList(); inp.blur(); }
        });
    }

    // ===== 方块搜索（实时列表，主进程扫描） =====
    let blockIds = [];
    let blockListLoaded = false;
    async function loadBlocks(force) {
        if (!window.api || !window.api.mcBlockList) return;
        if (blockListLoaded && !force) return;
        const res = await window.api.mcBlockList({ force: !!force });
        if (!res.ok || !Array.isArray(res.data)) return;
        blockIds = res.data;
        blockListLoaded = true;
        const note = $('edSrcNote');
        if (note) note.textContent = res.meta
            ? tf('structure.3d.edit.blockSource', { n: blockIds.length, v: res.meta.vanilla, m: res.meta.mods })
            : '';
    }
    function hideBlockList() { const el = $('edBlockList'); el.style.display = 'none'; el.innerHTML = ''; }
    function filterBlockList() {
        const inp = $('edBlock');
        const listEl = $('edBlockList');
        const q = (inp.value || '').trim().toLowerCase();
        const matches = blockIds.filter((id) => !q || id.toLowerCase().indexOf(q) >= 0).slice(0, 80);
        listEl.innerHTML = '';
        for (const id of matches) {
            const row = document.createElement('div');
            row.className = 'block-search-item';
            row.dataset.id = id;
            row.textContent = id;
            row.addEventListener('mousedown', (e) => { e.preventDefault(); inp.value = id; setTool('paint'); hideBlockList(); });
            listEl.appendChild(row);
        }
        listEl.style.display = matches.length ? 'block' : 'none';
    }

    // ===== 右键组件/NBT 查询 =====
    /** 纯数据函数（E2E 可直接调）：某格块的 blockId + jigsaw 组件（1.21.1 minecraft:jigsaw） */
    function inspectVoxel(x, y, z) {
        const piece = currentPiece();
        if (!piece) return null;
        const key = x + ',' + y + ',' + z;
        const bid = pv ? (pv.getPieceVoxelMap(piece.name).get(key) || null) : null;
        const jg = (piece.jigsaws || []).find((j) => j.x === x && j.y === y && j.z === z) || null;
        return {
            piece: piece.name,
            pos: [x, y, z],
            blockId: bid,
            jigsaw: jg ? {
                name: jg.name || '',
                pool: jg.pool || '',
                target: jg.target || '',
                orientation: jg.orientation || '',
                finalState: jg.finalState || 'minecraft:air',
                selectionPriority: jg.selectionPriority != null ? jg.selectionPriority : 10,
                placementPriority: jg.placementPriority != null ? jg.placementPriority : 10,
            } : null,
        };
    }

    async function showCtxMenu(cx, cy, pick) {
        const menu = $('edCtx');
        if (!pick) { hideCtx(); return; }
        const piece = currentPiece();
        const [x, y, z] = pick.local;
        const jg = (piece && piece.jigsaws || []).find((j) => j.x === x && j.y === y && j.z === z);
        const beBase = String(pick.blockId || '').split('[')[0].split(':').pop();
        const hasNbt = (piece && piece.blockNbt || []).some((b) => b.x === x && b.y === y && b.z === z);
        const isBe = BE_KNOWN.has(beBase) || hasNbt;
        let html = `<div class="ed-ctx-head">🧊 ${esc(pick.blockId || 'air（空）')}${isBe ? ` <span class="note">· ⚙ ${t('structure.piece3d.beBadge')}</span>` : ''}</div>`;
        html += `<div class="ed-ctx-row">${t('structure.piece3d.pos')} <b>${x}, ${y}, ${z}</b> <span class="note">· ${esc(pick.piece)}</span></div>`;
        if (jg) {
            // P12：jigsaw 组件可编辑（change 即落本地蓝图，写回时随 payload 上主窗口）
            html += `<div class="ed-ctx-sub">minecraft:jigsaw · <span class="note">${t('structure.piece3d.component')}</span></div>`;
            html += `
            <div class="ed-ctx-row"><b>name</b> <input class="ed-ctx-in" id="edJgName" value="${esc(jg.name || '')}"></div>
            <div class="ed-ctx-row"><b>pool</b> <input class="ed-ctx-in" id="edJgPool" value="${esc(jg.pool || '')}"></div>
            <div class="ed-ctx-row"><b>target</b> <input class="ed-ctx-in" id="edJgTarget" value="${esc(jg.target || '')}"></div>
            <div class="ed-ctx-row"><b>orientation</b> <select class="ed-ctx-in" id="edJgOrient">
                ${['north', 'south', 'west', 'east', 'up', 'down'].map((o) => `<option${(jg.orientation || '') === o ? ' selected' : ''}>${o}</option>`).join('')}
            </select></div>
            <div class="ed-ctx-row"><b>final_state</b> <input class="ed-ctx-in" id="edJgFinal" value="${esc(jg.finalState || 'minecraft:air')}"></div>
            <div class="ed-ctx-row"><b>sel_prio</b> <input class="ed-ctx-in" id="edJgSelP" type="number" min="0" max="999" value="${jg.selectionPriority != null ? jg.selectionPriority : 10}">
            <b>place_prio</b> <input class="ed-ctx-in" id="edJgPlaceP" type="number" min="0" max="999" value="${jg.placementPriority != null ? jg.placementPriority : 10}"></div>`;
        }
        // P12 方块实体 NBT（箱子/熔炉等功能方块：精确编辑实体数据，1.21.1 格式对照 vanilla 实测）
        const beName = beBase;
        const curNbt = (piece && piece.blockNbt || []).find((b) => b.x === x && b.y === y && b.z === z);
        const nbtInit = curNbt
            ? JSON.stringify(taggedToPlainDisplay(curNbt.nbt), null, 1)
            : (isBe && BE_TEMPLATES[beName] ? BE_TEMPLATES[beName] : (isBe ? '{ "id": "' + esc(String(pick.blockId).split('[')[0]) + '" }' : '{ }'));
        html += `<div class="ed-ctx-sub">NBT · ${t('structure.piece3d.nbtSub')}${isBe ? '' : ` <span class="note">${t('structure.piece3d.nbtNotBe')}</span>`}</div>`;
        html += `<textarea class="ed-ctx-in ed-ctx-nbtarea" id="edNbtArea" rows="6" spellcheck="false">${esc(nbtInit)}</textarea>`;
        html += `<div class="ed-ctx-row"><span class="note" id="edNbtMsg"></span></div>`;
        html += `<div class="ed-ctx-actions">
            <button class="btn ghost small" id="edNbtTpl">${t('structure.piece3d.nbtTpl')}</button>
            <button class="btn small" id="edNbtApply">${t('structure.piece3d.nbtApply')}</button>
            ${curNbt ? `<button class="btn ghost small" id="edNbtDel">${t('structure.piece3d.nbtDel')}</button>` : ''}
        </div>`;
        html += `<div class="ed-ctx-bs"><div class="note ed-ctx-loading">${t('structure.piece3d.bsLoading')}</div></div>`;
        html += `<div class="ed-ctx-actions">
            <button class="btn ghost small" id="edCtxUse">🖌 ${t('structure.piece3d.useAsBrush')}</button>
            <button class="btn ghost small" id="edCtxCopy">📋 ${t('structure.piece3d.copyId')}</button>
        </div>`;
        menu.innerHTML = html;
        menu.style.display = 'block';
        const wrap = $('edCanvasWrap').getBoundingClientRect();
        menu.style.left = Math.max(4, Math.min(cx - wrap.left + 4, wrap.width - 320)) + 'px';
        menu.style.top = Math.max(4, Math.min(cy - wrap.top + 4, wrap.height - 260)) + 'px';

        // P12：jigsaw 字段编辑 —— change 即落本地蓝图（首次改前备份，撤销可还原）
        if (jg) {
            const commitJg = () => {
                const i = (piece.jigsaws || []).indexOf(jg);
                if (i < 0) return;
                if (!jigsawBackup.has(piece.name)) jigsawBackup.set(piece.name, JSON.parse(JSON.stringify(piece.jigsaws || [])));
                piece.jigsaws[i] = {
                    name: $('edJgName').value.trim(),
                    pool: $('edJgPool').value.trim(),
                    target: $('edJgTarget').value.trim(),
                    orientation: $('edJgOrient').value,
                    finalState: $('edJgFinal').value.trim() || 'minecraft:air',
                    selectionPriority: Math.max(0, Math.min(999, parseInt($('edJgSelP').value, 10) || 0)),
                    placementPriority: Math.max(0, Math.min(999, parseInt($('edJgPlaceP').value, 10) || 0)),
                };
                jigsawEdited.add(piece.name);
                updateInfo();
            };
            ['edJgName', 'edJgPool', 'edJgTarget', 'edJgOrient', 'edJgFinal', 'edJgSelP', 'edJgPlaceP'].forEach((id) => {
                const el = $(id);
                if (el) el.addEventListener('change', commitJg);
            });
        }

        // P12：NBT 编辑 —— 应用/模板/删除（change 即落本地蓝图，写回时随 payload 上主窗口）
        const nbtMsg = (text, isError) => {
            const el = $('edNbtMsg');
            if (!el) return;
            el.textContent = text;
            el.style.color = isError ? '#f38ba8' : '';
        };
        $('edNbtApply').addEventListener('click', () => {
            if (!piece) return;
            const r = parseNbtInput($('edNbtArea').value);
            if (!r.ok) { nbtMsg(r.error, true); return; }
            // 1.21.1 每个 BE 都带 id 字段（vanilla 实测）：用户没写则按当前方块自动补
            const v = r.nbt.v || (r.nbt.v = {});
            if (!v.id && pick.blockId) v.id = { $t: 8, v: String(pick.blockId).split('[')[0] };
            if (!blockNbtBackup.has(piece.name)) blockNbtBackup.set(piece.name, JSON.parse(JSON.stringify(piece.blockNbt || [])));
            if (!Array.isArray(piece.blockNbt)) piece.blockNbt = [];
            const i = piece.blockNbt.findIndex((b) => b.x === x && b.y === y && b.z === z);
            const entry = { x, y, z, nbt: r.nbt };
            if (i >= 0) piece.blockNbt[i] = entry; else piece.blockNbt.push(entry);
            blockNbtEdited.add(piece.name);
            nbtMsg(t('structure.piece3d.nbtApplied'));
            updateInfo();
        });
        const tplBtn = $('edNbtTpl');
        if (tplBtn) tplBtn.addEventListener('click', () => {
            $('edNbtArea').value = (isBe && BE_TEMPLATES[beName]) ? BE_TEMPLATES[beName] : (isBe ? '{ "id": "' + String(pick.blockId || '').split('[')[0] + '" }' : '{ }');
            nbtMsg('');
        });
        const delBtn = $('edNbtDel');
        if (delBtn) delBtn.addEventListener('click', () => {
            if (!piece) return;
            if (!blockNbtBackup.has(piece.name)) blockNbtBackup.set(piece.name, JSON.parse(JSON.stringify(piece.blockNbt || [])));
            piece.blockNbt = (piece.blockNbt || []).filter((b) => !(b.x === x && b.y === y && b.z === z));
            blockNbtEdited.add(piece.name);
            hideCtx();
            updateInfo();
        });

        // P13 方块状态属性（与 NBT 平级）：定义来自通用链路 blockStateAny（vanilla/内容包/数据包/mod jar），
        // 实例值存在方块串 [props] 里 —— 改 select 即写回该格（palette Properties，导出游戏可直读）
        const baseId = String(pick.blockId || '').split('[')[0];
        if (window.api && window.api.blockState && baseId && baseId !== 'minecraft:air' && piece) {
            window.api.blockState(baseId, state ? { packDir: state.packDir } : undefined).then((res) => {
                if (menu.style.display === 'none') return;
                const slot = menu.querySelector('.ed-ctx-bs');
                if (!slot) return;
                const inst = parsePropsInstance(pick.blockId);
                if (!res || !res.ok || !res.data) {
                    // 无定义（自定义方块等）：实例值仍可读（只读展示）
                    if (Object.keys(inst).length) {
                        slot.innerHTML = `<div class="ed-ctx-sub">${t('structure.piece3d.propsSub')}</div>` +
                            Object.entries(inst).map(([p, v]) => `<div class="ed-ctx-row"><b>${esc(p)}</b>: ${esc(v)}</div>`).join('');
                    } else {
                        slot.innerHTML = `<div class="note">${t('structure.piece3d.bsMissing')}</div>`;
                    }
                    return;
                }
                const d = res.data;
                const props = Object.entries(d.properties);
                let line = `<div class="ed-ctx-sub">${t('structure.piece3d.propsSub')} · ${esc(d.file)}</div>`;
                if (!props.length) {
                    line += `<div class="note">${t('structure.piece3d.noProps')}</div>`;
                } else {
                    for (const [p, vs] of props) {
                        const cur = inst[p];
                        line += `<div class="ed-ctx-row"><b>${esc(p)}</b>
                            <select class="ed-ctx-in ed-prop-sel" data-prop="${esc(p)}">
                                <option value="">&lt;${t('structure.piece3d.propsDefault')}&gt;</option>
                                ${vs.map((v) => `<option${cur === v ? ' selected' : ''}>${esc(v)}</option>`).join('')}
                            </select></div>`;
                    }
                    for (const [p, v] of Object.entries(inst)) {
                        if (!props.some(([k]) => k === p)) {
                            line += `<div class="ed-ctx-row"><b>${esc(p)}</b>: ${esc(v)} <span class="note">${t('structure.piece3d.propUnknown')}</span></div>`;
                        }
                    }
                    line += `<div class="ed-ctx-actions"><button class="btn ghost small" id="edPropsReset">${t('structure.piece3d.propsReset')}</button>
                        <span class="note" id="edPropsMsg"></span></div>`;
                    line += `<div class="note">${d.variantCount} variants</div>`;
                }
                slot.innerHTML = line;
                const commitProps = () => {
                    const next = { ...inst };
                    slot.querySelectorAll('.ed-prop-sel').forEach((s) => {
                        const p = s.dataset.prop;
                        if (s.value) next[p] = s.value; else delete next[p];
                    });
                    const newStr = buildBlockStr(baseId, next);
                    pv.setVoxel(piece.name, [x, y, z], newStr);
                    const head = menu.querySelector('.ed-ctx-head');
                    if (head) head.innerHTML = `🧊 ${esc(newStr)}${isBe ? ` <span class="note">· ⚙ ${t('structure.piece3d.beBadge')}</span>` : ''}`;
                    const brush = $('edBlock');
                    if (brush) brush.value = newStr;
                    const msg = $('edPropsMsg');
                    if (msg) msg.textContent = t('structure.piece3d.propsApplied');
                };
                slot.querySelectorAll('.ed-prop-sel').forEach((s) => s.addEventListener('change', commitProps));
                const rb = $('edPropsReset');
                if (rb) rb.addEventListener('click', () => {
                    slot.querySelectorAll('.ed-prop-sel').forEach((s) => { s.value = ''; });
                    commitProps();
                });
            }).catch(() => {});
        }
        $('edCtxUse').addEventListener('click', () => { $('edBlock').value = pick.blockId; setTool('paint'); hideCtx(); });
        $('edCtxCopy').addEventListener('click', () => {
            if (navigator.clipboard) navigator.clipboard.writeText(pick.blockId).catch(() => {});
            hideCtx();
        });
    }
    function hideCtx() { const m = $('edCtx'); if (m) m.style.display = 'none'; }

    // E2E/调试：直接在指定局部坐标开中键菜单（合成 pick，画布中心定位）
    function openMenuAtCell(x, y, z) {
        const piece = currentPiece();
        if (!piece || !pv) return false;
        const pl = (pv.getPlacements ? (pv.getPlacements() || [])[0] : null) || { piece, name: piece.name, off: [0, 0, 0], rot: 0 };
        const map = pv.getPieceVoxelMap(piece.name);
        const bid = map.get(x + ',' + y + ',' + z) || 'minecraft:air';
        const rect = $('edCanvas').getBoundingClientRect();
        const pick = { piece: piece.name, local: [x, y, z], world: [x, y, z], blockId: bid, face: [0, 1, 0], placement: pl };
        showCtxMenu(rect.left + rect.width / 2, rect.top + rect.height / 2, pick);
        return true;
    }

    // ===== 写回主窗口（P12：voxel 编辑 + jigsaw 字段编辑 一起回传）=====
    function collectEdits() {
        if (!pv || !state) return [];
        const st = pv.debugStats();
        const names = [...new Set([...st.editedPieces, ...jigsawEdited, ...blockNbtEdited])];
        const out = [];
        for (const name of names) {
            const i = (state.blueprint.pieces || []).findIndex((p) => p.name === name);
            if (i < 0) continue;
            const entry = { name };
            if (st.editedPieces.includes(name)) {
                const map = pv.getPieceVoxelMap(name);
                if (!map) continue;
                const merged = window.Voxel.mergePieceEdits(state.blueprint.pieces[i], map);
                state.blueprint.pieces[i] = merged;
                entry.boxFills = merged.boxFills || [];
                entry.fillBlocks = merged.fillBlocks || [];
            }
            if (jigsawEdited.has(name)) entry.jigsaws = (state.blueprint.pieces[i].jigsaws || []).slice();
            if (blockNbtEdited.has(name)) entry.blockNbt = (state.blueprint.pieces[i].blockNbt || []).slice();
            out.push(entry);
        }
        return out;
    }
    function saveEdits() {
        const pieces = collectEdits();
        if (!pieces.length) { status(t('structure.piece3d.noEdit'), false); return false; }
        window.api.savePieceEdits({ packDir: state.packDir, structureId: state.structureId, pieces });
        if (pv) pv.clearEdits();
        updateInfo();
        status(tf('structure.piece3d.saved', { n: pieces.length }), false);
        return true;
    }
    // 关窗前自动写回（ipcRenderer.send 为同步 IPC，beforeunload 中可靠）
    window.addEventListener('beforeunload', () => {
        if (!initialized || !pv) return;
        const st = pv.debugStats();
        if (!st.editedPieces.length) return;
        const pieces = collectEdits();
        if (pieces.length) window.api.savePieceEdits({ packDir: state.packDir, structureId: state.structureId, pieces });
    });
})();
