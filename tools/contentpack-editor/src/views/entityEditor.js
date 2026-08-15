// entityEditor.js - 右栏：实体编辑器（多标签页，每标签 4 页签）
// 依赖 window.api、window.showError / window.showStatus / window.AppState（app.js 定义）
// 暴露 window.EntityEditor.open({ pack, entity, isNew }) / closeActiveTab()

window.EntityEditor = (function () {
    // i18n 兜底：window.I18n 已由 i18n.js 在 views 之前加载；此处仅为健壮性
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));

    // ===== 多标签状态 =====
    let tabs = [];              // [{ uid, pack, entity, healthConfig, model, selectedPartName, isNew }]
    let activeUid = null;
    let uidCounter = 0;

    function activeTab() {
        return tabs.find((t) => t.uid === activeUid) || null;
    }

    // ===== 打开/聚焦一个实体标签 =====
    // ctx = { pack, entity, isNew }
    async function open(ctx) {
        const pack = ctx.pack;
        const isNew = !!ctx.isNew;
        const ent = ctx.entity || null;

        // 已有实体的编辑：若已打开同实体标签则聚焦
        if (!isNew && ent && ent.id) {
            const existing = tabs.find((t) => t.pack.dir === pack.dir && t.entity && t.entity.id === ent.id && !t.isNew);
            if (existing) {
                await activateTab(existing.uid);
                return;
            }
        }
        // 新建标签
        await newTab(pack, ent, isNew);
    }

    async function newTab(pack, ent, isNew) {
        const uid = ++uidCounter;
        const entity = ent || await getDefaultEntity();
        if (!entity) return;
        // 新建时清空 id（避免默认 example_mob 冲突），保存时要求填
        if (isNew) entity.id = '';
        const ns = entity.namespace || packNamespace(pack);
        const healthConfig = (!isNew && entity.id) ? await readHealthFor(pack, ns, entity.id) : { parts: [] };
        // 自动预加载模型：从实体 model.id 解析骨骼树（无需手动点按钮）
        let model = null;
        if (entity.model && entity.model.id) {
            const mres = await window.api.resolveModelFromId(entity.model.id, entity.model.type || 'entity');
            if (mres.ok && mres.data) model = mres.data;
        }
        const tab = {
            uid,
            pack,
            entity,
            healthConfig,
            model,
            selectedPartName: null,
            isNew,
        };
        tabs.push(tab);
        await activateTab(uid);
    }

    async function activateTab(uid) {
        const tab = tabs.find((t) => t.uid === uid);
        if (!tab) return;

        // 若已有活跃标签：先把当前表单写回它
        if (activeUid && activeUid !== uid) {
            const cur = activeTab();
            if (cur) collectInto(cur);
        }
        activeUid = uid;

        // 同步 AppState（渲染函数全部读 AppState，零改动）
        window.AppState.pack = tab.pack;
        window.AppState.entity = tab.entity;
        window.AppState.healthConfig = tab.healthConfig;
        window.AppState.model = tab.model;

        renderTabBar();
        renderEditorFrame(tab);
        renderAll(tab);
    }

    function closeActiveTab() {
        if (!activeUid) return;
        const idx = tabs.findIndex((t) => t.uid === activeUid);
        if (idx === -1) return;
        const tab = tabs[idx];
        // 把当前表单写回（避免丢改动）
        collectInto(tab);
        tabs.splice(idx, 1);
        // 激活相邻标签
        if (tabs.length === 0) {
            activeUid = null;
            window.AppState.entity = null;
            window.AppState.healthConfig = { parts: [] };
            window.AppState.model = null;
            document.getElementById('editorContent').classList.add('hidden');
            document.getElementById('editorEmpty').classList.remove('hidden');
            return;
        }
        const next = tabs[Math.min(idx, tabs.length - 1)];
        activateTab(next.uid);
    }

    // ===== 标签栏渲染 =====
    function renderTabBar() {
        const bar = document.getElementById('editorTabsBar');
        bar.innerHTML = '';
        for (const tab of tabs) {
            const chip = document.createElement('div');
            chip.className = 'etab' + (tab.uid === activeUid ? ' active' : '');
            const label = document.createElement('span');
            const name = tab.isNew ? t('dyn.newEntity') : (tab.entity.id || t('dyn.unnamed'));
            label.textContent = name + (tab.isNew ? ' *' : '');
            chip.appendChild(label);
            const close = document.createElement('button');
            close.className = 'etab-close';
            close.textContent = '×';
            close.title = t('dyn.close');
            close.addEventListener('click', (e) => {
                e.stopPropagation();
                // 关闭非活跃标签：直接删（其内存状态已在离开时保存）
                if (tab.uid === activeUid) {
                    closeActiveTab();
                } else {
                    tabs = tabs.filter((t) => t.uid !== tab.uid);
                    renderTabBar();
                }
            });
            chip.appendChild(close);
            chip.addEventListener('click', () => activateTab(tab.uid));
            bar.appendChild(chip);
        }
    }

    // ===== 编辑器框架（标题/徽标/显示状态） =====
    function renderEditorFrame(tab) {
        document.getElementById('editorEmpty').classList.add('hidden');
        document.getElementById('editorContent').classList.remove('hidden');
        const title = document.getElementById('editorTitle');
        const id = tab.entity.id || t('dyn.unnamed');
        title.textContent = (tab.isNew ? t('dyn.newEntityTitle') : t('dyn.editEntity')) + ' — ' + id;
        // 暂存状态徽标
        const badge = document.getElementById('editorBadge');
        if (tab.isNew) {
            badge.textContent = t('dyn.newDraft');
        } else if (tab.entity._staged) {
            badge.textContent = t('dyn.draftUnexported');
        } else if (tab.healthConfig && tab.healthConfig._staged) {
            badge.textContent = t('dyn.draftUnexported');
        } else {
            badge.textContent = t('dyn.exported');
        }
        document.getElementById('statusBar').textContent = '';
        document.getElementById('buildOutput').style.display = 'none';
    }

    // ===== 渲染全部页签（从 AppState 读） =====
    function renderAll(tab) {
        renderBasic(tab.entity);
        renderModel(tab.entity);
        renderParts();
        loadTemplates(tab.entity);
        switchTab('basic');
    }

    function packNamespace(pack) {
        const name = pack && pack.name;
        return name ? name.replace(/-/g, '_') : 'mypack';
    }

    async function getDefaultEntity() {
        const res = await window.api.getEntityDefaults();
        if (!res.ok) { window.showError(t('dyn.getDefaultsFail') + res.error); return null; }
        return JSON.parse(JSON.stringify(res.data));
    }

    async function readHealthFor(pack, ns, entityId) {
        if (!entityId) return { parts: [] };
        const res = await window.api.readHealth(pack.dir, ns, entityId);
        if (res.ok && res.data) return res.data;
        return { parts: [] };
    }

    /** 把当前 DOM 表单写回指定 tab（切换/关闭前调用） */
    function collectInto(tab) {
        if (!tab || !tab.entity) return;
        const e = collectEntity();
        tab.entity = e;
        // 同时更新 AppState 里的 entity（若就是当前活跃 tab）
        if (tab.uid === activeUid) window.AppState.entity = e;
        return e;
    }

    // ===== 页签切换 =====
    function switchTab(name) {
        document.querySelectorAll('#editorTabs .tab').forEach((t) => {
            t.classList.toggle('active', t.dataset.tab === name);
        });
        document.querySelectorAll('.tab-page').forEach((p) => {
            p.classList.toggle('active', p.id === 'tab-' + name);
        });
    }

    // ===== 页签 1：基本信息 =====
    function renderBasic(e) {
        document.getElementById('eId').value = e.id || '';
        document.getElementById('eNs').value = e.namespace || '';
        document.getElementById('eDn').value = e.displayName || '';
        document.getElementById('eW').value = e.size ? e.size.width : 0.6;
        document.getElementById('eH').value = e.size ? e.size.height : 1.95;
        const a = e.attributes || {};
        document.getElementById('eMaxHp').value = a.maxHealth != null ? a.maxHealth : 20;
        document.getElementById('eAtk').value = a.attackDamage != null ? a.attackDamage : 0;
        document.getElementById('eSpeed').value = a.movementSpeed != null ? a.movementSpeed : 0.23;
        document.getElementById('eFollow').value = a.followRange != null ? a.followRange : 16;
        document.getElementById('eArmor').value = a.armor != null ? a.armor : 0;
        renderBehaviors(e);
    }

    function renderBehaviors(e) {
        const box = document.getElementById('behaviorsBox');
        box.innerHTML = '';
        const list = e.behaviors || [];
        list.forEach((b, i) => {
            const row = document.createElement('div');
            row.className = 'beh-row';
            row.innerHTML = `
                <select class="b-type">
                    <option value="attack" ${b.type === 'attack' ? 'selected' : ''}>attack</option>
                    <option value="ai" ${b.type === 'ai' ? 'selected' : ''}>ai</option>
                    <option value="raycast" ${b.type === 'raycast' ? 'selected' : ''}>raycast</option>
                </select>
                <input class="b-cfg" placeholder='{"damage":3.0}'>
                <button class="btn ghost small b-del">×</button>`;
            const cfg = {};
            for (const [k, v] of Object.entries(b)) if (k !== 'type') cfg[k] = v;
            row.querySelector('.b-cfg').value = Object.keys(cfg).length ? JSON.stringify(cfg) : '';
            row.querySelector('.b-del').addEventListener('click', () => row.remove());
            box.appendChild(row);
        });
    }

    // ===== 页签 2：模型与资源 =====
    function renderModel(e) {
        document.getElementById('eModelType').value = (e.model && e.model.type) || 'entity';
        document.getElementById('eModelId').value = (e.model && e.model.id) || '';
        document.getElementById('eTexture').value = e.texture || '';
        const s = e.sounds || {};
        document.getElementById('eS1').value = s.ambient || '';
        document.getElementById('eS2').value = s.hurt || '';
        document.getElementById('eS3').value = s.death || '';
        document.getElementById('eS4').value = s.step || '';
        document.getElementById('eS5').value = s.swing || '';
        // 显示已自动预加载的模型（若有）
        const model = window.AppState.model;
        const note = document.getElementById('modelNote');
        const tree = document.getElementById('modelBoneTree');
        if (model && model.bones) {
            note.textContent = tf('dyn.modelParsed', { id: model.identifier || model.fileName, n: model.bones.length }) + '（自动预加载）';
            renderBoneTree('modelBoneTree', model.tree, null);
        } else {
            note.textContent = '';
            tree.innerHTML = '';
        }
    }

    // 渲染骨骼树（模型解析后）
    function renderBoneTree(containerId, tree, onClick) {
        const container = document.getElementById(containerId);
        container.innerHTML = '';
        function walk(nodes, depth) {
            const wrap = document.createElement('div');
            if (depth > 0) wrap.className = 'bone-nesting';
            for (const node of nodes) {
                const div = document.createElement('div');
                div.className = 'bone-node';
                div.style.paddingLeft = (depth * 14 + 6) + 'px';
                const nameEl = document.createElement('span');
                nameEl.className = 'bn-name';
                nameEl.textContent = node.name;
                div.appendChild(nameEl);
                if (onClick) {
                    const btn = document.createElement('button');
                    btn.className = 'bn-add';
                    btn.title = t('dyn.addToPart');
                    btn.textContent = '+';
                    btn.addEventListener('click', (e) => { e.stopPropagation(); onClick(node); });
                    div.appendChild(btn);
                }
                wrap.appendChild(div);
                if (node.children && node.children.length) {
                    wrap.appendChild(walk(node.children, depth + 1));
                }
            }
            return wrap;
        }
        const top = document.createElement('div');
        top.appendChild(walk(tree || [], 0));
        container.appendChild(top);
    }

    // ===== 页签 3：部位配置 =====
    // selectedPartName 改为每个 tab 独立状态（存于 tab.selectedPartName）
    function getSelPart() {
        const t = activeTab();
        return t ? t.selectedPartName : null;
    }
    function setSelPart(v) {
        const t = activeTab();
        if (t) t.selectedPartName = v;
    }

    function renderParts() {
        const listEl = document.getElementById('partsList');
        listEl.innerHTML = '';
        const note = document.getElementById('partsNote');
        const cfg = window.AppState.healthConfig || { parts: [] };
        const selPart = getSelPart();
        if (!cfg.parts.length) {
            note.textContent = t('parts.noParts');
        } else {
            note.textContent = t('parts.hint');
        }
        // 渲染骨骼树（来自当前模型）
        const model = window.AppState.model;
        const treeEl = document.getElementById('partsBoneTree');
        if (model && model.tree) {
            renderBoneTree('partsBoneTree', model.tree, (node) => addBoneToSelectedPart(node.name));
        } else {
            treeEl.innerHTML = `<div class="note" style="padding:8px">${t('parts.noModel')}</div>`;
        }

        for (const part of cfg.parts) {
            const card = document.createElement('div');
            card.className = 'part-card' + (part.partName === selPart ? ' selected' : '');
            card.dataset.partName = part.partName;
            card.innerHTML = `
                <div class="part-head">
                    <label>${t('parts.name')} <input class="part-name-input f-part-name" value="${esc(part.partName)}"></label>
                    <label>${t('parts.hp')} <input class="num f-part-hp" type="number" step="0.5" min="0.5" value="${part.maxModuleHealth}"></label>
                    <label>${t('parts.fatal')} <input class="f-part-fatal" type="checkbox" ${part.isFatal ? 'checked' : ''}></label>
                    <label>${t('parts.trans')} <input class="num f-part-tb" type="number" step="0.1" min="0" value="${part.transmissionToBase}"></label>
                    <button class="btn ghost small f-part-del">${t('parts.del')}</button>
                </div>
                <div class="part-units"></div>`;
            const unitsBox = card.querySelector('.part-units');
            for (const unit of part.units || []) {
                unitsBox.appendChild(renderUnitRow(part, unit));
            }
            // 部位头部字段变更 → 写回
            card.querySelector('.f-part-name').addEventListener('change', (ev) => { part.partName = ev.target.value; card.dataset.partName = part.partName; });
            card.querySelector('.f-part-hp').addEventListener('change', (ev) => { part.maxModuleHealth = parseFloat(ev.target.value) || 0; });
            card.querySelector('.f-part-fatal').addEventListener('change', (ev) => { part.isFatal = ev.target.checked; });
            card.querySelector('.f-part-tb').addEventListener('change', (ev) => { part.transmissionToBase = parseFloat(ev.target.value) || 0; });
            card.querySelector('.f-part-del').addEventListener('click', () => {
                cfg.parts = cfg.parts.filter((p) => p !== part);
                if (getSelPart() === part.partName) setSelPart(null);
                renderParts();
            });
            card.addEventListener('click', (ev) => {
                if (ev.target.closest('.unit-row') || ev.target.closest('.f-part-del')) return;
                setSelPart(part.partName);
                listEl.querySelectorAll('.part-card').forEach((c) => c.classList.remove('selected'));
                card.classList.add('selected');
            });
            listEl.appendChild(card);
        }
    }

    function renderUnitRow(part, unit) {
        const row = document.createElement('div');
        row.className = 'unit-row';
        row.innerHTML = `
            <span class="u-bone">${esc(unit.boneName)}</span>
            <label>${t('parts.coeff')} <input class="u-num u-coeff" type="number" step="0.05" min="0" value="${unit.transmissionCoeff}"></label>
            <label>${t('parts.armor')} <input class="u-num u-armor" type="number" step="0.5" min="0" value="${unit.armorValue}"></label>
            <label>${t('parts.antiPierce')} <input class="u-num u-ap" type="number" step="0.5" min="0" value="${unit.antiPiercing}"></label>
            <label>${t('parts.tag')} <input class="u-tag u-tag-in" value="${esc(unit.collisionTag || '')}"></label>
            <button class="btn ghost small u-del">×</button>`;
        row.querySelector('.u-coeff').addEventListener('change', (ev) => { unit.transmissionCoeff = parseFloat(ev.target.value) || 0; });
        row.querySelector('.u-armor').addEventListener('change', (ev) => { unit.armorValue = parseFloat(ev.target.value) || 0; });
        row.querySelector('.u-ap').addEventListener('change', (ev) => { unit.antiPiercing = parseFloat(ev.target.value) || 0; });
        row.querySelector('.u-tag-in').addEventListener('change', (ev) => { unit.collisionTag = ev.target.value; });
        row.querySelector('.u-del').addEventListener('click', () => {
            part.units = part.units.filter((u) => u !== unit);
            row.remove();
        });
        return row;
    }

    function addBoneToSelectedPart(boneName) {
        const cfg = window.AppState.healthConfig || { parts: [] };
        const selPart = getSelPart();
        if (!selPart) {
            window.showError(t('dyn.selectPartFirst'));
            return;
        }
        // 检查重复
        for (const part of cfg.parts) {
            if (part.units.some((u) => u.boneName === boneName)) {
                window.showError(tf('dyn.boneDuplicated', { bone: boneName, part: part.partName }));
                return;
            }
        }
        const part = cfg.parts.find((p) => p.partName === selPart);
        if (!part) { window.showError(t('dyn.partMissing')); return; }
        part.units.push({
            boneName,
            transmissionCoeff: 1.0,
            armorValue: 0.0,
            antiPiercing: 0.0,
            collisionTag: boneName,
            specialEffects: [],
            hitSound: '',
        });
        renderParts();
    }

    function generateDefaultParts() {
        const model = window.AppState.model;
        if (!model || !model.bones || !model.bones.length) {
            window.showError(t('dyn.modelRequired'));
            return;
        }
        const boneNames = model.bones.map((b) => b.name);
        // 本地复刻 createDefaultHealthConfig：head/body/arm/leg 分组
        const isArm = (n) => n.includes('arm');
        const isLeg = (n) => n.includes('leg');
        const isHead = (n) => n.includes('head');
        const parts = ['head', 'body', 'arm', 'leg'].map((partName) => ({
            partName,
            maxModuleHealth: 10.0,
            isFatal: partName === 'head',
            transmissionToBase: 1.0,
            units: [],
        }));
        for (const boneName of boneNames) {
            let partName = 'body';
            if (isHead(boneName)) partName = 'head';
            else if (isArm(boneName)) partName = 'arm';
            else if (isLeg(boneName)) partName = 'leg';
            const part = parts.find((p) => p.partName === partName);
            part.units.push({
                boneName,
                transmissionCoeff: 1.0,
                armorValue: 0.0,
                antiPiercing: 0.0,
                collisionTag: boneName,
                specialEffects: [],
                hitSound: '',
            });
        }
        window.AppState.healthConfig = { parts };
        renderParts();
        window.showStatus(t('dyn.genPartsDone'), true);
    }

    // ===== 页签 4：动画状态机 =====
    async function loadTemplates(entity) {
        const res = await window.api.getStateMachineTemplates();
        if (!res.ok) { window.showError(t('dyn.loadTemplatesFail') + res.error); return; }
        const templates = res.data || {};
        const sel = document.getElementById('animTemplate');
        sel.innerHTML = '';
        for (const [id, t] of Object.entries(templates)) {
            const opt = document.createElement('option');
            opt.value = id;
            opt.textContent = t.name + '（' + id + '）';
            sel.appendChild(opt);
        }
        // 依据实体已有 animations 判断模板（无状态则默认 melee）
        let chosen = 'melee';
        const anims = entity.animations || {};
        if (anims['3'] === 'shoot') chosen = 'ranged';
        sel.value = chosen;
        document.getElementById('templateDesc').textContent = templates[chosen] ? templates[chosen].description : '';
        renderAnimTable(templates[chosen], entity);
    }

    function renderAnimTable(template, entity) {
        const tbody = document.getElementById('animTableBody');
        tbody.innerHTML = '';
        const anims = (entity && entity.animations) || {};
        for (const s of template.states) {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td class="a-id">${s.id}</td>
                <td>${esc(s.name)}</td>
                <td><input class="anim-input" data-key="${s.key}" value="${esc(anims[s.key] != null ? anims[s.key] : s.anim)}"></td>`;
            tbody.appendChild(tr);
        }
    }

    function applyTemplateAnims() {
        const sel = document.getElementById('animTemplate');
        const templates = window._animTemplates;
        const t = templates ? templates[sel.value] : null;
        if (!t) return;
        document.querySelectorAll('.anim-input').forEach((input) => {
            const state = t.states.find((s) => s.key === input.dataset.key);
            if (state) input.value = state.anim;
        });
        window.showStatus(tf('dyn.templateApplied', { name: t.name }), true);
    }

    // ===== 收集表单 → 实体对象 =====
    function collectEntity() {
        const e = window.AppState.entity || {};
        e.id = document.getElementById('eId').value.trim();
        e.namespace = document.getElementById('eNs').value.trim() || undefined;
        e.displayName = document.getElementById('eDn').value.trim() || undefined;
        e.size = {
            width: parseFloat(document.getElementById('eW').value) || 0.6,
            height: parseFloat(document.getElementById('eH').value) || 1.95,
        };
        e.attributes = {
            maxHealth: parseFloat(document.getElementById('eMaxHp').value) || 20,
            attackDamage: parseFloat(document.getElementById('eAtk').value) || 0,
            movementSpeed: parseFloat(document.getElementById('eSpeed').value) || 0.23,
            followRange: parseFloat(document.getElementById('eFollow').value) || 16,
            armor: parseFloat(document.getElementById('eArmor').value) || 0,
        };
        // 模型
        e.model = {
            type: document.getElementById('eModelType').value,
            id: document.getElementById('eModelId').value.trim(),
        };
        e.texture = document.getElementById('eTexture').value.trim() || undefined;
        // 音效
        const sounds = {};
        const sfx = [
            ['eS1', 'ambient'], ['eS2', 'hurt'], ['eS3', 'death'], ['eS4', 'step'], ['eS5', 'swing'],
        ];
        for (const [selId, key] of sfx) {
            const v = document.getElementById(selId).value.trim();
            if (v) sounds[key] = v;
        }
        e.sounds = Object.keys(sounds).length ? sounds : undefined;
        // 行为
        const behaviors = [];
        document.querySelectorAll('#behaviorsBox .beh-row').forEach((row) => {
            const type = row.querySelector('.b-type').value;
            const cfgText = row.querySelector('.b-cfg').value.trim();
            let cfg = {};
            try { cfg = cfgText ? JSON.parse(cfgText) : {}; } catch { cfg = { _error: cfgText }; }
            behaviors.push({ type, ...cfg });
        });
        e.behaviors = behaviors.length ? behaviors : undefined;
        // 动画
        const anims = {};
        document.querySelectorAll('.anim-input').forEach((input) => {
            const v = input.value.trim();
            if (v) anims[input.dataset.key] = v;
        });
        e.animations = Object.keys(anims).length ? anims : undefined;
        // 清理 undefined 键
        for (const k of Object.keys(e)) if (e[k] === undefined) delete e[k];
        return e;
    }

    // ===== 保存到暂存区（草稿） =====
    async function save() {
        const tab = activeTab();
        if (!tab) return;
        const entity = collectEntity();
        if (!entity.id) { window.showError(t('common.idRequired')); return; }
        // 保存实体草稿（写入 app 暂存区，不碰正式包）
        const res = await window.api.saveEntity(tab.pack.dir, entity);
        if (!res.ok) { window.showError(t('common.saveFailed') + res.error); return; }
        // 保存/删除部位配置草稿
        const ns = entity.namespace || packNamespace(tab.pack);
        const cfg = window.AppState.healthConfig;
        if (cfg && cfg.parts && cfg.parts.length) {
            const hres = await window.api.saveHealth(tab.pack.dir, ns, entity.id, cfg);
            if (!hres.ok) { window.showError(t('common.saveFailed') + hres.error); return; }
        } else {
            await window.api.deleteHealth(tab.pack.dir, ns, entity.id);
        }
        tab.entity = entity;
        window.AppState.entity = entity;
        document.getElementById('editorBadge').textContent = t('dyn.draftUnexported');
        window.showStatus(tf('dyn.savedAsDraft', { id: entity.id }), true);
        // 刷新中栏实体列表（显示暂存标记）
        if (window.EntityList && window.EntityList.loadEntities) {
            window.EntityList.loadEntities(tab.pack.dir);
        }
    }

    // ===== 导出全部暂存 → 正式包 → 回收 =====
    async function exportStaged() {
        const tab = activeTab();
        if (!tab) return;
        if (!confirm(tf('dyn.exportConfirm', { name: tab.pack.name }))) return;
        const res = await window.api.stagingExport(tab.pack.dir);
        if (!res.ok) { window.showError(t('common.exportFailed') + res.error); return; }
        const data = res.data || {};
        window.showStatus(tf('dyn.exportedDone', { n: data.exported }), true);
        document.getElementById('editorBadge').textContent = t('dyn.exported');
        // 刷新中栏
        if (window.EntityList && window.EntityList.loadEntities) {
            window.EntityList.loadEntities(tab.pack.dir);
        }
    }

    // ===== 打包 =====
    async function build() {
        const tab = activeTab();
        if (!tab) return;
        // 打包前提示：若有未导出的暂存，先提醒（打包基于正式包内容）
        const stagedRes = await window.api.stagingList(tab.pack.dir);
        if (stagedRes.ok && stagedRes.data && stagedRes.data.hasStaged) {
            const go = confirm(tf('dyn.buildStagedWarn', { n: stagedRes.data.items.length }));
            if (go) {
                const expRes = await window.api.stagingExport(tab.pack.dir);
                if (!expRes.ok) { window.showError(t('common.exportFailed') + expRes.error); return; }
            }
        }
        const outName = (tab.pack.name || 'pack') + '.jar';
        const outEl = document.getElementById('buildOutput');
        outEl.style.display = 'block';
        outEl.textContent = t('dyn.buildRunning') + '\n';
        const res = await window.api.buildPack({ packDir: tab.pack.dir, outJarName: outName });
        if (res.ok) {
            const data = res.data || {};
            outEl.textContent = '=== Build OK ===\n' + (data.stdout || '');
            window.showStatus(tf('dyn.buildDone', { name: outName }), true);
        } else {
            outEl.textContent = '=== Build Failed ===\n' + res.error;
            window.showStatus(t('dyn.buildFail'), false);
        }
    }

    // ===== 事件绑定 =====
    function bindEvents() {
        document.getElementById('btnSave').addEventListener('click', save);
        document.getElementById('btnExport').addEventListener('click', exportStaged);
        document.getElementById('btnBuild').addEventListener('click', build);
        document.getElementById('btnAddBehavior').addEventListener('click', () => {
            const box = document.getElementById('behaviorsBox');
            const row = document.createElement('div');
            row.className = 'beh-row';
            row.innerHTML = `
                <select class="b-type">
                    <option value="attack">attack</option>
                    <option value="ai">ai</option>
                    <option value="raycast">raycast</option>
                </select>
                <input class="b-cfg" placeholder='{"damage":3.0}'>
                <button class="btn ghost small b-del">×</button>`;
            row.querySelector('.b-del').addEventListener('click', () => row.remove());
            box.appendChild(row);
        });

        document.getElementById('btnPickModel').addEventListener('click', async () => {
            const res = await window.api.openModelFile();
            if (!res.ok) { if (res.error !== 'cancelled') window.showError(res.error); return; }
            const parseRes = await window.api.parseModelFile(res.data);
            if (!parseRes.ok) { window.showError(t('dyn.parseModelFail') + parseRes.error); return; }
            const model = parseRes.data;
            window.AppState.model = model;
            document.getElementById('modelNote').textContent = tf('dyn.modelParsed', { id: model.identifier || model.fileName, n: model.bones.length });
            renderBoneTree('modelBoneTree', model.tree, null);
            // 自动建议 model.id
            if (!document.getElementById('eModelId').value.trim()) {
                const tab = activeTab();
                const ns = document.getElementById('eNs').value.trim() || (tab ? packNamespace(tab.pack) : 'mypack');
                const fileName = model.fileName || 'model';
                const base = fileName.replace(/\.json$/, '');
                document.getElementById('eModelId').value = `${ns}:${base}`;
            }
            renderParts();
        });

        document.getElementById('btnPickJar').addEventListener('click', async () => {
            const res = await window.api.openJarFile();
            if (!res.ok) { if (res.error !== 'cancelled') window.showError(res.error); return; }
            const listRes = await window.api.listModelsInJar(res.data);
            if (!listRes.ok) { window.showError(t('dyn.readJarFail') + listRes.error); return; }
            const models = listRes.data || [];
            if (!models.length) { window.showError(t('dyn.jarNoModels')); return; }
            const pick = prompt(tf('dyn.pickModelNum', { list: models.map((m, i) => `${i}: ${m}`).join('\n') }), '0');
            if (pick === null) return;
            const idx = parseInt(pick, 10);
            if (isNaN(idx) || idx < 0 || idx >= models.length) { window.showError(t('dyn.invalidIndex')); return; }
            const parseRes = await window.api.parseModelFromJar(res.data, models[idx]);
            if (!parseRes.ok) { window.showError(t('dyn.parseModelFail') + parseRes.error); return; }
            const model = parseRes.data;
            window.AppState.model = model;
            document.getElementById('modelNote').textContent = tf('dyn.jarModelImported', { path: models[idx], n: model.bones.length });
            renderBoneTree('modelBoneTree', model.tree, null);
            if (!document.getElementById('eModelId').value.trim()) {
                const parts = models[idx].split('/');
                const base = parts[parts.length - 1].replace(/\.json$/, '');
                const tab = activeTab();
                const ns = parts.length >= 3 ? parts[2] : (tab ? packNamespace(tab.pack) : 'mypack');
                document.getElementById('eModelId').value = `${ns}:${base}`;
            }
            renderParts();
        });

        document.getElementById('btnGenParts').addEventListener('click', generateDefaultParts);
        document.getElementById('btnAddPart').addEventListener('click', () => {
            const cfg = window.AppState.healthConfig || { parts: [] };
            cfg.parts.push({
                partName: 'new_part_' + (cfg.parts.length + 1),
                maxModuleHealth: 10.0,
                isFatal: false,
                transmissionToBase: 1.0,
                units: [],
            });
            window.AppState.healthConfig = cfg;
            setSelPart(cfg.parts[cfg.parts.length - 1].partName);
            renderParts();
        });

        document.getElementById('btnApplyTemplate').addEventListener('click', applyTemplateAnims);
        document.getElementById('animTemplate').addEventListener('change', (ev) => {
            const templates = window._animTemplates || {};
            const t = templates[ev.target.value];
            document.getElementById('templateDesc').textContent = t ? t.description : '';
        });

        document.querySelectorAll('#editorTabs .tab').forEach((t) => {
            t.addEventListener('click', () => switchTab(t.dataset.tab));
        });
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    // 模板缓存（供 applyTemplateAnims 使用）
    window._animTemplates = null;
    (async function cacheTemplates() {
        const res = await window.api.getStateMachineTemplates();
        if (res.ok) window._animTemplates = res.data;
    })();

    // 初始化事件绑定（模块加载时执行一次）
    bindEvents();

    return { open, closeActiveTab };
})();
