// itemBlockSchema.js - 物品/方块定义契约（纯函数，单一真源）
//
// 对应 Java 权威实现（规则不是编辑器的意见，而是基座批量注册器的真实行为）：
//   src/main/java/.../core/registry/BatchContentRegistrar.java   —— 校验/注册行为
//   src/main/java/.../core/registry/ItemBlockParser.java         —— JSON 解析（缺省兜底）
//   src/main/java/.../core/registry/BlockPropertyCatalog.java    —— 参数目录/Property 构建
//   src/main/resources/assets/catoxidesbattlerebuild/editor/block_properties.json
//                                                                 —— 参数目录（单一真源，Java+编辑器共读）
//
// 职责：
//   1. 参数目录（BUILTIN_CATALOG 兜底；运行期由 main.js 读资源文件覆盖）
//   2. 物品/方块定义校验（镜像 BatchContentRegistrar.validateItem/validateBlock）
//   3. 方块形态：状态空间展开（笛卡尔积）+ 1.21.1 blockstate JSON 生成
//   4. 模型 JSON 生成（block model / item model）
//
// 本文件整体包在 IIFE 里（经典 <script> 共享全局词法环境，重名即整个脚本失效）。
(function () {
    'use strict';

    // ============================================================
    // 参数目录（兜底副本 —— 与主 mod 资源文件 block_properties.json 同步）
    // ============================================================

    const BUILTIN_CATALOG = [
        { name: 'facing', type: 'direction', values: ['north', 'south', 'west', 'east', 'up', 'down'], default: 'north', labelZh: '朝向', description: '六向朝向（furnace/campfire 一类）' },
        { name: 'horizontal_facing', type: 'direction', values: ['north', 'south', 'west', 'east'], default: 'north', labelZh: '水平朝向', description: '水平四向（barrel/ladder/door 一类）' },
        { name: 'axis', type: 'axis', values: ['x', 'y', 'z'], default: 'y', labelZh: '轴向', description: '立方体轴向（iron_bars/ladder 一类）' },
        { name: 'waterlogged', type: 'boolean', default: 'false', labelZh: '含水', description: '是否被水充满（fence/glass 一类）' },
        { name: 'lit', type: 'boolean', default: 'false', labelZh: '点亮', description: '点燃状态（campfire/torch 一类）' },
        { name: 'open', type: 'boolean', default: 'false', labelZh: '打开', description: '打开状态（door/chest 一类）' },
        { name: 'powered', type: 'boolean', default: 'false', labelZh: '充能', description: '红石充能（lever/button 一类）' },
        { name: 'enabled', type: 'boolean', default: 'true', labelZh: '启用', description: '启用状态（command block 一类）' },
        { name: 'snowy', type: 'boolean', default: 'false', labelZh: '积雪', description: '是否有雪（fence 一类）' },
        { name: 'short', type: 'boolean', default: 'false', labelZh: '低矮', description: '低矮状态（grass 一类）' },
        { name: 'half', type: 'enum', values: ['bottom', 'top'], default: 'bottom', labelZh: '半块', description: '上下半块（slab 一类）' },
        { name: 'shape', type: 'enum', values: ['straight', 'east_west', 'north_south', 'ascending_east', 'ascending_west', 'ascending_north', 'ascending_south'], default: 'straight', labelZh: '形状', description: '楼梯形状（stairs 一类）' },
        { name: 'level', type: 'int', min: 0, max: 15, default: '0', labelZh: '等级', description: '0..15 数值等级（通用档位参数）' },
    ];

    // 运行期目录（main.js 读主 mod 资源文件后 setCatalog；未设置时用 BUILTIN）
    let runtimeCatalog = null;

    /** 设置运行期参数目录（main 进程读到 block_properties.json 后注入） */
    function setCatalog(specs) {
        runtimeCatalog = Array.isArray(specs) && specs.length ? specs : null;
    }

    /** 当前生效的目录条目 */
    function catalogSpecs() {
        return runtimeCatalog || BUILTIN_CATALOG;
    }

    function findCatalogSpec(name) {
        return catalogSpecs().find((s) => s.name === name) || null;
    }

    // ============================================================
    // 标识符规则（与 ResourceLocation / Java 侧一致）
    // ============================================================

    const ID_RE = /^[a-z0-9_.\-]+$/;
    const NS_RE = /^[a-z0-9_.\-]+$/;
    const FQCN_RE = /^[A-Za-z_$][A-Za-z0-9_$.]*$/;
    const PROP_NAME_RE = /^[a-z0-9_]+$/; // 原版 StateDefinition.NAME_PATTERN
    const SOUND_RE = /^[a-z0-9_.\-]+(?::[a-z0-9_.\-]+)?$/;
    const RARITIES = ['common', 'uncommon', 'rare', 'epic'];
    const PROP_TYPES = ['direction', 'axis', 'boolean', 'int', 'enum'];
    const MODEL_TYPES = ['cube_all', 'cube', 'generated', 'raw'];
    const TEX_RE = /^[a-z0-9_.\-]+(?::[a-z0-9_.\-]+)?\/[a-z0-9_\-/]+$/; // 纹理路径：ns:path 或 相对路径（可带子目录）
    const MAX_STACK_SIZE = 99; // 1.21.1 Item.ABSOLUTE_MAX_STACK_SIZE
    const MAX_STATES = 4096;   // 状态空间爆炸保护（与 Java 侧一致）

    // ============================================================
    // 校验（镜像 BatchContentRegistrar）
    // ============================================================

    function validateItem(def) {
        const errors = [];
        if (!def || typeof def !== 'object') return { valid: false, errors: ['空定义'] };
        if (!def.id || !ID_RE.test(String(def.id))) {
            errors.push('id 非法（必须 ^[a-z0-9_.\\-]+$，小写）: ' + (def.id || '(空)'));
        }
        if (def.namespace && String(def.namespace) !== '' && !NS_RE.test(String(def.namespace))) {
            errors.push('namespace 非法: ' + def.namespace);
        }
        if (def.stackSize !== undefined && def.stackSize !== null && String(def.stackSize) !== '') {
            const n = Number(def.stackSize);
            if (!Number.isInteger(n) || n < 1 || n > MAX_STACK_SIZE) {
                errors.push('stackSize 必须是 1..' + MAX_STACK_SIZE + ' 的整数: ' + def.stackSize);
            }
        }
        if (def.rarity && !RARITIES.includes(String(def.rarity))) {
            errors.push('rarity 非法（common|uncommon|rare|epic）: ' + def.rarity);
        }
        return { valid: errors.length === 0, errors };
    }

    function validateProperty(spec, ctx) {
        const errors = [];
        if (!spec || !spec.name) { errors.push((ctx || '参数') + ' 缺少 name'); return errors; }
        if (!PROP_NAME_RE.test(spec.name)) {
            errors.push(`参数名 '${spec.name}' 非法（必须 ^[a-z0-9_]+$，小写蛇形）`);
            return errors;
        }
        if (!PROP_TYPES.includes(spec.type)) {
            errors.push(`参数 '${spec.name}' 类型非法（${PROP_TYPES.join('|')}）: ${spec.type}`);
            return errors;
        }
        const label = `参数 '${spec.name}'`;
        if (spec.type === 'direction' || spec.type === 'axis' || spec.type === 'enum') {
            const values = Array.isArray(spec.values) ? spec.values : [];
            if (values.length < 2) errors.push(`${label} 需要 ≥2 个取值（原版拒绝 ≤1 取值的属性）`);
            for (const v of values) {
                if (typeof v !== 'string' || !PROP_NAME_RE.test(v)) errors.push(`${label} 取值 '${v}' 非法（必须 ^[a-z0-9_]+$）`);
            }
            if (spec.type === 'direction') {
                for (const v of values) {
                    if (!['north', 'south', 'west', 'east', 'up', 'down'].includes(v)) {
                        errors.push(`${label} 方向取值非法: ${v}`);
                    }
                }
            }
            if (spec.type === 'axis') {
                for (const v of values) {
                    if (!['x', 'y', 'z'].includes(v)) errors.push(`${label} 轴向取值非法: ${v}`);
                }
            }
            if (spec.default !== undefined && spec.default !== null && String(spec.default) !== ''
                && !values.includes(String(spec.default))) {
                errors.push(`${label} 默认值 '${spec.default}' 不在取值集合里`);
            }
        } else if (spec.type === 'int') {
            const min = Number(spec.min ?? 0), max = Number(spec.max ?? 15);
            if (!Number.isInteger(min) || !Number.isInteger(max) || max <= min) {
                errors.push(`${label} int 范围非法：max 必须 > min ≥ 0`);
            }
            if (spec.default !== undefined && spec.default !== null && String(spec.default) !== '') {
                const d = Number(spec.default);
                if (!Number.isInteger(d) || d < min || d > max) {
                    errors.push(`${label} 默认值 '${spec.default}' 不在 ${min}..${max} 内`);
                }
            }
        }
        return errors;
    }

    function countStates(properties) {
        let n = 1;
        for (const spec of properties) {
            const v = spec.type === 'boolean' ? 2
                : spec.type === 'int' ? (Number(spec.max ?? 15) - Number(spec.min ?? 0) + 1)
                : (Array.isArray(spec.values) && spec.values.length ? spec.values.length : 2);
            n *= v;
            if (n > MAX_STATES) return n;
        }
        return n;
    }

    function validateBlock(def) {
        const errors = [];
        if (!def || typeof def !== 'object') return { valid: false, errors: ['空定义'] };
        if (!def.id || !ID_RE.test(String(def.id))) {
            errors.push('id 非法（必须 ^[a-z0-9_.\\-]+$，小写）: ' + (def.id || '(空)'));
        }
        if (def.namespace && String(def.namespace) !== '' && !NS_RE.test(String(def.namespace))) {
            errors.push('namespace 非法: ' + def.namespace);
        }
        const m = def.material || {};
        if (m.lightLevel !== undefined && m.lightLevel !== null && String(m.lightLevel) !== '') {
            const n = Number(m.lightLevel);
            if (!Number.isInteger(n) || n < 0 || n > 15) errors.push('lightLevel 超出 0..15: ' + m.lightLevel);
        }
        if (m.hardness !== undefined && m.hardness !== null && String(m.hardness) !== '') {
            const n = Number(m.hardness);
            if (!Number.isFinite(n) || n < -1) errors.push('hardness 不能小于 -1（-1 = 不可破坏）: ' + m.hardness);
        }
        if (m.sound && !SOUND_RE.test(String(m.sound))) {
            errors.push('sound 非法 ResourceLocation: ' + m.sound);
        }
        const props = Array.isArray(def.properties) ? def.properties : [];
        for (const spec of props) errors.push(...validateProperty(spec, '参数'));
        const states = countStates(props);
        if (states > MAX_STATES) {
            errors.push(`状态空间超过 ${MAX_STATES} 种组合（参数取值过多），请减少参数取值`);
        }
        if (def.blockEntity) {
            const be = def.blockEntity;
            if (be.type !== 'generic' && be.type !== 'custom') {
                errors.push(`blockEntity.type 必须是 generic 或 custom: ${be.type}`);
            } else if (be.type === 'custom' && (!be.class || !FQCN_RE.test(String(be.class)))) {
                errors.push('blockEntity.type=custom 需要合法的全限定类名 class（须继承 BlockEntity 且有 (BlockPos, BlockState) 构造器）');
            }
        }
        // 红石灯行为（可选）：property 必须指向已声明的 boolean 参数；亮度 0..15
        // （镜像 BatchContentRegistrar：不满足时红石行为失效、按普通方块注册）
        if (def.redstone) {
            const rs = def.redstone;
            if (!rs.property || !PROP_NAME_RE.test(String(rs.property))) {
                errors.push('redstone.property 必须是合法参数名（^[a-z0-9_]+$）: ' + (rs.property || '(空)'));
            } else {
                const spec = (Array.isArray(def.properties) ? def.properties : [])
                    .find((p) => p && p.name === rs.property);
                if (!spec) {
                    errors.push(`redstone 行为指向的参数 '${rs.property}' 未声明（需先在 properties 声明同名 boolean 参数）`);
                } else if (spec.type !== 'boolean') {
                    errors.push(`redstone 行为指向的参数 '${rs.property}' 必须是 boolean（当前 ${spec.type}）`);
                }
            }
            for (const k of ['lightLit', 'lightUnlit']) {
                if (rs[k] !== undefined && rs[k] !== null && String(rs[k]) !== '') {
                    const n = Number(rs[k]);
                    if (!Number.isInteger(n) || n < 0 || n > 15) errors.push(`redstone.${k} 超出 0..15: ${rs[k]}`);
                }
            }
        }
        // 模型（导出需要；Java 侧不消费，纯编辑器契约）
        if (def.model && !MODEL_TYPES.includes(def.model.type)) {
            errors.push('model.type 非法（' + MODEL_TYPES.join('|') + '）: ' + def.model.type);
        }
        // blockstates（完整方块形态定义 → 原版 blockstate 文件）
        errors.push(...validateBlockstates(def, props));
        return { valid: errors.length === 0, errors };
    }

    function validateBlockstates(def, props) {
        const errors = [];
        // 无参数 = 单状态方块：blockstate 固定 {model: 默认模型}，无需（也不校验）blockstates 定义
        if (!props.length) return errors;
        const bs = def.blockstates;
        if (!bs || typeof bs !== 'object' || (bs.mode !== 'variants' && bs.mode !== 'multipart')) {
            errors.push('有自定义参数时必须提供 blockstates（方块形态：状态组合 → 模型映射）');
            return errors;
        }
        const known = new Map(props.map((p) => [p.name, p]));
        const checkWhen = (when, ctx) => {
            if (!when) return;
            for (const [k, v] of Object.entries(when)) {
                const spec = known.get(k);
                if (!spec) { errors.push(`${ctx} 引用了未定义的参数 '${k}'`); continue; }
                const allowed = spec.type === 'boolean' ? ['true', 'false']
                    : spec.type === 'int'
                        ? Array.from({ length: Number(spec.max ?? 15) - Number(spec.min ?? 0) + 1 }, (_, i) => String(Number(spec.min ?? 0) + i))
                        : (Array.isArray(spec.values) ? spec.values : []);
                if (!allowed.includes(String(v))) errors.push(`${ctx} 的 ${k}='${v}' 不在取值集合里`);
            }
        };
        if (bs.mode === 'variants') {
            const rows = Array.isArray(bs.variants) ? bs.variants : [];
            const covered = new Set();
            rows.forEach((r, i) => {
                const ctx = `blockstates.variants[${i}]`;
                if (!r || !r.model || String(r.model).trim() === '') {
                    errors.push(`${ctx} 缺少 model`);
                }
                const when = r.when || {};
                checkWhen(when, ctx);
                covered.add(stateKey(when, props.map((p) => p.name)));
                if (r.x !== undefined && !isRotation90(r.x)) errors.push(`${ctx}.x 必须是 90 的倍数: ${r.x}`);
                if (r.y !== undefined && !isRotation90(r.y)) errors.push(`${ctx}.y 必须是 90 的倍数: ${r.y}`);
                if (r.texture !== undefined && r.texture !== null && String(r.texture) !== ''
                    && !TEX_RE.test(String(r.texture))) {
                    errors.push(`${ctx}.texture 是非法纹理路径（ns:path 或 block/xxx）: ${r.texture}`);
                }
            });
            // 完整覆盖检查（全部笛卡尔积）
            const full = expandStates(props);
            for (const combo of full) {
                if (!covered.has(stateKey(combo, props.map((p) => p.name)))) {
                    errors.push('blockstates 未覆盖状态组合: ' + stateKey(combo, props.map((p) => p.name)));
                }
            }
        } else { // multipart
            const rows = Array.isArray(bs.multipart) ? bs.multipart : [];
            if (rows.length === 0) errors.push('blockstates.multipart 不能为空');
            let hasBase = false;
            rows.forEach((r, i) => {
                const ctx = `blockstates.multipart[${i}]`;
                if (!r || !r.model || String(r.model).trim() === '') errors.push(`${ctx} 缺少 model`);
                checkWhen(r.when, ctx);
                if (!r.when || Object.keys(r.when).length === 0) hasBase = true;
            });
            if (props.length > 0 && !hasBase) {
                errors.push('multipart 建议包含一个无条件基础层（不带 when 的 apply）');
            }
        }
        return errors;
    }

    function isRotation90(n) {
        const v = Number(n);
        return Number.isInteger(v) && v % 90 === 0;
    }

    // ============================================================
    // 状态空间展开 + blockstate JSON（1.21.1）
    // ============================================================

    /** 参数取值笛卡尔积（按属性声明序） */
    function expandStates(properties) {
        const props = properties || [];
        let combos = [{}];
        for (const spec of props) {
            const values = spec.type === 'boolean' ? ['true', 'false']
                : spec.type === 'int'
                    ? Array.from({ length: Number(spec.max ?? 15) - Number(spec.min ?? 0) + 1 }, (_, i) => String(Number(spec.min ?? 0) + i))
                    : (Array.isArray(spec.values) ? spec.values : []);
            const next = [];
            for (const combo of combos) {
                for (const v of values) {
                    next.push(Object.assign({}, combo, { [spec.name]: String(v) }));
                }
            }
            combos = next;
            if (combos.length > MAX_STATES * 2) {
                throw new Error('状态空间超过 ' + MAX_STATES + ' 种组合，拒绝展开');
            }
        }
        return combos;
    }

    /** 组合 → blockstate 键（"facing=north,waterlogged=false"，按属性声明序） */
    function stateKey(assignment, propNames) {
        return (propNames || []).filter((k) => assignment[k] !== undefined)
            .map((k) => k + '=' + assignment[k]).join(',');
    }

    /**
     * 生成 1.21.1 blockstate 文件内容（assets/<ns>/blockstate/<id>.json 的 JSON 对象）。
     * 对照原版 1.21.1 格式：
     *   单状态   → { "model": "ns:..." }
     *   variants → { "variants": { "k=v,k2=v2": "ns:..." | {"model","x","y"} } }
     *   multipart→ { "multipart": [ {"when":{...},"apply":{"model",...}}, {"apply":{...}} ] }
     */
    function buildBlockstatesJson(def) {
        const ns = def.namespace || '';
        const props = Array.isArray(def.properties) ? def.properties : [];
        const bs = def.blockstates;
        const modelRef = (m) => normalizeModelRef(m, ns, 'block');
        const baseModel = (def.model && def.model.file) ? def.model.file : def.id;
        const blockModelRef = () => modelRef(baseModel);

        if (!props.length) {
            // 单状态方块
            if (bs && bs.mode === 'variants' && Array.isArray(bs.variants) && bs.variants.length) {
                const r = bs.variants[0];
                return variantObject(r, modelRef);
            }
            return { model: blockModelRef() };
        }

        if (bs && bs.mode === 'multipart' && Array.isArray(bs.multipart)) {
            // 1.21.1 multipart 的 apply 必须是对象 {model, x?, y?}（与 variants 的字符串简写不同）
            const multipart = bs.multipart.filter((r) => r && r.model).map((r) => {
                const apply = { model: modelRef(r.model) };
                if (r.x !== undefined && r.x !== null && Number(r.x) !== 0) apply.x = Number(r.x);
                if (r.y !== undefined && r.y !== null && Number(r.y) !== 0) apply.y = Number(r.y);
                const entry = {};
                if (r.when && Object.keys(r.when).length) entry.when = r.when;
                entry.apply = apply;
                return entry;
            });
            return { multipart };
        }

        // variants（默认）：编辑器保证完整覆盖（校验层强制）
        const variants = {};
        const rows = (bs && Array.isArray(bs.variants) && bs.variants.length ? bs.variants
            : expandStates(props).map((combo) => ({ when: combo, model: baseModel })));
        for (const r of rows) {
            const key = stateKey(r.when || {}, props.map((p) => p.name));
            variants[key] = variantObject({ model: r.model || baseModel, x: r.x, y: r.y }, modelRef);
        }
        return { variants };
    }

    /** variant 值：无旋转 = 模型字符串；有旋转 = 对象（对照原版 blockstate 文件） */
    function variantObject(row, modelRef) {
        const model = modelRef(row.model);
        if ((row.x === undefined || row.x === null || row.x === 0)
            && (row.y === undefined || row.y === null || row.y === 0)) {
            return model;
        }
        const obj = { model };
        if (row.x !== undefined && row.x !== null && row.x !== 0) obj.x = Number(row.x);
        if (row.y !== undefined && row.y !== null && row.y !== 0) obj.y = Number(row.y);
        return obj;
    }

    /**
     * 模型引用归一：
     *   完整 RL（ns:path）→ 原样；
     *   带 block/ 或 item/ 前缀的相对路径 → 补 ns 前缀；
     *   裸名 → 当作 models/<kind>/<裸名>。
     */
    function normalizeModelRef(ref, ns, kind) {
        const s = String(ref || '').trim();
        if (!s) return '';
        if (s.includes(':')) return s;
        if (/^(block|item)\//.test(s)) return (ns ? ns + ':' : '') + s;
        return (ns ? ns + ':' : '') + kind + '/' + s;
    }

    // ============================================================
    // 模型 JSON（1.21.1）
    // ============================================================

    /**
     * 方块模型 JSON（assets/<ns>/models/block/<file>.json）。
     * model: { type: cube_all|cube|generated|raw, texture?, top?, side?, bottom?, raw? }
     */
    function buildBlockModelJson(def) {
        const ns = def.namespace || '';
        const m = def.model || {};
        const tex = (p) => (p ? (String(p).includes(':') ? p : ns + ':' + p) : null);
        const file = m.file || def.id;
        switch (m.type || 'cube_all') {
            case 'cube':
                return {
                    parent: 'minecraft:block/cube',
                    textures: {
                        side: tex(m.side || m.texture || 'block/' + file),
                        top: tex(m.top || m.texture || 'block/' + file),
                        bottom: tex(m.bottom || m.texture || 'block/' + file),
                    },
                };
            case 'generated':
                return {
                    parent: 'minecraft:item/generated',
                    textures: { layer0: tex(m.texture || 'block/' + file) },
                };
            case 'raw':
                if (!m.raw || typeof m.raw !== 'object') {
                    throw new Error('model.type=raw 需要 raw 对象（完整模型 JSON）');
                }
                return m.raw;
            case 'cube_all':
            default:
                return {
                    parent: 'minecraft:block/cube_all',
                    textures: { all: tex(m.texture || 'block/' + file) },
                };
        }
    }

    /**
     * 物品模型 JSON（assets/<ns>/models/item/<id>.json）。
     * 方块物品 → 指向方块模型；纯物品 → generated + layer0 纹理。
     */
    function buildItemModelJson(def, kind) {
        const ns = def.namespace || '';
        if (kind === 'block') {
            // 方块物品模型指向的方块模型文件（可选 itemFile；缺省 = 主模型文件。
            // 红石灯一类：物品显示点亮态 → itemFile 指向 lit 模型）
            const file = (def.model && def.model.itemFile) ? def.model.itemFile
                : ((def.model && def.model.file) ? def.model.file : def.id);
            return { parent: (ns ? ns + ':' : '') + 'block/' + file };
        }
        const m = def.model || {};
        if (m.type === 'raw' && m.raw && typeof m.raw === 'object') return m.raw;
        const tex = m.texture || 'item/' + def.id;
        return {
            parent: 'minecraft:item/generated',
            textures: { layer0: tex.includes(':') ? tex : ns + ':' + tex },
        };
    }

    // ============================================================
    // lang key 约定
    // ============================================================

    function itemLangKey(def) {
        return 'item.' + (def.namespace || '') + '.' + def.id;
    }

    function blockLangKey(def) {
        return 'block.' + (def.namespace || '') + '.' + def.id;
    }

    function creativeTabId(def, packId) {
        return (def.namespace || '') + ':pack_' + String(packId || '').toLowerCase().replace(/[^a-z0-9_]/g, '_');
    }

    function creativeTabLangKey(tabId) {
        return 'itemGroup.' + tabId;
    }

    // ============================================================
    // 新定义工厂
    // ============================================================

    function newItem(namespace) {
        return {
            id: '',
            namespace: namespace || '',
            lang: { en_us: '', zh_cn: '' },
            stackSize: 64,
            rarity: 'common',
            creative: true,
            fireResistant: false,
            model: { type: 'generated', texture: '' },
            _isNew: true,
        };
    }

    function newBlock(namespace) {
        return {
            id: '',
            namespace: namespace || '',
            lang: { en_us: '', zh_cn: '' },
            material: {
                hardness: 1.5,
                resistance: 6.0,
                sound: 'minecraft:block.stone',
                lightLevel: 0,
                friction: 0.6,
                transparent: false,
                randomTicks: false,
                requiresTool: false,
                solid: true,
            },
            properties: [],
            blockstates: { mode: 'variants', variants: [] },
            model: { type: 'cube_all', texture: '', file: '' },
            blockEntity: null,
            loot: { selfDrop: true },
            tags: [],
            creative: true,
            blockItem: true,
            _isNew: true,
        };
    }

    /** 从目录条目生成一个新参数声明 */
    function newPropertyFromCatalog(spec) {
        const p = { name: spec.name, type: spec.type, default: spec.default };
        if (spec.values) p.values = spec.values.slice();
        if (spec.type === 'int') { p.min = spec.min ?? 0; p.max = spec.max ?? 15; }
        if (spec.labelZh) p.labelZh = spec.labelZh;
        return p;
    }

    const ItemBlockSchemaAPI = {
        BUILTIN_CATALOG,
        setCatalog,
        catalogSpecs,
        findCatalogSpec,
        validateItem,
        validateBlock,
        validateProperty,
        countStates,
        expandStates,
        stateKey,
        buildBlockstatesJson,
        buildBlockModelJson,
        buildItemModelJson,
        normalizeModelRef,
        itemLangKey,
        blockLangKey,
        creativeTabId,
        creativeTabLangKey,
        newItem,
        newBlock,
        newPropertyFromCatalog,
        ID_RE,
        NS_RE,
        FQCN_RE,
        PROP_NAME_RE,
        SOUND_RE,
        RARITIES,
        PROP_TYPES,
        MODEL_TYPES,
        MAX_STACK_SIZE,
        MAX_STATES,
    };

    if (typeof module === 'object' && module.exports) module.exports = ItemBlockSchemaAPI;
    if (typeof self !== 'undefined') self.ItemBlockSchema = ItemBlockSchemaAPI;
})();
