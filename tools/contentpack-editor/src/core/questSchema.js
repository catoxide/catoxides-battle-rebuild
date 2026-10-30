// questSchema.js - 任务定义契约（纯函数，单一真源）
//
// 对应 Java 权威实现：
//   src/main/java/.../core/quest/QuestDefinitionParser.java  —— 严格解析（未知组件 = 抛错）
//   src/main/java/.../core/quest/QuestSystem.java            —— 完成语义（goals 全完成 / 空 goals = 容器）
//   src/main/java/.../core/quest/QuestGivePolicy.java        —— repeatable / cooldownTicks 的自动发放判定
//   src/main/java/.../core/quest/QuestBuiltinComponents.java —— 内置组件类型（本文件的前两类）
//   src/main/java/.../core/quest/goal/ItemPresentGoal.java   —— item_present 字段
//   src/main/java/.../core/quest/goal/ItemSubmitGoal.java    —— item_submit 字段（完成时扣除）
//   src/main/java/.../core/quest/giver/ProximityGiver.java   —— proximity_giver 字段
//
// 文件形态：quests/<id>.json，一个文件 = 一个任务定义（子任务嵌在 "children" 里）。
//
// 本文件整体包在 IIFE 里（经典 <script> 共享全局词法环境，重名即整个脚本失效）。
(function () {
    'use strict';

    const ID_RE = /^[a-z0-9_.\-]+$/;
    const ITEM_RE = /^[a-z0-9_.\-]+(?::[a-z0-9_.\-]+)?$/;

    const QUEST_DIR = 'quests';

    /**
     * 内置 giver 类型（基座提供，纯 JSON 任务包不用写 Java）。
     * fields 同时驱动编辑器表单与校验：key/type/required/default 是唯一定义处。
     */
    const GIVER_TYPES = {
        proximity_giver: {
            zh: '进入区域发放',
            en: 'Proximity trigger',
            summary: '玩家进入 center 半径内时发放任务',
            fields: [
                { key: 'center', zh: '中心坐标', en: 'Center', type: 'vec3', required: true, default: [0, 64, 0] },
                { key: 'radius', zh: '半径', en: 'Radius', type: 'number', min: 0, default: 10 },
            ],
        },
    };

    /** 内置 goal 类型（基座提供） */
    const GOAL_TYPES = {
        item_present: {
            zh: '持有物品',
            en: 'Have items',
            summary: '背包里持有 N 个即完成（只检测，不扣除）',
            fields: [
                { key: 'item', zh: '物品 id', en: 'Item id', type: 'item', required: true, placeholder: 'minecraft:apple' },
                { key: 'count', zh: '数量', en: 'Count', type: 'int', min: 1, default: 1 },
                { key: 'label', zh: '进度名（默认物品路径）', en: 'Progress label (defaults to item path)', type: 'text', default: '' },
            ],
        },
        item_submit: {
            zh: '提交物品',
            en: 'Submit items',
            summary: '持有 N 个即视为可交付，任务完成时扣除这 N 个',
            fields: [
                { key: 'item', zh: '物品 id', en: 'Item id', type: 'item', required: true, placeholder: 'minecraft:iron_ingot' },
                { key: 'count', zh: '数量', en: 'Count', type: 'int', min: 1, default: 1 },
                { key: 'label', zh: '进度名（默认物品路径）', en: 'Progress label (defaults to item path)', type: 'text', default: '' },
            ],
        },
    };

    // ============================================================
    // 基础判定
    // ============================================================

    function isVec3(v) {
        return Array.isArray(v) && v.length === 3 && v.every((n) => typeof n === 'number' && Number.isFinite(n));
    }

    function isPlainObject(v) {
        return !!v && typeof v === 'object' && !Array.isArray(v);
    }

    function isEmpty(v) {
        return v === undefined || v === null || (typeof v === 'string' && v.trim() === '');
    }

    function clone(v) {
        return Array.isArray(v) ? v.slice() : v;
    }

    // ============================================================
    // 校验（镜像 QuestDefinitionParser 的严格性）
    // ============================================================

    /**
     * @param {Object} def 任务定义
     * @param {{idPrefix?:string}} [opts] idPrefix = 包名前缀，只用于警告（任务 id 在主 mod 命名空间共用）
     * @returns {{valid:boolean, errors:string[], warnings:string[]}}
     */
    function validateQuest(def, opts) {
        const errors = [];
        const warnings = [];
        if (!isPlainObject(def)) {
            return { valid: false, errors: ['空定义'], warnings };
        }
        const options = opts || {};

        // id：运行时无冒号 = 主 mod 命名空间（B6）；编辑器只写主 mod 命名空间
        const rawId = String(def.id || '').trim();
        if (!rawId) {
            errors.push('id 必填');
        } else if (rawId.includes(':')) {
            errors.push("id 不要带命名空间（编辑器只写主 mod 命名空间，B6）；确需显式命名空间请手改 JSON: " + rawId);
        } else if (!ID_RE.test(rawId)) {
            errors.push('id 非法（必须 ^[a-z0-9_.\\-]+$，小写）: ' + rawId);
        } else if (options.idPrefix && !rawId.startsWith(options.idPrefix)) {
            warnings.push(`id 建议以包名开头（${options.idPrefix}_...）：任务 id 在所有包间共用主 mod 命名空间，撞名会互相覆盖`);
        }

        if (isEmpty(def.title)) {
            errors.push('title 必填（缺省会在游戏里显示「未命名任务」）');
        } else if (typeof def.title !== 'string') {
            errors.push('title 必须是字符串');
        }
        if (def.description !== undefined && def.description !== null && typeof def.description !== 'string') {
            errors.push('description 必须是字符串');
        }

        for (const key of ['givers', 'goals', 'conditions', 'failConditions', 'children']) {
            if (def[key] !== undefined && !Array.isArray(def[key])) {
                errors.push(key + ' 必须是数组');
            }
        }

        const goals = Array.isArray(def.goals) ? def.goals : [];
        const children = Array.isArray(def.children) ? def.children : [];
        // 运行时：goals 为空 = 容器任务，完成条件退化成「所有子任务完成」；两者都空 → 空集全真 → 发放即完成
        if (!goals.length && !children.length) {
            errors.push('没有 goals 也没有 children：运行时会被当作空容器任务，发放后立即完成');
        }

        validateComponents(def.givers, 'givers', GIVER_TYPES, 'giver', errors, warnings);
        validateComponents(def.goals, 'goals', GOAL_TYPES, 'goal', errors, warnings);
        validateComponents(def.conditions, 'conditions', null, 'condition', errors, warnings);
        validateComponents(def.failConditions, 'failConditions', null, 'failCondition', errors, warnings);

        children.forEach((child, i) => {
            const r = validateQuest(child, options);
            for (const e of r.errors) errors.push(`children[${i}]: ${e}`);
            for (const w of r.warnings) warnings.push(`children[${i}]: ${w}`);
        });

        if (def.location !== undefined && def.location !== null && !isVec3(def.location)) {
            errors.push('location 必须是 [x, y, z] 三个数字（或留空）');
        }
        if (def.priority !== undefined && def.priority !== null && String(def.priority) !== '') {
            if (!Number.isFinite(Number(def.priority))) errors.push('priority 必须是数字: ' + def.priority);
        }

        // repeatable / cooldownTicks：自动发放的节流（Java 侧 QuestGivePolicy）
        if (def.repeatable !== undefined && def.repeatable !== null && typeof def.repeatable !== 'boolean') {
            errors.push('repeatable 必须是 true/false: ' + def.repeatable);
        }
        const cdRaw = def.cooldownTicks;
        if (cdRaw !== undefined && cdRaw !== null && String(cdRaw) !== '') {
            const cd = Number(cdRaw);
            // Java 侧要求 JSON number（字符串 "200" 会在加载期抛错），这里同严
            if (typeof cdRaw !== 'number' || !Number.isInteger(cd) || cd < 0) {
                errors.push('cooldownTicks 必须是 ≥0 的整数（JSON 数字，tick，20 tick = 1 秒）: ' + cdRaw);
            } else if (cd > 0 && def.repeatable !== true) {
                errors.push('cooldownTicks 只在 repeatable=true 时生效：一次性任务不会二次发放，冷却没有意义');
            }
        }

        return { valid: errors.length === 0, errors, warnings };
    }

    function validateComponents(arr, key, catalog, kind, errors, warnings) {
        if (!Array.isArray(arr)) return;
        arr.forEach((comp, i) => {
            const ctx = `${key}[${i}]`;
            if (!isPlainObject(comp)) {
                errors.push(ctx + ' 必须是对象');
                return;
            }
            if (isEmpty(comp.type)) {
                errors.push(ctx + ' 缺 type');
                return;
            }
            const type = String(comp.type).trim();
            const spec = catalog && catalog[type];
            if (!spec) {
                warnings.push(`${ctx} 类型 '${type}' 不是内置 ${kind}：字段由包的 Java 工厂决定，编辑器不校验（保存时原样保留）`);
                return;
            }
            validateFields(comp, spec.fields, ctx, errors);
        });
    }

    function validateFields(comp, fields, ctx, errors) {
        for (const f of fields) {
            const v = comp[f.key];
            if (isEmpty(v)) {
                if (f.required) errors.push(`${ctx}.${f.key} 必填`);
                continue;
            }
            if (f.type === 'vec3') {
                if (!isVec3(v)) errors.push(`${ctx}.${f.key} 必须是 [x, y, z] 三个数字`);
            } else if (f.type === 'int') {
                const n = Number(v);
                const min = f.min === undefined ? -Infinity : f.min;
                const max = f.max === undefined ? Infinity : f.max;
                if (!Number.isInteger(n) || n < min || n > max) {
                    errors.push(`${ctx}.${f.key} 必须是 ${f.min === undefined ? '' : f.min + '..' + (f.max === undefined ? '' : f.max) + ' 的'}整数: ${v}`);
                }
            } else if (f.type === 'number') {
                const n = Number(v);
                if (!Number.isFinite(n) || (f.min !== undefined && n < f.min)) {
                    errors.push(`${ctx}.${f.key} 必须是数字${f.min !== undefined ? '（≥' + f.min + '）' : ''}: ${v}`);
                }
            } else if (f.type === 'item') {
                if (typeof v !== 'string' || !ITEM_RE.test(v.trim())) {
                    errors.push(`${ctx}.${f.key} 物品 id 非法（ns:path，小写）: ${v}`);
                }
            } else if (typeof v !== 'string') {
                errors.push(`${ctx}.${f.key} 必须是字符串`);
            }
        }
    }

    // ============================================================
    // 归一化 / 序列化
    // ============================================================

    /** 补默认值、去空串、剥内部字段；未知类型组件原样保留（包工厂负责） */
    function normalizeQuest(def) {
        const d = isPlainObject(def) ? def : {};
        return {
            id: String(d.id || '').trim(),
            title: String(d.title || '').trim(),
            description: String(d.description || '').trim(),
            givers: normalizeComponents(d.givers, GIVER_TYPES),
            goals: normalizeComponents(d.goals, GOAL_TYPES),
            conditions: normalizeComponents(d.conditions, null),
            failConditions: normalizeComponents(d.failConditions, null),
            children: (Array.isArray(d.children) ? d.children : []).map(normalizeQuest),
            location: isVec3(d.location) ? d.location.map(Number) : null,
            priority: d.priority !== undefined && d.priority !== null && String(d.priority) !== '' && Number.isFinite(Number(d.priority))
                ? Number(d.priority) : 0,
            repeatable: d.repeatable === true,
            cooldownTicks: Number.isInteger(Number(d.cooldownTicks)) && Number(d.cooldownTicks) > 0
                ? Number(d.cooldownTicks) : 0,
        };
    }

    function normalizeComponents(arr, catalog) {
        if (!Array.isArray(arr)) return [];
        return arr.filter(isPlainObject).map((comp) => {
            const type = String(comp.type || '').trim();
            const spec = catalog && catalog[type];
            if (!spec) return stripInternal(comp);
            const out = { type };
            for (const f of spec.fields) {
                const v = comp[f.key];
                if (isEmpty(v)) {
                    if (!f.required && f.default !== undefined) out[f.key] = clone(f.default);
                    continue;
                }
                if (f.type === 'vec3') out[f.key] = v.map(Number);
                else if (f.type === 'int') out[f.key] = Math.trunc(Number(v));
                else if (f.type === 'number') out[f.key] = Number(v);
                else out[f.key] = String(v).trim();
            }
            return out;
        });
    }

    function stripInternal(obj) {
        const out = {};
        for (const [k, v] of Object.entries(obj)) {
            if (!k.startsWith('_')) out[k] = v;
        }
        return out;
    }

    /** 正式写入 quests/<id>.json 的对象（键序 = Java 解析顺序，空可选字段不落盘） */
    function buildQuestJson(def) {
        const n = normalizeQuest(def);
        return {
            id: n.id,
            title: n.title,
            description: n.description,
            givers: n.givers.map((c) => componentJson(c, GIVER_TYPES)),
            goals: n.goals.map((c) => componentJson(c, GOAL_TYPES)),
            conditions: n.conditions.map((c) => componentJson(c, null)),
            failConditions: n.failConditions.map((c) => componentJson(c, null)),
            children: n.children.map(buildQuestJson),
            location: n.location,
            priority: n.priority,
            repeatable: n.repeatable,
            cooldownTicks: n.cooldownTicks,
        };
    }

    function componentJson(comp, catalog) {
        const type = String(comp.type || '').trim();
        const spec = catalog && catalog[type];
        if (!spec) return stripInternal(comp);
        const out = { type };
        for (const f of spec.fields) {
            const v = comp[f.key];
            if (v === undefined) continue;
            // 可选空串（label）不落盘 —— Java 侧按「空则回退物品路径」处理
            if (!f.required && f.type !== 'vec3' && typeof v === 'string' && v === '') continue;
            out[f.key] = v;
        }
        return out;
    }

    // ============================================================
    // 新建
    // ============================================================

    function newQuest() {
        return {
            id: '',
            title: '',
            description: '',
            givers: [newComponent('giver', 'proximity_giver')],
            goals: [newComponent('goal', 'item_present')],
            conditions: [],
            failConditions: [],
            children: [],
            location: null,
            priority: 0,
            repeatable: false,
            cooldownTicks: 0,
            _isNew: true,
        };
    }

    function newComponent(kind, type) {
        const catalog = kind === 'giver' ? GIVER_TYPES : kind === 'goal' ? GOAL_TYPES : null;
        const spec = catalog && catalog[type];
        if (!spec) return { type };
        const comp = { type };
        for (const f of spec.fields) {
            comp[f.key] = f.default !== undefined ? clone(f.default) : (f.type === 'vec3' ? [0, 64, 0] : '');
        }
        return comp;
    }

    /** quests/<id>.json 的文件名（id 已通过校验时不含路径分隔符） */
    function filenameFor(def) {
        return String((def && def.id) || '').trim() + '.json';
    }

    function idFromFilename(name) {
        return String(name || '').replace(/\.json$/i, '');
    }

    const QuestSchemaAPI = {
        QUEST_DIR,
        ID_RE,
        ITEM_RE,
        GIVER_TYPES,
        GOAL_TYPES,
        isVec3,
        validateQuest,
        normalizeQuest,
        buildQuestJson,
        newQuest,
        newComponent,
        filenameFor,
        idFromFilename,
    };

    if (typeof module === 'object' && module.exports) module.exports = QuestSchemaAPI;
    if (typeof self !== 'undefined') self.QuestSchema = QuestSchemaAPI;
})();
