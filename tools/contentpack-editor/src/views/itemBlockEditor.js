// itemBlockEditor.js - 右栏：物品/方块编辑器
// 表单 → items/<id>.json / blocks/<id>.json（暂存草稿，「导出」进正式包）
// 契约（单一真源）：
//   Java  src/main/java/.../core/registry/BatchContentRegistrar.java（注册行为）
//   目录  src/main/resources/assets/catoxidesbattlerebuild/editor/block_properties.json
//   前端  src/core/itemBlockSchema.js（校验 + blockstate 生成）
//
// 绑定策略：basic/model 页的输入带 data-k（点路径），bindCollect 统一 input/change
// → 写回 S.def；shape 页的参数行自带显式监听（结构变化重渲染）。
//
// 暴露 window.ItemBlockEditor.open({pack, kind, def, isNew}) / close() / hasActiveTab() / init({onListChanged})

window.ItemBlockEditor = (function () {
    'use strict';
    const S = {
        kind: null,          // 'item' | 'block'
        def: null,           // 当前定义（唯一数据源）
        isNew: false,
        isStaged: false,
        pack: null,          // {dir, name, ...}
        tab: 'basic',
        catalog: null,       // 参数目录（itemblock:catalog）
        modelByCombo: {},    // variants 模式：组合键 → {model, x, y}
        multipart: [],       // multipart 模式：[{when, model}]
        handlers: { onListChanged: null },
    };

    // ---------- 小工具 ----------
    function el(tag, cls, text) {
        const e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text !== undefined) e.textContent = text;
        return e;
    }

    function field(labelText, input, title) {
        const d = el('div');
        if (title) d.title = title;
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

    function inNum(value, min, max, step, placeholder) {
        const i = inText(value, placeholder);
        i.type = 'number';
        if (min !== undefined) i.min = min;
        if (max !== undefined) i.max = max;
        if (step !== undefined) i.step = step;
        return i;
    }

    function sel(options, value, title) {
        const s = el('select', 'f-input');
        if (title) s.title = title;
        for (const [v, label] of options) {
            const o = el('option', null, label);
            o.value = v;
            s.appendChild(o);
        }
        if (value !== undefined && value !== null) s.value = String(value);
        return s;
    }

    function opt(value, label) {
        const o = el('option', null, label);
        o.value = value;
        return o;
    }

    function chk(labelText, checked, title) {
        const d = el('div', 'f-check');
        const c = el('input');
        c.type = 'checkbox';
        c.checked = !!checked;
        d.appendChild(c);
        d.appendChild(el('label', null, labelText));
        if (title) d.title = title;
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

    function propNames() {
        return ((S.def && S.def.properties) || []).map((p) => p.name);
    }

    function baseModelFile() {
        const m = S.def.model || {};
        return m.file ? m.file : (S.def.id || '');
    }

    // ---------- 生命周期 ----------
    async function ensureCatalog() {
        if (Array.isArray(S.catalog)) return;
        try {
            const r = await window.api.itemBlockCatalog();
            S.catalog = (r && r.ok && Array.isArray(r.data)) ? r.data : (window.ItemBlockSchema.BUILTIN_CATALOG);
        } catch {
            S.catalog = window.ItemBlockSchema.BUILTIN_CATALOG;
        }
    }

    async function open(ctx) {
        S.pack = ctx.pack;
        S.kind = ctx.kind === 'block' ? 'block' : 'item';
        S.isNew = !!ctx.isNew;
        S.isStaged = !!(ctx.def && ctx.def._staged);
        S.tab = 'basic';
        const ns = (ctx.pack && ctx.pack.name) ? String(ctx.pack.name).replace(/-/g, '_') : '';
        if (!ctx.def) {
            S.def = S.kind === 'item' ? window.ItemBlockSchema.newItem(ns) : window.ItemBlockSchema.newBlock(ns);
        } else {
            S.def = JSON.parse(JSON.stringify(ctx.def));
            delete S.def._staged;
            delete S.def._isNew;
        }
        // 初始化 variants 组合→模型映射
        S.modelByCombo = {};
        if (S.kind === 'block' && S.def.blockstates && Array.isArray(S.def.blockstates.variants)) {
            for (const r of S.def.blockstates.variants) {
                if (!r || !r.when) continue;
                S.modelByCombo[window.ItemBlockSchema.stateKey(r.when, propNames())] = {
                    model: r.model || '', x: r.x, y: r.y,
                };
            }
        }
        S.multipart = (S.kind === 'block' && S.def.blockstates && Array.isArray(S.def.blockstates.multipart))
            ? S.def.blockstates.multipart.map((r) => ({
                when: r.when ? Object.entries(r.when).map(([k, v]) => k + '=' + v).join(',') : '',
                model: r.model || '',
            }))
            : [];
        await ensureCatalog();
        renderAll();
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    function close() {
        S.def = null;
        S.kind = null;
        const mount = document.getElementById('itemBlockMount');
        if (mount) mount.innerHTML = '';
        const rep = document.getElementById('itemBlockReport');
        if (rep) { rep.style.display = 'none'; rep.textContent = ''; }
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    function hasActiveTab() { return !!S.def; }

    function renderTitle() {
        const t = document.getElementById('itemBlockTitle');
        const b = document.getElementById('itemBlockBadge');
        if (t) t.textContent = (S.kind === 'block' ? '🧱 方块' : '🧩 物品') + (S.def.id ? ' · ' + S.def.id : ' · 新建');
        if (b) {
            b.textContent = S.isNew ? '新建' : (S.isStaged ? '草稿' : '正式');
            b.className = 'editor-badge ' + (S.isStaged || S.isNew ? 'warn' : 'ok');
        }
    }

    // ---------- 主渲染 ----------
    function renderAll() {
        const mount = document.getElementById('itemBlockMount');
        mount.innerHTML = '';
        const tabBar = el('div', 'ib-tabbar');
        const tabs = S.kind === 'block'
            ? [['basic', '基本信息'], ['shape', '形态与参数'], ['model', '模型'], ['preview', '预览']]
            : [['basic', '基本信息'], ['model', '模型'], ['preview', '预览']];
        for (const [name, label] of tabs) {
            const b = el('button', 'tab' + (S.tab === name ? ' active' : ''), label);
            b.type = 'button';
            b.addEventListener('click', () => { S.tab = name; renderAll(); });
            tabBar.appendChild(b);
        }
        mount.appendChild(tabBar);

        const page = el('div', 'ib-page');
        if (S.tab === 'basic') {
            for (const s of buildBasicSections()) page.appendChild(s);
            bindCollect(page);
        } else if (S.tab === 'shape') {
            page.appendChild(buildShapeSection());
        } else if (S.tab === 'model') {
            page.appendChild(buildModelSection());
            bindCollect(page);
        } else {
            page.appendChild(buildPreviewSection());
        }
        mount.appendChild(page);
        renderTitle();
    }

    /** 统一绑定 [data-k] 输入 → 写回 S.def（点路径），轻量刷新 */
    function bindCollect(root) {
        const d = S.def;
        for (const i of root.querySelectorAll('input[data-k], select[data-k]')) {
            if (i.dataset.bound) continue;
            i.dataset.bound = '1';
            const k = i.dataset.k;
            const csv = i.dataset.join === 'csv';
            i.addEventListener('input', () => {
                setByPath(d, k, i.type === 'checkbox' ? i.checked : (csv ? i.value.split(',').map((s) => s.trim()).filter(Boolean) : i.value));
                onDefMutated(false);
            });
            i.addEventListener('change', () => {
                setByPath(d, k, i.type === 'checkbox' ? i.checked : (csv ? i.value.split(',').map((s) => s.trim()).filter(Boolean) : i.value));
                onDefMutated(false);
            });
        }
    }

    function setByPath(obj, dotted, value) {
        const parts = dotted.split('.');
        let cur = obj;
        for (let i = 0; i < parts.length - 1; i++) {
            if (typeof cur[parts[i]] !== 'object' || cur[parts[i]] === null) cur[parts[i]] = {};
            cur = cur[parts[i]];
        }
        const last = parts[parts.length - 1];
        cur[last] = (value === '') ? '' : value;
    }

    /** 输入变更：轻量刷新（预览页跟随；不重渲染当前表单避免丢焦点） */
    function onDefMutated() {
        renderTitle();
        if (S.tab === 'preview') renderAll();
    }

    // ---------- 页签：基本信息 ----------
    function buildBasicSections() {
        const d = S.def;
        const idIn = inText(d.id, '如 iron_plate / example_wall');
        idIn.dataset.k = 'id';
        const nsIn = inText(d.namespace, '留空 = catoxidesbattlerebuild');
        nsIn.dataset.k = 'namespace';
        const langEn = inText((d.lang || {}).en_us, '英文显示名');
        langEn.dataset.k = 'lang.en_us';
        const langZh = inText((d.lang || {}).zh_cn, '中文显示名');
        langZh.dataset.k = 'lang.zh_cn';

        const out = [
            section('标识',
                field('id（必填，注册名）', idIn, '必须 ^[a-z0-9_.-]+$，小写'),
                field('namespace（缺省挂主 mod）', nsIn, '资源/注册命名空间'),
                field('显示名（英文）', langEn),
                field('显示名（中文）', langZh),
            ),
        ];
        if (S.kind === 'item') out.push(buildItemBasic());
        else out.push(...buildBlockBasic());
        return out;
    }

    function buildItemBasic() {
        const d = S.def;
        const stack = inNum(d.stackSize == null ? 64 : d.stackSize, 1, 99);
        stack.dataset.k = 'stackSize';
        const rarity = sel([['common', 'common（白）'], ['uncommon', 'uncommon（绿）'], ['rare', 'rare（蓝）'], ['epic', 'epic（粉）']], d.rarity || 'common');
        rarity.dataset.k = 'rarity';
        const creative = chk('进创造栏（creative）', d.creative !== false, '关闭则不出现在本包创造栏');
        creative.querySelector('input').dataset.k = 'creative';
        const fire = chk('防火 fireResistant', d.fireResistant, '末地门类物品');
        fire.querySelector('input').dataset.k = 'fireResistant';
        return section('物品属性',
            field('堆叠上限 stackSize（1-99）', stack, '1.21.1 绝对上限 99（原版超 64 会报越界）'),
            field('稀有度 rarity', rarity),
            creative,
            fire,
        );
    }

    function buildBlockBasic() {
        const d = S.def;
        const m = d.material || (d.material = {});
        const hard = inNum(m.hardness == null ? 1.5 : m.hardness, -1, 10000, 0.1);
        hard.dataset.k = 'material.hardness';
        const res = inNum(m.resistance == null ? 6 : m.resistance, 0, 100000, 1);
        res.dataset.k = 'material.resistance';
        const sound = inText(m.sound || 'minecraft:block.stone', '如 minecraft:block.stone / minecraft:block.wood');
        sound.dataset.k = 'material.sound';
        const light = inNum(m.lightLevel == null ? 0 : m.lightLevel, 0, 15);
        light.dataset.k = 'material.lightLevel';
        const fric = inNum(m.friction == null ? 0.6 : m.friction, 0, 1, 0.05);
        fric.dataset.k = 'material.friction';

        const cTransparent = chk('透明 transparent（noOcclusion）', !!m.transparent);
        cTransparent.querySelector('input').dataset.k = 'material.transparent';
        const cRandom = chk('随机刻 randomTicks', !!m.randomTicks);
        cRandom.querySelector('input').dataset.k = 'material.randomTicks';
        const cTool = chk('需正确工具 requiresCorrectToolForDrops', !!m.requiresTool);
        cTool.querySelector('input').dataset.k = 'material.requiresTool';
        const cSolid = chk('实体碰撞 solid（false = noCollission）', m.solid !== false);
        cSolid.querySelector('input').dataset.k = 'material.solid';

        const matSec = section('材质与物理（→ BlockBehaviour.Properties）',
            field('硬度 hardness（-1 = 不可破坏）', hard, '挖掘时间基准（秒）'),
            field('抗爆 resistance', res, '爆炸衰减系数'),
            field('声音 sound（SoundType 名）', sound, '反射映射到 SoundType 静态常量；映射失败退回 STONE 并告警'),
            field('自发光 lightLevel（0-15）', light, '如 sea_lantern=15'),
            field('摩擦 friction（0-1）', fric, '越小越滑（冰 0.18）；1.21.1 无 slipperiness 字段'),
            cTransparent,
            cRandom,
            cTool,
            cSolid,
        );

        const beType = d.blockEntity ? d.blockEntity.type : 'none';
        const beSel = sel([['none', '无方块实体'], ['generic', 'generic（基座通用 BE）'], ['custom', 'custom（自定义 BE 类）']], beType);
        beSel.dataset.k = 'blockEntity.type';
        const beClass = inText((d.blockEntity && d.blockEntity.class) || '', '如 com.catoxide.my_pack.registry.MyBlockEntity', '须继承 BlockEntity 且有 (BlockPos, BlockState) 构造器；jar 插件类（ChildFirstClassLoader）');
        beClass.dataset.k = 'blockEntity.class';
        beClass.style.display = beType === 'custom' ? '' : 'none';
        beSel.addEventListener('change', () => { beClass.style.display = beSel.value === 'custom' ? '' : 'none'; });
        const beSec = section('方块实体',
            field('类型', beSel, '带 BE 的方块：Java 侧自动注册 BlockEntityType 并挂 BlockEntityTypeAddBlocksEvent（原版 validBlocks 白名单）'),
            field('自定义类（custom 时必填）', beClass),
            el('div', 'f-note', 'BE 类型 id 约定：<id>_be；generic = 基座内置（空存档占位，适合纯装饰带状态方块）'),
        );

        // 红石行为（可选，RedstoneLampBlock）：布尔参数受红石信号切换，亮度随状态
        const rs = (d.redstone && typeof d.redstone === 'object') ? d.redstone : {};
        const rsOn = !!rs.property;
        const boolProps = (d.properties || []).filter((p) => p && p.type === 'boolean').map((p) => p.name);
        const cRedstone = chk('红石灯行为（布尔参数受红石信号切换，行为=原版红石灯）', rsOn);
        cRedstone.querySelector('input').dataset.k = 'redstone.enabled';
        const rsPropSel = sel([['', '— 选择 boolean 参数 —'], ...boolProps.map((n) => [n, n])], rsOn ? String(rs.property) : '');
        rsPropSel.dataset.k = 'redstone.property';
        rsPropSel.style.display = rsOn ? '' : 'none';
        const rsLit = inNum(rs.lightLit == null ? 15 : rs.lightLit, 0, 15);
        rsLit.dataset.k = 'redstone.lightLit';
        const rsUnlit = inNum(rs.lightUnlit == null ? 1 : rs.lightUnlit, 0, 15);
        rsUnlit.dataset.k = 'redstone.lightUnlit';
        cRedstone.addEventListener('change', () => {
            rsPropSel.style.display = cRedstone.checked ? '' : 'none';
            onDefMutated();
        });
        if (!boolProps.length) {
            rsPropSel.style.display = 'none';
        }
        const rsSec = section('红石行为（可选，→ RedstoneLampBlock）',
            cRedstone,
            field('切换参数（须为已声明 boolean 参数）', rsPropSel,
                '无 boolean 参数时先切「形态与参数」页签声明（如目录里的 lit）；参数不合法时红石行为失效、按普通方块注册'),
            el('div', 'row',
                field('点亮亮度 lightLit（0-15）', rsLit),
                field('熄灭亮度 lightUnlit（0-15）', rsUnlit)),
            el('div', 'f-note', '行为与原版红石灯一致：邻居红石信号变化 → 点亮立即生效、熄灭延迟 4 tick（余辉）；放置初值 = 放置处有无信号。亮度按状态动态（覆盖材质静态 lightLevel）。'),
        );

        const selfDrop = chk('挖掘掉落自身（导出 loot_table/blocks/<id>.json）', !d.loot || d.loot.selfDrop !== false);
        selfDrop.querySelector('input').dataset.k = 'loot.selfDrop';
        const tagsIn = inText((d.tags || []).join(', '), '如 minecraft:mineable/pickaxe, minecraft:needs_stone_tool');
        tagsIn.dataset.k = 'tags';
        tagsIn.dataset.join = 'csv';
        const cCreative = chk('进创造栏（creative）', d.creative !== false);
        cCreative.querySelector('input').dataset.k = 'creative';
        const cBlockItem = chk('生成方块物品（blockItem，默认开）', d.blockItem !== false, '关闭则注册时跳过 BlockItem（自定义 Item 类场景）');
        cBlockItem.querySelector('input').dataset.k = 'blockItem';
        const otherSec = section('掉落 / 标签 / 创造栏',
            selfDrop,
            field('原版数据标签（逗号分隔）', tagsIn, '导出 data/<ns>/tags/block/...，同包合并写'),
            cCreative,
            cBlockItem,
        );
        return [matSec, beSec, rsSec, otherSec];
    }

    // ---------- 页签：形态与参数（block 专属） ----------
    function buildShapeSection() {
        const d = S.def;
        const wrap = el('div');
        const props = d.properties || (d.properties = []);

        const list = el('div', 'ib-prop-list');
        props.forEach((p, idx) => list.appendChild(buildPropRow(p, idx)));
        if (!props.length) {
            list.appendChild(el('div', 'f-note', '（还没有自定义参数 —— 单状态方块，blockstate 直接指默认模型）'));
        }

        const used = new Set(props.map((p) => p.name));
        const catSel = el('select', 'f-input');
        catSel.appendChild(opt('', '— 选择目录参数 —'));
        for (const spec of (S.catalog || [])) {
            if (used.has(spec.name)) continue;
            catSel.appendChild(opt(spec.name, (spec.labelZh ? spec.labelZh + ' · ' : '') + spec.name + ' (' + spec.type + ')'));
        }
        const btnAddCat = el('button', 'btn small', '＋ 从目录添加');
        btnAddCat.type = 'button';
        btnAddCat.title = '目录 = 基座内置参数（block_properties.json），Java/编辑器共用同一份';
        btnAddCat.addEventListener('click', () => {
            const spec = (S.catalog || []).find((s) => s.name === catSel.value);
            if (!spec) return;
            props.push(window.ItemBlockSchema.newPropertyFromCatalog(spec));
            renderAll();
        });
        const btnAddCustom = el('button', 'btn ghost small', '＋ 自定义参数');
        btnAddCustom.type = 'button';
        btnAddCustom.addEventListener('click', async () => {
            const name = await window.askText({ title: '自定义参数', label: '参数名（^[a-z0-9_]+$，小写蛇形，如 glow）', placeholder: 'glow' });
            if (!name) return;
            const type = await window.askChoice({ title: '参数类型', options: ['enum', 'boolean', 'int', 'direction', 'axis'], value: 'enum' });
            if (!type) return;
            const p = { name: String(name).trim(), type, default: '0' };
            if (type === 'direction' || type === 'axis' || type === 'enum') {
                p.values = type === 'axis' ? ['x', 'y', 'z'] : (type === 'direction' ? ['north', 'south'] : []);
            }
            if (type === 'int') { p.min = 0; p.max = 15; }
            if (type === 'boolean') p.default = 'false';
            if (Array.isArray(p.values) && p.values.length && p.default === '0') p.default = p.values[0];
            props.push(p);
            renderAll();
        });

        wrap.appendChild(section('自定义参数（→ StateDefinition 属性）',
            list,
            el('div', 'row', btnAddCat, catSel, btnAddCustom),
            el('div', 'f-note', '参数声明后 Java 侧自动加入 StateDefinition；取值数 ≥2（direction/axis/enum）；状态组合数 ≤4096。'),
        ));

        let count = 1;
        for (const p of props) {
            count *= p.type === 'boolean' ? 2
                : p.type === 'int' ? ((Number(p.max ?? 15)) - (Number(p.min ?? 0)) + 1)
                : (Array.isArray(p.values) && p.values.length ? p.values.length : 2);
        }
        const countNote = el('div', 'f-note', props.length ? ('当前状态组合数：' + count + (count > 4096 ? '（⚠ 超过 4096，注册会失败）' : '')) : '无参数 = 单状态方块');
        if (props.length && count > 4096) countNote.style.color = 'var(--err, #f66)';
        wrap.appendChild(countNote);

        if (props.length) {
            const bs = d.blockstates || (d.blockstates = { mode: 'variants', variants: [] });
            wrap.appendChild(buildBlockstatesEditor(bs));
        } else {
            wrap.appendChild(section('方块形态（blockstate）',
                el('div', 'f-note', '单状态方块 → assets/<ns>/blockstates/<id>.json 输出 { "variants": { "": { "model": "<ns>:block/<file>" } } }。要状态组合请添加参数。'),
            ));
        }
        return wrap;
    }

    function buildPropRow(p, idx) {
        const row = el('div', 'ib-prop-row');
        const props = S.def.properties;

        const nameIn = inText(p.name, '参数名');
        nameIn.addEventListener('change', () => { p.name = nameIn.value.trim(); renderAll(); });
        const typeSel = sel([['direction', 'direction 方向'], ['axis', 'axis 轴向'], ['boolean', 'boolean 布尔'], ['int', 'int 整数'], ['enum', 'enum 枚举']], p.type);
        typeSel.addEventListener('change', () => {
            p.type = typeSel.value;
            if (!Array.isArray(p.values) && (p.type === 'direction' || p.type === 'axis' || p.type === 'enum')) {
                p.values = p.type === 'axis' ? ['x', 'y', 'z'] : (p.type === 'direction' ? ['north', 'south'] : []);
            }
            renderAll();
        });

        const valuesBox = el('div', 'ib-prop-values');
        const rebuildValues = () => {
            valuesBox.innerHTML = '';
            if (p.type === 'direction' || p.type === 'axis' || p.type === 'enum') {
                const vi = inText((Array.isArray(p.values) ? p.values : []).join(', '), '取值，逗号分隔（≥2）');
                vi.addEventListener('input', () => {
                    p.values = vi.value.split(',').map((s) => s.trim()).filter(Boolean);
                    renderAll();
                });
                valuesBox.appendChild(field('取值', vi));
            } else if (p.type === 'int') {
                const minI = inNum(p.min == null ? 0 : p.min, 0, 1024);
                const maxI = inNum(p.max == null ? 15 : p.max, 0, 1024);
                const apply = () => { p.min = Number(minI.value || 0); p.max = Number(maxI.value || 15); renderAll(); };
                minI.addEventListener('change', apply);
                maxI.addEventListener('change', apply);
                valuesBox.appendChild(el('div', 'row', field('min', minI), field('max', maxI)));
            } else {
                valuesBox.appendChild(el('span', 'f-note', 'boolean：取值固定 true/false'));
            }
        };
        rebuildValues();

        const defIn = inText(p.default == null ? '' : p.default, '默认值（缺省 = 首取值）');
        defIn.addEventListener('input', () => { p.default = defIn.value.trim(); });

        const del = el('button', 'btn ghost small', '✕');
        del.type = 'button';
        del.title = '删除该参数';
        del.style.color = 'var(--err)';
        del.addEventListener('click', () => { props.splice(idx, 1); S.modelByCombo = {}; renderAll(); });

        row.appendChild(field('参数名', nameIn));
        row.appendChild(field('类型', typeSel));
        row.appendChild(valuesBox);
        row.appendChild(field('默认值', defIn));
        row.appendChild(del);
        return row;
    }

    function buildBlockstatesEditor(bs) {
        const modeSel = sel([['variants', 'variants（组合 → 模型，最常用）'], ['multipart', 'multipart（层叠加，栅栏/墙一类）']], bs.mode || 'variants');
        const body = el('div');
        const sec = section('方块形态定义（→ assets/<ns>/blockstates/<id>.json）',
            field('模式', modeSel),
            el('div', 'f-note', 'variants = 每个状态组合一行模型映射；multipart = 基础层 + 条件层叠加（when 命中才叠加）。'),
            body,
        );
        const rebuild = () => {
            body.innerHTML = '';
            if (modeSel.value === 'variants') {
                bs.mode = 'variants';
                buildVariantsRows(body);
            } else {
                bs.mode = 'multipart';
                buildMultipartRows(body);
            }
        };
        modeSel.addEventListener('change', rebuild);
        rebuild();
        return sec;
    }

    function buildVariantsRows(body) {
        const d = S.def;
        const props = d.properties || [];
        const combos = window.ItemBlockSchema.expandStates(props);
        const table = el('table', 'ib-table');
        const thead = el('tr');
        for (const h of ['状态组合', '模型（models/block/ 下路径或 RL）', '纹理（可选，附加模型）', 'x°', 'y°']) thead.appendChild(el('th', null, h));
        table.appendChild(thead);
        for (const combo of combos) {
            const key = window.ItemBlockSchema.stateKey(combo, props.map((p) => p.name));
            const saved = S.modelByCombo[key] || {};
            const tr = el('tr');
            tr.appendChild(el('td', 'ib-combo', Object.entries(combo).map(([k, v]) => k + '=' + v).join(', ')));
            const modelIn = inText(saved.model || baseModelFile(), baseModelFile());
            const texIn = inText(saved.texture || '', '空 = 跟随主纹理；完整 RL 如 minecraft:block/redstone_lamp_lit',
                '仅对该行引用的附加模型生效（自动生成的 models/block/<模型>.json 用这个纹理）');
            const xIn = inNum(saved.x == null ? '' : saved.x, -360, 360, 90, '0');
            const yIn = inNum(saved.y == null ? '' : saved.y, -360, 360, 90, '0');
            tr.appendChild(el('td', null, modelIn));
            tr.appendChild(el('td', null, texIn));
            tr.appendChild(el('td', null, xIn));
            tr.appendChild(el('td', null, yIn));
            table.appendChild(tr);
            const sync = () => {
                S.modelByCombo[key] = { model: modelIn.value, texture: texIn.value, x: xIn.value, y: yIn.value };
            };
            modelIn.addEventListener('input', sync);
            texIn.addEventListener('input', sync);
            xIn.addEventListener('input', sync);
            yIn.addEventListener('input', sync);
        }
        body.appendChild(table);
        // 同步回 S.def.blockstates.variants（供保存/预览用）
        d.blockstates.variants = combos.map((combo) => {
            const key = window.ItemBlockSchema.stateKey(combo, props.map((p) => p.name));
            const saved = S.modelByCombo[key] || {};
            const row = { when: Object.assign({}, combo), model: saved.model || baseModelFile() };
            if (saved.texture !== undefined && saved.texture !== null && String(saved.texture).trim() !== '') {
                row.texture = String(saved.texture).trim();
            }
            if (saved.x !== undefined && saved.x !== null && saved.x !== '') row.x = Number(saved.x);
            if (saved.y !== undefined && saved.y !== null && saved.y !== '') row.y = Number(saved.y);
            return row;
        });
    }

    function buildMultipartRows(body) {
        const d = S.def;
        const table = el('table', 'ib-table');
        const thead = el('tr');
        for (const h of ['when 条件（prop=val 逗号分隔；空 = 基础层）', '模型', '']) thead.appendChild(el('th', null, h));
        table.appendChild(thead);
        S.multipart.forEach((r, idx) => {
            const tr = el('tr');
            const whenIn = inText(r.when || '', '如 facing=north');
            const modelIn = inText(r.model || baseModelFile(), baseModelFile());
            tr.appendChild(el('td', null, whenIn));
            tr.appendChild(el('td', null, modelIn));
            const del = el('button', 'btn ghost small', '✕');
            del.type = 'button';
            del.style.color = 'var(--err)';
            del.addEventListener('click', () => { S.multipart.splice(idx, 1); renderAll(); });
            tr.appendChild(el('td', null, del));
            whenIn.addEventListener('input', () => { r.when = whenIn.value; });
            modelIn.addEventListener('input', () => { r.model = modelIn.value; });
            table.appendChild(tr);
        });
        body.appendChild(table);
        const btnAdd = el('button', 'btn small', '＋ 加一层');
        btnAdd.type = 'button';
        btnAdd.addEventListener('click', () => { S.multipart.push({ when: '', model: baseModelFile() }); renderAll(); });
        body.appendChild(el('div', 'row', btnAdd));
        // 同步回 S.def
        d.blockstates.multipart = S.multipart.map((r) => {
            const entry = { model: r.model };
            const when = {};
            for (const part of String(r.when || '').split(',')) {
                const m = part.trim().match(/^([a-z0-9_]+)=(.+)$/);
                if (m) when[m[1]] = m[2];
            }
            if (Object.keys(when).length) entry.when = when;
            return entry;
        });
    }

    // ---------- 页签：模型 ----------
    function buildModelSection() {
        const d = S.def;
        const m = d.model || (d.model = {});
        const isBlock = S.kind === 'block';
        const types = isBlock
            ? [['cube_all', 'cube_all（六面同纹）'], ['cube', 'cube（上/侧/下 可分）'], ['generated', 'generated（平铺单层，像物品）'], ['raw', 'raw（手写完整模型 JSON）']]
            : [['generated', 'generated（平铺单层）'], ['raw', 'raw（手写完整模型 JSON）']];

        const typeSel = sel(types, m.type || (isBlock ? 'cube_all' : 'generated'));
        typeSel.dataset.k = 'model.type';
        const texIn = inText(m.texture || '', isBlock ? 'block/<id>' : 'item/<id>', '贴图相对路径（assets/<ns>/textures/ 下），支持完整 RL');
        texIn.dataset.k = 'model.texture';
        const fileIn = inText(m.file || '', '缺省 = id', '模型文件名（models/block/<file>.json）');
        fileIn.dataset.k = 'model.file';
        fileIn.style.display = isBlock ? '' : 'none';
        const itemFileIn = inText(m.itemFile || '', '缺省 = 模型文件名',
            '方块物品模型指向的方块模型（红石灯一类：物品显示点亮态 → 填 lit 模型文件名）');
        itemFileIn.dataset.k = 'model.itemFile';
        itemFileIn.style.display = isBlock ? '' : 'none';

        const topIn = inText(m.top || '', '缺省 = texture');
        topIn.dataset.k = 'model.top';
        const sideIn = inText(m.side || '', '缺省 = texture');
        sideIn.dataset.k = 'model.side';
        const bottomIn = inText(m.bottom || '', '缺省 = texture');
        bottomIn.dataset.k = 'model.bottom';
        const cubeRow = el('div', 'row', field('top', topIn), field('side', sideIn), field('bottom', bottomIn));
        cubeRow.style.display = (m.type === 'cube') ? '' : 'none';

        const rawTa = el('textarea', 'f-input ib-raw');
        rawTa.rows = 12;
        rawTa.placeholder = '{"parent":"minecraft:block/cube_all","textures":{"all":"<ns>:block/<id>"}}';
        try { rawTa.value = m.raw ? JSON.stringify(m.raw, null, 2) : ''; } catch { rawTa.value = ''; }
        rawTa.style.display = (m.type === 'raw') ? '' : 'none';
        rawTa.addEventListener('change', () => {
            try { m.raw = JSON.parse(rawTa.value || '{}'); statusMsg('raw 模型 JSON 已解析', true); }
            catch (e) { window.showError('raw 模型 JSON 解析失败: ' + e.message); }
        });

        typeSel.addEventListener('change', () => {
            m.type = typeSel.value;
            cubeRow.style.display = m.type === 'cube' ? '' : 'none';
            rawTa.style.display = m.type === 'raw' ? '' : 'none';
            onDefMutated();
        });

        const wrap = el('div');
        wrap.appendChild(section('模型（→ assets/<ns>/models/' + (isBlock ? 'block' : 'item') + '/）',
            field('类型', typeSel),
            field('纹理', texIn),
            isBlock ? field('模型文件名', fileIn) : null,
            isBlock ? field('物品模型指向（可选）', itemFileIn) : null,
            cubeRow,
            rawTa,
            el('div', 'f-note', isBlock
                ? '方块模型文件：assets/<ns>/models/block/<文件名>.json；方块物品模型自动 parent 指向它。'
                : '物品模型：assets/<ns>/models/item/<id>.json（parent=minecraft:item/generated + layer0）。'),
        ));
        wrap.appendChild(section('预览',
            pre(isBlock
                ? window.ItemBlockSchema.buildBlockModelJson(d)
                : window.ItemBlockSchema.buildItemModelJson(d, 'item')),
        ));
        return wrap;
    }

    // ---------- 页签：预览 ----------
    function buildPreviewSection() {
        const d = S.def;
        const wrap = el('div');
        if (S.kind === 'block') {
            wrap.appendChild(section('blockstate（完整方块形态 → assets/<ns>/blockstates/<id>.json）',
                pre(window.ItemBlockSchema.buildBlockstatesJson(d)),
            ));
            wrap.appendChild(section('方块模型（assets/<ns>/models/block/）',
                pre(window.ItemBlockSchema.buildBlockModelJson(d)),
            ));
            if (d.blockItem !== false) {
                wrap.appendChild(section('方块物品模型（assets/<ns>/models/item/<id>.json）',
                    pre(window.ItemBlockSchema.buildItemModelJson(d, 'block')),
                ));
            }
            if (!d.loot || d.loot.selfDrop !== false) {
                wrap.appendChild(section('掉落表（data/<ns>/loot_table/blocks/<id>.json）',
                    pre({ type: 'minecraft:block', pools: [{ rolls: 1.0, bonus_rolls: 0.0, entries: [{ type: 'minecraft:item', name: (d.namespace || '') + ':' + d.id }] }] }),
                ));
            }
        } else {
            wrap.appendChild(section('物品模型（assets/<ns>/models/item/<id>.json）',
                pre(window.ItemBlockSchema.buildItemModelJson(d, 'item')),
            ));
        }
        const check = S.kind === 'block' ? window.ItemBlockSchema.validateBlock(d) : window.ItemBlockSchema.validateItem(d);
        const box = el('div', 'ib-section');
        box.appendChild(el('h3', 'ib-sec-title', '校验（与基座注册器一致）'));
        if (check.valid) {
            box.appendChild(el('div', 'f-note', '✓ 通过 —— Java 侧 BatchContentRegistrar 将正常注册'));
        } else {
            for (const e of check.errors) box.appendChild(el('div', 'ib-check-err', '✗ ' + e));
        }
        wrap.appendChild(box);
        return wrap;
    }

    // ---------- 采集 + 保存/导出/删除 ----------
    function collect() {
        const d = S.def;
        d.id = String(d.id || '').trim();
        d.namespace = String(d.namespace || '').trim();
        d.lang = {
            en_us: String((d.lang && d.lang.en_us) || '').trim(),
            zh_cn: String((d.lang && d.lang.zh_cn) || '').trim(),
        };
        if (S.kind === 'block') {
            if (Array.isArray(d.tags)) d.tags = d.tags.map((s) => String(s).trim()).filter(Boolean);
            else d.tags = String(d.tags || '').split(',').map((s) => s.trim()).filter(Boolean);
            if (!d.blockEntity || d.blockEntity.type === 'none') d.blockEntity = null;
            else if (d.blockEntity.type === 'custom' && String(d.blockEntity.class || '').trim() === '') d.blockEntity = null;
            // 红石行为：UI 字段 redstone.enabled（复选框）→ 正式结构 {property, lightLit, lightUnlit} | null
            {
                const rs = (d.redstone && typeof d.redstone === 'object') ? d.redstone : {};
                if (rs.enabled && rs.property) {
                    d.redstone = {
                        property: String(rs.property).trim(),
                        lightLit: (rs.lightLit === '' || rs.lightLit == null) ? 15 : Number(rs.lightLit),
                        lightUnlit: (rs.lightUnlit === '' || rs.lightUnlit == null) ? 1 : Number(rs.lightUnlit),
                    };
                } else {
                    d.redstone = null;
                }
            }
            if (Array.isArray(d.properties)) {
                d.properties = d.properties.filter((p) => p && String(p.name || '').trim() !== '');
                for (const p of d.properties) {
                    if (p.type === 'direction' || p.type === 'axis' || p.type === 'enum') {
                        p.values = String(Array.isArray(p.values) ? p.values.join(',') : (p.values || '')).split(',').map((s) => s.trim()).filter(Boolean);
                    }
                }
                if (d.blockstates && d.blockstates.mode === 'variants' && d.properties.length) {
                    const props = d.properties;
                    d.blockstates.variants = props.length ? (d.blockstates.variants && d.blockstates.variants.length ? d.blockstates.variants : undefined) : [];
                    if (!d.blockstates.variants) {
                        // 未进过形态页：按默认模型补齐
                        S.modelByCombo = {};
                        buildVariantsRows(el('div'));
                        d.blockstates.variants = S.def.blockstates.variants;
                    }
                } else if (!d.properties.length) {
                    d.blockstates = { mode: 'variants', variants: [] };
                }
            }
        }
        for (const k of Object.keys(d)) if (d[k] === undefined) delete d[k];
        return d;
    }

    async function save() {
        if (!S.def || !S.pack) return;
        const d = collect();
        const check = S.kind === 'block' ? window.ItemBlockSchema.validateBlock(d) : window.ItemBlockSchema.validateItem(d);
        if (!check.valid) {
            window.showError((S.kind === 'block' ? '方块' : '物品') + '校验失败:\n' + check.errors.join('\n'));
            S.tab = 'preview';
            renderAll();
            return;
        }
        try {
            const r = await window.api.saveItemBlock(S.pack.dir, S.kind, d);
            if (!r.ok) { window.showError('保存失败: ' + r.error); return; }
            S.isStaged = true;
            S.isNew = false;
            renderTitle();
            statusMsg('已保存草稿 ' + (S.kind === 'block' ? '方块' : '物品') + ' ' + d.id + '（「导出」写入正式包 + 原版文件）', true);
            if (S.handlers.onListChanged) S.handlers.onListChanged();
        } catch (e) {
            window.showError('保存失败: ' + String(e.message || e));
        }
    }

    async function exportVanilla() {
        if (!S.def || !S.pack) return;
        const d = collect();
        const id = d.id;
        if (!id) { window.showError('先填 id 并保存'); return; }
        // 未保存的草稿也要能导出：先确保已暂存
        try {
            const r = await window.api.exportItemBlockVanilla(S.pack.dir, S.kind, id);
            const rep = document.getElementById('itemBlockReport');
            if (r.ok) {
                const files = (r.data && r.data.files) || [];
                if (rep) {
                    rep.style.display = 'block';
                    rep.textContent = '=== 导出原版（' + id + '）===\n' + files.map((f) => '  ✔ ' + f).join('\n');
                }
                statusMsg('已导出原版文件 ' + files.length + ' 个（' + id + '）', true);
            } else {
                if (rep) { rep.style.display = 'block'; rep.textContent = '✗ ' + r.error; }
                window.showError('导出失败: ' + r.error);
            }
        } catch (e) {
            window.showError('导出失败: ' + String(e.message || e));
        }
    }

    async function remove() {
        if (!S.def || !S.pack) return;
        const id = S.def.id;
        if (!id) return;
        if (!confirm('确认删除' + (S.kind === 'block' ? '方块' : '物品') + ' ' + id + '？（正式包 + 草稿）')) return;
        try {
            const r = await window.api.deleteItemBlock(S.pack.dir, S.kind, id);
            if (!r.ok) { window.showError('删除失败: ' + r.error); return; }
            statusMsg('已删除 ' + id, true);
            close();
            if (S.handlers.onListChanged) S.handlers.onListChanged();
        } catch (e) {
            window.showError('删除失败: ' + String(e.message || e));
        }
    }

    // ---------- 初始化 ----------
    function init(handlers) {
        S.handlers = handlers || {};
        const saveBtn = document.getElementById('btnItemBlockSave');
        const expBtn = document.getElementById('btnItemBlockExport');
        const delBtn = document.getElementById('btnItemBlockDelete');
        if (saveBtn) saveBtn.addEventListener('click', save);
        if (expBtn) expBtn.addEventListener('click', exportVanilla);
        if (delBtn) delBtn.addEventListener('click', remove);
    }

    return { open, close, hasActiveTab, init };
})();
