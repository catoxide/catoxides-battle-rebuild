// questEditor.js - 右栏：任务编辑器（quests/<id>.json）
// 契约（单一真源）：src/core/questSchema.js
//   Java  QuestDefinitionParser.java（严格解析）/ QuestSystem.java（完成语义）
//   Java  QuestBuiltinComponents.java + goal/*.java + giver/*.java（内置组件字段）
//
// 本阶段只编辑：id / title / description / location / priority / repeatable / cooldownTicks / givers / goals。
// conditions / failConditions / children 原样保留并在预览页只读展示（子任务编辑、依赖、奖励待后续）。
// 写入方式：保存 = 暂存草稿（staging）；进正式包由中栏「⬆ 写入正式包」完成。
//
// 暴露 window.QuestEditor.open({pack, def, isNew}) / close() / hasActiveTab() / rerender() / init({onListChanged})

window.QuestEditor = (function () {
    'use strict';
    const S = {
        pack: null,
        def: null,
        isNew: false,
        isStaged: false,
        tab: 'basic',
        handlers: { onListChanged: null },
    };

    const KNOWN_KEYS = ['id', 'title', 'description', 'givers', 'goals',
        'conditions', 'failConditions', 'children', 'location', 'priority',
        'repeatable', 'cooldownTicks'];

    const Q = () => window.QuestSchema;
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : k);
    const isEn = () => !!(window.I18n && window.I18n.getLang() === 'en');
    const fl = (f) => (isEn() && f.en) ? f.en : f.zh;
    const tl = (spec) => (isEn() && spec.en) ? spec.en : spec.zh;

    // ---------- 小工具（与 itemBlockEditor 同形，独立一份避免跨视图耦合） ----------
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
        const s = el('div', 'ib-section');
        s.appendChild(el('h3', 'ib-sec-title', title));
        for (const c of children) if (c) s.appendChild(c);
        return s;
    }

    function pre(obj) {
        const p = el('pre', 'out ib-pre');
        p.textContent = JSON.stringify(obj, null, 2);
        return p;
    }

    function statusMsg(msg, ok) {
        if (window.showStatus) window.showStatus(msg, ok !== false);
    }

    function note(text) {
        return el('div', 'f-note', text);
    }

    // ---------- 生命周期 ----------
    function open(ctx) {
        S.pack = ctx.pack;
        S.isNew = !!ctx.isNew;
        S.isStaged = !!(ctx.def && ctx.def._staged);
        S.tab = 'basic';
        if (!ctx.def) {
            S.def = Q().newQuest();
            delete S.def._isNew;
        } else {
            S.def = JSON.parse(JSON.stringify(ctx.def));
            delete S.def._staged;
            delete S.def._file;
        }
        for (const k of ['givers', 'goals', 'conditions', 'failConditions', 'children']) {
            if (!Array.isArray(S.def[k])) S.def[k] = [];
        }
        renderAll();
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    function close() {
        S.def = null;
        S.pack = null;
        const mount = document.getElementById('questMount');
        if (mount) mount.innerHTML = '';
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    function hasActiveTab() { return !!S.def; }

    function rerender() {
        if (S.def) renderAll();
    }

    function renderTitle() {
        const titleEl = document.getElementById('questTitle');
        const badge = document.getElementById('questBadge');
        if (titleEl) titleEl.textContent = '🗺 ' + t('quest.title') + (S.def.id ? ' · ' + S.def.id : ' · ' + t('quest.badge.new'));
        if (badge) {
            badge.textContent = S.isNew ? t('quest.badge.new') : (S.isStaged ? t('quest.badge.draft') : t('quest.badge.formal'));
            badge.className = 'editor-badge ' + (S.isStaged || S.isNew ? 'warn' : 'ok');
        }
    }

    // ---------- 主渲染 ----------
    function renderAll() {
        const mount = document.getElementById('questMount');
        mount.innerHTML = '';
        const tabBar = el('div', 'ib-tabbar');
        const tabs = [
            ['basic', t('quest.tab.basic')],
            ['components', t('quest.tab.components')],
            ['preview', t('quest.tab.preview')],
        ];
        for (const [name, label] of tabs) {
            const b = el('button', 'tab' + (S.tab === name ? ' active' : ''), label);
            b.type = 'button';
            b.addEventListener('click', () => { S.tab = name; renderAll(); });
            tabBar.appendChild(b);
        }
        mount.appendChild(tabBar);

        const page = el('div', 'ib-page');
        if (S.tab === 'basic') buildBasic(page);
        else if (S.tab === 'components') buildComponents(page);
        else buildPreview(page);
        mount.appendChild(page);
        renderTitle();
    }

    // ---------- 页签：基本信息 ----------
    function buildBasic(page) {
        const d = S.def;
        const idIn = inText(d.id, 'quest_test_present', t('quest.idTip'));
        idIn.disabled = !S.isNew;
        idIn.addEventListener('input', () => { d.id = idIn.value.trim(); renderTitle(); });
        const titleIn = inText(d.title, t('quest.titlePh'));
        titleIn.addEventListener('input', () => { d.title = titleIn.value; });
        const descTa = el('textarea', 'f-input ib-raw');
        descTa.rows = 3;
        descTa.value = d.description == null ? '' : String(d.description);
        descTa.addEventListener('input', () => { d.description = descTa.value; });

        page.appendChild(section(t('quest.identity'),
            field(t('quest.id'), idIn),
            field(t('quest.titleLabel'), titleIn),
            field(t('quest.desc'), descTa),
            S.isNew ? note(t('quest.idNote')) : null,
        ));

        // 位置（可选）+ 优先级
        const hasLoc = Array.isArray(d.location);
        const loc = hasLoc ? d.location.slice() : [0, 64, 0];
        const locChk = chk(t('quest.enableLocation'), hasLoc);
        const coordRow = el('div', 'row');
        coordRow.style.display = hasLoc ? '' : 'none';
        ['X', 'Y', 'Z'].forEach((axis, i) => {
            const inp = inNum(loc[i], undefined, 1, axis);
            inp.style.maxWidth = '110px';
            inp.addEventListener('input', () => {
                loc[i] = Number(inp.value);
                if (Array.isArray(d.location)) d.location[i] = loc[i];
            });
            coordRow.appendChild(field(axis, inp));
        });
        locChk.querySelector('input').addEventListener('change', (e) => {
            if (e.target.checked) {
                d.location = loc.slice();
                coordRow.style.display = '';
            } else {
                d.location = null;
                coordRow.style.display = 'none';
            }
        });

        const prioIn = inNum(d.priority == null ? 0 : d.priority, undefined, 1);
        prioIn.addEventListener('input', () => { d.priority = Number(prioIn.value); });

        page.appendChild(section(t('quest.placement'),
            locChk,
            coordRow,
            note(t('quest.locationNote')),
            field(t('quest.priority'), prioIn),
            note(t('quest.priorityNote')),
        ));

        // 可重复发放：一次性任务收到过就永不再发；可重复任务在终态 + 冷却后可再发（新实例 id）
        const repChk = chk(t('quest.repeatable'), d.repeatable === true);
        const cdIn = inNum(d.cooldownTicks == null ? 0 : d.cooldownTicks, 0, 20);
        cdIn.disabled = d.repeatable !== true;
        cdIn.addEventListener('input', () => {
            d.cooldownTicks = Math.max(0, Math.trunc(Number(cdIn.value) || 0));
        });
        repChk.querySelector('input').addEventListener('change', (e) => {
            d.repeatable = !!e.target.checked;
            cdIn.disabled = !d.repeatable;
            if (!d.repeatable) {
                d.cooldownTicks = 0;
                cdIn.value = '0';
            }
        });

        page.appendChild(section(t('quest.repeatTitle'),
            repChk,
            field(t('quest.cooldownTicks'), cdIn),
            note(t('quest.repeatNote')),
        ));
    }

    // ---------- 页签：发放与目标 ----------
    function buildComponents(page) {
        page.appendChild(section(t('quest.givers'),
            note(t('quest.giversNote')),
            buildComponentList('giver', 'givers', Q().GIVER_TYPES),
        ));
        page.appendChild(section(t('quest.goals'),
            note(t('quest.goalsNote')),
            buildComponentList('goal', 'goals', Q().GOAL_TYPES),
        ));
        if (S.def.conditions.length || S.def.failConditions.length || S.def.children.length) {
            page.appendChild(section(t('quest.extraTitle'),
                note(tf('quest.extraNote', {
                    c: S.def.conditions.length,
                    f: S.def.failConditions.length,
                    k: S.def.children.length,
                })),
            ));
        }
    }

    function buildComponentList(kind, key, catalog) {
        const wrap = el('div', 'ib-prop-list');
        const arr = S.def[key];
        arr.forEach((comp, idx) => wrap.appendChild(buildComponentRow(kind, key, comp, idx, catalog)));
        if (!arr.length) wrap.appendChild(note(t('quest.noComponents')));

        const types = Object.keys(catalog);
        const typeSel = sel(types.map((x) => [x, tl(catalog[x]) + ' (' + x + ')']), types[0]);
        const addBtn = el('button', 'btn small', kind === 'giver' ? t('quest.addGiver') : t('quest.addGoal'));
        addBtn.type = 'button';
        addBtn.addEventListener('click', () => {
            arr.push(Q().newComponent(kind, typeSel.value));
            renderAll();
        });
        if (types.length) {
            wrap.appendChild(el('div', 'row', typeSel, addBtn));
        } else {
            wrap.appendChild(note(t('quest.noBuiltinTypes')));
        }
        return wrap;
    }

    function buildComponentRow(kind, key, comp, idx, catalog) {
        const arr = S.def[key];
        const row = el('div', 'ib-prop-row');
        const spec = catalog[comp.type];

        if (!spec) {
            // 包 Java 工厂提供的类型：编辑器不认识字段，原样展示 + 保留
            row.appendChild(el('div', 'f-note', tf('quest.unknownType', { type: comp.type })));
            row.appendChild(pre(comp));
            row.appendChild(deleteBtn(arr, idx));
            return row;
        }

        const typeSel = sel(Object.keys(catalog).map((x) => [x, tl(catalog[x]) + ' (' + x + ')']), comp.type);
        typeSel.addEventListener('change', () => {
            arr[idx] = Q().newComponent(kind, typeSel.value);
            renderAll();
        });
        row.appendChild(field(t('quest.type'), typeSel));

        for (const f of spec.fields) {
            row.appendChild(buildFieldRow(comp, f));
        }
        row.appendChild(deleteBtn(arr, idx));
        return row;
    }

    function deleteBtn(arr, idx) {
        const btn = el('button', 'btn ghost small', '✕');
        btn.type = 'button';
        btn.title = t('quest.removeComponent');
        btn.style.color = 'var(--err)';
        btn.addEventListener('click', () => { arr.splice(idx, 1); renderAll(); });
        return btn;
    }

    function buildFieldRow(comp, f) {
        const v = comp[f.key];
        if (f.type === 'vec3') {
            const coords = Q().isVec3(v) ? v.slice() : (f.default || [0, 64, 0]).slice();
            comp[f.key] = coords;
            const row = el('div', 'row');
            ['X', 'Y', 'Z'].forEach((axis, i) => {
                const inp = inNum(coords[i], undefined, 1, axis);
                inp.style.maxWidth = '100px';
                inp.addEventListener('input', () => { coords[i] = Number(inp.value); });
                row.appendChild(field(axis, inp));
            });
            return field(fl(f), row);
        }
        if (f.type === 'int' || f.type === 'number') {
            const inp = inNum(v == null || v === '' ? (f.default == null ? '' : f.default) : v,
                f.min, f.type === 'int' ? 1 : 0.1);
            inp.addEventListener('input', () => {
                comp[f.key] = f.type === 'int' ? Math.trunc(Number(inp.value)) : Number(inp.value);
            });
            return field(fl(f), inp);
        }
        const inp = inText(v == null ? '' : v, f.placeholder || '');
        inp.addEventListener('input', () => { comp[f.key] = inp.value.trim(); });
        return field(fl(f), inp);
    }

    // ---------- 页签：预览 / 校验 ----------
    function buildPreview(page) {
        const check = Q().validateQuest(S.def);
        const box = section(t('quest.validation'));
        if (check.valid) box.appendChild(note(t('quest.valid')));
        for (const e of check.errors) box.appendChild(el('div', 'ib-check-err', '✗ ' + e));
        for (const w of check.warnings) box.appendChild(el('div', 'f-note', '⚠ ' + w));
        page.appendChild(box);

        page.appendChild(section(t('quest.keyPreview'), pre(Q().buildQuestJson(S.def))));

        if (S.def.conditions.length) {
            page.appendChild(section(tf('quest.readonlyConditions', { n: S.def.conditions.length }), pre(S.def.conditions)));
        }
        if (S.def.failConditions.length) {
            page.appendChild(section(tf('quest.readonlyFail', { n: S.def.failConditions.length }), pre(S.def.failConditions)));
        }
        if (S.def.children.length) {
            page.appendChild(section(tf('quest.readonlyChildren', { n: S.def.children.length }), pre(S.def.children)));
        }
    }

    // ---------- 保存 / 删除 ----------
    function collect() {
        const d = S.def;
        d.id = String(d.id || '').trim();
        d.title = String(d.title || '').trim();
        d.description = String(d.description == null ? '' : d.description).trim();
        if (d.location != null && !Q().isVec3(d.location)) d.location = null;
        if (d.priority == null || d.priority === '' || !Number.isFinite(Number(d.priority))) d.priority = 0;
        else d.priority = Number(d.priority);
        d.repeatable = d.repeatable === true;
        if (!d.repeatable || !Number.isInteger(Number(d.cooldownTicks)) || Number(d.cooldownTicks) < 0) {
            d.cooldownTicks = 0;
        } else {
            d.cooldownTicks = Number(d.cooldownTicks);
        }
        return d;
    }

    async function save() {
        if (!S.def || !S.pack) return;
        const d = collect();
        const check = Q().validateQuest(d);
        if (!check.valid) {
            window.showError(t('quest.saveInvalid') + '\n' + check.errors.join('\n'));
            S.tab = 'preview';
            renderAll();
            return;
        }
        // buildQuestJson 只序列化已知键；包私有的顶层字段（后续 rewards 一类）原样保留，避免保存即丢数据
        const json = Q().buildQuestJson(d);
        for (const [k, v] of Object.entries(d)) {
            if (k.startsWith('_') || KNOWN_KEYS.includes(k)) continue;
            json[k] = v;
        }
        try {
            const r = await window.api.saveQuest(S.pack.dir, json);
            if (!r || !r.ok) { window.showError(t('quest.saveFail') + ((r && r.error) || '')); return; }
            S.isNew = false;
            S.isStaged = true;
            renderTitle();
            statusMsg(tf('quest.saved', { id: d.id }), true);
            if (S.handlers.onListChanged) S.handlers.onListChanged();
        } catch (e) {
            window.showError(t('quest.saveFail') + String((e && e.message) || e));
        }
    }

    async function remove() {
        if (!S.def || !S.pack) return;
        const id = S.def.id;
        if (!id) return;
        if (!confirm(tf('quest.deleteConfirm', { id }))) return;
        try {
            const r = await window.api.deleteQuest(S.pack.dir, id);
            if (!r || !r.ok) { window.showError(t('quest.deleteFail') + ((r && r.error) || '')); return; }
            statusMsg(tf('quest.deleted', { id }), true);
            close();
            if (S.handlers.onListChanged) S.handlers.onListChanged();
        } catch (e) {
            window.showError(t('quest.deleteFail') + String((e && e.message) || e));
        }
    }

    function init(handlers) {
        S.handlers = handlers || {};
        const saveBtn = document.getElementById('btnQuestSave');
        const delBtn = document.getElementById('btnQuestDelete');
        if (saveBtn) saveBtn.addEventListener('click', save);
        if (delBtn) delBtn.addEventListener('click', remove);
    }

    return { open, close, hasActiveTab, rerender, init };
})();
