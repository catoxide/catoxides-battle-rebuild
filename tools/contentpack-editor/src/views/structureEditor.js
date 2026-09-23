// structureEditor.js - 右栏：建筑结构（建筑拼图）编辑器（多标签页，每标签 5 页签）
// 依赖 window.api、window.AppState（app.js 定义）
// 暴露 window.StructureEditor.open({ pack, structure, isNew }) / closeActiveTab() / hasActiveTab()
//
// 数据流（对齐 EDITOR_EXTENSION_GUIDE 五步法）：
//   编辑 → 保存草稿（暂存区）→ 导出（暂存→包）/ 导出原版数据（blueprint→原版 4 类文件）→ 打包（build-pack.ps1）

window.StructureEditor = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));
    const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

    // ===== 多标签状态 =====
    let tabs = [];              // [{ uid, packDir, packName, structureId, isNew, bp, hiddenPieces:[] }]
    let activeUid = null;
    let uidCounter = 0;
    let activeStab = 'basic';   // 当前页签（标签间共享，切标签保持）
    let constants = null;       // 原版常量（steps/orientations...）

    function activeTab() { return tabs.find((x) => x.uid === activeUid) || null; }

    // ===== 状态条（独立于实体编辑器的 #statusBar） =====
    function showStatus(msg, ok) {
        const bar = document.getElementById('structStatus');
        if (bar) { bar.className = 'status ' + (ok ? 'ok' : 'err'); bar.textContent = msg; }
    }
    function showError(msg) { showStatus(msg, false); console.error('[StructEditor]', msg); }

    // ===== 打开/聚焦 =====
    async function ensureConstants() {
        if (constants) return constants;
        const res = await window.api.getStructureDefaults();
        if (res.ok) constants = res.data.constants;
        fillConstantSelects();
        return constants;
    }

    function fillConstantSelects() {
        if (!constants) return;
        const stepSel = document.getElementById('sStep');
        stepSel.innerHTML = constants.steps.map((s) => `<option value="${s}">${s}</option>`).join('');
        const taSel = document.getElementById('sTa');
        taSel.innerHTML = constants.terrainAdaptations.map((s) => `<option value="${s}">${s}</option>`).join('');
    }

    async function open(ctx) {
        await ensureConstants();
        const pack = ctx.pack;
        const isNew = !!ctx.isNew;
        const structure = ctx.structure || null;

        if (!isNew && structure && structure.structureId) {
            const existing = tabs.find((x) => x.packDir === pack.dir && x.structureId === structure.structureId && !x.isNew);
            if (existing) { activateTab(existing.uid); return; }
        }
        // 读蓝图（暂存优先由主进程处理）
        let bp = null;
        if (!isNew && structure && structure.structureId) {
            const res = await window.api.readStructure(pack.dir, structure.file, structure.structureId);
            if (!res.ok) { window.showError('读取结构失败: ' + res.error); return; }
            bp = res.data;
        } else {
            const res = await window.api.getStructureDefaults();
            bp = res.ok ? res.data.blueprint : null;
        }
        if (!bp) return;
        if (isNew) { bp.structureId = ''; }
        const uid = ++uidCounter;
        tabs.push({
            uid,
            packDir: pack.dir,
            packName: pack.name,
            structureId: bp.structureId || '',
            isNew,
            bp,
            hiddenPieces: [],
            _staged: !!(bp && bp._staged),
        });
        activateTab(uid);
    }

    function activateTab(uid) {
        const tab = tabs.find((x) => x.uid === uid);
        if (!tab) return;
        if (activeUid && activeUid !== uid) collectInto(activeTab());
        activeUid = uid;
        renderTabBar();
        renderFrame(tab);
        populate(tab.bp);
        renderLegend(tab);
        schedulePreviewUpdate(tab);
        if (window.applyEditorVisibility) window.applyEditorVisibility();
        // 竞态修复：用户可能在结构还在异步加载时就点了 3D 页签
        //（那时 activeTab() 还是 null，预览没拿到数据）→ 这里补跑一次 3D 初始化
        if (activeStab === '3d') switchTab('3d');
    }

    function closeActiveTab() {
        if (!activeUid) return;
        const tab = activeTab();
        if (tab) collectInto(tab);
        const idx = tabs.findIndex((x) => x.uid === activeUid);
        tabs.splice(idx, 1);
        stopPreview();
        if (tabs.length === 0) {
            activeUid = null;
            document.getElementById('structureEditorContent').classList.add('hidden');
            if (window.applyEditorVisibility) window.applyEditorVisibility();
            return;
        }
        activateTab(tabs[Math.min(idx, tabs.length - 1)].uid);
    }

    function hasActiveTab() { return activeUid !== null; }

    // ===== 标签栏 / 框架 =====
    function renderTabBar() {
        const bar = document.getElementById('structTabsBar');
        bar.innerHTML = '';
        for (const tab of tabs) {
            const chip = document.createElement('div');
            chip.className = 'etab' + (tab.uid === activeUid ? ' active' : '');
            const label = document.createElement('span');
            label.textContent = (tab.isNew ? t('dyn.newStructure') : (tab.structureId || t('dyn.unnamed'))) + (tab.isNew ? ' *' : '');
            chip.appendChild(label);
            const close = document.createElement('button');
            close.className = 'etab-close';
            close.textContent = '×';
            close.title = t('dyn.close');
            close.addEventListener('click', (e) => {
                e.stopPropagation();
                if (tab.uid === activeUid) closeActiveTab();
                else {
                    tabs = tabs.filter((x) => x.uid !== tab.uid);
                    renderTabBar();
                }
            });
            chip.appendChild(close);
            chip.addEventListener('click', () => activateTab(tab.uid));
            bar.appendChild(chip);
        }
    }

    function renderFrame(tab) {
        document.getElementById('editorEmpty').classList.add('hidden');
        document.getElementById('structureEditorContent').classList.remove('hidden');
        document.getElementById('structTitle').textContent =
            (tab.isNew ? t('dyn.newStructureTitle') : t('dyn.editStructure')) + ' — ' + (tab.structureId || t('dyn.unnamed'));
        const badge = document.getElementById('structBadge');
        badge.textContent = tab.isNew ? t('dyn.newDraft') : (tab._staged ? t('dyn.draftUnexported') : '');
        const out = document.getElementById('structOutput');
        out.style.display = 'none'; out.textContent = '';
        const bar = document.getElementById('structStatus');
        if (bar) { bar.textContent = ''; bar.className = 'status'; }
    }

    // ===== 表单填充（blueprint → DOM） =====
    function populate(bp) {
        document.getElementById('sId').value = bp.structureId || '';
        document.getElementById('sNs').value = bp.namespace || '';
        document.getElementById('sStep').value = (bp.structure && bp.structure.step) || 'surface_structures';
        document.getElementById('sTa').value = (bp.structure && bp.structure.terrainAdaptation) || 'beard_thin';
        document.getElementById('sStartPool').value = (bp.structure && bp.structure.startPool) || '';
        document.getElementById('sMaxDist').value = (bp.structure && bp.structure.maxDistanceFromCenter != null) ? bp.structure.maxDistanceFromCenter : 80;
        document.getElementById('sBiomes').value = Array.isArray(bp.structure && bp.structure.biomes)
            ? bp.structure.biomes.join(', ')
            : ((bp.structure && bp.structure.biomes) || '');
        document.getElementById('sMaxLevel').value = (bp.structure && bp.structure.maxSurfaceLevel) || 0;
        document.getElementById('sProject').checked = (bp.structure ? bp.structure.projectToSurface !== false : true);
        const ss = bp.structureSet || {};
        document.getElementById('ssSpacing').value = ss.spacing != null ? ss.spacing : 32;
        document.getElementById('ssSeparation').value = ss.separation != null ? ss.separation : 12;
        document.getElementById('ssSalt').value = ss.salt != null ? ss.salt : 0;
        document.getElementById('ssFrequency').value = ss.frequency != null ? ss.frequency : 1;
        const ez = ss.exclusionZone;
        document.getElementById('ezEnabled').checked = !!ez;
        document.getElementById('ezChunks').value = (ez && ez.chunkCount) || 9;
        document.getElementById('ezOther').value = (ez && ez.otherSet) || '';
        renderPieces(bp);
        renderPools(bp);
    }

    // ---- 圈地读取（.nbt → 片段）----
    /** 把选中的 .nbt 结构模板导入为片段内容（替换 size/boxFills/fillBlocks/jigsaws） */
    async function importRegionIntoPiece(tab, piece) {
        if (!tab || !piece) return;
        const r = await window.api.importRegion();
        if (!r || r.canceled) return;
        if (!r.ok) { showError(t('structure.importRegionErr') + ' ' + r.error); return; }
        piece.size = r.piece.size.slice();
        piece.boxFills = [];
        piece.fillBlocks = r.piece.fillBlocks;
        piece.jigsaws = r.piece.jigsaws;
        renderPieces(tab.bp);
        renderLegend(tab);
        schedulePreviewUpdate(tab);
        showStatus(
            t('structure.importRegionDone').replace('{file}', r.file)
                .replace('{w}', r.info.size[0]).replace('{h}', r.info.size[1]).replace('{d}', r.info.size[2])
                .replace('{b}', r.info.blocks).replace('{j}', r.info.jigsaws)
        , true);
    }

    // ---- 片段卡片 ----
    function renderPieces(bp) {
        const box = document.getElementById('piecesContainer');
        box.innerHTML = '';
        for (const p of bp.pieces || []) {
            const card = document.createElement('div');
            card.className = 'card piece-card';
            const [w, h, d] = p.size || [8, 4, 8];
            card.innerHTML = `
                <div class="row" style="justify-content:space-between">
                    <h3 style="margin:0; display:flex; gap:8px; align-items:center">
                        ${t('structure.piece')}
                        <input class="pc-name" value="${esc(p.name)}" style="width:180px" placeholder="main_hall">
                        <span class="pc-dim muted">[${w}×${h}×${d}]</span>
                    </h3>
                    <div class="row">
                        <button class="btn ghost small pc-eye" title="${t('structure.3d.pieceHide')}">👁</button>
                        <button class="btn ghost small pc-3d" title="${t('structure.piece3d.btnTip')}">🧱 ${t('structure.piece3d.btn')}</button>
                        <button class="btn ghost small pc-region" title="${t('structure.pieceRegionTitle')}">⛏ ${t('structure.pieceRegion')}</button>
                        <button class="btn ghost small pc-del" style="color:var(--err)">${t('dyn.delete')}</button>
                    </div>
                </div>
                <div class="grid2" style="margin-top:10px">
                    <div><label>${t('structure.sizeW')}</label><input class="pc-s0" type="number" min="1" value="${w}"></div>
                    <div><label>${t('structure.sizeH')}</label><input class="pc-s1" type="number" min="1" value="${h}"></div>
                    <div><label>${t('structure.sizeD')}</label><input class="pc-s2" type="number" min="1" value="${d}"></div>
                </div>
                <div class="subhead">${t('structure.boxFills')}</div>
                <table class="mini-table"><thead><tr>
                    <th>from x</th><th>from y</th><th>from z</th><th>to x</th><th>to y</th><th>to z</th><th>block</th><th></th>
                </tr></thead><tbody class="pc-boxfills"></tbody></table>
                <button class="btn ghost small pc-addbox">+ ${t('structure.boxFills')}</button>
                <div class="subhead">${t('structure.fillBlocks')}</div>
                <table class="mini-table"><thead><tr>
                    <th colspan="7">x / y / z / block</th><th></th>
                </tr></thead><tbody class="pc-fillblocks"></tbody></table>
                <button class="btn ghost small pc-addblock">+ ${t('structure.fillBlocks')}</button>
                <div class="subhead">${t('structure.jigsaws')}</div>
                <table class="mini-table jg-table"><thead><tr>
                    <th>x</th><th>y</th><th>z</th><th>name</th><th>pool</th><th>target</th>
                    <th>joint</th><th>orientation</th><th>finalState</th><th>sel</th><th>place</th><th></th>
                </tr></thead><tbody class="pc-jigsaws"></tbody></table>
                <button class="btn ghost small pc-addjg">+ ${t('structure.jigsaw')}</button>`;
            // boxFills 行
            const bxB = card.querySelector('.pc-boxfills');
            for (const bf of p.boxFills || []) bxB.appendChild(boxFillRow(bf));
            // fillBlocks 行
            const fB = card.querySelector('.pc-fillblocks');
            for (const fb of p.fillBlocks || []) fB.appendChild(fillBlockRow(fb));
            // jigsaws 行
            const jB = card.querySelector('.pc-jigsaws');
            const c = constants || { joints: ['rollable', 'aligned'], orientations: [] };
            for (const j of p.jigsaws || []) jB.appendChild(jigsawRow(j, c));

            card.querySelector('.pc-del').addEventListener('click', () => {
                const tab = activeTab();
                const name = p.name;
                const i = tab.bp.pieces.findIndex((x) => x === p);
                if (i >= 0) tab.bp.pieces.splice(i, 1);
                renderPieces(tab.bp);
                renderLegend(tab);
                schedulePreviewUpdate(tab);
            });
            // P11：🧱 → 在独立 3D 编辑器窗口打开该片段
            card.querySelector('.pc-3d').addEventListener('click', () => {
                const tab = activeTab();
                const name = (card.querySelector('.pc-name').value || '').trim();
                if (!tab || !name) return;
                collectInto(tab);
                window.api.openPieceEditor({
                    packDir: tab.packDir,
                    structureId: tab.structureId,
                    pieceName: name,
                    blueprint: tab.bp,
                }).then((res) => {
                    if (res && !res.ok) showError('打开 3D 编辑器失败: ' + res.error);
                });
            });
            card.querySelector('.pc-region').addEventListener('click', () => importRegionIntoPiece(activeTab(), p));
            card.querySelector('.pc-eye').addEventListener('click', () => {
                const tab = activeTab();
                const name = (card.querySelector('.pc-name').value || '').trim();
                const i = tab.hiddenPieces.indexOf(name);
                if (i >= 0) tab.hiddenPieces.splice(i, 1); else tab.hiddenPieces.push(name);
                if (pv) pv.setPieceVisible(name, i < 0);
            });
            card.querySelector('.pc-addbox').addEventListener('click', () => {
                const row = boxFillRow({ from: [0, 0, 0], to: [w, 0, 0], block: 'minecraft:stone_bricks' });
                bxB.appendChild(row);
                wireFillRow(row, card, bxFillChanged);
            });
            card.querySelector('.pc-addblock').addEventListener('click', () => {
                const row = fillBlockRow({ x: 0, y: 0, z: 0, block: 'minecraft:stone_bricks' });
                fB.appendChild(row);
                wireFillRow(row, card, bxFillChanged);
            });
            card.querySelector('.pc-addjg').addEventListener('click', () => {
                const jg = {
                    x: 0, y: 0, z: 0, name: '', pool: '', target: '',
                    joint: 'rollable', orientation: c.orientations[0] || 'north_up',
                    finalState: 'minecraft:air', selectionPriority: 10, placementPriority: 10,
                };
                const row = jigsawRow(jg, c);
                jB.appendChild(row);
                wireJigsawRow(row, card, bxFillChanged);
            });
            // 尺寸/名称变更 → 预览
            ['pc-name', 'pc-s0', 'pc-s1', 'pc-s2'].forEach((cls) => {
                card.querySelector('.' + cls).addEventListener('input', bxFillChanged);
            });
            wireAllRows(card);
            box.appendChild(card);
        }
        function bxFillChanged() {
            const tab = activeTab();
            collectInto(tab);
            schedulePreviewUpdate(tab);
        }
        // P6：片段数据变化 → 刷新导航树
        const _treeTab = activeTab();
        if (_treeTab) renderTree(_treeTab);
    }

    function wireAllRows(card) {
        card.querySelectorAll('.pc-boxfills .fill-row, .pc-fillblocks .fill-row').forEach((row) => wireFillRow(row, card, rowChange));
        card.querySelectorAll('.pc-jigsaws .jg-row').forEach((row) => wireJigsawRow(row, card, rowChange));
        function rowChange() {
            const tab = activeTab();
            collectInto(tab);
            schedulePreviewUpdate(tab);
        }
    }

    function boxFillRow(bf) {
        const tr = document.createElement('tr');
        tr.className = 'fill-row';
        tr.innerHTML = `
            <td><input class="bf-f0" type="number" min="0" value="${bf.from[0]}"></td>
            <td><input class="bf-f1" type="number" min="0" value="${bf.from[1]}"></td>
            <td><input class="bf-f2" type="number" min="0" value="${bf.from[2]}"></td>
            <td><input class="bf-t0" type="number" min="0" value="${bf.to[0]}"></td>
            <td><input class="bf-t1" type="number" min="0" value="${bf.to[1]}"></td>
            <td><input class="bf-t2" type="number" min="0" value="${bf.to[2]}"></td>
            <td style="width:220px"><input class="bf-block" value="${esc(bf.block)}" placeholder="minecraft:stone_bricks"></td>
            <td><button class="btn ghost small fr-del">×</button></td>`;
        return tr;
    }
    function fillBlockRow(fb) {
        const tr = document.createElement('tr');
        tr.className = 'fill-row';
        tr.innerHTML = `
            <td colspan="7" style="display:flex; gap:6px; align-items:center; flex-wrap:wrap">
                <span class="muted">x</span><input class="fb-x" type="number" min="0" style="width:60px" value="${fb.x}">
                <span class="muted">y</span><input class="fb-y" type="number" min="0" style="width:60px" value="${fb.y}">
                <span class="muted">z</span><input class="fb-z" type="number" min="0" style="width:60px" value="${fb.z}">
                <input class="fb-block" style="width:260px" value="${esc(fb.block)}" placeholder="minecraft:smooth_stone">
            </td>
            <td><button class="btn ghost small fr-del">×</button></td>`;
        return tr;
    }
    function wireFillRow(tr, card, onChange) {
        tr.querySelectorAll('input').forEach((inp) => inp.addEventListener('input', onChange));
        tr.querySelector('.fr-del').addEventListener('click', () => { tr.remove(); onChange(); });
    }

    function jigsawRow(j, c) {
        const tr = document.createElement('tr');
        tr.className = 'jg-row';
        const jointOpts = (c.joints || ['rollable', 'aligned']).map((v) => `<option value="${v}"${j.joint === v ? ' selected' : ''}>${v}</option>`).join('');
        const oriOpts = (c.orientations || []).map((v) => `<option value="${v}"${j.orientation === v ? ' selected' : ''}>${v}</option>`).join('');
        tr.innerHTML = `
            <td><input class="jg-x" type="number" min="0" style="width:52px" value="${j.x}"></td>
            <td><input class="jg-y" type="number" min="0" style="width:52px" value="${j.y}"></td>
            <td><input class="jg-z" type="number" min="0" style="width:52px" value="${j.z}"></td>
            <td><input class="jg-name" style="width:90px" value="${esc(j.name)}" placeholder="top"></td>
            <td><input class="jg-pool" style="width:100px" value="${esc(j.pool)}" placeholder="tower"></td>
            <td><input class="jg-target" style="width:80px" value="${esc(j.target || '')}" placeholder="bottom"></td>
            <td><select class="jg-joint" style="width:92px">${jointOpts}</select></td>
            <td><select class="jg-ori" style="width:110px">${oriOpts}</select></td>
            <td><input class="jg-final" style="width:150px" value="${esc(j.finalState)}" placeholder="minecraft:air"></td>
            <td><input class="jg-sel" type="number" style="width:52px" value="${j.selectionPriority != null ? j.selectionPriority : 10}"></td>
            <td><input class="jg-pl" type="number" style="width:52px" value="${j.placementPriority != null ? j.placementPriority : 10}"></td>
            <td><button class="btn ghost small fr-del">×</button></td>`;
        return tr;
    }
    function wireJigsawRow(tr, card, onChange) {
        tr.querySelectorAll('input, select').forEach((inp) => inp.addEventListener('input', onChange));
        tr.querySelector('.fr-del').addEventListener('click', () => { tr.remove(); onChange(); });
    }

    // ---- P6 结构导航树（结构 → 片段 → jigsaws / 池 → 元素）----
    /** 契约检查结果按 piece|jigsaw 索引 */
    function contractIndex(bp) {
        const Dg = window.StructureDiagnostics;
        const map = new Map();
        if (Dg && Dg.checkJigsawContracts) {
            for (const r of Dg.checkJigsawContracts(bp)) map.set(r.piece + '|' + r.jigsaw, r);
        }
        return map;
    }

    /** 树节点行（递归缩进；展开状态存 tab._treeExpanded） */
    function renderTree(tab) {
        const box = document.getElementById('structTree');
        const sumEl = document.getElementById('structTreeSummary');
        if (!box || !tab) return;
        const bp = tab.bp;
        box.innerHTML = '';
        tab._treeExpanded = tab._treeExpanded || new Map();
        const contracts = contractIndex(bp);
        let issues = 0;
        for (const r of contracts.values()) if (!r.ok) issues++;
        if (sumEl) sumEl.textContent = contracts.size
            ? tf('structure.tree.summary', { n: contracts.size, i: issues })
            : '';

        // 挂载辅助：先入盒，再展开子节点（保证父行在子行之前）
        const mount = (c) => {
            if (!c) return;
            box.appendChild(c);
            if (c._treePending) { const p = c._treePending; c._treePending = null; p(); }
        };
        const row = (id, depth, icon, labelHtml, expandedDefault, childrenBuilder) => {
            const hasKids = typeof childrenBuilder === 'function';
            const exp = tab._treeExpanded.has(id) ? tab._treeExpanded.get(id) : (expandedDefault !== false);
            const el = document.createElement('div');
            el.className = 'tree-row' + (exp && hasKids ? ' tree-open' : '');
            el.style.paddingLeft = (8 + depth * 18) + 'px';
            const toggle = hasKids
                ? `<button class="tree-toggle btn ghost small" style="padding:0 4px">${exp ? '▾' : '▸'}</button>`
                : '<span style="display:inline-block;width:20px"></span>';
            el.innerHTML = toggle + '<span class="tree-label">' + labelHtml + '</span>';
            if (hasKids) {
                el.querySelector('.tree-toggle').addEventListener('click', (e) => {
                    e.stopPropagation();
                    tab._treeExpanded.set(id, !tab._treeExpanded.get(id));
                    renderTree(tab);
                });
            }
            if (hasKids && exp) {
                el._treePending = () => childrenBuilder(mount);
            }
            return el;
        };
        // 根：结构
        mount(row('root', 0, '📐',
            `<b>${esc(bp.structureId || '?')}</b> <span class="note">${t('structure.tree.startPool')} ${esc((bp.structure && bp.structure.startPool) || '?')}</span>`,
            true,
            (add) => {
                // 片段组
                add(row('pieces', 1, '', `<b>🧱 ${t('structure.tree.pieces')}</b> <span class="note">(${(bp.pieces || []).length})</span>`, true, (add2) => {
                    for (const p of bp.pieces || []) {
                        const [w, h, d] = p.size || [0, 0, 0];
                        const jgs = p.jigsaws || [];
                        const pEl = row('piece:' + p.name, 2, '',
                            `🧱 ${esc(p.name)} <span class="note">[${w}×${h}×${d}]</span> ` +
                            (jgs.length ? `<span class="note">◇×${jgs.length}</span>` : '') +
                            `<button class="btn ghost small tree-region" title="${t('structure.pieceRegionTitle')}">⛏ ${t('structure.pieceRegion')}</button>`,
                            true,
                            (add3) => {
                                for (const j of jgs) {
                                    const c = contracts.get(p.name + '|' + j.name);
                                    const badge = c
                                        ? (c.ok ? '<span style="color:var(--ok)">✓</span>'
                                            : `<span style="color:var(--${c.level === 'error' ? 'err' : 'warn'})" title="${esc(c.msg)}">${c.level === 'error' ? '✗' : '⚠'}</span>`)
                                        : '';
                                    const jgEl = row('jg:' + p.name + ':' + j.name, 3, '',
                                        `◇ ${esc(j.name)} <span class="note">→ ${esc(j.pool)} · ${esc(j.target)} · ${esc(j.orientation)}</span> ${badge}`,
                                        false,
                                        null);
                                    jgEl.addEventListener('click', () => {
                                        showStatus(tf('structure.tree.jgInfo',
                                            { p: p.name, j: j.name, pool: j.pool, target: j.target, o: j.orientation, msg: c ? c.msg : '' }), true);
                                    });
                                    add3(jgEl);
                                }
                            });
                        pEl.querySelector('.tree-region').addEventListener('click', (e) => {
                            e.stopPropagation();
                            importRegionIntoPiece(tab, p);
                        });
                        add2(pEl);
                    }
                }));
                // 池组
                add(row('pools', 1, '', `<b>🏊 ${t('structure.tree.pools')}</b> <span class="note">(${(bp.templatePools || []).length})</span>`, true, (add2) => {
                    for (const pl of bp.templatePools || []) {
                        const els = pl.elements || [];
                        add2(row('pool:' + pl.name, 2, '',
                            `🏊 ${esc(pl.name)} <span class="note">${t('structure.tree.fallback')} ${esc(pl.fallback || 'minecraft:empty_pool_element')}</span>`,
                            true,
                            (add3) => {
                                for (let i = 0; i < els.length; i++) {
                                    const el = els[i];
                                    const missing = !(bp.pieces || []).some((p) => p.name === el.template);
                                    add3(row('el:' + pl.name + ':' + i, 3, '',
                                        `→ ${esc(el.template)} <span class="note">w${el.weight || 1} · ${esc(el.projection || 'rigid')}</span>` +
                                        (missing ? ' <span style="color:var(--err)">✗</span>' : ''),
                                        false, null));
                                }
                            }));
                    }
                }));
            }));
    }

    // ---- 池卡片 ----
    function renderPools(bp) {
        const box = document.getElementById('poolsContainer');
        box.innerHTML = '';
        const c = constants || { projections: ['rigid', 'terrain_matching'] };
        for (const pl of bp.templatePools || []) {
            const card = document.createElement('div');
            card.className = 'card pool-card';
            card.innerHTML = `
                <div class="row" style="justify-content:space-between">
                    <h3 style="margin:0; display:flex; gap:8px; align-items:center">
                        ${t('structure.pool')}
                        <input class="pl-name" value="${esc(pl.name)}" style="width:200px" placeholder="start">
                    </h3>
                    <button class="btn ghost small pl-del" style="color:var(--err)">${t('dyn.delete')}</button>
                </div>
                <table class="mini-table" style="margin-top:10px"><thead><tr>
                    <th>${t('structure.template')}</th><th>weight (1-150)</th><th>projection</th><th></th>
                </tr></thead><tbody class="pl-elements"></tbody></table>
                <button class="btn ghost small pl-addelem">+ ${t('structure.element')}</button>
                <div class="row" style="margin-top:8px">
                    <label style="margin:0">${t('structure.fallback')}</label>
                    <input class="pl-fallback" value="${esc(pl.fallback || 'minecraft:empty_pool_element')}" style="width:300px">
                </div>`;
            const elsB = card.querySelector('.pl-elements');
            const pieceNames = (bp.pieces || []).map((p) => p.name).filter(Boolean);
            for (const el of pl.elements || []) elsB.appendChild(poolElemRow(el, pieceNames, c));
            card.querySelector('.pl-addelem').addEventListener('click', () => {
                const row = poolElemRow({ template: pieceNames[0] || '', weight: 1, projection: 'rigid' }, pieceNames, c);
                elsB.appendChild(row);
                wireElemRow(row, () => { const tab = activeTab(); collectInto(tab); });
            });
            card.querySelector('.pl-del').addEventListener('click', () => {
                const tab = activeTab();
                const i = tab.bp.templatePools.findIndex((x) => x === pl);
                if (i >= 0) tab.bp.templatePools.splice(i, 1);
                renderPools(tab.bp);
            });
            card.querySelector('.pl-name').addEventListener('input', () => { const tab = activeTab(); collectInto(tab); });
            card.querySelector('.pl-fallback').addEventListener('input', () => { const tab = activeTab(); collectInto(tab); });
            card.querySelectorAll('.pl-elements .el-row').forEach((row) => wireElemRow(row, () => { const tab = activeTab(); collectInto(tab); }));
            box.appendChild(card);
        }
        // P6：池数据变化 → 刷新导航树
        const _treeTab2 = activeTab();
        if (_treeTab2) renderTree(_treeTab2);
    }

    function poolElemRow(el, pieceNames, c) {
        const tr = document.createElement('tr');
        tr.className = 'el-row';
        const opts = (pieceNames.length ? pieceNames : ['<无片段>']).map((n) => `<option value="${esc(n)}"${el.template === n ? ' selected' : ''}>${esc(n)}</option>`).join('');
        const projOpts = (c.projections || ['rigid', 'terrain_matching']).map((v) => `<option value="${v}"${el.projection === v ? ' selected' : ''}>${v}</option>`).join('');
        tr.innerHTML = `
            <td><select class="el-template" style="width:200px">${opts}</select></td>
            <td><input class="el-weight" type="number" min="1" max="150" style="width:80px" value="${el.weight != null ? el.weight : 1}"></td>
            <td><select class="el-proj" style="width:150px">${projOpts}</select></td>
            <td><button class="btn ghost small fr-del">×</button></td>`;
        return tr;
    }
    function wireElemRow(tr, onChange) {
        tr.querySelectorAll('input, select').forEach((inp) => inp.addEventListener('input', onChange));
        tr.querySelector('.fr-del').addEventListener('click', () => { tr.remove(); onChange(); });
    }

    // ===== 收集（DOM → blueprint） =====
    function collectInto(tab) {
        if (!tab) return;
        tab.structureId = document.getElementById('sId').value.trim();
        tab.bp.structureId = tab.structureId;
        tab.bp.namespace = document.getElementById('sNs').value.trim();
        tab.bp.structure = tab.bp.structure || {};
        tab.bp.structure.step = document.getElementById('sStep').value;
        tab.bp.structure.terrainAdaptation = document.getElementById('sTa').value;
        tab.bp.structure.startPool = document.getElementById('sStartPool').value.trim();
        tab.bp.structure.maxDistanceFromCenter = parseInt(document.getElementById('sMaxDist').value, 10) || 80;
        tab.bp.structure.biomes = document.getElementById('sBiomes').value
            .split(',').map((s) => s.trim()).filter(Boolean);
        tab.bp.structure.maxSurfaceLevel = parseInt(document.getElementById('sMaxLevel').value, 10) || 0;
        tab.bp.structure.projectToSurface = document.getElementById('sProject').checked;
        tab.bp.structureSet = tab.bp.structureSet || {};
        tab.bp.structureSet.spacing = parseInt(document.getElementById('ssSpacing').value, 10) || 32;
        tab.bp.structureSet.separation = parseInt(document.getElementById('ssSeparation').value, 10) || 12;
        tab.bp.structureSet.salt = parseInt(document.getElementById('ssSalt').value, 10) || 0;
        const freq = parseFloat(document.getElementById('ssFrequency').value);
        tab.bp.structureSet.frequency = isNaN(freq) ? 1 : freq;
        if (document.getElementById('ezEnabled').checked) {
            tab.bp.structureSet.exclusionZone = {
                chunkCount: parseInt(document.getElementById('ezChunks').value, 10) || 9,
                otherSet: document.getElementById('ezOther').value.trim(),
            };
        } else {
            tab.bp.structureSet.exclusionZone = null;
        }

        // 片段
        tab.bp.pieces = [];
        document.querySelectorAll('#piecesContainer .piece-card').forEach((card) => {
            const p = {
                name: card.querySelector('.pc-name').value.trim(),
                size: [
                    parseInt(card.querySelector('.pc-s0').value, 10) || 1,
                    parseInt(card.querySelector('.pc-s1').value, 10) || 1,
                    parseInt(card.querySelector('.pc-s2').value, 10) || 1,
                ],
                boxFills: [],
                fillBlocks: [],
                jigsaws: [],
            };
            card.querySelectorAll('.pc-boxfills .fill-row').forEach((tr) => {
                const bf = {
                    from: [num(tr.querySelector('.bf-f0')), num(tr.querySelector('.bf-f1')), num(tr.querySelector('.bf-f2'))],
                    to: [num(tr.querySelector('.bf-t0')), num(tr.querySelector('.bf-t1')), num(tr.querySelector('.bf-t2'))],
                    block: tr.querySelector('.bf-block').value.trim(),
                };
                if (bf.block) p.boxFills.push(bf);
            });
            card.querySelectorAll('.pc-fillblocks .fill-row').forEach((tr) => {
                const fb = {
                    x: num(tr.querySelector('.fb-x')), y: num(tr.querySelector('.fb-y')), z: num(tr.querySelector('.fb-z')),
                    block: tr.querySelector('.fb-block').value.trim(),
                };
                if (fb.block) p.fillBlocks.push(fb);
            });
            card.querySelectorAll('.pc-jigsaws .jg-row').forEach((tr) => {
                const jg = {
                    x: num(tr.querySelector('.jg-x')), y: num(tr.querySelector('.jg-y')), z: num(tr.querySelector('.jg-z')),
                    name: tr.querySelector('.jg-name').value.trim(),
                    pool: tr.querySelector('.jg-pool').value.trim(),
                    target: tr.querySelector('.jg-target').value.trim(),
                    joint: tr.querySelector('.jg-joint').value,
                    orientation: tr.querySelector('.jg-ori').value,
                    finalState: tr.querySelector('.jg-final').value.trim() || 'minecraft:air',
                    selectionPriority: num(tr.querySelector('.jg-sel')),
                    placementPriority: num(tr.querySelector('.jg-pl')),
                };
                if (jg.name || jg.pool) p.jigsaws.push(jg);
            });
            if (p.name) tab.bp.pieces.push(p);
        });

        // 池
        tab.bp.templatePools = [];
        document.querySelectorAll('#poolsContainer .pool-card').forEach((card) => {
            const pl = {
                name: card.querySelector('.pl-name').value.trim(),
                elements: [],
                fallback: card.querySelector('.pl-fallback').value.trim() || 'minecraft:empty_pool_element',
            };
            card.querySelectorAll('.pl-elements .el-row').forEach((tr) => {
                pl.elements.push({
                    template: tr.querySelector('.el-template').value,
                    weight: num(tr.querySelector('.el-weight')) || 1,
                    projection: tr.querySelector('.el-proj').value,
                });
            });
            if (pl.name) tab.bp.templatePools.push(pl);
        });
        delete tab.bp._staged;
    }
    function num(inp) {
        const v = parseInt(inp.value, 10);
        return isNaN(v) ? 0 : v;
    }

    // ===== 3D 预览（3D 页签：拼合生长/并排，只读）；片段 3D 编辑在独立窗口（P11 piece-editor.html） =====
    let pv = null;
    let previewPromise = null;
    let previewTimer = null;

    async function ensurePreview() {
        if (pv) return pv;
        if (!previewPromise) {
            previewPromise = import('../lib/structurePreview.js').then((mod) => {
                const canvas = document.getElementById('structCanvas');
                pv = mod.createStructurePreview(canvas);
                window.__structPreview = pv; // 调试/测试钩子
                return pv;
            }).catch((e) => {
                console.error('3D 预览模块加载失败:', e);
                showError(t('structure.3d.fail') + ' ' + e.message);
                return null;
            });
        }
        return previewPromise;
    }

    /** 运行 jigsaw 生长模拟（window.StructureGrowth，见 core/structureGrowth.js） */
    function runGrowth(bp) {
        const G = window.StructureGrowth;
        if (!G) return { placements: [], deadEnds: [], error: 'growth module missing' };
        const max = parseInt(document.getElementById('inp3dMax').value, 10) || 24;
        try {
            return G.growStructure(bp, { maxPieces: Math.max(1, Math.min(128, max)) });
        } catch (e) {
            return { placements: [], deadEnds: [], error: String(e.message || e) };
        }
    }

    function previewMode() {
        return document.getElementById('sel3dMode').value;
    }

    /** 刷新结构 3D 预览（3D 页签：拼合生长 / 并排），tab._growth 缓存生长结果 */
    function updatePreview(tab, regrow = false) {
        if (!pv || !tab) return;
        if (activeStab !== '3d') return;
        collectInto(tab);
        if (previewMode() === 'side') {
            pv.setBlueprint(tab.bp, { hiddenPieces: tab.hiddenPieces });
        } else {
            if (regrow || !tab._growth) tab._growth = runGrowth(tab.bp);
            const g = tab._growth;
            pv.setPlacements(g.placements, { pieces: tab.bp.pieces, hiddenPieces: tab.hiddenPieces });
            applyGhosts(g);
            if (g.error) {
                showStatus(t('structure.3d.growFail') + ': ' + g.error, true);
            } else {
                showStatus(tf('dyn.grownStats', { n: g.placements.length, d: g.deadEnds.length }), true);
            }
        }
        renderLegend(tab);
    }

    /** P5 死端幽灵盒（受 chk3dGhosts 开关控制） */
    function applyGhosts(g) {
        if (!pv || !pv.setGhosts) return;
        const show = document.getElementById('chk3dGhosts').checked;
        const ghosts = (show && g && g.deadEnds)
            ? g.deadEnds.filter((d) => d.ghost).map((d) => d.ghost)
            : [];
        pv.setGhosts(ghosts);
    }

    function schedulePreviewUpdate(tab) {
        clearTimeout(previewTimer);
        previewTimer = setTimeout(() => {
            const t2 = activeTab();
            if (!t2) return;
            if (activeStab === '3d') {
                t2._growth = null; // 数据变了 → 重新生长
                updatePreview(t2);
            }
        }, 250);
    }

    function renderLegend(tab) {
        const legend = document.getElementById('structLegend');
        legend.innerHTML = '';
        // 拼合模式：统计每个片段类型被放置的次数
        let counts = null;
        if (previewMode() !== 'side' && tab._growth) {
            counts = new Map();
            for (const pl of tab._growth.placements || []) {
                counts.set(pl.pieceName, (counts.get(pl.pieceName) || 0) + 1);
            }
        }
        for (const p of (tab.bp.pieces || [])) {
            const row = document.createElement('label');
            row.className = 'chk legend-row';
            const hidden = tab.hiddenPieces.includes(p.name);
            const n = counts ? (counts.get(p.name) || 0) : null;
            row.innerHTML = `<input type="checkbox" ${hidden ? '' : 'checked'}> <span class="legend-swatch" data-piece="${esc(p.name)}"></span> <span>${esc(p.name)}</span>` +
                (n !== null ? ` <span class="legend-count">×${n}</span>` : '');
            row.querySelector('input').addEventListener('change', (e) => {
                const i = tab.hiddenPieces.indexOf(p.name);
                if (e.target.checked) { if (i >= 0) tab.hiddenPieces.splice(i, 1); }
                else if (i < 0) tab.hiddenPieces.push(p.name);
                if (pv) pv.setPieceVisible(p.name, e.target.checked);
            });
            legend.appendChild(row);
        }
        // 颜色与 3D 一致（hash → HSL）
        legend.querySelectorAll('.legend-swatch').forEach((sw) => {
            let h = 0;
            const name = sw.dataset.piece;
            for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
            sw.style.background = `hsl(${h}, 50%, 55%)`;
        });
    }

    function stopPreview() { if (pv) pv.stop(); }

    // ===== 页签切换 =====
    function switchTab(name) {
        const prev = activeStab;
        activeStab = name;
        document.querySelectorAll('#structTabs .tab').forEach((el) => {
            el.classList.toggle('active', el.dataset.stab === name);
        });
        document.querySelectorAll('#structureEditorContent .tab-page').forEach((el) => {
            el.classList.toggle('active', el.id === 'stab-' + name);
        });
        if (name === '3d') {
            (async () => {
                const p = await ensurePreview();
                if (!p) return;
                // 同步图层开关
                p.setLayer('fills', document.getElementById('chk3dFills').checked);
                p.setLayer('jigsaws', document.getElementById('chk3dJigsaws').checked);
                p.setLayer('grid', document.getElementById('chk3dGrid').checked);
                p.setAutoRotate(document.getElementById('chk3dRotate').checked);
                p.start();
                const tab = activeTab();
                if (tab) updatePreview(tab);
            })();
        }
        // 离开 3D 页签 → 停掉结构预览器（省渲染循环）
        if (name !== '3d') stopPreview();
        void prev;
    }

    /**
     * 把 3D 体素编辑（点选/笔刷）写回蓝图片段（P9 起用 Voxel.mergePieceEdits）：
     * 差异写 fillBlocks（覆盖/删除）；差异触及 boxFills 区域才整片扁平化；
     * jigsaw 位置受保护；占位符 #* 原样保留（导出时转 air）
     */
    function applyVoxelEdits(tab) {
        if (!tab || !pv || !window.Voxel || !window.Voxel.mergePieceEdits) return 0;
        const st = pv.debugStats ? pv.debugStats() : null;
        if (!st || !st.editedPieces.length) return 0;
        let n = 0;
        const pieces = tab.bp.pieces || [];
        for (const name of st.editedPieces) {
            const i = pieces.findIndex((x) => x.name === name);
            if (i < 0) continue;
            const map = pv.getPieceVoxelMap(name);
            if (!map) continue;
            const merged = window.Voxel.mergePieceEdits(pieces[i], map);
            if (merged !== pieces[i]) { pieces[i] = merged; n++; }
        }
        return n;
    }

    // ===== 保存草稿 / 导出 / 打包 / 导出原版数据 =====
    async function saveDraft() {
        const tab = activeTab();
        if (!tab) return;
        collectInto(tab);
        const edited = applyVoxelEdits(tab);
        if (!tab.bp.structureId) { showError(t('common.idRequired')); return; }
        const res = await window.api.saveStructure(tab.packDir, tab.bp);
        if (!res.ok) { showError(t('common.saveFailed') + res.error); return; }
        tab._staged = true;
        tab.isNew = false;
        renderFrame(tab);
        showStatus(tf('dyn.savedAsDraft', { id: tab.bp.structureId }), true);
        if (window.StructureList && window.StructureList.loadStructures) {
            window.StructureList.loadStructures(tab.packDir);
        }
    }

    async function exportStaged() {
        const tab = activeTab();
        if (!tab) return;
        if (!confirm(tf('dyn.exportConfirm', { name: tab.packName }))) return;
        const res = await window.api.stagingExport(tab.packDir);
        if (!res.ok) { showError(t('common.exportFailed') + res.error); return; }
        const data = res.data || {};
        showStatus(tf('dyn.exportedDone', { n: data.exported }), true);
        tab._staged = false;
        renderFrame(tab);
        if (window.StructureList && window.StructureList.loadStructures) {
            window.StructureList.loadStructures(tab.packDir);
        }
    }

    async function build() {
        const tab = activeTab();
        if (!tab) return;
        const stagedRes = await window.api.stagingList(tab.packDir);
        if (stagedRes.ok && stagedRes.data && stagedRes.data.hasStaged) {
            const go = confirm(tf('dyn.buildStagedWarn', { n: stagedRes.data.items.length }));
            if (go) {
                const expRes = await window.api.stagingExport(tab.packDir);
                if (!expRes.ok) { showError(t('common.exportFailed') + expRes.error); return; }
            }
        }
        const outName = (tab.packName || 'pack') + '.jar';
        const outEl = document.getElementById('structOutput');
        outEl.style.display = 'block';
        outEl.textContent = t('dyn.buildRunning') + '\n';
        const res = await window.api.buildPack({ packDir: tab.packDir, outJarName: outName });
        if (res.ok) {
            outEl.textContent = '=== Build OK ===\n' + ((res.data && res.data.stdout) || '');
            showStatus(tf('dyn.buildDone', { name: outName }), true);
        } else {
            outEl.textContent = '=== Build Failed ===\n' + res.error;
            showStatus(t('dyn.buildFail'), false);
        }
    }

    /** 导出原版数据：当前蓝图 → 原版 jigsaw 4 类文件（写入包源码目录） */
    async function exportVanilla() {
        const tab = activeTab();
        if (!tab) return;
        collectInto(tab);
        applyVoxelEdits(tab);
        if (!tab.bp.structureId) { showError(t('common.idRequired')); return; }
        if (!confirm(t('structure.exportVanillaConfirm'))) return;
        const outEl = document.getElementById('structOutput');
        outEl.style.display = 'block';
        outEl.textContent = t('structure.exportVanillaRunning') + '\n';
        const res = await window.api.exportStructureVanilla(tab.packDir, tab.bp);
        if (!res.ok) {
            outEl.textContent = '=== Export Failed ===\n' + res.error;
            showError(t('structure.exportVanillaFail'));
            return;
        }
        const files = (res.data && res.data.files) || [];
        outEl.textContent = '=== Vanilla Export OK ===\n' + files.map((f) => '  ' + f).join('\n');
        showStatus(tf('structure.exportVanillaDone', { n: files.length }), true);
    }

    // ===== 事件绑定 =====
    function bindEvents() {
        document.getElementById('btnStructSave').addEventListener('click', saveDraft);
        document.getElementById('btnStructExport').addEventListener('click', exportStaged);
        document.getElementById('btnStructBuild').addEventListener('click', build);
        document.getElementById('btnStructExportVanilla').addEventListener('click', exportVanilla);
        document.getElementById('btnAddPiece').addEventListener('click', () => {
            const tab = activeTab();
            if (!tab) return;
            tab.bp.pieces = tab.bp.pieces || [];
            tab.bp.pieces.push({
                name: 'piece_' + (tab.bp.pieces.length + 1),
                size: [8, 4, 8],
                boxFills: [{ from: [0, 0, 0], to: [7, 0, 7], block: 'minecraft:stone_bricks' }],
                fillBlocks: [],
                jigsaws: [],
            });
            renderPieces(tab.bp);
            renderLegend(tab);
            schedulePreviewUpdate(tab);
        });
        document.getElementById('btnImportRegion').addEventListener('click', async () => {
            const tab = activeTab();
            if (!tab) return;
            const r = await window.api.importRegion();
            if (!r || r.canceled) return;
            if (!r.ok) { showError(t('structure.importRegionErr') + ' ' + r.error); return; }
            tab.bp.pieces = tab.bp.pieces || [];
            // 唯一片段名
            let name = r.piece.name || 'region';
            let k = 2;
            const exists = (n) => tab.bp.pieces.some((x) => x.name === n);
            while (exists(name)) name = (r.piece.name || 'region') + '_' + (k++);
            tab.bp.pieces.push({ name, size: r.piece.size, boxFills: [], fillBlocks: r.piece.fillBlocks, jigsaws: r.piece.jigsaws });
            renderPieces(tab.bp);
            renderLegend(tab);
            schedulePreviewUpdate(tab);
            showStatus(
                t('structure.importRegionDone').replace('{file}', r.file)
                    .replace('{w}', r.info.size[0]).replace('{h}', r.info.size[1]).replace('{d}', r.info.size[2])
                    .replace('{b}', r.info.blocks).replace('{j}', r.info.jigsaws)
                    + ' → ' + t('structure.importRegionAs').replace('{p}', name)
            , true);
        });
        document.getElementById('btnAddPool').addEventListener('click', () => {
            const tab = activeTab();
            if (!tab) return;
            tab.bp.templatePools = tab.bp.templatePools || [];
            tab.bp.templatePools.push({
                name: 'pool_' + (tab.bp.templatePools.length + 1),
                elements: [{ template: (tab.bp.pieces[0] && tab.bp.pieces[0].name) || '', weight: 1, projection: 'rigid' }],
                fallback: 'minecraft:empty_pool_element',
            });
            renderPools(tab.bp);
        });
        document.querySelectorAll('#structTabs .tab').forEach((el) => {
            el.addEventListener('click', () => switchTab(el.dataset.stab));
        });
        document.getElementById('btn3dReset').addEventListener('click', () => { if (pv) pv.resetView(); });
        document.getElementById('sel3dMode').addEventListener('change', () => {
            const tab = activeTab();
            if (tab && activeStab === '3d') updatePreview(tab);
        });
        document.getElementById('btn3dRegrow').addEventListener('click', () => {
            const tab = activeTab();
            if (tab) { tab._growth = null; updatePreview(tab, true); }
        });
        document.getElementById('inp3dMax').addEventListener('change', () => {
            const tab = activeTab();
            if (tab && activeStab === '3d') { tab._growth = null; updatePreview(tab, true); }
        });
        document.getElementById('chk3dRotate').addEventListener('change', (e) => { if (pv) pv.setAutoRotate(e.target.checked); });
        document.getElementById('chk3dFills').addEventListener('change', (e) => { if (pv) pv.setLayer('fills', e.target.checked); });
        document.getElementById('chk3dJigsaws').addEventListener('change', (e) => { if (pv) pv.setLayer('jigsaws', e.target.checked); });
        document.getElementById('chk3dGrid').addEventListener('change', (e) => { if (pv) pv.setLayer('grid', e.target.checked); });
        document.getElementById('chk3dGhosts').addEventListener('change', () => {
            const tab = activeTab();
            if (tab) applyGhosts(tab._growth);
        });

        // ===== P5 拼合诊断：可生成性检验（N 种子）+ 报告 =====
        document.getElementById('btn3dDiagnose').addEventListener('click', () => {
            const tab = activeTab();
            if (!tab) return;
            const Dg = window.StructureDiagnostics;
            if (!Dg) { showError(t('structure.3d.diagFail') + ' (module missing)'); return; }
            collectInto(tab);
            const runs = 50;
            showStatus(t('structure.3d.diagRunning').replace('{n}', runs), true);
            // 同步但耗时（50 次生长 + 体素展开），让状态先绘制
            setTimeout(() => {
                let report;
                try {
                    report = Dg.checkGeneratability(tab.bp, { runs, maxPieces: parseInt(document.getElementById('inp3dMax').value, 10) || 24 });
                } catch (e) {
                    showError(t('structure.3d.diagFail') + ' ' + e.message);
                    return;
                }
                renderDiagReport(report);
                const ov = report.overlaps;
                if (report.successRuns === 0) {
                    showError(t('structure.3d.diagAllFail') + '：' + (report.errors[0] || '?'));
                } else if (ov.runsWithOverlaps > 0) {
                    showError(tf('structure.3d.diagWarnOverlap', { r: ov.runsWithOverlaps, n: runs, v: ov.totalVoxels }));
                } else if (report.deadEnds.total > 0) {
                    showStatus(tf('structure.3d.diagWarnDead', { d: report.deadEnds.total, n: runs }), true);
                } else {
                    showStatus(tf('structure.3d.diagOk', { n: runs }), true);
                }
            }, 30);
        });

        function renderDiagReport(r) {
            const box = document.getElementById('diagReport');
            const reasons = Object.entries(r.deadEnds.byReason)
                .sort((a, b) => b[1] - a[1])
                .map(([k, v]) => `<span class="note">${esc(k)} ×${v}</span>`).join(' ');
            const errLine = r.errors.length ? `<div class="note" style="color:var(--err)">${esc(r.errors[0])}</div>` : '';
            box.style.display = 'block';
            box.innerHTML = `
                <div class="panel-title">${t('structure.3d.diagTitle')} <span class="note">(${t('structure.3d.diagRuns').replace('{n}', r.runs)})</span></div>
                <div class="grid2">
                    <div><label>${t('structure.3d.diagSuccess')}</label><div>${r.successRuns} / ${r.runs}</div></div>
                    <div><label>${t('structure.3d.diagPieces')}</label><div>${r.pieces.min}–${r.pieces.max}（avg ${r.pieces.avg}）</div></div>
                    <div><label>${t('structure.3d.diagDead')}</label><div>${r.deadEnds.total}（avg ${r.deadEnds.avgPerRun}）${r.deadEnds.ghosted ? ' · ' + esc(t('structure.3d.diagGhostShown').replace('{n}', r.deadEnds.ghosted)) : ''}</div></div>
                    <div><label>${t('structure.3d.diagOverlap')}</label><div>${r.overlaps.runsWithOverlaps} / ${r.runs}（${r.overlaps.totalVoxels} vox）</div></div>
                    <div><label>${t('structure.3d.diagFootprint')}</label><div>${r.footprint.maxW}×${r.footprint.maxH}×${r.footprint.maxD}</div></div>
                </div>
                ${reasons ? `<div style="margin-top:6px">${t('structure.3d.diagReasons')} ${reasons}</div>` : ''}
                ${errLine}`;
        }

        // P11：片段 3D 编辑（笔刷/剖切/方块搜索/右键组件查询）在独立编辑器窗口（piece-editor.html）
        // 主窗口这里只负责：🧱 按钮打开窗口（见 renderPieces .pc-3d）+ 接收写回（见下方 onPieceEditorEdits）

        // P11：片段 3D 编辑器窗口 → 编辑写回（合并进内存 tab；"保存草稿"时落盘）
        if (window.api && window.api.onPieceEditorEdits) {
            window.api.onPieceEditorEdits((payload) => {
                const tab = tabs.find((x) => x.packDir === payload.packDir && x.structureId === payload.structureId);
                if (!tab) return;
                let n = 0;
                for (const pe of payload.pieces || []) {
                    const i = (tab.bp.pieces || []).findIndex((p) => p.name === pe.name);
                    if (i < 0) continue;
                    tab.bp.pieces[i] = Object.assign({}, tab.bp.pieces[i], { boxFills: pe.boxFills || [], fillBlocks: pe.fillBlocks || [] });
                    n++;
                }
                if (n) {
                    if (activeUid === tab.uid) { renderPieces(tab.bp); schedulePreviewUpdate(tab); }
                    showStatus('🧱 3D 编辑器写回：' + n + ' 个片段已更新（记得"保存草稿"落盘）', true);
                }
                window.__lastEditorEdits = payload; // E2E 钩子
            });
        }
    }

    bindEvents();

    return { open, closeActiveTab, hasActiveTab };
})();
