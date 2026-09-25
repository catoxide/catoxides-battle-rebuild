// pieceEditor.js - P11/P12/P15 片段 3D 编辑器窗口（独立窗口渲染进程）
// 布局：顶栏（片段选择/方块搜索/快捷按钮/撤销/写回）+ 左侧工具栏（工具/槽位行数/剖切/视角）
//       + 画布（底部 9×N 方块槽位缓存栏）+ 状态栏（按工具提示）
// 交互（P15 定稿，不复合）：
//   移动工具：左键拖拽=旋转 · 右键/Ctrl+滚轮=平移/缩放 · WASD=平移 · 中键=NBT 编辑菜单
//   笔刷/占位：左键单击=替换命中格 · 左键拖拽=笔触 · 右键单击=命中面相邻格放置 ·
//              中键=补货当前槽位（悬停方块+NBT）
//   框选工具：左键点角点 A → 悬停预览 → 左键点角点 B = 范围内填充当前槽位方块（Esc 取消）
//   槽位（9×N，N 可配置）：1-9/滚轮=切槽 · +/-/PgUp/PgDn=切行 · Q=清槽 · E=当前槽导入搜索框
//   全局：Delete/Backspace=删悬停格 · Ctrl+Z=撤销 · Shift=移动↔笔刷切换（按住 Shift 拖拽=平移，不触发切换）
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
    let tool = 'move';     // 默认"移动"（先摆视角）；笔刷/框选/占位 = 写入模式（左键不转视角）
    let initialized = false;
    // ===== P15 方块槽位缓存栏（9×N，N 可配置；槽位 = {id: 'block[props]', nbt: tagged|null}）=====
    const SLOTS_PER_ROW = 9;
    let slotRowsN = 3;
    let slots = [];            // [row][col] → {id, nbt} | null
    let slotRow = 0, slotCol = 0;
    let boxA = null;           // 框选工具第一角点 [x,y,z]（局部坐标）
    let lastMouse = null;      // 画布上的最后指针位置（Delete/快捷键拾取用）
    // P12 jigsaw 字段编辑：记录被改过的片段 + 原始快照（撤销用）
    let jigsawEdited = new Set();
    let jigsawBackup = new Map();
    // P12 方块实体 NBT 编辑：同上（blockNbt 列表）
    let blockNbtEdited = new Set();
    let blockNbtBackup = new Map();
    let lastSelectedPiece = null;   // P14：片段切换提示（恢复选择用）
    let autoSaveNote = 0;           // P14：重开编辑器时自动写回的片段数（状态栏提示）

    // ===== P14 未写回修改（dirty）跟踪 =====
    // 语义：编辑已进本地 blueprint（内存），「写回」才同步主窗口并落盘 → dirty = 改过未写回
    function dirtyCount() {
        let n = jigsawEdited.size + blockNbtEdited.size;
        if (pv) n += pv.debugStats().editedPieces.length;
        return n;
    }
    function pushDirty() {
        if (window.api && window.api.notifyEditorDirty) window.api.notifyEditorDirty(dirtyCount());
    }
    /** 立即写回全部未写回修改；@returns 写回的片段数 */
    function flushEdits() {
        if (!pv || !state) return 0;
        const st = pv.debugStats();
        if (!st.editedPieces.length && !jigsawEdited.size && !blockNbtEdited.size) return 0;
        const pieces = collectEdits();
        if (!pieces.length) return 0;
        window.api.savePieceEdits({ packDir: state.packDir, structureId: state.structureId, pieces });
        if (pv) pv.clearEdits();
        jigsawEdited.clear();
        blockNbtEdited.clear();
        // 注意：【不清】jigsawBackup/blockNbtBackup —— 撤销功能依赖原始快照
        //（写回后仍可撤销本地蓝图到写回前状态，与旧行为一致）
        updateInfo();
        pushDirty();
        return pieces.length;
    }

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

    // 工具切换：按钮高亮 + 左键拖拽门控（移动=转视角；笔刷/框选/占位=写，不转）+ 悬停高亮（随工具配色）
    function setTool(name) {
        tool = name;
        document.querySelectorAll('.ed-tool[data-tool]').forEach((b) => b.classList.toggle('active', b.dataset.tool === name));
        // 离开框选工具 → 丢弃未完成的角点
        if (boxA) { boxA = null; if (pv) pv.setSelectionBox(null); }
        if (!pv) return;
        pv.setOrbitOnLeft(name === 'move');
        // P15 用户补充：移动模式滚轮=缩放；笔刷/框选/占位滚轮=切槽（Ctrl+滚轮始终缩放）
        pv.setWheelMode(name === 'move' ? 'zoom' : 'slots');
        if (name === 'move') {
            pv.setHoverEnabled(false);
        } else {
            pv.setHoverEnabled(true);
            pv.setHoverColor(name === 'box' ? 0xf9e2af : name === 'placeholder' ? 0x89b4fa : 0xa6e3a1);
        }
        updateHint();
    }

    // ===== P15 底部提示栏：按当前工具切换 =====
    function updateHint() {
        const el = $('edHint');
        if (!el) return;
        const key = tool === 'move' ? 'structure.piece3d.hintMove'
            : tool === 'paint' ? 'structure.piece3d.hintPaint'
            : tool === 'box' ? 'structure.piece3d.hintBox'
            : 'structure.piece3d.hintPlaceholder';
        el.textContent = t(key);
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
    let loadSeq = 0; // P14：payload 到达计数（E2E 等待 state 替换完成，避免读到旧窗口状态）
    window.api.onPieceEditorLoad((payload) => {
        // P14：有未写回修改 → 先自动写回（修改属于旧结构，payload 已到达，不能丢）
        if (initialized && pv && dirtyCount()) {
            const n = flushEdits();
            if (n) autoSaveNote = n;
        }
        state = payload;
        loadSeq++;
        if (!initialized) { init(); return; }
        // 再次打开（换结构/换片段）→ 重置本地编辑并加载新数据
        if (pv) pv.clearEdits();
        jigsawEdited.clear();
        jigsawBackup.clear();
        blockNbtEdited.clear();
        blockNbtBackup.clear();
        pushDirty();
        syncPieceSelect();
        selectPiece(state.pieceName);
        status((autoSaveNote ? tf('structure.piece3d.autoSaved', { n: autoSaveNote }) + ' · ' : '')
            + t('structure.piece3d.loaded') + ' — ' + (state.structureId || '') + (state.pieceName ? ' / ' + state.pieceName : ''));
        autoSaveNote = 0;
    });

    async function init() {
        try {
            const mod = await import('../lib/structurePreview.js');
            pv = mod.createStructurePreview($('edCanvas'), {
                onChange: pushDirty, // P14：dirty 通知
                onWheelSlot: (d) => selectSlot(slotRow, (slotCol + d + SLOTS_PER_ROW) % SLOTS_PER_ROW), // P15 滚轮切槽
            });
            window.__pieceEditor = {
                pv: () => pv,
                getState: () => state,
                inspect: inspectVoxel,
                openMenuAt: openMenuAtCell, // P12 E2E：指定格开中键菜单
                dirtyCount: () => dirtyCount(), // P14 调试
                loadSeq: () => loadSeq, // P14：payload 到达计数（E2E 等待用）
                // P15 槽位/快捷键/框选（E2E + 调试）
                palette: () => ({ rows: slotRowsN, row: slotRow, col: slotCol,
                    slots: slots.map((r) => r.map((s) => (s ? { id: s.id, hasNbt: !!s.nbt } : null))) }),
                refillSlot: (id, nbt) => { refillCurrentSlot(id, nbt); return currentSlot(); },
                clearSlot: () => clearCurrentSlot(),
                selectSlot,
                toggleTool: () => toggleMovePaint(),
                undoAll,
                deleteHovered,
                boxFill: (a, b) => doBoxFill(a, b),
                boxState: () => boxA,
                importSlotToSearch,
                hint: () => ($('edHint') ? $('edHint').textContent : ''),
                // P16b 虚影拼合（E2E + 调试）
                ghost: () => ({ on: ghostOn, mode: ghostMode, seed: ghostSeed, idx: ghostCandIdx, cands: edGhostCandidates().length }),
                ghostInfo: () => (edGhostInfo ? ($('edGhostInfo') ? $('edGhostInfo').textContent : '') : ''),
            }; // E2E/调试钩子
            pv.start();
            // 编辑器窗口用按需渲染（交互/编辑时渲染，不跑 60fps 连续循环）：
            // 省主线程 → 搜索输入/右键菜单不卡；rebuild 末尾强制 renderOnce → 编辑即时可见
            pv.setContinuous(false);
            // 滚轮模式随工具（setTool 维护）：移动=缩放，笔刷/框选/占位=切槽（默认工具=移动）
            pv.setWheelMode(tool === 'move' ? 'zoom' : 'slots');
            pv.setOrbitOnLeft(tool === 'move');
            syncPieceSelect();
            selectPiece(state.pieceName);
            wireUI();
            initPalette();
            updateHint();
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
        lastSelectedPiece = piece.name; // P14
        // P14 顺序关键：先重置剖切（若切片激活会触发一轮旧片段重建），
        // 再换片段 —— 新片段的构建序号在后 → 并发时新片段场景必然生效
        pv.setSlice(null);
        if (pv.setWorldSlice) pv.setWorldSlice(null); // P16b：镜像的世界切片一并清
        pv.setPiece(piece);
        $('edSliceAxis').value = 'off';
        $('edSliceI').value = '0';
        syncSliceControl();
        updateInfo();
        status(t('structure.piece3d.loaded') + ' — ' + piece.name + ' (' + (piece.size || [0, 0, 0]).join('×') + ')');
        applyEdGhost(); // P16b：虚影随片段重算（保留开/关/模式状态）
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
        if (axis === 'off') {
            pv.setSlice(null);
            if (pv.setWorldSlice) pv.setWorldSlice(null); // P16b：同步切虚影
        } else {
            pv.setSlice({ axis, index: i });
            // P16b：本地切片（片段在原点 rot=0）镜像为世界切片 → 同一平面切实体+虚影
            //（实体被两层同平面切片，结果幂等；相机越面自动翻转由渲染器处理）
            if (pv.setWorldSlice) pv.setWorldSlice({ axis, index: i });
        }
    }

    // ===== P16b：jigsaw 虚影拼合（给人类编辑者参考：不同拼图接缝处会附着什么）=====
    // 当前片段在原点（rot=0）：每个带池 jigsaw 块 → 池逻辑 1:1 附着（poolPick1to1）或
    // 手动循环全部候选（jigsaw × 模板 × 旋转）。半透明体素层（pv.setGhostPlacements）。
    // 编辑器的 lastBuild.pieces 只含当前片段 → 模板定义经 opts.pieces 传入 + warmUpPieces 补贴图。
    let ghostOn = false;
    let ghostMode = 'pool';
    let ghostSeed = 1;
    let ghostCandIdx = 0;
    let ghostApplySeq = 0; // 贴图预热重放防竞态

    function edGhostInfo(s) {
        const el = $('edGhostInfo');
        if (el) el.textContent = s || '';
    }
    function edHashStr(s) {
        let h = 2166136261;
        for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
        return h >>> 0;
    }
    /** 当前片段（原点）的全部 1:1 候选展平（手动模式循环列表；顺序确定性） */
    function edGhostCandidates() {
        const G = window.StructureGrowth;
        const piece = currentPiece();
        if (!G || !state || !piece) return [];
        const out = [];
        const jis = (piece.jigsaws || []).map((j, ji) => ({ j, ji })).filter(({ j }) => j && j.pool);
        for (const { ji } of jis) {
            const r = G.jigsawCandidates(state.blueprint, piece.name, ji, [0, 0, 0]);
            for (const c of r.cands) out.push(Object.assign({ srcJi: ji }, c));
        }
        return out;
    }
    /** 取景 = 片段 + 虚影包围盒（保留当前 θ/φ）；ghostList 空 → 只取景片段 */
    function edReframe(piece, ghostList) {
        if (!pv || !pv.reframe || !piece) return;
        const [w, h, d] = piece.size || [1, 1, 1];
        let mn = [0, 0, 0], mx = [w, h, d];
        const byName = new Map();
        for (const p of (state && state.blueprint && state.blueprint.pieces) || []) byName.set(p.name, p);
        for (const pl of ghostList || []) {
            const tp = byName.get(pl.pieceName);
            if (!tp) continue;
            const [tw, th, td] = tp.size || [1, 1, 1];
            mn = [Math.min(mn[0], pl.offset[0]), Math.min(mn[1], pl.offset[1]), Math.min(mn[2], pl.offset[2])];
            mx = [Math.max(mx[0], pl.offset[0] + tw), Math.max(mx[1], pl.offset[1] + th), Math.max(mx[2], pl.offset[2] + td)];
        }
        pv.reframe(mn, mx);
    }
    function applyEdGhost() {
        if (!pv || !pv.setGhostPlacements) return;
        const piece = currentPiece();
        if (!ghostOn || !piece) {
            pv.setGhostPlacements([]);
            edGhostInfo('');
            edReframe(piece, []);
            return;
        }
        const G = window.StructureGrowth;
        if (!G) { edGhostInfo('growth module missing'); return; }
        const allPieces = (state && state.blueprint && state.blueprint.pieces) || [];
        const op = Math.max(10, Math.min(80, +($('edInpGhostOp') ? $('edInpGhostOp').value : 35) || 35)) / 100;
        const list = [];
        if (ghostMode === 'pool') {
            // 池逻辑：当前片段的每个带池 jigsaw → 1 个 1:1 附着（确定性种子 = seed ^ 位置哈希）
            (piece.jigsaws || []).forEach((j, ji) => {
                if (!j || !j.pool) return;
                const seed = (ghostSeed ^ (edHashStr(piece.name + ':' + ji) >>> 0)) >>> 0;
                const c = G.poolPick1to1(state.blueprint, piece.name, ji, [0, 0, 0], G.mulberry32(seed));
                if (c) list.push({ pieceName: c.template, offset: c.offset, rotation: c.rotation });
            });
            edGhostInfo(t('structure.3d.ghostPool') + ' · ' + list.length + ' 个 1:1 附着（🎲 换种子）');
        } else {
            // 手动：循环当前片段全部 1:1 候选
            const cands = edGhostCandidates();
            if (!cands.length) {
                edGhostInfo('该片段无匹配候选（池/目标/orientation 对不上）');
                pv.setGhostPlacements([]);
                return;
            }
            ghostCandIdx = ((ghostCandIdx % cands.length) + cands.length) % cands.length;
            const c = cands[ghostCandIdx];
            list.push({ pieceName: c.template, offset: c.offset, rotation: c.rotation });
            edGhostInfo('[' + (ghostCandIdx + 1) + '/' + cands.length + '] ' + piece.name + '·' + c.jigsawName +
                ' → ' + c.template + ' · rot' + c.rotation + ' · 目标 ' + c.targetName + ' · offset[' + c.offset.join(',') + ']');
        }
        pv.setGhostPlacements(list, { opacity: op, pieces: allPieces });
        edReframe(piece, list);
        // 贴图预热：模板方块可能未烘焙（当前片段的构建只烘了自己的）→ 到位后重放升级贴图
        const seq = ++ghostApplySeq;
        const tpls = [...new Set(list.map((l) => l.pieceName))].map((n) => allPieces.find((p) => p.name === n)).filter(Boolean);
        if (tpls.length && pv.warmUpPieces) {
            pv.warmUpPieces(tpls).then(() => {
                if (seq !== ghostApplySeq || !ghostOn) return; // 已被后续操作取代
                pv.setGhostPlacements(list, { opacity: op, pieces: allPieces });
            }).catch(() => { /* 贴图缺失时虚影保持 unitBox 回退 */ });
        }
    }

    // ===== UI 绑定 =====
    function wireUI() {
        // 左侧工具按钮（移动 / 笔刷 / 框选 / 占位）
        document.querySelectorAll('.ed-tool[data-tool]').forEach((btn) => {
            btn.addEventListener('click', () => setTool(btn.dataset.tool));
        });
        $('edResetView').addEventListener('click', () => { if (pv) pv.resetView(); });
        $('edSliceAxis').addEventListener('change', sliceChange);
        $('edSliceI').addEventListener('input', sliceChange);
        // P16b：jigsaw 虚影拼合（接缝参考）
        $('edChkGhost').addEventListener('change', (e) => { ghostOn = e.target.checked; applyEdGhost(); });
        $('edSelGhostMode').addEventListener('change', (e) => { ghostMode = e.target.value; ghostCandIdx = 0; applyEdGhost(); });
        $('edBtnGhostPrev').addEventListener('click', () => {
            const n = edGhostCandidates().length;
            if (n) ghostCandIdx = ((((ghostCandIdx || 0) - 1) % n) + n) % n;
            applyEdGhost();
        });
        $('edBtnGhostNext').addEventListener('click', () => {
            const n = edGhostCandidates().length;
            if (n) ghostCandIdx = (ghostCandIdx || 0) + 1; // applyEdGhost 内取模回绕
            applyEdGhost();
        });
        $('edBtnGhostReroll').addEventListener('click', () => {
            ghostSeed = (Math.imul(ghostSeed, 1103515245) + 12345) >>> 0;
            applyEdGhost();
        });
        $('edInpGhostOp').addEventListener('input', () => { if (ghostOn) applyEdGhost(); });
        // P14：切换片段 → 有未写回修改先提示（修改仍保留在本窗口，写回才同步主窗口）
        $('edPiece').addEventListener('change', () => {
            const next = $('edPiece').value;
            window.__peSwitchLog = window.__peSwitchLog || [];
            window.__peSwitchLog.push({ next, dirty: dirtyCount(), last: lastSelectedPiece, t: Date.now() });
            if (dirtyCount() && next !== lastSelectedPiece) {
                const ok = window.confirm(tf('structure.piece3d.dirtySwitch', { n: dirtyCount() }));
                window.__peSwitchLog.push({ decided: ok ? 'confirm-yes' : 'confirm-no' });
                if (!ok) { $('edPiece').value = lastSelectedPiece || next; return; }
            }
            selectPiece(next);
            window.__peSwitchLog.push({ decided: 'direct' });
        });

        // 撤销（按钮 + P15 Ctrl+Z 共用）/ 写回
        $('edUndo').addEventListener('click', () => undoAll());
        $('edSave').addEventListener('click', () => saveEdits());

        // 画布（P15 定稿，不复合）：
        //   左键：笔刷/占位 单击=替换命中格 / 拖拽=笔触；框选 点两角点=填充；移动 拖拽=旋转
        //   右键：单击=命中面相邻格放置（仅单击）；拖拽=平移
        //   中键：移动模式=编辑菜单（NBT/jigsaw/props）；笔刷/框选/占位=补货当前槽位（悬停方块+NBT）
        const canvas = $('edCanvas');
        let down = null, rdown = null, mdown = null;
        let painting = false, lastPaintKey = '';
        // 在 (cx,cy) 落一笔：action 'replace'=命中格 / 'place'=相邻格
        // 槽位带 NBT 时同步写入目标格 blockNbt（同块备份，撤销可还原）
        const strokeAt = (cx, cy, action) => {
            if (!pv) return;
            const pick = pv.pickVoxel(cx, cy);
            if (!pick) return;
            const id = blockIdForTool();
            pv.brushVoxel(pick, id, action).then(() => {
                const s = currentSlot();
                const piece = currentPiece();
                if (s && s.nbt && piece && id !== 'minecraft:air' && id !== '#empty') {
                    writeNbtToCells(piece, pv.brushTargetCells(pick, action), s.nbt);
                }
                updateInfo();
            });
        };
        // 笔触去重：同一命中格+同动作不重复写
        const paintAt = (cx, cy, action) => {
            if (!pv || tool === 'move' || tool === 'box') return;
            const pick = pv.pickVoxel(cx, cy);
            if (!pick) return;
            const key = pick.piece + '|' + pick.local.join(',') + '|' + action;
            if (key === lastPaintKey) return;
            lastPaintKey = key;
            strokeAt(cx, cy, action);
        };
        // 框选：左键第一下=角点 A；第二下=角点 B → 填充 [min..max] 当前槽位方块
        canvas.addEventListener('pointerdown', (e) => {
            if (e.button === 0) {
                down = { x: e.clientX, y: e.clientY };
                lastMouse = { x: e.clientX, y: e.clientY };
                if (tool === 'box') {
                    if (!pv) return;
                    const pick = pv.pickVoxel(e.clientX, e.clientY);
                    if (!pick) return;
                    if (!boxA) {
                        boxA = pick.local.slice();
                        pv.setSelectionBox(boxA, boxA);
                    } else {
                        const a = boxA;
                        boxA = null;
                        pv.setSelectionBox(null);
                        doBoxFill(a, pick.local);
                    }
                    return;
                }
                if (tool !== 'move') { painting = true; lastPaintKey = ''; paintAt(e.clientX, e.clientY, 'replace'); }
            } else if (e.button === 1) {
                mdown = { x: e.clientX, y: e.clientY };
                e.preventDefault(); // 防中键自动滚屏
            } else if (e.button === 2) {
                rdown = { x: e.clientX, y: e.clientY };
            }
        });
        canvas.addEventListener('pointermove', (e) => {
            lastMouse = { x: e.clientX, y: e.clientY };
            if (painting && (e.buttons & 1)) paintAt(e.clientX, e.clientY, 'replace'); // 笔触
            // 框选角点 A 已定 → 悬停即预览填充范围
            if (tool === 'box' && boxA && !(e.buttons & 1) && pv) {
                const pick = pv.pickVoxel(e.clientX, e.clientY);
                if (pick) pv.setSelectionBox(boxA, pick.local);
            }
        });
        canvas.addEventListener('pointerup', (e) => {
            if (e.button === 0) { painting = false; down = null; }
            else if (e.button === 1) {
                if (!mdown) return;
                const moved = Math.hypot(e.clientX - mdown.x, e.clientY - mdown.y);
                mdown = null;
                if (moved > 4 || !pv) return;
                const pick = pv.pickVoxel(e.clientX, e.clientY);
                if (tool === 'move') {
                    showCtxMenu(e.clientX, e.clientY, pick); // P15：NBT 菜单保留在移动模式
                } else {
                    // P15：笔刷类模式中键 = 补货当前槽位（悬停方块 + NBT）
                    if (!pick) { status('光标下没有方块可补货', false); return; }
                    const piece = currentPiece();
                    const entry = piece && (piece.blockNbt || []).find((b) => b.x === pick.local[0] && b.y === pick.local[1] && b.z === pick.local[2]);
                    refillCurrentSlot(pick.blockId, entry ? entry.nbt : null);
                }
            } else if (e.button === 2) {
                if (!rdown) return;
                const moved = Math.hypot(e.clientX - rdown.x, e.clientY - rdown.y);
                rdown = null;
                if (moved > 4 || !pv || tool === 'move') return; // 右键拖拽=平移，不放置
                const pick = pv.pickVoxel(e.clientX, e.clientY);
                if (!pick) return;
                lastPaintKey = '';
                strokeAt(e.clientX, e.clientY, 'place');
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

        // ===== P15 快捷键 =====
        // 1-9/滚轮=切槽 · +/-/PgUp/PgDn=切行 · Q=清槽 · E=当前槽导入搜索框 · Delete/Backspace=删悬停格
        // Ctrl+Z=撤销 · Shift=移动↔笔刷（按住 Shift 拖拽=平移：按住期间有指针按下则不触发切换）
        let shiftDown = false, shiftWithPointer = false;
        canvas.addEventListener('pointerdown', () => { if (shiftDown) shiftWithPointer = true; });
        window.addEventListener('keydown', (e) => {
            if (isTyping(e)) return;
            if (e.key === 'Shift' && !e.repeat) { shiftDown = true; shiftWithPointer = false; return; }
            if (e.ctrlKey || e.metaKey) {
                if ((e.key || '').toLowerCase() === 'z') { e.preventDefault(); undoAll(); } // P15：Ctrl+Z 撤销
                return; // Ctrl+C/V 暂缓（业务逻辑未定）
            }
            if (e.altKey) return;
            const k = e.key || '';
            if (k === 'Delete' || k === 'Backspace') { deleteHovered(); e.preventDefault(); return; }
            if (e.repeat) return;
            const lk = k.toLowerCase();
            if (lk >= '1' && lk <= '9') { selectSlot(slotRow, +lk - 1); return; }
            if (k === '+' || k === '=') { changeRow(+1); e.preventDefault(); return; }
            if (k === '-') { changeRow(-1); e.preventDefault(); return; }
            if (k === 'PageUp') { changeRow(-1); e.preventDefault(); return; }
            if (k === 'PageDown') { changeRow(+1); e.preventDefault(); return; }
            if (lk === 'q') { clearCurrentSlot(); return; }
            if (lk === 'e') { importSlotToSearch(); return; }
        });
        window.addEventListener('keyup', (e) => {
            if (e.key === 'Shift' && shiftDown) {
                shiftDown = false;
                if (!shiftWithPointer && !isTyping(e)) toggleMovePaint();
            }
        });

        // 菜单外点击 / Esc 关闭（Esc 同时取消框选角点 A）
        document.addEventListener('click', (e) => { if (!e.target.closest('#edCtx')) hideCtx(); });
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                hideCtx();
                if (boxA) { boxA = null; if (pv) pv.setSelectionBox(null); }
            }
        });

        // 快捷方块按钮 + 搜索（P15：选中方块 = refill 当前槽位 + 自动切到笔刷工具，直接可点写）
        $('edBtnJigsaw').addEventListener('click', () => { $('edBlock').value = 'minecraft:jigsaw'; refillCurrentSlot('minecraft:jigsaw', null); setTool('paint'); hideBlockList(); });
        $('edBtnVoid').addEventListener('click', () => { $('edBlock').value = 'minecraft:structure_void'; refillCurrentSlot('minecraft:structure_void', null); setTool('paint'); hideBlockList(); });
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
                if (first) {
                    e.preventDefault();
                    inp.value = first.dataset.id;
                    refillCurrentSlot(first.dataset.id, null); // P15：搜索选中 = refill 当前槽位
                    setTool('paint');
                    hideBlockList();
                }
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
            row.addEventListener('mousedown', (e) => { e.preventDefault(); inp.value = id; refillCurrentSlot(id, null); setTool('paint'); hideBlockList(); });
            listEl.appendChild(row);
        }
        listEl.style.display = matches.length ? 'block' : 'none';
    }

    // ===== P15 方块槽位缓存栏（9×N，N 可配置；槽位 = {id: 'block[props]', nbt: tagged|null}）=====
    // 填充路径：① 笔刷类模式中键=refill（悬停方块+NBT）② 搜索框/快捷按钮选中=refill
    // ③ 右键菜单"用作笔刷"=refill（含 NBT）。取用：E=当前槽导入搜索框；左键/框选=当前槽方块
    function initPalette() {
        slots = Array.from({ length: slotRowsN }, () => Array(SLOTS_PER_ROW).fill(null));
        const rowsInput = $('edSlotRows');
        if (rowsInput) {
            rowsInput.value = String(slotRowsN);
            rowsInput.addEventListener('change', () => {
                const n = Math.max(1, Math.min(6, parseInt(rowsInput.value, 10) || 1));
                rowsInput.value = String(n);
                slotRowsN = n;
                while (slots.length < n) slots.push(Array(SLOTS_PER_ROW).fill(null));
                if (slots.length > n) slots.length = n;
                if (slotRow >= n) slotRow = n - 1;
                renderPalette();
            });
        }
        renderPalette();
    }
    function currentSlot() { return (slots[slotRow] && slots[slotRow][slotCol]) || null; }
    function refillCurrentSlot(id, nbt) {
        if (!slots.length) return;
        slots[slotRow][slotCol] = id ? { id, nbt: nbt || null } : null;
        renderPalette();
        status('槽位 ' + (slotRow + 1) + '-' + (slotCol + 1) + (id ? ' ← ' + id : '（已清空）'), false);
    }
    function clearCurrentSlot() { refillCurrentSlot(null, null); }
    function selectSlot(r, c) {
        slotRow = Math.max(0, Math.min(slotRowsN - 1, r));
        slotCol = Math.max(0, Math.min(SLOTS_PER_ROW - 1, c));
        renderPalette();
    }
    function changeRow(d) { selectSlot(slotRow + d, slotCol); }
    function importSlotToSearch() {
        const s = currentSlot();
        const inp = $('edBlock');
        if (inp) inp.value = s ? s.id : '';
        status(s ? '当前槽 → 搜索框: ' + s.id : '当前槽位为空', false);
    }
    function renderPalette() {
        const el = $('edPalette');
        if (!el) return;
        el.innerHTML = '';
        for (let r = 0; r < slots.length; r++) {
            const rowEl = document.createElement('div');
            rowEl.className = 'ed-palrow';
            for (let c = 0; c < SLOTS_PER_ROW; c++) {
                const s = slots[r][c];
                const d = document.createElement('div');
                d.className = 'ed-slot' + (r === slotRow && c === slotCol ? ' active' : '');
                d.dataset.row = String(r);
                d.dataset.col = String(c);
                if (r === slotRow) {
                    const idx = document.createElement('span');
                    idx.className = 'ed-slot-idx';
                    idx.textContent = String(c + 1);
                    d.appendChild(idx);
                }
                if (s) {
                    const cv = document.createElement('canvas');
                    cv.width = 64; cv.height = 64;
                    d.appendChild(cv);
                    if (s.nbt) {
                        const nb = document.createElement('span');
                        nb.className = 'ed-slot-nbt';
                        nb.textContent = '⚙';
                        d.appendChild(nb);
                    }
                    d.title = s.id;
                    ensureSlotIcon(cv, s.id);
                } else {
                    const em = document.createElement('span');
                    em.className = 'ed-slot-empty';
                    em.textContent = '·';
                    d.appendChild(em);
                }
                d.addEventListener('click', () => selectSlot(r, c));
                rowEl.appendChild(d);
            }
            el.appendChild(rowEl);
        }
    }
    // 面贴图加载（mcBlockFaces：top/side/bottom dataURL → Image；按 blockId 缓存 + 去重）
    const faceImgCache = new Map();
    const faceImgPending = new Map();
    function loadFaceImages(blockId) {
        if (faceImgCache.has(blockId)) return faceImgCache.get(blockId);
        if (faceImgPending.has(blockId)) return faceImgPending.get(blockId);
        const p = (async () => {
            const faces = { top: null, side: null };
            try {
                const res = await window.api.mcBlockFaces(blockId);
                if (res && res.ok && res.data) {
                    for (const k of ['top', 'side']) {
                        const url = res.data[k];
                        if (!url) continue;
                        const img = new Image();
                        img.src = url;
                        await new Promise((done) => { img.onload = done; img.onerror = done; });
                        if (img.naturalWidth) faces[k] = img;
                    }
                }
            } catch {}
            return faces;
        })();
        faceImgPending.set(blockId, p);
        p.then((f) => { faceImgCache.set(blockId, f); faceImgPending.delete(blockId); });
        return p;
    }
    async function ensureSlotIcon(cv, blockId) {
        const faces = await loadFaceImages(blockId);
        if (cv.isConnected) drawIsoIcon(cv, faces); // 重渲染后旧 canvas 已摘除 → 不画
    }
    // 槽位图标：真等距投影（三投影轴互成 120°，正六边形轮廓，隐藏背顶点与正面顶角 2D 重合于中心）。
    // 原版物品栏视角：顶面朝上 · 正面（南 +Z）在右下 · 东面在左下；面亮度 顶1.0 南0.8 东0.6
    function drawIsoIcon(canvas, faces) {
        const size = 64;
        const g = canvas.getContext('2d');
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.clearRect(0, 0, size, size);
        g.imageSmoothingEnabled = false;
        const s = 25; // 边长（六边形高 2s=50 < 64）
        const cx = size / 2, cy = size / 2;
        const C30 = Math.cos(Math.PI / 6);
        // 投影轴（每单位）：+X 东=(-C30·s, 0.5s) · +Z 南=(C30·s, 0.5s) · +Y 上=(0,-s) —— 两两 120°
        const X = { x: -C30 * s, y: s / 2 };
        const Z = { x: C30 * s, y: s / 2 };
        const Y = { x: 0, y: -s };
        const P = (x, y, z) => ({ x: cx + x * X.x + y * Y.x + z * Z.x, y: cy + x * X.y + y * Y.y + z * Z.y });
        // 面绘制：o=纹理(0,0)对应顶点；uv/vv=每单位 u/v 向量（16px 纹理）
        const drawFace = (o, uv, vv, img, shade) => {
            g.save();
            if (shade !== 1) g.filter = 'brightness(' + shade + ')';
            g.setTransform(uv.x / 16, uv.y / 16, vv.x / 16, vv.y / 16, o.x, o.y);
            if (img && img.complete && img.naturalWidth) g.drawImage(img, 0, 0);
            else g.fillRect(0, 0, 16, 16); // 无贴图：纯色面
            g.restore();
        };
        const top = faces.top || faces.side;
        const side = faces.side || faces.top;
        // 顶面：原点 (0,1,0) 顶角；u=+X（西→东）v=+Z（北→南）
        drawFace(P(0, 1, 0), X, Z, top, 1.0);
        // 南面（正面，右下）：原点 (0,1,1) 右上角；u=+X v=下；无贴图时用深色
        g.save(); g.filter = 'brightness(0.8)';
        if (!side) g.fillStyle = '#3b3b4f';
        drawFace(P(0, 1, 1), X, { x: 0, y: s }, side, 0.8);
        g.restore();
        // 东面（左下）：原点 (1,1,1) 中心；u=-Z（南→北，外侧观看纹理不镜像）v=下
        g.save(); g.filter = 'brightness(0.6)';
        if (!side) g.fillStyle = '#31313f';
        drawFace(P(1, 1, 1), { x: -Z.x, y: -Z.y }, { x: 0, y: s }, side, 0.6);
        g.restore();
    }

    // ===== P15 快捷键动作 =====
    /** 撤销（↩ 按钮 + Ctrl+Z）：jigsaw 字段 / blockNbt / 体素编辑 全部还原到快照 */
    function undoAll() {
        if (!pv || !state) return;
        for (const [name, orig] of jigsawBackup) {
            const i = (state.blueprint.pieces || []).findIndex((p) => p.name === name);
            if (i >= 0) state.blueprint.pieces[i].jigsaws = JSON.parse(JSON.stringify(orig));
        }
        jigsawEdited.clear();
        jigsawBackup.clear();
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
    }
    /** Delete：删悬停格（左键会命中的那格 → air） */
    function deleteHovered() {
        if (!pv || !lastMouse) { status('光标移到方块上再按 Delete', false); return; }
        const pick = pv.pickVoxel(lastMouse.x, lastMouse.y);
        if (!pick) { status('光标下没有方块', false); return; }
        pv.brushVoxel(pick, 'minecraft:air', 'replace').then(() => updateInfo());
    }
    function toggleMovePaint() { setTool(tool === 'move' ? 'paint' : 'move'); }
    /** 框选填充：[a..b] 闭区间 → 当前槽位方块（带 NBT 时逐格写入） */
    function doBoxFill(a, b) {
        const piece = currentPiece();
        if (!piece || !pv || !a || !b) return;
        const id = blockIdForTool();
        const min = [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])];
        const max = [Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])];
        pv.fillBox(piece.name, min, max, id).then(() => {
            const s = currentSlot();
            if (s && s.nbt && id !== 'minecraft:air' && id !== '#empty') {
                const cells = [];
                for (let x = min[0]; x <= max[0]; x++)
                    for (let y = min[1]; y <= max[1]; y++)
                        for (let z = min[2]; z <= max[2]; z++) cells.push([x, y, z]);
                writeNbtToCells(piece, cells, s.nbt);
            }
            updateInfo();
            status('框选填充 ' + (max[0] - min[0] + 1) + '×' + (max[1] - min[1] + 1) + '×' + (max[2] - min[2] + 1) + ' — ' + id);
        });
    }
    /** 把 NBT 批量写入格（首改前备份整片 blockNbt → 撤销可还原） */
    function writeNbtToCells(piece, cells, nbt) {
        if (!piece || !cells || !cells.length || !nbt) return;
        if (!blockNbtBackup.has(piece.name)) blockNbtBackup.set(piece.name, JSON.parse(JSON.stringify(piece.blockNbt || [])));
        if (!Array.isArray(piece.blockNbt)) piece.blockNbt = [];
        for (const [x, y, z] of cells) {
            const entry = { x, y, z, nbt: JSON.parse(JSON.stringify(nbt)) };
            const i = piece.blockNbt.findIndex((b) => b.x === x && b.y === y && b.z === z);
            if (i >= 0) piece.blockNbt[i] = entry; else piece.blockNbt.push(entry);
        }
        blockNbtEdited.add(piece.name);
        pushDirty();
    }
    /** 笔刷方块 = 当前槽位；槽位空 → 回退搜索框值；占位工具恒 '#empty' */
    function blockIdForTool() {
        if (tool === 'placeholder') return '#empty';
        const s = currentSlot();
        if (s && s.id) return s.id;
        const inp = $('edBlock');
        return (inp.value || '').trim() || 'minecraft:stone_bricks';
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
                pushDirty(); // P14
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
            pushDirty(); // P14
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
            pushDirty(); // P14
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
        $('edCtxUse').addEventListener('click', () => {
            // P15：用作笔刷 = 当前槽位（连带该格 NBT，若有）
            $('edBlock').value = pick.blockId;
            const piece = currentPiece();
            const entry = piece && (piece.blockNbt || []).find((b) => b.x === x && b.y === y && b.z === z);
            refillCurrentSlot(pick.blockId, entry ? entry.nbt : null);
            setTool('paint');
            hideCtx();
        });
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
        const n = flushEdits();
        if (!n) { status(t('structure.piece3d.noEdit'), false); return false; }
        status(tf('structure.piece3d.saved', { n }), false);
        return true;
    }
    // P14 关窗提示由主进程对话框负责（pieceEditor:dirty 计数）：取消 / 写回并关闭 / 不保存关闭。
    // 「写回并关闭」：主进程下发 flush → 这里同步写回（ipcRenderer.send 可靠）→ 回报放行
    if (window.api && window.api.onPieceEditorFlush) {
        window.api.onPieceEditorFlush(() => {
            flushEdits();
            if (window.api.notifyEditorFlushed) window.api.notifyEditorFlushed();
        });
    }
})();
