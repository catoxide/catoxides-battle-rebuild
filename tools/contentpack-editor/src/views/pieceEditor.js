// pieceEditor.js - P11 片段 3D 编辑器窗口（独立窗口渲染进程）
// 布局：顶栏（片段选择/方块搜索/快捷按钮/撤销/写回）+ 左侧工具栏（工具/笔刷/剖切/视角）+ 画布 + 状态栏
// 交互：左键单击 = 应用当前工具；右键单击方块 = 查组件/NBT（jigsaw 组件含朝向；blockstate 属性参考）
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

    // 工具切换：更新按钮高亮 + 预览器左键拖拽门控（移动=转视角；写入=左键点按，不与视角冲突）
    function setTool(name) {
        tool = name;
        document.querySelectorAll('.ed-tool[data-tool]').forEach((b) => b.classList.toggle('active', b.dataset.tool === name));
        if (pv) pv.setOrbitOnLeft(name === 'move');
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
    function brushSize() { return parseInt($('edBrushSize').value, 10) || 1; }

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
            pv.clearEdits().then(() => { updateInfo(); status(t('structure.3d.edit.undoDone'), false); });
        });
        $('edSave').addEventListener('click', () => saveEdits());

        // 画布：左键单击 = 工具；右键单击 = 组件菜单（右键拖拽 = 平移，不弹菜单）
        const canvas = $('edCanvas');
        let down = null, rdown = null;
        canvas.addEventListener('pointerdown', (e) => {
            if (e.button === 0) down = { x: e.clientX, y: e.clientY, t: Date.now() };
            else if (e.button === 2) rdown = { x: e.clientX, y: e.clientY };
        });
        canvas.addEventListener('pointerup', (e) => {
            if (!down || !pv) { down = null; return; }
            const moved = Math.hypot(e.clientX - down.x, e.clientY - down.y);
            const dt = Date.now() - down.t;
            down = null;
            if (e.button !== 0 || moved > 4 || dt > 450) return;
            if (tool === 'move') return; // 移动工具：左键只管视角，单击不写入
            const pick = pv.pickVoxel(e.clientX, e.clientY);
            if (!pick) return;
            const inp = $('edBlock');
            const blockId = (inp.value || '').trim() || 'minecraft:stone_bricks';
            const bid = tool === 'erase' ? 'minecraft:air' : (tool === 'placeholder' ? '#empty' : blockId);
            pv.brushVoxel(pick, bid, brushSize()).then(() => updateInfo());
        });
        canvas.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            if (rdown) {
                const moved = Math.hypot(e.clientX - rdown.x, e.clientY - rdown.y);
                rdown = null;
                if (moved > 4) return; // 右键拖拽 = 平移，不弹菜单
            }
            if (!pv) return;
            const pick = pv.pickVoxel(e.clientX, e.clientY);
            showCtxMenu(e.clientX, e.clientY, pick);
        });

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
        let html = `<div class="ed-ctx-head">🧊 ${esc(pick.blockId)}</div>`;
        html += `<div class="ed-ctx-row">${t('structure.piece3d.pos')} <b>${x}, ${y}, ${z}</b> <span class="note">· ${esc(pick.piece)}</span></div>`;
        if (jg) {
            html += `<div class="ed-ctx-sub">minecraft:jigsaw <span class="note">${t('structure.piece3d.component')}</span></div>`;
            html += `<pre class="ed-ctx-nbt">name: "${esc(jg.name || '')}"
pool: "${esc(jg.pool || '')}"
target: "${esc(jg.target || '')}"
orientation: "${esc(jg.orientation || '')}"
final_state: "${esc(jg.finalState || 'minecraft:air')}"
selection_priority: ${jg.selectionPriority != null ? jg.selectionPriority : 10}
placement_priority: ${jg.placementPriority != null ? jg.placementPriority : 10}</pre>`;
        }
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

        // 异步补 blockstate 定义（朝向/属性参考）
        if (window.api && window.api.blockState) {
            window.api.blockState(pick.blockId).then((res) => {
                if (menu.style.display === 'none') return;
                const slot = menu.querySelector('.ed-ctx-bs');
                if (!slot) return;
                if (!res || !res.ok || !res.data) {
                    slot.innerHTML = `<div class="note">${t('structure.piece3d.bsMissing')}</div>`;
                    return;
                }
                const d = res.data;
                const props = Object.entries(d.properties);
                let line = `<div class="ed-ctx-sub">blockstate · ${esc(d.file)}</div>`;
                if (!props.length) {
                    line += `<div class="note">${t('structure.piece3d.noProps')}</div>`;
                } else {
                    for (const [p, vs] of props) {
                        line += `<div class="ed-ctx-row"><b>${esc(p)}</b>: ${vs.map((v) => esc(v)).join(' / ')}</div>`;
                    }
                    line += `<div class="note">${t('structure.piece3d.propNote')}</div>`;
                }
                line += `<div class="note">${d.variantCount} variants</div>`;
                slot.innerHTML = line;
            }).catch(() => {});
        }
        $('edCtxUse').addEventListener('click', () => { $('edBlock').value = pick.blockId; setTool('paint'); hideCtx(); });
        $('edCtxCopy').addEventListener('click', () => {
            if (navigator.clipboard) navigator.clipboard.writeText(pick.blockId).catch(() => {});
            hideCtx();
        });
    }
    function hideCtx() { const m = $('edCtx'); if (m) m.style.display = 'none'; }

    // ===== 写回主窗口 =====
    function collectEdits() {
        if (!pv || !state) return [];
        const st = pv.debugStats();
        const out = [];
        for (const name of st.editedPieces) {
            const i = (state.blueprint.pieces || []).findIndex((p) => p.name === name);
            if (i < 0) continue;
            const map = pv.getPieceVoxelMap(name);
            if (!map) continue;
            const merged = window.Voxel.mergePieceEdits(state.blueprint.pieces[i], map);
            state.blueprint.pieces[i] = merged;
            out.push({ name, boxFills: merged.boxFills || [], fillBlocks: merged.fillBlocks || [] });
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
