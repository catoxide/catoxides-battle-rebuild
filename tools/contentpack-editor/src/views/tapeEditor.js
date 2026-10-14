// tapeEditor.js — TimeTape 状态机可视化编辑器（W2 图形节点编辑 + W3 实时仿真）
// 入口：window.TapeEditor.open({packName, relPath, animNames, title})
// 依赖：window.api / window.AppState / window.applyEditorVisibility / window.TapeEngine / window.TapeDoc
// 布局：#tapeEditorContent 内工具栏 + SVG 画布（节点=状态，连线=转移）+ 检查器 + 仿真面板

window.TapeEditor = (function () {
    'use strict';
    const T = (k) => (window.I18n ? window.I18n.t(k) : k);
    const Engine = window.TapeEngine;
    const Doc = window.TapeDoc;

    const NODE_W = 196;
    const ROW_H = 16;

    let S = null; // 编辑器状态（open 时初始化）

    function freshState() {
        return {
            open: false,
            packName: null, relPath: null,
            animNames: null, title: '',
            doc: null,
            machine: null,
            layout: {},
            activeTrack: null,
            sel: null,           // {kind:'state'|'edge', track, name, index?}
            undoStack: [],
            dirty: false,
            problems: { errors: [], warnings: [] },
            sim: { owner: null, running: false, timer: null, speed: 1, log: [], tick: 0 },
        };
    }

    // ==================== 打开/关闭 ====================

    async function open(ctx) {
        // DOMContentLoaded 前被程序化调用时兜底初始化
        if (!document.getElementById('tapeToolbar')) init();
        S = freshState();
        S.packName = ctx.packName;
        S.relPath = ctx.relPath;
        S.animNames = ctx.animNames || null;
        S.title = ctx.title || ctx.relPath;
        S.open = true;
        window.AppState.activeFrame = 'tape';

        const frame = document.getElementById('tapeEditorContent');
        frame.classList.remove('hidden');
        if (window.applyEditorVisibility) window.applyEditorVisibility();

        document.getElementById('tapeTitle').textContent = T('tape.title') + ' — ' + S.title;

        const res = await window.api.tapeReadFile({ pack: S.packName, rel: S.relPath });
        if (!res.ok) {
            if (window.showError) window.showError(T('tape.readFail') + ' ' + res.error);
            S.open = false;
            if (window.applyEditorVisibility) window.applyEditorVisibility();
            return;
        }
        const loaded = Doc.loadDoc(res.data.content);
        if (!loaded.ok) {
            if (window.showError) window.showError(loaded.error);
            return;
        }
        S.doc = loaded.doc;
        S.activeTrack = Doc.listTracks(S.doc)[0];

        // 布局（可选 sidecar，编辑器状态目录）
        try {
            const lr = await window.api.tapeReadLayout({ pack: S.packName });
            if (lr.ok) S.layout = JSON.parse(lr.data.content);
        } catch { /* 无布局文件 = 自动布局 */ }
        for (const [k] of Object.entries(S.layout)) if (!k.startsWith(S.activeTrack + ':') && !hasKey(S.doc, k)) delete S.layout[k];
        if (!Object.keys(S.layout).length) S.layout = Doc.autoLayout(S.doc);

        buildSimOwner();
        rebuildMachine();
        validateNow();
        renderAll();
        updateBadge();
        if (window.showStatus) window.showStatus(T('tape.loaded') + ' ' + S.relPath, true);
    }

    function hasKey(doc, key) {
        const i = key.indexOf(':');
        const tr = key.slice(0, i), st = key.slice(i + 1);
        return !!(doc.tracks[tr] && doc.tracks[tr].states[st]);
    }

    function close() {
        if (S) { stopSim(); S.open = false; }
        const frame = document.getElementById('tapeEditorContent');
        if (frame) frame.classList.add('hidden');
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    function hasActiveTab() { return !!(S && S.open); }

    // ==================== 文档变更（统一入口：undo + 重建 + 重绘） ====================

    function mutate(fn, label) {
        pushUndo(label);
        const r = fn();
        if (r && r.ok === false) {
            S.undoStack.pop();
            if (window.showError) window.showError(r.error);
            return null;
        }
        markDirty();
        return r;
    }

    function pushUndo(label) {
        S.undoStack.push(Doc.serialize(S.doc));
        if (S.undoStack.length > 50) S.undoStack.shift();
    }

    function undo() {
        if (!S.undoStack.length) return;
        const prev = S.undoStack.pop();
        const loaded = Doc.loadDoc(prev);
        if (loaded.ok) {
            S.doc = loaded.doc;
            markDirty();
        }
    }

    function markDirty() {
        S.dirty = true;
        rebuildMachine();
        validateNow();
        stopSim(); // 结构变了，仿真从 0 重来
        renderAll();
        updateBadge();
    }

    function rebuildMachine() {
        try {
            S.machine = Engine.TapeMachine.fromJson(Doc.serialize(S.doc));
        } catch (e) {
            S.machine = null; // 坏结构：仿真不可用，画布仍可编辑
        }
    }

    function validateNow() {
        const r = Doc.validateDoc(S.doc, S.animNames);
        S.problems = { errors: r.errors, warnings: r.warnings };
    }

    // ==================== 渲染 ====================

    function renderAll() {
        renderToolbar();
        renderCanvas();
        renderInspector();
        renderSim();
    }

    function renderToolbar() {
        const wrap = document.getElementById('tapeToolbar');
        wrap.innerHTML = '';
        // 轨 tabs
        const tabs = document.createElement('div');
        tabs.className = 'tape-tracktabs';
        for (const tr of Doc.listTracks(S.doc)) {
            const b = document.createElement('button');
            b.className = 'tab' + (tr === S.activeTrack ? ' active' : '');
            b.textContent = tr;
            b.addEventListener('click', () => { S.activeTrack = tr; S.sel = null; renderAll(); });
            tabs.appendChild(b);
        }
        const addTr = document.createElement('button');
        addTr.className = 'btn ghost small';
        addTr.textContent = '+ ' + T('tape.track');
        addTr.addEventListener('click', async () => {
            const id = await window.askText({ title: T('tape.addTrack'), label: T('tape.trackNamePh') });
            if (!id) return;
            const r = mutate(() => Doc.addTrack(S.doc, id.trim()));
            if (r) { S.activeTrack = id.trim(); S.sel = null; renderAll(); }
        });
        tabs.appendChild(addTr);
        wrap.appendChild(tabs);

        const actions = document.createElement('div');
        actions.className = 'tape-toolbar-actions';
        const mk = (label, title, cls, fn) => {
            const b = document.createElement('button');
            b.className = 'btn ghost small ' + (cls || '');
            b.textContent = label;
            if (title) b.title = title;
            b.addEventListener('click', fn);
            actions.appendChild(b);
            return b;
        };
        mk(T('tape.addState'), null, '', () => addStateAtCenter());
        mk('↺ ' + T('tape.undo'), null, '', undo);
        mk(T('tape.resetLayout'), null, '', () => {
            S.layout = Doc.autoLayout(S.doc);
            persistLayout();
            renderCanvas();
        });
        const save = mk(T('tape.save'), null, 'accent', saveNow);
        wrap.appendChild(actions);

        // 问题徽标
        const badge = document.createElement('span');
        badge.className = 'tape-problems-badge ' + (S.problems.errors.length ? 'err' : S.problems.warnings.length ? 'warn' : 'ok');
        badge.textContent = S.problems.errors.length
            ? S.problems.errors.length + ' ✗'
            : (S.problems.warnings.length ? S.problems.warnings.length + ' ⚠' : '✓');
        badge.title = [...S.problems.errors, ...S.problems.warnings].join('\n');
        wrap.appendChild(badge);
    }

    function nodeRows(st) {
        const rows = [];
        if (st.anim) {
            let a = st.anim.name;
            if (st.anim.alt && st.anim.alt.then) a += ' ⇢' + st.anim.alt.then;
            rows.push(a);
        }
        for (const seg of st.segments || []) {
            rows.push(seg.until + 's' + (seg.next ? ' → ' + seg.next : ' (hold)') + (seg.anim ? ' [' + seg.anim.name + ']' : ''));
        }
        if ((st.on_enter || []).length) rows.push('⚡' + st.on_enter.length);
        const nExit = (st.exit || []).length;
        if (nExit) rows.push('⇒ ' + nExit);
        return rows;
    }

    function nodeH(st) { return 30 + Math.max(1, nodeRows(st).length) * ROW_H + 10; }

    function renderCanvas() {
        const svg = document.getElementById('tapeSvg');
        const wrap = document.getElementById('tapeCanvasWrap');
        svg.innerHTML = '';
        const tr = S.doc.tracks[S.activeTrack];
        if (!tr) return;

        // 扩展画布
        let maxX = 800, maxY = 500;
        for (const name of Doc.listStates(S.doc, S.activeTrack)) {
            const p = S.layout[S.activeTrack + ':' + name] || { x: 0, y: 0 };
            maxX = Math.max(maxX, p.x + NODE_W + 300);
            maxY = Math.max(maxY, p.y + 120);
        }
        svg.setAttribute('width', maxX);
        svg.setAttribute('height', maxY);
        svg.setAttribute('viewBox', `0 0 ${maxX} ${maxY}`);

        const ns = 'http://www.w3.org/2000/svg';
        const edgesG = document.createElementNS(ns, 'g');
        const nodesG = document.createElementNS(ns, 'g');
        svg.appendChild(edgesG);
        svg.appendChild(nodesG);

        // ---- 边 ----
        for (const [name, st] of Object.entries(tr.states)) {
            (st.exit || []).forEach((ex, i) => {
                const from = S.layout[S.activeTrack + ':' + name] || { x: 0, y: 0 };
                const to = S.layout[S.activeTrack + ':' + ex.to] || { x: 200, y: 0 };
                const h1 = nodeH(st) / 2;
                const h2 = nodeH(tr.states[ex.to] || {}) / 2;
                const x1 = from.x + NODE_W, y1 = from.y + h1;
                const x2 = to.x, y2 = to.y + h2;
                const dx = Math.max(60, Math.abs(x2 - x1) / 2);
                const path = document.createElementNS(ns, 'path');
                path.setAttribute('d', `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`);
                path.setAttribute('class', 'tape-edge'
                    + (ex.priority >= 5 ? ' hi' : '')
                    + (isSelEdge(S.activeTrack, name, i) ? ' sel' : ''));
                path.dataset.track = S.activeTrack;
                path.dataset.state = name;
                path.dataset.index = i;
                edgesG.appendChild(path);

                const label = document.createElementNS(ns, 'text');
                label.setAttribute('x', (x1 + x2) / 2);
                label.setAttribute('y', (y1 + y2) / 2 - 6);
                label.setAttribute('class', 'tape-edge-label');
                let txt = ex.when.length > 26 ? ex.when.slice(0, 25) + '…' : ex.when;
                if (ex.priority) txt += ` (p${ex.priority})`;
                if (ex.after) txt += ` [≥${ex.after}]`;
                label.textContent = txt;
                edgesG.appendChild(label);
            });
        }

        // ---- 节点 ----
        for (const [name, st] of Object.entries(tr.states)) {
            const p = S.layout[S.activeTrack + ':' + name] || { x: 40, y: 40 };
            const h = nodeH(st);
            const g = document.createElementNS(ns, 'g');
            g.setAttribute('transform', `translate(${p.x},${p.y})`);
            g.dataset.track = S.activeTrack;
            g.dataset.state = name;
            const cls = ['tape-node'];
            if (tr.initial === name) cls.push('initial');
            if (st.terminal) cls.push('terminal');
            if (isSelState(S.activeTrack, name)) cls.push('sel');
            if (stateHasError(name)) cls.push('err');
            g.setAttribute('class', cls.join(' '));

            const rect = document.createElementNS(ns, 'rect');
            rect.setAttribute('width', NODE_W);
            rect.setAttribute('height', h);
            rect.setAttribute('rx', 8);
            g.appendChild(rect);

            const title = document.createElementNS(ns, 'text');
            title.setAttribute('x', 10);
            title.setAttribute('y', 20);
            title.setAttribute('class', 'tape-node-title');
            title.textContent = (tr.initial === name ? '⭐ ' : '') + (st.terminal ? '⛔ ' : '') + name;
            g.appendChild(title);

            nodeRows(st).forEach((r, i) => {
                const t = document.createElementNS(ns, 'text');
                t.setAttribute('x', 10);
                t.setAttribute('y', 34 + i * ROW_H);
                t.setAttribute('class', 'tape-node-row');
                t.textContent = r.length > 26 ? r.slice(0, 25) + '…' : r;
                g.appendChild(t);
            });

            // 连线手柄
            const handle = document.createElementNS(ns, 'circle');
            handle.setAttribute('cx', NODE_W);
            handle.setAttribute('cy', h / 2);
            handle.setAttribute('r', 6);
            handle.setAttribute('class', 'tape-handle');
            g.appendChild(handle);

            wireNode(g, name);
            nodesG.appendChild(g);
        }

        // 画布空白双击 = 新建状态
        svg.ondblclick = (e) => {
            if (e.target !== svg && e.target.tagName !== 'rect') return;
            const pt = svgPoint(svg, e);
            const name = Doc.suggestedName(S.doc, S.activeTrack, 'state');
            const r = mutate(() => Doc.addState(S.doc, S.activeTrack, name));
            if (r) {
                S.layout[S.activeTrack + ':' + name] = { x: pt.x - NODE_W / 2, y: pt.y - 30 };
                S.sel = { kind: 'state', track: S.activeTrack, name };
                persistLayout();
                renderAll();
            }
        };
    }

    function svgPoint(svg, e) {
        const r = svg.getBoundingClientRect();
        return { x: e.clientX - r.left, y: e.clientY - r.top };
    }

    function isSelState(track, name) {
        return S.sel && S.sel.kind === 'state' && S.sel.track === track && S.sel.name === name;
    }
    function isSelEdge(track, name, i) {
        return S.sel && S.sel.kind === 'edge' && S.sel.track === track && S.sel.name === name && S.sel.index === i;
    }
    function stateHasError(name) {
        return S.problems.errors.some((e) => e.includes('状态 ' + name) || e.includes('state ' + name));
    }

    function addStateAtCenter() {
        const wrap = document.getElementById('tapeCanvasWrap');
        const name = Doc.suggestedName(S.doc, S.activeTrack, 'state');
        const r = mutate(() => Doc.addState(S.doc, S.activeTrack, name));
        if (r) {
            S.layout[S.activeTrack + ':' + name] = {
                x: Math.max(40, (wrap.scrollLeft + wrap.clientWidth / 2 - NODE_W / 2)),
                y: Math.max(40, (wrap.scrollTop + wrap.clientHeight / 2 - 40)),
            };
            S.sel = { kind: 'state', track: S.activeTrack, name };
            persistLayout();
        }
    }

    // ---- 节点交互：拖动 / 点击选中 / 手柄连线 ----

    function wireNode(g, name) {
        let drag = null;
        g.addEventListener('pointerdown', (e) => {
            if (e.button !== 0) return;
            const isHandle = e.target.classList.contains('tape-handle');
            e.preventDefault();
            const start = { x: e.clientX, y: e.clientY };
            const p0 = S.layout[S.activeTrack + ':' + name] || { x: 0, y: 0 };
            if (isHandle) {
                startConnect(name, e);
                return;
            }
            drag = { p0, moved: false };
            try { g.setPointerCapture(e.pointerId); } catch { /* 合成事件无真实指针 */ }
            const onMove = (ev) => {
                const dx = ev.clientX - start.x, dy = ev.clientY - start.y;
                if (Math.abs(dx) + Math.abs(dy) > 4) drag.moved = true;
                if (drag.moved) {
                    S.layout[S.activeTrack + ':' + name] = { x: p0.x + dx, y: p0.y + dy };
                    g.setAttribute('transform', `translate(${p0.x + dx},${p0.y + dy})`);
                }
            };
            const onUp = (ev) => {
                try { g.releasePointerCapture(e.pointerId); } catch { /* 同上 */ }
                g.removeEventListener('pointermove', onMove);
                g.removeEventListener('pointerup', onUp);
                if (drag.moved) {
                    persistLayout();
                } else {
                    S.sel = { kind: 'state', track: S.activeTrack, name };
                    renderAll();
                }
            };
            g.addEventListener('pointermove', onMove);
            g.addEventListener('pointerup', onUp);
        });
    }

    function startConnect(fromName, e) {
        const svg = document.getElementById('tapeSvg');
        const line = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        line.setAttribute('class', 'tape-edge temp');
        svg.appendChild(line);
        const from = S.layout[S.activeTrack + ':' + fromName] || { x: 0, y: 0 };
        const st = S.doc.tracks[S.activeTrack].states[fromName];
        const x1 = from.x + NODE_W, y1 = from.y + nodeH(st) / 2;

        const onMove = (ev) => {
            const pt = svgPoint(svg, ev);
            line.setAttribute('d', `M ${x1} ${y1} C ${x1 + 80} ${y1}, ${pt.x - 80} ${pt.y}, ${pt.x} ${pt.y}`);
        };
        const onUp = (ev) => {
            window.removeEventListener('pointermove', onMove);
            window.removeEventListener('pointerup', onUp);
            line.remove();
            const el = document.elementFromPoint(ev.clientX, ev.clientY);
            const nodeG = el && el.closest ? el.closest('.tape-node') : null;
            if (nodeG && nodeG.dataset.state !== fromName) {
                const to = nodeG.dataset.state;
                const r = mutate(() => Doc.addExit(S.doc, S.activeTrack, fromName, { when: 'has_target', to }));
                if (r) S.sel = { kind: 'edge', track: S.activeTrack, name: fromName, index: r.index };
            }
        };
        window.addEventListener('pointermove', onMove);
        window.addEventListener('pointerup', onUp);
    }

    // 边点击选中
    function initCanvasClicks() {
        const svg = document.getElementById('tapeSvg');
        svg.addEventListener('click', (e) => {
            const t = e.target;
            if (t.classList && t.classList.contains('tape-edge')) {
                S.sel = { kind: 'edge', track: t.dataset.track, name: t.dataset.state, index: Number(t.dataset.index) };
                renderAll();
            } else if (t === svg) {
                S.sel = null;
                renderInspector();
            }
        }, true);
    }

    // ==================== 检查器 ====================

    function renderInspector() {
        const box = document.getElementById('tapeInspector');
        box.innerHTML = '';
        if (S.sel && S.sel.kind === 'state') renderStateInspector(box, S.sel);
        else if (S.sel && S.sel.kind === 'edge') renderEdgeInspector(box, S.sel);
        else renderTrackInspector(box);
        renderProblems(box);
    }

    function h3(text) {
        const h = document.createElement('h3');
        h.textContent = text;
        return h;
    }
    function fieldRow(label, input) {
        const r = document.createElement('label');
        r.className = 'field';
        r.innerHTML = `<span class="field-label"></span>`;
        r.querySelector('.field-label').textContent = label;
        r.appendChild(input);
        return r;
    }
    function numInput(value, step) {
        const i = document.createElement('input');
        i.type = 'number';
        i.step = step || 0.05;
        if (value != null) i.value = value;
        return i;
    }
    function txtInput(value, placeholder, listId) {
        const i = document.createElement('input');
        i.type = 'text';
        i.value = value != null ? value : '';
        if (placeholder) i.placeholder = placeholder;
        if (listId) {
            i.setAttribute('list', listId);
            const dl = document.createElement('datalist');
            dl.id = listId;
            (S.animNames || []).forEach((a) => {
                const o = document.createElement('option');
                o.value = a;
                dl.appendChild(o);
            });
            document.body.appendChild(dl);
        }
        return i;
    }
    function selectInput(options, value) {
        const s = document.createElement('select');
        for (const [v, label] of options) {
            const o = document.createElement('option');
            o.value = v;
            o.textContent = label != null ? label : v;
            if (String(v) === String(value)) o.selected = true;
            s.appendChild(o);
        }
        return s;
    }
    function commit(fn) {
        const r = mutate(fn);
        if (r === null) renderInspector(); // 失败回滚
        return r;
    }

    function renderStateInspector(box, sel) {
        const tr = S.doc.tracks[sel.track];
        const st = tr.states[sel.name];
        box.appendChild(h3(T('tape.state')));

        // 名称
        const nameInput = txtInput(sel.name);
        nameInput.classList.add('mono');
        const commitName = () => {
            const v = nameInput.value.trim();
            if (v && v !== sel.name) {
                const r = commit(() => Doc.renameState(S.doc, sel.track, sel.name, v));
                if (r) S.sel = { kind: 'state', track: sel.track, name: v };
                renderAll();
            }
        };
        nameInput.addEventListener('change', commitName);
        nameInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); commitName(); } });
        box.appendChild(fieldRow(T('tape.name'), nameInput));

        // 初始 / terminal
        const flagRow = document.createElement('div');
        flagRow.className = 'field-row';
        const initBtn = document.createElement('button');
        initBtn.className = 'btn ghost small';
        initBtn.textContent = tr.initial === sel.name ? '⭐ ' + T('tape.isInitial') : T('tape.setInitial');
        initBtn.addEventListener('click', () => { commit(() => Doc.setInitial(S.doc, sel.track, sel.name)); renderAll(); });
        flagRow.appendChild(initBtn);
        const termChk = document.createElement('label');
        termChk.className = 'chk';
        const tc = document.createElement('input');
        tc.type = 'checkbox';
        tc.checked = !!st.terminal;
        tc.addEventListener('change', () => {
            commit(() => { if (tc.checked) st.terminal = true; else delete st.terminal; });
            renderAll();
        });
        termChk.appendChild(tc);
        termChk.appendChild(document.createTextNode(' ' + T('tape.terminal')));
        flagRow.appendChild(termChk);
        box.appendChild(flagRow);

        // 删除
        const delBtn = document.createElement('button');
        delBtn.className = 'btn ghost small danger';
        delBtn.textContent = T('tape.delete');
        delBtn.addEventListener('click', () => {
            const r = mutate(() => Doc.removeState(S.doc, sel.track, sel.name));
            if (r) S.sel = null;
            renderAll();
        });
        box.appendChild(delBtn);

        const isSeq = !!(st.segments && st.segments.length);

        // ---- 动画（循环态） ----
        if (!isSeq) {
            box.appendChild(h3(T('tape.anim')));
            const a = st.anim || (st.anim = { group: 1, name: sel.name, loop: true, blend_in: 0.05 });
            const grid = document.createElement('div');
            grid.className = 'field-grid';
            grid.appendChild(fieldRow(T('tape.group'), (() => {
                const i = numInput(a.group, 1);
                i.addEventListener('change', () => { commit(() => { a.group = Number(i.value) || 1; }); renderCanvas(); });
                return i;
            })()));
            grid.appendChild(fieldRow(T('tape.name'), (() => {
                const i = txtInput(a.name, '', 'tapeAnimList');
                i.classList.add('mono');
                i.addEventListener('change', () => { commit(() => { a.name = i.value.trim() || a.name; }); renderAll(); });
                return i;
            })()));
            grid.appendChild(fieldRow(T('tape.blendIn'), (() => {
                const i = numInput(a.blend_in, 0.05);
                i.addEventListener('change', () => { commit(() => { a.blend_in = Number(i.value) || 0; }); });
                return i;
            })()));
            const loopLbl = document.createElement('label');
            loopLbl.className = 'chk';
            const lc = document.createElement('input');
            lc.type = 'checkbox';
            lc.checked = a.loop !== false;
            lc.addEventListener('change', () => { commit(() => { a.loop = lc.checked; }); renderCanvas(); });
            loopLbl.appendChild(lc);
            loopLbl.appendChild(document.createTextNode(' ' + T('tape.loop')));
            grid.appendChild(loopLbl);
            box.appendChild(grid);

            // alt
            box.appendChild(h3(T('tape.alt')));
            const alt = a.alt || {};
            const altGrid = document.createElement('div');
            altGrid.className = 'field-grid';
            const altTagI = txtInput(alt.tag || '', 'hit_dir');
            const altIfI = txtInput(alt.if != null ? alt.if : '', '=');
            const altThenI = txtInput(alt.then || '', '', 'tapeAnimList2');
            altGrid.appendChild(fieldRow(T('tape.altTag'), altTagI));
            altGrid.appendChild(fieldRow(T('tape.altIf'), altIfI));
            altGrid.appendChild(fieldRow(T('tape.altThen'), altThenI));
            const altCommit = () => {
                commit(() => {
                    const at = altTagI.value.trim(), ai = altIfI.value.trim(), atn = altThenI.value.trim();
                    if (at && atn) a.alt = { tag: at, if: ai || null, then: atn };
                    else delete a.alt;
                });
                renderAll();
            };
            [altTagI, altIfI, altThenI].forEach((i) => i.addEventListener('change', altCommit));
            box.appendChild(altGrid);
        }

        // ---- 纸带段 ----
        if (isSeq) {
            box.appendChild(h3(T('tape.segments')));
            const states = Doc.listStates(S.doc, sel.track);
            st.segments.forEach((seg, si) => {
                const row = document.createElement('div');
                row.className = 'tape-seg-row';
                const u = numInput(seg.until, 0.05);
                u.classList.add('w40');
                u.addEventListener('change', () => { commit(() => { seg.until = Math.max(0.05, Number(u.value) || 0.05); }); renderAll(); });
                row.appendChild(u);
                const an = txtInput(seg.anim ? seg.anim.name : '', '', 'tapeAnimList3');
                an.classList.add('mono', 'w80');
                an.addEventListener('change', () => {
                    commit(() => {
                        if (!seg.anim) seg.anim = { group: 1, loop: false, blend_in: 0.05 };
                        seg.anim.name = an.value.trim() || seg.anim.name;
                    });
                    renderCanvas();
                });
                row.appendChild(an);
                const nx = selectInput([['', T('tape.hold')]].concat(states.map((s2) => [s2, s2])), seg.next || '');
                nx.classList.add('w80');
                nx.addEventListener('change', () => {
                    commit(() => { if (nx.value) seg.next = nx.value; else delete seg.next; });
                    renderCanvas();
                });
                row.appendChild(nx);
                const del = document.createElement('button');
                del.className = 'btn ghost small danger';
                del.textContent = '✕';
                del.title = T('tape.delete');
                del.addEventListener('click', () => { commit(() => Doc.removeSegment(S.doc, sel.track, sel.name, si)); renderAll(); });
                row.appendChild(del);
                box.appendChild(row);

                // 里程碑
                if ((seg.milestones || []).length) {
                    const msBox = document.createElement('div');
                    msBox.className = 'tape-ms';
                    (seg.milestones || []).forEach((m, mi) => {
                        const mrow = document.createElement('div');
                        mrow.className = 'tape-ms-row';
                        const at = numInput(m.at, 0.05);
                        at.classList.add('w40');
                        at.addEventListener('change', () => { commit(() => { m.at = Math.max(0, Number(at.value) || 0); }); });
                        mrow.appendChild(at);
                        const doI = selectInput(Engine.ACTIONS.map((a) => [a, a]), m.do);
                        doI.classList.add('w80');
                        doI.addEventListener('change', () => {
                            commit(() => {
                                const old = Doc.actionToFields(m);
                                const nf = { ...old, do: doI.value };
                                Object.assign(m, Doc.fieldsToAction(nf));
                            });
                            renderInspector();
                        });
                        mrow.appendChild(doI);
                        const mdel = document.createElement('button');
                        mdel.className = 'btn ghost small danger';
                        mdel.textContent = '✕';
                        mdel.addEventListener('click', () => {
                            commit(() => { seg.milestones.splice(mi, 1); if (!seg.milestones.length) delete seg.milestones; });
                            renderAll();
                        });
                        mrow.appendChild(mdel);
                        msBox.appendChild(mrow);
                    });
                    const addMs = document.createElement('button');
                    addMs.className = 'btn ghost small';
                    addMs.textContent = '+ ' + T('tape.milestone');
                    addMs.addEventListener('click', () => {
                        const last = st.segments[si];
                        commit(() => Doc.addMilestone(S.doc, sel.track, sel.name, si, { at: last.until }));
                        renderAll();
                    });
                    msBox.appendChild(addMs);
                    box.appendChild(msBox);
                }
            });
            const addSeg = document.createElement('button');
            addSeg.className = 'btn ghost small';
            addSeg.textContent = '+ ' + T('tape.segment');
            addSeg.addEventListener('click', () => { commit(() => Doc.addSegment(S.doc, sel.track, sel.name, {})); renderAll(); });
            box.appendChild(addSeg);
        } else {
            const toSeq = document.createElement('button');
            toSeq.className = 'btn ghost small';
            toSeq.textContent = '▶ ' + T('tape.makeSequence');
            toSeq.title = T('tape.makeSequenceHint');
            toSeq.addEventListener('click', () => { commit(() => Doc.addSegment(S.doc, sel.track, sel.name, {})); renderAll(); });
            box.appendChild(toSeq);
        }

        // ---- 转移 ----
        box.appendChild(h3(T('tape.exits')));
        const states2 = Doc.listStates(S.doc, sel.track);
        (st.exit || []).forEach((ex, i) => {
            const row = document.createElement('div');
            row.className = 'tape-exit-row';
            const when = txtInput(ex.when, 'has_target');
            when.classList.add('mono');
            when.setAttribute('list', 'tapeCondList');
            ensureCondDatalist();
            when.addEventListener('change', () => {
                commit(() => { ex.when = when.value.trim() || ex.when; });
                renderCanvas();
            });
            row.appendChild(when);
            const toSel = selectInput(states2.map((s2) => [s2, s2]), ex.to);
            toSel.addEventListener('change', () => {
                commit(() => { ex.to = toSel.value; });
                renderCanvas();
            });
            row.appendChild(toSel);
            const pI = numInput(ex.priority || 0, 1);
            pI.classList.add('w30');
            pI.title = T('tape.priority');
            pI.addEventListener('change', () => {
                commit(() => {
                    const v = Number(pI.value) || 0;
                    if (v) ex.priority = v; else delete ex.priority;
                });
                renderCanvas();
            });
            row.appendChild(pI);
            const aI = numInput(ex.after || 0, 0.05);
            aI.classList.add('w40');
            aI.title = T('tape.after');
            aI.addEventListener('change', () => {
                commit(() => {
                    const v = Number(aI.value) || 0;
                    if (v) ex.after = v; else delete ex.after;
                });
                renderCanvas();
            });
            row.appendChild(aI);
            const del = document.createElement('button');
            del.className = 'btn ghost small danger';
            del.textContent = '✕';
            del.addEventListener('click', () => { commit(() => Doc.removeExit(S.doc, sel.track, sel.name, i)); renderAll(); });
            row.appendChild(del);
            box.appendChild(row);
        });
        const addExit = document.createElement('button');
        addExit.className = 'btn ghost small';
        addExit.textContent = '+ ' + T('tape.addExit');
        addExit.addEventListener('click', () => {
            const other = states2.find((s2) => s2 !== sel.name) || sel.name;
            const r = commit(() => Doc.addExit(S.doc, sel.track, sel.name, { when: 'has_target', to: other }));
            if (r) S.sel = { kind: 'edge', track: sel.track, name: sel.name, index: r.index };
            renderAll();
        });
        box.appendChild(addExit);

        // ---- on_enter ----
        box.appendChild(h3('on_enter'));
        (st.on_enter || []).forEach((act, i) => {
            const row = renderActionEditor(act, () => { commit(() => { st.on_enter.splice(i, 1); if (!st.on_enter.length) delete st.on_enter; }); renderAll(); });
            box.appendChild(row);
        });
        const addOE = document.createElement('button');
        addOE.className = 'btn ghost small';
        addOE.textContent = '+ ' + T('tape.action');
        addOE.addEventListener('click', () => { commit(() => Doc.addOnEnter(S.doc, sel.track, sel.name, { do: 'swing' })); renderAll(); });
        box.appendChild(addOE);
    }

    function ensureCondDatalist() {
        if (document.getElementById('tapeCondList')) return;
        const dl = document.createElement('datalist');
        dl.id = 'tapeCondList';
        Engine.FLAGS.forEach((f) => {
            const o = document.createElement('option');
            o.value = f;
            dl.appendChild(o);
        });
        ['all(x, y)', 'any(x, y)', 'not(x)', 'tag(hit_dir)=back', 'tag(hit_dir)!=front'].forEach((s) => {
            const o = document.createElement('option');
            o.value = s;
            dl.appendChild(o);
        });
        document.body.appendChild(dl);
    }

    function renderEdgeInspector(box, sel) {
        const st = S.doc.tracks[sel.track].states[sel.name];
        const ex = st.exit[sel.index];
        if (!ex) { S.sel = null; renderTrackInspector(box); return; }
        box.appendChild(h3(`${sel.name} ⇒ ${ex.to}`));
        const states = Doc.listStates(S.doc, sel.track);
        const when = txtInput(ex.when, '', 'tapeCondList');
        when.classList.add('mono');
        when.addEventListener('change', () => { commit(() => { ex.when = when.value.trim() || ex.when; }); renderCanvas(); });
        box.appendChild(fieldRow('when', when));
        const toSel = selectInput(states.map((s2) => [s2, s2]), ex.to);
        toSel.addEventListener('change', () => { commit(() => { ex.to = toSel.value; }); renderAll(); });
        box.appendChild(fieldRow(T('tape.to'), toSel));
        const grid = document.createElement('div');
        grid.className = 'field-grid';
        const pI = numInput(ex.priority || 0, 1);
        pI.addEventListener('change', () => { commit(() => { const v = Number(pI.value) || 0; if (v) ex.priority = v; else delete ex.priority; }); renderCanvas(); });
        grid.appendChild(fieldRow('priority', pI));
        const aI = numInput(ex.after || 0, 0.05);
        aI.addEventListener('change', () => { commit(() => { const v = Number(aI.value) || 0; if (v) ex.after = v; else delete ex.after; }); renderCanvas(); });
        grid.appendChild(fieldRow('after (s)', aI));
        const bI = numInput(ex.before != null ? ex.before : '', 0.05);
        bI.addEventListener('change', () => { commit(() => { const v = Number(bI.value); if (bI.value !== '') ex.before = v; else delete ex.before; }); renderCanvas(); });
        grid.appendChild(fieldRow('before (s)', bI));
        box.appendChild(grid);
        const del = document.createElement('button');
        del.className = 'btn ghost small danger';
        del.textContent = T('tape.delete');
        del.addEventListener('click', () => { commit(() => Doc.removeExit(S.doc, sel.track, sel.name, sel.index)); S.sel = null; renderAll(); });
        box.appendChild(del);
    }

    function renderTrackInspector(box) {
        const tr = S.doc.tracks[S.activeTrack];
        box.appendChild(h3(T('tape.track') + ': ' + S.activeTrack));
        const iniSel = selectInput(Doc.listStates(S.doc, S.activeTrack).map((s) => [s, s]), tr.initial);
        iniSel.addEventListener('change', () => { commit(() => Doc.setInitial(S.doc, S.activeTrack, iniSel.value)); renderAll(); });
        box.appendChild(fieldRow(T('tape.initial'), iniSel));

        // initial_tags
        box.appendChild(h3('initial_tags'));
        const tagRows = document.createElement('div');
        tagRows.className = 'tape-tags';
        const rebuildTags = () => {
            tagRows.innerHTML = '';
            for (const [k, v] of Object.entries(tr.initial_tags || {})) {
                const row = document.createElement('div');
                row.className = 'tape-tag-row';
                const ki = txtInput(k);
                ki.classList.add('w80', 'mono');
                const vi = txtInput(v);
                vi.classList.add('w80', 'mono');
                const del = document.createElement('button');
                del.className = 'btn ghost small danger';
                del.textContent = '✕';
                del.addEventListener('click', () => { commit(() => Doc.setTags(S.doc, without(tr.initial_tags, k))); renderInspector(); });
                row.append(ki, vi, del);
                tagRows.appendChild(row);
            }
        };
        rebuildTags();
        box.appendChild(tagRows);
        const addTag = document.createElement('button');
        addTag.className = 'btn ghost small';
        addTag.textContent = '+ tag';
        addTag.addEventListener('click', () => {
            commit(() => Doc.setTags(S.doc, { ...(tr.initial_tags || {}), new_tag: '1' }));
            renderInspector();
        });
        box.appendChild(addTag);

        const help = document.createElement('p');
        help.className = 'note';
        help.textContent = T('tape.help');
        box.appendChild(help);
    }

    function without(obj, k) {
        const o = { ...(obj || {}) };
        delete o[k];
        return o;
    }

    function renderActionEditor(act, onDel) {
        const row = document.createElement('div');
        row.className = 'tape-act-row';
        const doSel = selectInput(Engine.ACTIONS.map((a) => [a, a]), act.do);
        doSel.classList.add('w80');
        doSel.addEventListener('change', () => {
            commit(() => {
                const f = Doc.actionToFields(act);
                f.do = doSel.value;
                Object.assign(act, Doc.fieldsToAction(f));
            });
            renderInspector();
        });
        row.appendChild(doSel);
        for (const [k] of Doc.ACTION_FIELDS[act.do] || []) {
            const i = (k === 'sound' || k === 'name' || k === 'id')
                ? txtInput(act[k] != null ? act[k] : '')
                : numInput(act[k] != null ? act[k] : 0);
            i.classList.add('w80');
            i.title = k;
            i.addEventListener('change', () => {
                commit(() => {
                    const f = Doc.actionToFields(act);
                    f[k] = i.value;
                    Object.assign(act, Doc.fieldsToAction(f));
                });
            });
            row.appendChild(i);
        }
        const del = document.createElement('button');
        del.className = 'btn ghost small danger';
        del.textContent = '✕';
        del.addEventListener('click', onDel);
        row.appendChild(del);
        return row;
    }

    function renderProblems(box) {
        if (!S.problems.errors.length && !S.problems.warnings.length) return;
        const p = document.createElement('div');
        p.className = 'tape-problems';
        for (const e of S.problems.errors) {
            const d = document.createElement('div');
            d.className = 'p-err';
            d.textContent = '✗ ' + e;
            p.appendChild(d);
        }
        for (const w of S.problems.warnings) {
            const d = document.createElement('div');
            d.className = 'p-warn';
            d.textContent = '⚠ ' + w;
            p.appendChild(d);
        }
        box.appendChild(p);
    }

    // ==================== 仿真面板（W3） ====================

    function buildSimOwner() {
        S.sim.owner = {
            is_moving: false, is_sprinting: false, has_target: false, is_on_ground: true,
            is_in_water: false, is_on_climbable: false, died: false, swinging: false,
            playSound() { }, applyDamage() { },
            swing() { this.swinging = true; },
            spawnParticle() { },
            onTapeAction() { },
        };
    }

    const SIM_FLAGS = [
        ['is_moving', 'tape.sim.is_moving'],
        ['is_sprinting', 'tape.sim.is_sprinting'],
        ['has_target', 'tape.sim.has_target'],
        ['is_on_ground', 'tape.sim.is_on_ground'],
        ['is_in_water', 'tape.sim.is_in_water'],
        ['is_on_climbable', 'tape.sim.is_on_climbable'],
        ['died', 'tape.sim.died'],
        ['swinging', 'tape.sim.swinging'],
    ];

    function renderSim() {
        const box = document.getElementById('tapeSim');
        box.innerHTML = '';
        if (!S.machine) {
            box.innerHTML = '<span class="note">' + T('tape.sim.unavailable') + '</span>';
            return;
        }
        // 控制行
        const ctl = document.createElement('div');
        ctl.className = 'tape-sim-ctl';
        const mkBtn = (label, title, fn) => {
            const b = document.createElement('button');
            b.className = 'btn ghost small';
            b.textContent = label;
            if (title) b.title = title;
            b.addEventListener('click', fn);
            ctl.appendChild(b);
            return b;
        };
        mkBtn('⏮', T('tape.sim.reset'), simReset);
        mkBtn('⏭', T('tape.sim.step'), simStep);
        const playBtn = mkBtn(S.sim.running ? '⏸' : '▶', T('tape.sim.play'), toggleSim);
        const speed = selectInput([[1, '×1'], [2, '×2'], [0.5, '×0.5']], S.sim.speed);
        speed.className = 'w60';
        speed.addEventListener('change', () => {
            S.sim.speed = Number(speed.value);
            if (S.sim.running) { stopSim(); startSim(); }
        });
        ctl.appendChild(speed);

        // force state
        const forceSel = selectInput([['', '⚡ ' + T('tape.sim.force')]].concat(
            Doc.listStates(S.doc, S.activeTrack).map((s) => [s, s])));
        forceSel.addEventListener('change', () => {
            if (!forceSel.value || !S.machine) return;
            const from = S.machine.trackStates()[S.activeTrack];
            S.machine.forceState(S.activeTrack, forceSel.value, S.sim.owner, simDriver(), simObserver());
            pushLog(0, `${S.activeTrack}: ${from}→${forceSel.value} (force)`);
            renderSimStatus();
            forceSel.value = '';
        });
        ctl.appendChild(forceSel);
        box.appendChild(ctl);

        // 条件行
        const cond = document.createElement('div');
        cond.className = 'tape-sim-cond';
        for (const [flag, key] of SIM_FLAGS) {
            const lbl = document.createElement('label');
            lbl.className = 'chk';
            const c = document.createElement('input');
            c.type = 'checkbox';
            c.checked = !!S.sim.owner[flag];
            c.addEventListener('change', () => { S.sim.owner[flag] = c.checked; });
            lbl.appendChild(c);
            lbl.appendChild(document.createTextNode(' ' + T(key)));
            cond.appendChild(lbl);
        }
        // tags
        const tagLbl = document.createElement('span');
        tagLbl.className = 'note sim-tag-sep';
        tagLbl.textContent = ' tags:';
        cond.appendChild(tagLbl);
        for (const [k, v] of Object.entries(S.machine.tags || {})) {
            const chip = document.createElement('span');
            chip.className = 'sim-tag';
            chip.textContent = `${k}=${v} ✕`;
            chip.title = T('tape.sim.removeTag');
            chip.addEventListener('click', () => {
                const t2 = { ...S.machine.tags };
                delete t2[k];
                S.machine.mirrorTags(t2);
                renderSim();
            });
            cond.appendChild(chip);
        }
        const tagKey = txtInput('', 'tag');
        tagKey.classList.add('w50', 'sim-tag-in');
        const tagVal = txtInput('', 'value');
        tagVal.classList.add('w50', 'sim-tag-in');
        const addT = document.createElement('button');
        addT.className = 'btn ghost small';
        addT.textContent = '+';
        addT.addEventListener('click', () => {
            const k = tagKey.value.trim();
            if (!k) return;
            const t2 = { ...S.machine.tags, [k]: tagVal.value.trim() };
            S.machine.mirrorTags(t2);
            renderSim();
        });
        cond.append(tagKey, tagVal, addT);
        box.appendChild(cond);

        // 状态行
        const status = document.createElement('div');
        status.className = 'tape-sim-status';
        status.id = 'tapeSimStatus';
        box.appendChild(status);

        // 日志
        const log = document.createElement('div');
        log.className = 'tape-sim-log';
        log.id = 'tapeSimLog';
        for (const line of S.sim.log.slice(-200)) {
            const d = document.createElement('div');
            d.textContent = line;
            log.appendChild(d);
        }
        log.scrollTop = log.scrollHeight;
        box.appendChild(log);

        renderSimStatus();
    }

    function simDriver() {
        return {
            onAnim(track, state, b) {
                S._lastAnim = { track, state, name: b.name, t: simT(track) };
                renderSimStatus();
            },
        };
    }

    function simObserver() {
        return {
            onTransition(ev) {
                const t = simT(ev.track);
                pushLog(t, `${ev.track}: ${ev.from}→${ev.to}${ev.via === 'exit' ? ' [' + ev.exit.whenText + ']' : ' (' + ev.via + ')'}`);
                highlightStates();
            },
            onMilestone(ev) {
                pushLog(simT(ev.track), `${ev.track}/${ev.state} ⚡${ev.action} @${ev.at}s`);
            },
        };
    }

    function simT(track) {
        const s = S.machine ? S.machine.snapshot().find((x) => x.track === track) : null;
        return s ? s.t : 0;
    }

    function pushLog(t, msg) {
        S.sim.log.push(`t=${t.toFixed(2)}s  ${msg}`);
        if (S.sim.log.length > 400) S.sim.log.splice(0, S.sim.log.length - 400);
        const log = document.getElementById('tapeSimLog');
        if (log) {
            const d = document.createElement('div');
            d.textContent = `t=${t.toFixed(2)}s  ${msg}`;
            log.appendChild(d);
            while (log.children.length > 300) log.removeChild(log.firstChild);
            log.scrollTop = log.scrollHeight;
        }
    }

    function simReset() {
        stopSim();
        rebuildMachine();
        S.sim.log = [];
        S.sim.tick = 0;
        S._lastAnim = null;
        renderSim();
    }

    function simStep() {
        if (!S.machine) return;
        S.machine.tick(S.sim.owner, simDriver(), simObserver());
        S.sim.tick++;
        highlightStates();
    }

    function toggleSim() {
        if (S.sim.running) stopSim();
        else startSim();
    }

    function startSim() {
        if (!S.machine || S.sim.running) return;
        S.sim.running = true;
        const interval = 50 / S.sim.speed;
        S.sim.timer = setInterval(() => simStep(), interval);
        renderSim();
    }

    function stopSim() {
        if (S.sim.timer) { clearInterval(S.sim.timer); S.sim.timer = null; }
        S.sim.running = false;
    }

    function highlightStates() {
        const svg = document.getElementById('tapeSvg');
        if (!svg || !S.machine) return;
        const cur = S.machine.trackStates();
        for (const g of svg.querySelectorAll('.tape-node')) {
            g.classList.toggle('sim-current', cur[g.dataset.track] === g.dataset.state);
        }
    }

    function renderSimStatus() {
        const el = document.getElementById('tapeSimStatus');
        if (!el || !S.machine) return;
        el.innerHTML = '';
        for (const s of S.machine.snapshot()) {
            const d = document.createElement('span');
            d.className = 'sim-state';
            const anim = (S._lastAnim && S._lastAnim.track === s.track) ? ` ▶ ${S._lastAnim.name}` : '';
            d.textContent = `${s.track}: ${s.state} (t=${s.t.toFixed(2)}s)${anim}`;
            el.appendChild(d);
        }
    }

    // ==================== 保存 ====================

    async function saveNow() {
        validateNow();
        if (S.problems.errors.length) {
            if (window.showError) window.showError(T('tape.saveBlocked') + '：' + S.problems.errors[0]);
            return;
        }
        const res = await window.api.tapeSaveFile({
            pack: S.packName, rel: S.relPath, content: Doc.serialize(S.doc),
        });
        if (!res.ok) {
            if (window.showError) window.showError(res.error);
            return;
        }
        await persistLayout(true);
        S.dirty = false;
        S.undoStack = [];
        updateBadge();
        if (window.showStatus) window.showStatus(T('tape.saved') + ' ' + S.relPath, true);
    }

    async function persistLayout(silent) {
        try {
            const r = await window.api.tapeSaveLayout({
                pack: S.packName, content: JSON.stringify(S.layout, null, 2),
            });
            if (!r.ok && !silent && window.showError) window.showError('layout: ' + r.error);
        } catch (e) {
            if (!silent && window.showError) window.showError('layout: ' + e.message);
        }
    }

    function updateBadge() {
        const badge = document.getElementById('tapeBadge');
        if (!badge) return;
        badge.textContent = S.problems.errors.length
            ? S.problems.errors.length + ' ✗'
            : (S.dirty ? '● ' + T('tape.dirty') : T('tape.ok'));
        badge.className = 'editor-badge ' + (S.problems.errors.length ? 'err' : S.dirty ? 'warn' : 'ok');
    }

    // ==================== 初始化 ====================

    let _inited = false;
    function init() {
        const mount = document.getElementById('tapeMount');
        if (!mount) return;
        mount.innerHTML = `
            <div class="tape-toolbar" id="tapeToolbar"></div>
            <div class="tape-body">
                <div class="tape-canvas-wrap" id="tapeCanvasWrap">
                    <svg id="tapeSvg" xmlns="http://www.w3.org/2000/svg"></svg>
                </div>
                <div class="tape-inspector" id="tapeInspector"></div>
            </div>
            <div class="tape-sim" id="tapeSim"></div>`;
        initCanvasClicks();
        // header 按钮（index.html 静态定义）——只绑一次
        if (!_inited) {
            _inited = true;
            const saveBtn = document.getElementById('tapeBtnSave');
            if (saveBtn) saveBtn.addEventListener('click', saveNow);
            const undoBtn = document.getElementById('tapeBtnUndo');
            if (undoBtn) undoBtn.addEventListener('click', () => { undo(); renderAll(); });
        }
    }

    if (typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', init);
        } else {
            init();
        }
    }

    return { open, close, hasActiveTab, init };
})();
