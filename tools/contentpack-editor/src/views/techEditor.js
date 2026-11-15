// techEditor.js - 右栏：科技树编辑器（techs/tree.json）
// 契约（单一真源）：src/core/techSchema.js
//   Java  TechTreeLoader.java（解析/自检）· TechRequirementEngine.java（条件语义）
//         TechService.java（成本/奖励）· TechTreeScreen.java（页内网格常量）
//
// 与实体/任务编辑器不同：这是单文件内容类型，保存直写 contentpacks-source/<包>/techs/tree.json，
// 不经暂存区（与 TimeTape 编辑器同一套写法）。
//
// 布局是「手排」的：col/row 就是运行期网格坐标（NODE_W=84 / NODE_H=40 / COL_STRIDE=128 /
// ROW_STRIDE=64）。画布拖拽只做吸附到格，不做自动布局；重叠格子会标红并在校验里报 warn。
//
// 暴露 window.TechEditor.open({pack, tree, exists, refs, pageId, nodeId, tab})
//      close() / hasActiveTab() / rerender() / init({onListChanged})

window.TechEditor = (function () {
    'use strict';

    const S = {
        pack: null,
        tree: null,
        exists: false,
        refs: null,
        page: null,
        tab: 'layout',
        selected: null,          // {kind:'node'|'research', id}
        zoom: 1.4,
        handlers: { onListChanged: null },
    };

    // 与 TechTreeScreen.java:42-45 一致的网格常量（这里是「原尺寸」，画布再乘缩放）
    const NODE_W = 84;
    const NODE_H = 40;
    const COL_STRIDE = 128;
    const ROW_STRIDE = 64;
    const PAD = 24;

    const TS = () => window.TechSchema;
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : k);
    const isEn = () => !!(window.I18n && window.I18n.getLang() === 'en');
    const fl = (spec) => ((isEn() && spec && spec.en) ? spec.en : (spec ? spec.zh : ''));

    // ---------- 小工具 ----------
    function el(tag, cls, text) {
        const e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text !== undefined) e.textContent = text;
        return e;
    }

    function field(labelText, input) {
        const d = el('div');
        d.appendChild(el('label', 'f-label', labelText));
        d.appendChild(input);
        return d;
    }

    function inText(value, placeholder, title) {
        const i = el('input', 'f-input');
        i.type = 'text';
        i.value = value == null ? '' : String(value);
        if (placeholder) i.placeholder = placeholder;
        if (title) i.title = title;
        return i;
    }

    function inNum(value, min, step, placeholder) {
        const i = el('input', 'f-input');
        i.type = 'number';
        i.value = value == null ? '' : String(value);
        if (min !== undefined) i.min = min;
        if (step !== undefined) i.step = step;
        if (placeholder) i.placeholder = placeholder;
        return i;
    }

    function sel(options, value) {
        const s = el('select', 'f-input');
        for (const [v, label] of options) {
            const o = el('option', null, label);
            o.value = v;
            s.appendChild(o);
        }
        if (value !== undefined && value !== null) s.value = String(value);
        return s;
    }

    function chk(labelText, checked) {
        const d = el('div', 'f-check');
        const c = el('input');
        c.type = 'checkbox';
        c.checked = !!checked;
        d.appendChild(c);
        d.appendChild(el('label', null, labelText));
        return d;
    }

    function section(title, ...children) {
        const s = el('div', 'ib-section tech-section');
        s.appendChild(el('h3', 'ib-sec-title', title));
        for (const c of children) if (c) s.appendChild(c);
        return s;
    }

    function note(text) {
        return el('div', 'f-note', text);
    }

    function pre(obj) {
        const p = el('pre', 'out ib-pre');
        p.textContent = JSON.stringify(obj, null, 2);
        return p;
    }

    function statusMsg(msg, ok) {
        if (window.showStatus) window.showStatus(msg, ok !== false);
    }

    function delBtn(title, onClick) {
        const b = el('button', 'btn ghost small', '✕');
        b.type = 'button';
        b.title = title;
        b.style.color = 'var(--err)';
        b.addEventListener('click', onClick);
        return b;
    }

    function svgEl(tag) {
        return document.createElementNS('http://www.w3.org/2000/svg', tag);
    }

    function arr(v) {
        return Array.isArray(v) ? v : [];
    }

    function num(v, fallback) {
        const n = Number(v);
        return Number.isFinite(n) ? Math.trunc(n) : fallback;
    }

    // ---------- 生命周期 ----------
    function open(ctx) {
        S.pack = ctx.pack;
        S.refs = ctx.refs || (window.AppState && window.AppState.techRefs) || null;
        S.exists = !!ctx.exists;
        S.tree = ctx.tree ? TS().clone(ctx.tree) : TS().newTree();
        if (!S.tree.pages || !S.tree.pages.length) {
            S.tree.pages = [TS().newPage(TS().DEFAULT_PAGE, t('tech.defaultPage'))];
        }
        for (const key of ['nodes', 'research']) if (!Array.isArray(S.tree[key])) S.tree[key] = [];
        // 保存时省略空数组（见 buildTreeJson），所以重新打开后必须补回来：
        // 表单的「+ 添加」是对数组原地 push，字段缺席时改动会落在临时数组里丢掉。
        for (const node of S.tree.nodes) {
            if (!node || typeof node !== 'object') continue;
            for (const k of ['requirements', 'visibleWhen', 'resetWhen', 'costs', 'rewards', 'onUnlock']) {
                if (!Array.isArray(node[k])) node[k] = [];
            }
        }
        for (const entry of S.tree.research) {
            if (!entry || typeof entry !== 'object') continue;
            for (const k of ['requirements', 'visibleWhen', 'costs']) {
                if (!Array.isArray(entry[k])) entry[k] = [];
            }
        }
        if (!S.tree.points || typeof S.tree.points !== 'object') S.tree.points = { questReward: 1 };
        if (!S.tree.table || typeof S.tree.table !== 'object') S.tree.table = { maxTier: 1, upgrades: [] };
        if (!Array.isArray(S.tree.table.upgrades)) S.tree.table.upgrades = [];
        for (const upgrade of S.tree.table.upgrades) {
            if (upgrade && typeof upgrade === 'object' && !Array.isArray(upgrade.costs)) upgrade.costs = [];
        }

        const wantPage = ctx.pageId;
        S.page = (wantPage && S.tree.pages.some((p) => p.id === wantPage))
            ? wantPage : (S.tree.pages[0] ? S.tree.pages[0].id : null);
        S.selected = null;
        if (ctx.nodeId && S.tree.nodes.some((n) => n.id === ctx.nodeId)) {
            S.selected = { kind: 'node', id: ctx.nodeId };
        } else if (ctx.researchId && S.tree.research.some((r) => r.id === ctx.researchId)) {
            S.selected = { kind: 'research', id: ctx.researchId };
        }
        S.tab = ctx.tab || 'layout';
        renderAll();
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    function close() {
        S.tree = null;
        S.pack = null;
        S.selected = null;
        const mount = document.getElementById('techMount');
        if (mount) mount.innerHTML = '';
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    function hasActiveTab() { return !!S.tree; }

    function rerender() {
        if (S.tree) renderAll();
    }

    function renderTitle() {
        const titleEl = document.getElementById('techTitle');
        const badge = document.getElementById('techBadge');
        const tree = S.tree;
        if (titleEl) {
            const page = tree.pages.find((p) => p.id === S.page);
            titleEl.textContent = '🌳 ' + (tree.title || t('tech.title'))
                + (page ? ' · ' + (page.title || page.id) : '');
        }
        if (badge) {
            badge.textContent = S.exists ? t('tech.badge.formal') : t('tech.badge.new');
            badge.className = 'editor-badge ' + (S.exists ? 'ok' : 'warn');
        }
        const del = document.getElementById('btnTechDelete');
        if (del) del.disabled = !S.selected;
    }

    // ---------- 主渲染 ----------
    function renderAll() {
        const mount = document.getElementById('techMount');
        if (!mount) return;
        mount.innerHTML = '';

        // 全局 datalist：触发器名 / 节点 id / 本包物品 id
        mount.appendChild(buildDatalists());

        const tabBar = el('div', 'ib-tabbar');
        const tabs = [
            ['layout', t('tech.tab.layout')],
            ['edit', t('tech.tab.edit')],
            ['tree', t('tech.tab.tree')],
            ['check', t('tech.tab.check')],
        ];
        for (const [name, label] of tabs) {
            const b = el('button', 'tab' + (S.tab === name ? ' active' : ''), label);
            b.type = 'button';
            b.addEventListener('click', () => { S.tab = name; renderAll(); });
            tabBar.appendChild(b);
        }
        mount.appendChild(tabBar);

        const page = el('div', 'ib-page');
        if (S.tab === 'layout') buildLayout(page);
        else if (S.tab === 'edit') buildEdit(page);
        else if (S.tab === 'tree') buildTree(page);
        else buildCheck(page);
        mount.appendChild(page);
        renderTitle();
    }

    function buildDatalists() {
        const box = el('div');
        const addList = (id, values) => {
            const dl = document.createElement('datalist');
            dl.id = id;
            for (const v of values) {
                const o = document.createElement('option');
                o.value = v;
                dl.appendChild(o);
            }
            box.appendChild(dl);
        };
        const usage = TS().collectTriggerUsage(S.tree);
        addList('techTriggerNames', usage.map((u) => u.name));
        addList('techNodeIds', arr(S.tree.nodes).map((n) => n.id).filter(Boolean));
        const refs = S.refs || {};
        addList('techItemIds', arr(refs.items));
        return box;
    }

    // ---------- 页签：手动排版 ----------
    function buildLayout(page) {
        const wrap = el('div', 'tech-layout');
        const tabs = el('div', 'tech-page-tabs');
        for (const p of S.tree.pages) {
            const b = el('button', 'tab' + (S.page === p.id ? ' active' : ''), p.title || p.id);
            b.type = 'button';
            b.title = p.id;
            b.addEventListener('click', () => { S.page = p.id; S.selected = null; renderAll(); });
            tabs.appendChild(b);
        }
        wrap.appendChild(tabs);

        const bar = el('div', 'row tech-canvas-bar');
        const addPageBtn = el('button', 'btn ghost small', '+ ' + t('tech.newPage'));
        addPageBtn.type = 'button';
        addPageBtn.addEventListener('click', addPage);
        const delPageBtn = el('button', 'btn ghost small', t('tech.deletePage'));
        delPageBtn.type = 'button';
        delPageBtn.style.color = 'var(--err)';
        delPageBtn.addEventListener('click', deletePage);
        const addNodeBtn = el('button', 'btn small', '+ ' + t('tech.newNode'));
        addNodeBtn.type = 'button';
        addNodeBtn.addEventListener('click', addNode);
        bar.appendChild(addPageBtn);
        bar.appendChild(delPageBtn);
        bar.appendChild(addNodeBtn);

        const zoomWrap = el('label', 'tech-zoom');
        zoomWrap.appendChild(el('span', null, t('tech.zoom')));
        const zoom = el('input');
        zoom.type = 'range';
        zoom.min = '0.8';
        zoom.max = '2.4';
        zoom.step = '0.1';
        zoom.value = String(S.zoom);
        zoom.addEventListener('input', () => {
            S.zoom = Number(zoom.value) || 1;
            renderAll();
        });
        zoomWrap.appendChild(zoom);
        bar.appendChild(zoomWrap);

        const pageNodes = arr(S.tree.nodes).filter((n) => n && n.page === S.page);
        const collisionIds = collisionSet(pageNodes);
        bar.appendChild(el('span', 'f-note',
            tf('tech.layoutStats', { n: pageNodes.length, c: collisionIds.size })));
        wrap.appendChild(bar);

        if (collisionIds.size) {
            wrap.appendChild(el('div', 'ib-check-err', '⚠ ' + t('tech.layoutCollision')));
        }

        const u = S.zoom;
        const colW = COL_STRIDE * u;
        const rowH = ROW_STRIDE * u;
        const nodeW = NODE_W * u;
        const nodeH = NODE_H * u;
        const pad = PAD * u;
        let maxCol = 0;
        let maxRow = 0;
        for (const n of pageNodes) {
            maxCol = Math.max(maxCol, num(n.col, 0));
            maxRow = Math.max(maxRow, num(n.row, 0));
        }
        const width = pad * 2 + maxCol * colW + nodeW;
        const height = pad * 2 + maxRow * rowH + nodeH;

        const canvas = el('div', 'tech-canvas');
        canvas.style.width = Math.max(width, 480) + 'px';
        canvas.style.height = Math.max(height, 240) + 'px';
        canvas.style.backgroundSize = colW + 'px ' + rowH + 'px';
        canvas.style.backgroundPosition = pad + 'px ' + pad + 'px';

        // 依赖连线：只画同页内「前置 → 本节点」，跨页引用不画（避免长线穿过整张图）
        const svg = svgEl('svg');
        svg.setAttribute('class', 'tech-links');
        svg.setAttribute('width', String(Math.max(width, 480)));
        svg.setAttribute('height', String(Math.max(height, 240)));
        const byId = new Map(pageNodes.map((n) => [n.id, n]));
        const drawn = new Set();
        for (const target of pageNodes) {
            const deps = [];
            collectNodeRefs(target.requirements, deps);
            for (const depId of deps) {
                const src = byId.get(depId);
                if (!src || depId === target.id) continue;
                const key = src.id + '>' + target.id;
                if (drawn.has(key)) continue;
                drawn.add(key);
                const x1 = pad + num(src.col, 0) * colW + nodeW;
                const y1 = pad + num(src.row, 0) * rowH + nodeH / 2;
                const x2 = pad + num(target.col, 0) * colW;
                const y2 = pad + num(target.row, 0) * rowH + nodeH / 2;
                const dx = Math.max(20, Math.abs(x2 - x1) / 2);
                const path = svgEl('path');
                path.setAttribute('d', `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`);
                path.setAttribute('class', 'tech-link');
                svg.appendChild(path);
            }
        }
        canvas.appendChild(svg);

        for (const node of pageNodes) {
            const card = el('div', 'tech-node');
            card.style.left = (pad + num(node.col, 0) * colW) + 'px';
            card.style.top = (pad + num(node.row, 0) * rowH) + 'px';
            card.style.width = nodeW + 'px';
            card.style.height = nodeH + 'px';
            card.style.fontSize = Math.max(9, Math.round(10 * u)) + 'px';
            if (S.selected && S.selected.kind === 'node' && S.selected.id === node.id) card.classList.add('selected');
            if (collisionIds.has(node.id)) card.classList.add('collision');
            if (node.quest === true) card.classList.add('has-quest');
            const title = el('div', 'tech-node-title', node.title || node.id || '?');
            const sub = el('div', 'tech-node-sub', (node.id || '?') + '  (' + num(node.col, 0) + ',' + num(node.row, 0) + ')');
            card.appendChild(title);
            card.appendChild(sub);
            card.title = (node.title || node.id || '?') + '\n' + (node.id || '')
                + '\n(' + num(node.col, 0) + ', ' + num(node.row, 0) + ')';
            bindDrag(card, node, { pad, colW, rowH });
            card.addEventListener('dblclick', () => {
                S.selected = { kind: 'node', id: node.id };
                S.tab = 'edit';
                renderAll();
            });
            canvas.appendChild(card);
        }

        const scroller = el('div', 'tech-canvas-wrap');
        scroller.appendChild(canvas);
        wrap.appendChild(scroller);
        wrap.appendChild(note(t('tech.layoutHint')));
        page.appendChild(wrap);
    }

    function collisionSet(nodes) {
        const cells = new Map();
        for (const n of nodes) {
            const key = num(n.col, 0) + ',' + num(n.row, 0);
            if (!cells.has(key)) cells.set(key, []);
            cells.get(key).push(n.id);
        }
        const out = new Set();
        for (const ids of cells.values()) {
            if (ids.length > 1) for (const id of ids) out.add(id);
        }
        return out;
    }

    function collectNodeRefs(reqs, out) {
        for (const req of arr(reqs)) {
            if (!req || typeof req !== 'object') continue;
            if (req.type === 'tech') for (const id of arr(req.nodes)) out.push(String(id));
            else if (req.type === 'all' || req.type === 'any') collectNodeRefs(req.requirements, out);
        }
    }

    function bindDrag(card, node, geo) {
        card.addEventListener('mousedown', (e) => {
            if (e.button !== 0) return;
            e.preventDefault();
            const startX = e.clientX;
            const startY = e.clientY;
            const startLeft = parseFloat(card.style.left) || 0;
            const startTop = parseFloat(card.style.top) || 0;
            let moved = false;
            const onMove = (ev) => {
                const dx = ev.clientX - startX;
                const dy = ev.clientY - startY;
                if (Math.abs(dx) > 3 || Math.abs(dy) > 3) moved = true;
                card.style.left = Math.max(0, startLeft + dx) + 'px';
                card.style.top = Math.max(0, startTop + dy) + 'px';
            };
            const onUp = () => {
                document.removeEventListener('mousemove', onMove);
                document.removeEventListener('mouseup', onUp);
                if (!moved) {
                    // 不做整页重绘：重绘会替换掉卡片 DOM，双击的第二下就落不到同一个元素上。
                    S.selected = { kind: 'node', id: node.id };
                    const host = card.parentElement;
                    if (host) {
                        for (const other of host.querySelectorAll('.tech-node')) {
                            other.classList.toggle('selected', other === card);
                        }
                    }
                    const del = document.getElementById('btnTechDelete');
                    if (del) del.disabled = false;
                    return;
                }
                const col = Math.max(0, Math.round((parseFloat(card.style.left) - geo.pad) / geo.colW));
                const row = Math.max(0, Math.round((parseFloat(card.style.top) - geo.pad) / geo.rowH));
                node.col = col;
                node.row = row;
                S.selected = { kind: 'node', id: node.id };
                renderAll();
            };
            document.addEventListener('mousemove', onMove);
            document.addEventListener('mouseup', onUp);
        });
    }

    // ---------- 页签：节点 / 研究项表单 ----------
    function buildEdit(page) {
        if (!S.selected) {
            page.appendChild(note(t('tech.selectHint')));
            return;
        }
        if (S.selected.kind === 'research') {
            buildResearchForm(page);
            return;
        }
        const node = S.tree.nodes.find((n) => n.id === S.selected.id);
        if (!node) {
            page.appendChild(note(t('tech.selectHint')));
            return;
        }
        const isNew = node._isNew === true;

        // 标识与位置
        const idIn = inText(node.id, 'blueprint_ak_long', t('tech.idTip'));
        idIn.disabled = !isNew;
        idIn.addEventListener('input', () => { node.id = idIn.value.trim(); });
        const titleIn = inText(node.title, t('tech.nodeTitlePh'));
        titleIn.addEventListener('input', () => { node.title = titleIn.value; });
        const descIn = el('textarea', 'f-input tech-desc');
        descIn.rows = 3;
        descIn.value = node.desc == null ? '' : String(node.desc);
        descIn.addEventListener('input', () => { node.desc = descIn.value; });
        const pageSel = sel(S.tree.pages.map((p) => [p.id, (p.title || p.id) + ' (' + p.id + ')']), node.page);
        pageSel.addEventListener('change', () => {
            node.page = pageSel.value;
            S.page = pageSel.value;
            renderAll();
        });
        const colIn = inNum(node.col, 0, 1);
        colIn.addEventListener('input', () => { node.col = colIn.value === '' ? '' : num(colIn.value, 0); });
        const rowIn = inNum(node.row, 0, 1);
        rowIn.addEventListener('input', () => { node.row = rowIn.value === '' ? '' : num(rowIn.value, 0); });
        const iconIn = inText(node.icon || TS().DEFAULT_ICON, 'minecraft:paper');
        iconIn.setAttribute('list', 'techItemIds');
        iconIn.addEventListener('input', () => { node.icon = iconIn.value.trim(); });

        page.appendChild(section(t('tech.identity'),
            isNew ? null : note(t('tech.idLocked')),
            field(t('tech.id'), idIn),
            field(t('tech.nodeTitle'), titleIn),
            field(t('tech.nodeDesc'), descIn),
            field(t('tech.page'), pageSel),
            el('div', 'row',
                field(t('tech.col'), colIn),
                field(t('tech.row'), rowIn)),
            note(t('tech.colRowNote')),
            field(t('tech.icon'), iconIn),
            note(t('tech.iconNote')),
        ));

        // 解锁条件
        const reqBox = el('div', 'tech-list');
        buildReqList(reqBox, node.requirements);
        page.appendChild(section(t('tech.unlockReq'), note(t('tech.unlockReqNote')), reqBox));

        // 可见条件
        const visBox = el('div', 'tech-list');
        buildReqList(visBox, node.visibleWhen);
        page.appendChild(section(t('tech.visibleWhen'), note(t('tech.visibleWhenNote')), visBox));

        // 成本与奖励
        const costBox = el('div', 'tech-list');
        buildCostList(costBox, node.costs);
        const rewardBox = el('div', 'tech-list');
        buildRewardList(rewardBox, node.rewards);
        const questChk = chk(t('tech.quest'), node.quest === true);
        questChk.querySelector('input').addEventListener('change', (e) => { node.quest = !!e.target.checked; });
        page.appendChild(section(t('tech.costs'), note(t('tech.costsNote')), costBox,
            section(t('tech.rewards'), note(t('tech.rewardsNote')), rewardBox),
            questChk, note(t('tech.questNote')),
        ));

        // 重置
        const resetTicks = inNum(node.resetAfterTicks == null ? 0 : node.resetAfterTicks, 0, 20);
        resetTicks.addEventListener('input', () => {
            node.resetAfterTicks = resetTicks.value === '' ? 0 : Math.max(0, num(resetTicks.value, 0));
        });
        const resetBox = el('div', 'tech-list');
        buildReqList(resetBox, node.resetWhen);
        page.appendChild(section(t('tech.reset'),
            field(t('tech.resetAfterTicks'), resetTicks),
            note(t('tech.resetAfterTicksNote')),
            note(t('tech.resetWhenNote')),
            resetBox,
        ));

        // 解锁时触发的触发器
        const onUnlockIn = inText(arr(node.onUnlock).join(', '), 'crafted_component', t('tech.onUnlockNote'));
        onUnlockIn.setAttribute('list', 'techTriggerNames');
        onUnlockIn.addEventListener('input', () => {
            node.onUnlock = onUnlockIn.value.split(/[,，\s]+/).map((x) => x.trim()).filter(Boolean);
        });
        page.appendChild(section(t('tech.onUnlock'), field(t('tech.onUnlockNames'), onUnlockIn), note(t('tech.onUnlockNote'))));
    }

    function buildResearchForm(page) {
        const entry = S.tree.research.find((r) => r.id === S.selected.id);
        if (!entry) {
            page.appendChild(note(t('tech.selectHint')));
            return;
        }
        const isNew = entry._isNew === true;
        const idIn = inText(entry.id, 'salvage_guns', t('tech.idTip'));
        idIn.disabled = !isNew;
        idIn.addEventListener('input', () => { entry.id = idIn.value.trim(); });
        const titleIn = inText(entry.title, t('tech.researchTitlePh'));
        titleIn.addEventListener('input', () => { entry.title = titleIn.value; });
        const descIn = el('textarea', 'f-input tech-desc');
        descIn.rows = 2;
        descIn.value = entry.desc == null ? '' : String(entry.desc);
        descIn.addEventListener('input', () => { entry.desc = descIn.value; });
        const pageSel = sel(S.tree.pages.map((p) => [p.id, (p.title || p.id) + ' (' + p.id + ')']), entry.page);
        pageSel.addEventListener('change', () => { entry.page = pageSel.value; S.page = pageSel.value; renderAll(); });
        const iconIn = inText(entry.icon || TS().DEFAULT_ICON, 'minecraft:paper');
        iconIn.setAttribute('list', 'techItemIds');
        iconIn.addEventListener('input', () => { entry.icon = iconIn.value.trim(); });
        const pointsIn = inNum(entry.points == null ? 0 : entry.points, 0, 1);
        pointsIn.addEventListener('input', () => { entry.points = pointsIn.value === '' ? 0 : num(pointsIn.value, 0); });
        const cdIn = inNum(entry.cooldownTicks == null ? 0 : entry.cooldownTicks, 0, 20);
        cdIn.addEventListener('input', () => { entry.cooldownTicks = cdIn.value === '' ? 0 : Math.max(0, num(cdIn.value, 0)); });

        page.appendChild(section(t('tech.identity'),
            isNew ? null : note(t('tech.idLocked')),
            field(t('tech.id'), idIn),
            field(t('tech.nodeTitle'), titleIn),
            field(t('tech.nodeDesc'), descIn),
            field(t('tech.page'), pageSel),
            field(t('tech.icon'), iconIn),
            el('div', 'row',
                field(t('tech.points'), pointsIn),
                field(t('tech.cooldownTicks'), cdIn)),
            note(t('tech.researchNote')),
        ));

        const reqBox = el('div', 'tech-list');
        buildReqList(reqBox, entry.requirements);
        page.appendChild(section(t('tech.unlockReq'), note(t('tech.unlockReqNote')), reqBox));

        const visBox = el('div', 'tech-list');
        buildReqList(visBox, entry.visibleWhen);
        page.appendChild(section(t('tech.visibleWhen'), note(t('tech.visibleWhenNote')), visBox));

        const costBox = el('div', 'tech-list');
        buildCostList(costBox, entry.costs);
        page.appendChild(section(t('tech.costs'), note(t('tech.researchCostsNote')), costBox));
    }

    // ---------- 条件编辑器（递归） ----------
    function buildReqList(container, list) {
        if (!Array.isArray(list)) list = [];
        list.forEach((req, idx) => container.appendChild(buildReqRow(list, idx)));

        const types = Object.keys(TS().REQUIREMENT_TYPES);
        const typeSel = sel(types.map((x) => [x, fl(TS().REQUIREMENT_TYPES[x]) + ' (' + x + ')']), types[0]);
        const add = el('button', 'btn ghost small', '+ ' + t('tech.addRequirement'));
        add.type = 'button';
        add.addEventListener('click', () => {
            list.push(TS().newRequirement(typeSel.value));
            renderAll();
        });
        const row = el('div', 'row tech-add-row');
        row.appendChild(typeSel);
        row.appendChild(add);
        container.appendChild(row);
    }

    function buildReqRow(list, idx) {
        const req = list[idx];
        const row = el('div', 'tech-req-row');
        if (!req || typeof req !== 'object') {
            row.appendChild(note(t('tech.badReq')));
            row.appendChild(delBtn(t('tech.remove'), () => { list.splice(idx, 1); renderAll(); }));
            return row;
        }
        const spec = TS().REQUIREMENT_TYPES[req.type];
        const typeOptions = Object.keys(TS().REQUIREMENT_TYPES).map((x) => [x, fl(TS().REQUIREMENT_TYPES[x]) + ' (' + x + ')']);
        if (!spec && req.type) typeOptions.push([req.type, req.type + ' (?)']);
        const typeSel = sel(typeOptions, req.type);
        typeSel.addEventListener('change', () => {
            list[idx] = TS().newRequirement(typeSel.value);
            renderAll();
        });
        const head = el('div', 'row tech-req-head');
        head.appendChild(typeSel);
        head.appendChild(delBtn(t('tech.remove'), () => { list.splice(idx, 1); renderAll(); }));
        row.appendChild(head);

        if (!spec) {
            row.appendChild(note(tf('tech.unknownReqType', { type: req.type || '?' })));
            row.appendChild(pre(req));
            return row;
        }
        for (const f of spec.fields) row.appendChild(buildReqField(req, f));
        return row;
    }

    function buildReqField(req, f) {
        if (f.type === 'int') {
            const inp = inNum(req[f.key] == null ? '' : req[f.key], f.min, 1);
            inp.addEventListener('input', () => {
                req[f.key] = inp.value === '' ? '' : num(inp.value, 0);
            });
            return field(f.key, inp);
        }
        if (f.type === 'bool') {
            const c = chk(f.key, req[f.key] === true);
            c.querySelector('input').addEventListener('change', (e) => { req[f.key] = !!e.target.checked; });
            return c;
        }
        if (f.type === 'item' || f.type === 'rl') {
            const inp = inText(req[f.key] == null ? '' : req[f.key], f.type === 'item' ? 'tech_tree_pack:blueprint_x' : 'minecraft:story/root');
            if (f.type === 'item') inp.setAttribute('list', 'techItemIds');
            inp.addEventListener('input', () => { req[f.key] = inp.value.trim(); });
            return field(f.key, inp);
        }
        if (f.type === 'trigger') {
            const inp = inText(req[f.key] == null ? '' : req[f.key], 'crafted_component');
            inp.setAttribute('list', 'techTriggerNames');
            inp.addEventListener('input', () => { req[f.key] = inp.value.trim(); });
            return field(f.key, inp);
        }
        if (f.type === 'stringList') {
            const inp = inText(arr(req[f.key]).join(', '), 'catoxidesbattlerebuild:quest_id, …');
            inp.addEventListener('input', () => {
                req[f.key] = inp.value.split(/[,，\s]+/).map((x) => x.trim()).filter(Boolean);
            });
            return field(f.key, inp);
        }
        if (f.type === 'nodeRefs') {
            const inp = inText(arr(req[f.key]).join(', '), 'node_a, node_b');
            inp.setAttribute('list', 'techNodeIds');
            inp.addEventListener('input', () => {
                req[f.key] = inp.value.split(/[,，\s]+/).map((x) => x.trim()).filter(Boolean);
            });
            return field(f.key, inp);
        }
        if (f.type === 'reqList') {
            if (!Array.isArray(req[f.key])) req[f.key] = [];
            const box = el('div', 'tech-list tech-list-nested');
            box.appendChild(note(t('tech.nestedReq')));
            buildReqList(box, req[f.key]);
            return box;
        }
        const inp = inText(req[f.key] == null ? '' : req[f.key]);
        inp.addEventListener('input', () => { req[f.key] = inp.value; });
        return field(f.key, inp);
    }

    // ---------- 成本 / 奖励 ----------
    function buildCostList(container, list) {
        if (!Array.isArray(list)) list = [];
        list.forEach((cost, idx) => container.appendChild(buildCostRow(list, idx)));

        const types = Object.keys(TS().COST_TYPES);
        const typeSel = sel(types.map((x) => [x, fl(TS().COST_TYPES[x]) + ' (' + x + ')']), types[0]);
        const add = el('button', 'btn ghost small', '+ ' + t('tech.addCost'));
        add.type = 'button';
        add.addEventListener('click', () => {
            list.push(TS().newCost(typeSel.value));
            renderAll();
        });
        const row = el('div', 'row tech-add-row');
        row.appendChild(typeSel);
        row.appendChild(add);
        container.appendChild(row);
    }

    function buildCostRow(list, idx) {
        const cost = list[idx];
        const row = el('div', 'tech-req-row');
        if (!cost || typeof cost !== 'object') {
            row.appendChild(note(t('tech.badCost')));
            row.appendChild(delBtn(t('tech.remove'), () => { list.splice(idx, 1); renderAll(); }));
            return row;
        }
        const spec = TS().COST_TYPES[cost.type];
        const typeOptions = Object.keys(TS().COST_TYPES).map((x) => [x, fl(TS().COST_TYPES[x]) + ' (' + x + ')']);
        if (!spec && cost.type) typeOptions.push([cost.type, cost.type + ' (?)']);
        const typeSel = sel(typeOptions, cost.type);
        typeSel.addEventListener('change', () => {
            list[idx] = TS().newCost(typeSel.value);
            renderAll();
        });
        const head = el('div', 'row tech-req-head');
        head.appendChild(typeSel);
        head.appendChild(delBtn(t('tech.remove'), () => { list.splice(idx, 1); renderAll(); }));
        row.appendChild(head);

        if (!spec) {
            row.appendChild(note(tf('tech.unknownCostType', { type: cost.type || '?' })));
            row.appendChild(pre(cost));
            return row;
        }
        for (const f of spec.fields) {
            if (f.type === 'item') {
                const inp = inText(cost[f.key] == null ? '' : cost[f.key], 'minecraft:iron_ingot');
                inp.setAttribute('list', 'techItemIds');
                inp.addEventListener('input', () => { cost[f.key] = inp.value.trim(); });
                row.appendChild(field(f.key, inp));
            } else {
                const inp = inNum(cost[f.key] == null ? '' : cost[f.key], f.min, 1);
                inp.addEventListener('input', () => {
                    cost[f.key] = inp.value === '' ? '' : num(inp.value, 0);
                });
                row.appendChild(field(f.key, inp));
            }
        }
        return row;
    }

    function buildRewardList(container, list) {
        if (!Array.isArray(list)) list = [];
        list.forEach((reward, idx) => {
            const row = el('div', 'tech-req-row');
            const itemIn = inText(reward.item, 'tech_tree_pack:blueprint_x');
            itemIn.setAttribute('list', 'techItemIds');
            itemIn.addEventListener('input', () => { reward.item = itemIn.value.trim(); });
            const countIn = inNum(reward.count == null ? 1 : reward.count, 1, 1);
            countIn.addEventListener('input', () => {
                reward.count = countIn.value === '' ? 1 : Math.max(1, num(countIn.value, 1));
            });
            const head = el('div', 'row tech-req-head');
            head.appendChild(el('span', 'f-note', t('tech.reward')));
            head.appendChild(delBtn(t('tech.remove'), () => { list.splice(idx, 1); renderAll(); }));
            row.appendChild(head);
            row.appendChild(field(t('tech.item'), itemIn));
            row.appendChild(field(t('tech.count'), countIn));
            container.appendChild(row);
        });
        const add = el('button', 'btn ghost small', '+ ' + t('tech.addReward'));
        add.type = 'button';
        add.addEventListener('click', () => {
            list.push({ item: '', count: 1 });
            renderAll();
        });
        container.appendChild(add);
    }

    // ---------- 页签：树设置 ----------
    function buildTree(page) {
        const tree = S.tree;
        const titleIn = inText(tree.title, t('tech.treeTitlePh'));
        titleIn.addEventListener('input', () => { tree.title = titleIn.value; renderTitle(); });
        page.appendChild(section(t('tech.treeSettings'),
            field(t('tech.treeTitle'), titleIn),
            note(t('tech.treeTitleNote')),
        ));

        const rewardIn = inNum(tree.points.questReward == null ? 0 : tree.points.questReward, 0, 1);
        rewardIn.addEventListener('input', () => {
            tree.points.questReward = rewardIn.value === '' ? 0 : num(rewardIn.value, 0);
        });
        page.appendChild(section(t('tech.points'),
            field(t('tech.questReward'), rewardIn),
            note(t('tech.questRewardNote')),
        ));

        const maxTierIn = inNum(tree.table.maxTier == null ? 1 : tree.table.maxTier, 1, 1);
        maxTierIn.addEventListener('input', () => {
            tree.table.maxTier = maxTierIn.value === '' ? 1 : Math.max(1, num(maxTierIn.value, 1));
        });
        const upgradesBox = el('div', 'tech-list');
        arr(tree.table.upgrades).forEach((upgrade, idx) => {
            const row = el('div', 'tech-req-row');
            const toIn = inNum(upgrade.to, 2, 1);
            toIn.addEventListener('input', () => {
                upgrade.to = toIn.value === '' ? '' : num(toIn.value, 0);
            });
            const head = el('div', 'row tech-req-head');
            head.appendChild(field(t('tech.upgradeTo'), toIn));
            head.appendChild(delBtn(t('tech.remove'), () => {
                tree.table.upgrades.splice(idx, 1);
                renderAll();
            }));
            row.appendChild(head);
            buildCostList(row, upgrade.costs);
            upgradesBox.appendChild(row);
        });
        const addUpgrade = el('button', 'btn ghost small', '+ ' + t('tech.addUpgrade'));
        addUpgrade.type = 'button';
        addUpgrade.addEventListener('click', () => {
            const taken = new Set(arr(tree.table.upgrades).map((u) => num(u.to, -1)));
            let to = 2;
            while (taken.has(to)) to++;
            tree.table.upgrades.push({ to, costs: [TS().newCost('item')] });
            renderAll();
        });
        upgradesBox.appendChild(addUpgrade);
        page.appendChild(section(t('tech.table'),
            field(t('tech.maxTier'), maxTierIn),
            note(t('tech.tableNote')),
            upgradesBox,
        ));

        const researchBox = el('div', 'tech-list');
        const research = arr(tree.research);
        if (!research.length) researchBox.appendChild(note(t('tech.researchEmpty')));
        for (const entry of research) {
            const row = el('div', 'tech-req-row tech-research-row');
            const textBox = el('div', 'li-text');
            textBox.appendChild(el('div', 'li-title', '🔬 ' + (entry.title || entry.id)));
            textBox.appendChild(el('div', 'li-sub',
                entry.id + ' · ' + (entry.page || '?') + ' · +' + num(entry.points, 0) + ' pt'));
            row.appendChild(textBox);
            const actions = el('div', 'row');
            const edit = el('button', 'btn ghost small', t('tech.editResearch'));
            edit.type = 'button';
            edit.addEventListener('click', () => {
                S.selected = { kind: 'research', id: entry.id };
                S.tab = 'edit';
                renderAll();
            });
            const del = el('button', 'btn ghost small', t('tech.deleteResearch'));
            del.type = 'button';
            del.style.color = 'var(--err)';
            del.addEventListener('click', () => deleteResearch(entry.id));
            actions.appendChild(edit);
            actions.appendChild(del);
            row.appendChild(actions);
            researchBox.appendChild(row);
        }
        const addResearch = el('button', 'btn ghost small', '+ ' + t('tech.addResearch'));
        addResearch.type = 'button';
        addResearch.addEventListener('click', addResearchEntry);
        researchBox.appendChild(addResearch);
        page.appendChild(section(t('tech.research'), note(t('tech.researchListNote')), researchBox));
    }

    // ---------- 页签：校验 + 触发器 ----------
    function buildCheck(page) {
        const check = TS().validateTree(S.tree, refSets());
        const box = section(t('tech.validation'));
        if (check.valid) box.appendChild(note(t('tech.checkValid')));
        for (const e of check.errors) box.appendChild(el('div', 'ib-check-err', '✗ ' + e));
        if (check.errors.length) box.appendChild(el('div', 'f-note', tf('tech.checkErrN', { n: check.errors.length })));
        if (check.warnings.length) {
            box.appendChild(el('div', 'f-note', tf('tech.checkWarnN', { n: check.warnings.length })));
            for (const w of check.warnings) box.appendChild(el('div', 'f-note', '⚠ ' + w));
        } else {
            box.appendChild(note(t('tech.checkNoWarn')));
        }
        page.appendChild(box);

        // 触发器名册
        const usage = TS().collectTriggerUsage(S.tree);
        const trigBox = section(t('tech.triggerRoster'));
        if (!usage.length) {
            trigBox.appendChild(note(t('tech.triggerNone')));
        } else {
            const table = el('table', 'ib-table tech-trigger-table');
            const thead = el('tr');
            thead.appendChild(el('th', null, t('tech.triggerName')));
            thead.appendChild(el('th', null, t('tech.triggerCount')));
            thead.appendChild(el('th', null, t('tech.triggerUsage')));
            table.appendChild(thead);
            for (const u of usage) {
                const tr = el('tr');
                tr.appendChild(el('td', null, u.name));
                tr.appendChild(el('td', null, String(u.count)));
                tr.appendChild(el('td', null, u.uses.map((x) => x.owner + ':' + x.id + '/' + x.field).join(', ')));
                table.appendChild(tr);
            }
            trigBox.appendChild(table);
        }
        page.appendChild(trigBox);

        // 通用接口说明（当前运行期实际存在的入口）
        const iface = section(t('tech.triggerInterfaces'));
        iface.appendChild(el('div', 'f-note', t('tech.triggerProgrammatic')));
        iface.appendChild(pre('NeoForge.EVENT_BUS.post(new TechHooks.TriggerEvent(player, "crafted_component"));'));
        iface.appendChild(el('div', 'f-note', t('tech.triggerCommand')));
        iface.appendChild(pre('/techtree trigger crafted_component [target]'));
        iface.appendChild(el('div', 'f-note', t('tech.triggerDataDriven')));
        page.appendChild(iface);

        page.appendChild(section(t('tech.jsonPreview'), pre(TS().buildTreeJson(S.tree))));
    }

    // ---------- 增删 ----------
    function addNode() {
        if (!S.page) { window.showError(t('tech.atLeastOnePage')); return; }
        const node = TS().newNode(S.tree, S.page);
        node._isNew = true;
        S.tree.nodes.push(node);
        S.selected = { kind: 'node', id: node.id };
        S.tab = 'edit';
        renderAll();
        statusMsg(tf('tech.nodeAdded', { id: node.id }), true);
    }

    async function addPage() {
        let id = await window.askText({
            title: t('tech.newPage'),
            label: t('tech.pageIdPrompt'),
            placeholder: 'bolts',
            confirmLabel: t('tech.confirm'),
            cancelLabel: t('tech.cancel'),
        });
        if (id == null) return;
        id = String(id).trim();
        if (!TS().isId(id)) { window.showError(t('tech.pageIdInvalid')); return; }
        if (S.tree.pages.some((p) => p.id === id)) { window.showError(t('tech.pageIdDup')); return; }
        const title = await window.askText({
            title: t('tech.newPage'),
            label: t('tech.pageTitlePrompt'),
            placeholder: '枪机',
            value: id,
            confirmLabel: t('tech.confirm'),
            cancelLabel: t('tech.cancel'),
        });
        if (title == null) return;
        S.tree.pages.push(TS().newPage(id, String(title).trim() || id));
        S.page = id;
        S.selected = null;
        renderAll();
        statusMsg(tf('tech.pageAdded', { id }), true);
    }

    async function deletePage() {
        if (!S.page) return;
        const id = S.page;
        if (!confirm(tf('tech.deletePageConfirm', { id }))) return;
        const res = TS().deletePage(S.tree, id);
        if (!res.ok) { window.showError(t('tech.deletePageFail') + res.error); return; }
        S.page = S.tree.pages[0] ? S.tree.pages[0].id : null;
        S.selected = null;
        renderAll();
        statusMsg(tf('tech.pageDeleted', { id }), true);
    }

    function deleteSelected() {
        if (!S.selected) return;
        if (S.selected.kind === 'research') { deleteResearch(S.selected.id); return; }
        const id = S.selected.id;
        if (!confirm(tf('tech.deleteNodeConfirm', { id }))) return;
        const res = TS().deleteNode(S.tree, id);
        if (!res.ok) { window.showError(res.error); return; }
        S.selected = null;
        renderAll();
        statusMsg(tf('tech.nodeDeleted', { id }), true);
    }

    function deleteResearch(id) {
        if (!confirm(tf('tech.deleteResearchConfirm', { id }))) return;
        const idx = S.tree.research.findIndex((r) => r.id === id);
        if (idx < 0) return;
        S.tree.research.splice(idx, 1);
        if (S.selected && S.selected.kind === 'research' && S.selected.id === id) S.selected = null;
        renderAll();
        statusMsg(tf('tech.researchDeleted', { id }), true);
    }

    function addResearchEntry() {
        const entry = TS().newResearch(S.tree, S.page || undefined);
        entry._isNew = true;
        S.tree.research.push(entry);
        S.selected = { kind: 'research', id: entry.id };
        S.tab = 'edit';
        renderAll();
        statusMsg(tf('tech.researchAdded', { id: entry.id }), true);
    }

    // ---------- 保存 ----------
    function refSets() {
        const r = S.refs || {};
        const sets = {
            knownItems: new Set(arr(r.items)),
            knownQuests: new Set(arr(r.quests)),
            packNamespaces: new Set(arr(r.namespaces)),
        };
        return sets;
    }

    async function save() {
        if (!S.tree || !S.pack) { window.showError(t('tech.needPack')); return; }
        const check = TS().validateTree(S.tree, refSets());
        if (!check.valid) {
            S.tab = 'check';
            renderAll();
            window.showError(t('tech.saveInvalid') + '\n' + check.errors.join('\n'));
            return;
        }
        let r;
        try {
            r = await window.api.saveTechTree(S.pack.dir, S.tree);
        } catch (e) {
            r = { ok: false, error: String((e && e.message) || e) };
        }
        if (!r || !r.ok) { window.showError(t('tech.saveFail') + ((r && r.error) || '')); return; }
        for (const node of S.tree.nodes) delete node._isNew;
        for (const entry of S.tree.research) delete entry._isNew;
        S.exists = true;
        renderTitle();
        const warnN = (r.data && r.data.warnings) ? r.data.warnings.length : 0;
        statusMsg(warnN ? tf('tech.savedWarnN', { n: warnN }) : t('tech.saved'), true);
        if (S.handlers.onListChanged) S.handlers.onListChanged();
    }

    // ---------- 初始化 ----------
    function init(handlers) {
        S.handlers = handlers || {};
        const saveBtn = document.getElementById('btnTechSave');
        const delBtn2 = document.getElementById('btnTechDelete');
        const newNodeBtn = document.getElementById('btnTechNewNode');
        if (saveBtn) saveBtn.addEventListener('click', save);
        if (delBtn2) delBtn2.addEventListener('click', deleteSelected);
        if (newNodeBtn) newNodeBtn.addEventListener('click', addNode);

        // 手排微调：布局页选中节点后方向键挪一格（Shift = 5 格）。输入框里不抢键。
        document.addEventListener('keydown', (e) => {
            if (!S.tree || S.tab !== 'layout' || !S.selected || S.selected.kind !== 'node') return;
            const tag = (e.target && e.target.tagName || '').toUpperCase();
            if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
            const node = S.tree.nodes.find((n) => n.id === S.selected.id);
            if (!node) return;
            const step = e.shiftKey ? 5 : 1;
            let moved = true;
            if (e.key === 'ArrowLeft') node.col = Math.max(0, num(node.col, 0) - step);
            else if (e.key === 'ArrowRight') node.col = num(node.col, 0) + step;
            else if (e.key === 'ArrowUp') node.row = Math.max(0, num(node.row, 0) - step);
            else if (e.key === 'ArrowDown') node.row = num(node.row, 0) + step;
            else moved = false;
            if (!moved) return;
            e.preventDefault();
            renderAll();
        });
    }

    return { open, close, hasActiveTab, rerender, init };
})();
