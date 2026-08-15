// entityEditor.js - 右栏：实体编辑器（4 页签）
// 依赖 window.api、window.showError / window.showStatus / window.AppState（app.js 定义）
// 暴露 window.EntityEditor.open({ pack, entity, isNew })

window.EntityEditor = (function () {
    let current = { pack: null, entity: null, isNew: false };

    // ===== 打开编辑器 =====
    async function open(ctx) {
        current.pack = ctx.pack;
        current.entity = ctx.entity || null;
        current.isNew = !!ctx.isNew;

        document.getElementById('editorEmpty').classList.add('hidden');
        document.getElementById('editorContent').classList.remove('hidden');
        document.getElementById('editorTitle').textContent = (current.isNew ? '新建实体' : '编辑实体') +
            (current.entity ? ' — ' + (current.entity.id || '') : '');
        document.getElementById('editorBadge').textContent = current.isNew ? '未保存' : ('v' + (current.pack.version || ''));

        const entity = current.entity || await getDefaultEntity();
        if (!entity) return;
        window.AppState.entity = entity;

        // 部位配置：从文件读取（已有实体）或留空（新实体）
        const ns = entity.namespace || packNamespace();
        window.AppState.healthConfig = await readHealthFor(ns, entity.id);

        renderBasic(entity);
        renderModel(entity);
        renderParts();
        renderAnim(entity);
        loadTemplates(entity);

        document.getElementById('statusBar').textContent = '';
        document.getElementById('buildOutput').style.display = 'none';
        switchTab('basic');
    }

    function packNamespace() {
        const name = current.pack && current.pack.name;
        return name ? name.replace(/-/g, '_') : 'mypack';
    }

    async function getDefaultEntity() {
        const res = await window.api.getEntityDefaults();
        if (!res.ok) { window.showError('获取默认实体失败: ' + res.error); return null; }
        const e = JSON.parse(JSON.stringify(res.data));
        // 用包名做缺省 namespace 建议
        e.namespace = packNamespace();
        // 模型 id 建议
        const ns = e.namespace;
        const hint = document.getElementById('modelNote');
        return e;
    }

    async function readHealthFor(ns, entityId) {
        if (!entityId) return { parts: [] };
        const res = await window.api.readHealth(current.pack.dir, ns, entityId);
        if (res.ok && res.data) return res.data;
        return { parts: [] };
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
        document.getElementById('modelNote').textContent = '';
        document.getElementById('modelBoneTree').innerHTML = '';
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
                    btn.title = '添加到选中部位';
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
    let selectedPartName = null;

    function renderParts() {
        const listEl = document.getElementById('partsList');
        listEl.innerHTML = '';
        const note = document.getElementById('partsNote');
        const cfg = window.AppState.healthConfig || { parts: [] };
        if (!cfg.parts.length) {
            note.textContent = '尚无部位配置。先选择模型（模型与资源页签），再点"生成默认部位"。';
        } else {
            note.textContent = '点击部位卡片选中，再从左侧骨骼树点 + 添加骨骼到该部位。';
        }
        // 渲染骨骼树（来自当前模型）
        const model = window.AppState.model;
        const treeEl = document.getElementById('partsBoneTree');
        if (model && model.tree) {
            renderBoneTree('partsBoneTree', model.tree, (node) => addBoneToSelectedPart(node.name));
        } else {
            treeEl.innerHTML = '<div class="note" style="padding:8px">未解析模型。请在"模型与资源"页签选择模型文件。</div>';
        }

        for (const part of cfg.parts) {
            const card = document.createElement('div');
            card.className = 'part-card' + (part.partName === selectedPartName ? ' selected' : '');
            card.dataset.partName = part.partName;
            card.innerHTML = `
                <div class="part-head">
                    <label>部位名 <input class="part-name-input f-part-name" value="${esc(part.partName)}"></label>
                    <label>血量 <input class="num f-part-hp" type="number" step="0.5" min="0.5" value="${part.maxModuleHealth}"></label>
                    <label>致死 <input class="f-part-fatal" type="checkbox" ${part.isFatal ? 'checked' : ''}></label>
                    <label>传导 <input class="num f-part-tb" type="number" step="0.1" min="0" value="${part.transmissionToBase}"></label>
                    <button class="btn ghost small f-part-del">删除部位</button>
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
                if (selectedPartName === part.partName) selectedPartName = null;
                renderParts();
            });
            card.addEventListener('click', (ev) => {
                if (ev.target.closest('.unit-row') || ev.target.closest('.f-part-del')) return;
                selectedPartName = part.partName;
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
            <label>传导 <input class="u-num u-coeff" type="number" step="0.05" min="0" value="${unit.transmissionCoeff}"></label>
            <label>装甲 <input class="u-num u-armor" type="number" step="0.5" min="0" value="${unit.armorValue}"></label>
            <label>抗贯穿 <input class="u-num u-ap" type="number" step="0.5" min="0" value="${unit.antiPiercing}"></label>
            <label>标签 <input class="u-tag u-tag-in" value="${esc(unit.collisionTag || '')}"></label>
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
        if (!selectedPartName) {
            window.showError('请先点击选中一个部位，再点骨骼 + 按钮');
            return;
        }
        // 检查重复
        for (const part of cfg.parts) {
            if (part.units.some((u) => u.boneName === boneName)) {
                window.showError(`骨骼 ${boneName} 已分配到部位 ${part.partName}`);
                return;
            }
        }
        const part = cfg.parts.find((p) => p.partName === selectedPartName);
        if (!part) { window.showError('选中部位不存在'); return; }
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
            window.showError('请先在"模型与资源"页签选择/解析模型文件');
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
        window.showStatus('已按模型骨骼生成默认部位分组', true);
    }

    // ===== 页签 4：动画状态机 =====
    async function loadTemplates(entity) {
        const res = await window.api.getStateMachineTemplates();
        if (!res.ok) { window.showError('加载状态机模板失败: ' + res.error); return; }
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
        window.showStatus(`已应用模板 "${t.name}" 的默认动画名`, true);
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

    // ===== 保存 =====
    async function save() {
        const entity = collectEntity();
        if (!entity.id) { window.showError('id 必填'); return; }
        // 保存实体 JSON
        const res = await window.api.saveEntity(current.pack.dir, entity);
        if (!res.ok) { window.showError('保存实体失败: ' + res.error); return; }
        // 保存/删除部位配置
        const ns = entity.namespace || packNamespace();
        const cfg = window.AppState.healthConfig;
        if (cfg && cfg.parts && cfg.parts.length) {
            const hres = await window.api.saveHealth(current.pack.dir, ns, entity.id, cfg);
            if (!hres.ok) { window.showError('保存部位配置失败: ' + hres.error); return; }
        } else {
            await window.api.deleteHealth(current.pack.dir, ns, entity.id);
        }
        window.AppState.entity = entity;
        document.getElementById('editorBadge').textContent = '已保存';
        window.showStatus(`实体 "${entity.id}" 已保存（实体 + 部位配置）`, true);
        // 刷新中栏实体列表
        if (window.EntityList && window.EntityList.loadEntities) {
            window.EntityList.loadEntities(current.pack.dir);
        }
    }

    // ===== 打包 =====
    async function build() {
        const outName = (current.pack.name || 'pack') + '.jar';
        const outEl = document.getElementById('buildOutput');
        outEl.style.display = 'block';
        outEl.textContent = '打包中… 调用 contentpacks-source/build-pack.ps1\n';
        const res = await window.api.buildPack({ packDir: current.pack.dir, outJarName: outName });
        if (res.ok) {
            const data = res.data || {};
            outEl.textContent = '=== 打包成功 ===\n' + (data.stdout || '');
            window.showStatus(`打包完成 → contentpacks/${outName}`, true);
        } else {
            outEl.textContent = '=== 打包失败 ===\n' + res.error;
            window.showStatus('打包失败', false);
        }
    }

    // ===== 事件绑定 =====
    function bindEvents() {
        document.getElementById('btnSave').addEventListener('click', save);
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
            if (!parseRes.ok) { window.showError('解析模型失败: ' + parseRes.error); return; }
            const model = parseRes.data;
            window.AppState.model = model;
            document.getElementById('modelNote').textContent = `已解析: ${model.identifier || model.fileName}（${model.bones.length} 骨骼）`;
            renderBoneTree('modelBoneTree', model.tree, null);
            // 自动建议 model.id
            if (!document.getElementById('eModelId').value.trim()) {
                const ns = document.getElementById('eNs').value.trim() || packNamespace();
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
            if (!listRes.ok) { window.showError('读取 jar 失败: ' + listRes.error); return; }
            const models = listRes.data || [];
            if (!models.length) { window.showError('jar 中没有 spark_models'); return; }
            const pick = prompt('选择要导入的模型（输入序号）:\n' + models.map((m, i) => `${i}: ${m}`).join('\n'), '0');
            if (pick === null) return;
            const idx = parseInt(pick, 10);
            if (isNaN(idx) || idx < 0 || idx >= models.length) { window.showError('无效序号'); return; }
            const parseRes = await window.api.parseModelFromJar(res.data, models[idx]);
            if (!parseRes.ok) { window.showError('解析模型失败: ' + parseRes.error); return; }
            const model = parseRes.data;
            window.AppState.model = model;
            document.getElementById('modelNote').textContent = `已从 jar 导入: ${models[idx]}（${model.bones.length} 骨骼）`;
            renderBoneTree('modelBoneTree', model.tree, null);
            if (!document.getElementById('eModelId').value.trim()) {
                const parts = models[idx].split('/');
                const base = parts[parts.length - 1].replace(/\.json$/, '');
                const ns = parts.length >= 3 ? parts[2] : packNamespace();
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
            selectedPartName = cfg.parts[cfg.parts.length - 1].partName;
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

    return { open };
})();
