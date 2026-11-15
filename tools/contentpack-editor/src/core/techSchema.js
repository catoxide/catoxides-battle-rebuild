// techSchema.js - 科技树定义契约（纯函数，单一真源）
//
// 对应 Java 权威实现：
//   contentpacks-source/tech-tree-pack/java/example/contentpack/tech/TechTreeLoader.java
//       —— 解析 + 自检（重复 id / 缺前置 / 成环 / 非法成本 / 台子升级缺口）
//   contentpacks-source/tech-tree-pack/java/example/contentpack/tech/TechRequirementEngine.java
//       —— 条件类型与字段
//   contentpacks-source/tech-tree-pack/java/example/contentpack/tech/TechTree.java
//       —— 页签 / 研究项 / 研究台 / 科技点
//   contentpacks-source/tech-tree-pack/java/example/contentpack/tech/TechService.java
//       —— 成本与奖励语义
//   src/main/java/.../core/tech/TechHooks.java
//       —— 扩展条件类型 + 触发器事件（运行期判定，静态不校验字段）
//
// 文件形态：techs/tree.json（单文件 = 整棵树）。编辑器直写该文件，不经暂存区。
// 布局：col/row 是页内固定网格（运行期 NODE_W=84 / NODE_H=40 / COL_STRIDE=128 /
// ROW_STRIDE=64），界面不做自动布局 —— 坐标就是用户手排的结果。
(function () {
    'use strict';

    const DEFAULT_ICON = 'minecraft:paper';
    const DEFAULT_PAGE = 'main';
    const ID_RE = /^[a-z0-9_.\-]+$/;
    const RL_RE = /^[a-z0-9_.\-]+:[a-z0-9_.\-/]+$/;

    /**
     * 条件类型目录：字段既是校验依据，也是编辑器表单的定义处。
     * 与 TechRequirementEngine 的 switch 一一对应；新增内置类型必须同时改 Java 与这里。
     */
    const REQUIREMENT_TYPES = {
        table_tier: {
            zh: '研究台层级 ≥', en: 'Table tier ≥',
            fields: [{ key: 'min', type: 'int', min: 1, default: 1 }],
        },
        tech: {
            zh: '已解锁节点', en: 'Unlocked nodes',
            fields: [{ key: 'nodes', type: 'nodeRefs', required: true }],
        },
        quest: {
            zh: '已完成任务', en: 'Completed quests',
            fields: [{ key: 'quests', type: 'stringList', required: true }],
        },
        item: {
            zh: '持有物品', en: 'Have items',
            fields: [
                { key: 'item', type: 'item', required: true },
                { key: 'count', type: 'int', min: 1, default: 1 },
                { key: 'consume', type: 'bool', default: false },
            ],
        },
        advancement: {
            zh: '已完成进度', en: 'Advancement done',
            fields: [{ key: 'id', type: 'rl', required: true }],
        },
        trigger: {
            zh: '自定义触发器', en: 'Custom trigger',
            fields: [{ key: 'name', type: 'trigger', required: true }],
        },
        tech_points: {
            zh: '科技点 ≥', en: 'Tech points ≥',
            fields: [{ key: 'min', type: 'int', min: 1, default: 1 }],
        },
        all: {
            zh: '全部满足', en: 'All of',
            fields: [{ key: 'requirements', type: 'reqList' }],
        },
        any: {
            zh: '任一满足', en: 'Any of',
            fields: [{ key: 'requirements', type: 'reqList' }],
        },
    };

    /** 成本类型目录（TechTreeLoader.parseCost：未知类型会让节点解析失败） */
    const COST_TYPES = {
        item: {
            zh: '物品', en: 'Item',
            fields: [
                { key: 'item', type: 'item', required: true },
                { key: 'amount', type: 'int', min: 1, default: 1 },
            ],
        },
        xp_levels: {
            zh: '经验等级', en: 'XP levels',
            fields: [{ key: 'amount', type: 'int', min: 1, default: 1 }],
        },
        tech_point: {
            zh: '科技点', en: 'Tech points',
            fields: [{ key: 'amount', type: 'int', min: 1, default: 1 }],
        },
    };

    const KNOWN_TREE_KEYS = ['title', 'points', 'pages', 'research', 'table', 'nodes'];
    const KNOWN_NODE_KEYS = ['id', 'title', 'desc', 'page', 'col', 'row', 'icon',
        'requirements', 'costs', 'rewards', 'quest', 'visibleWhen', 'resetAfterTicks',
        'resetWhen', 'onUnlock'];
    const KNOWN_RESEARCH_KEYS = ['id', 'title', 'desc', 'page', 'icon', 'requirements',
        'visibleWhen', 'costs', 'points', 'cooldownTicks'];

    function isPlainObject(v) {
        return !!v && typeof v === 'object' && !Array.isArray(v);
    }

    function isId(v) {
        return typeof v === 'string' && ID_RE.test(v);
    }

    function isRl(v) {
        return typeof v === 'string' && RL_RE.test(v);
    }

    function asInt(v, fallback) {
        const n = Number(v);
        return Number.isFinite(n) ? Math.trunc(n) : fallback;
    }

    function clone(v) {
        return v === undefined ? v : JSON.parse(JSON.stringify(v));
    }

    function idList(v) {
        return Array.isArray(v) ? v.map((x) => String(x).trim()).filter(Boolean) : [];
    }

    // ============================================================
    // 工厂
    // ============================================================

    function newTree() {
        return {
            title: '科技树',
            points: { questReward: 1 },
            pages: [{ id: DEFAULT_PAGE, title: '主页', icon: DEFAULT_ICON }],
            research: [],
            table: { maxTier: 1, upgrades: [] },
            nodes: [],
        };
    }

    function newPage(id, title) {
        return { id: String(id || '').trim(), title: String(title || '').trim(), icon: DEFAULT_ICON };
    }

    function newRequirement(type) {
        const spec = REQUIREMENT_TYPES[type];
        const req = { type: String(type || 'tech') };
        if (!spec) return req;
        for (const f of spec.fields) {
            if (f.type === 'reqList' || f.type === 'nodeRefs' || f.type === 'stringList') req[f.key] = [];
            else if (f.type === 'bool') req[f.key] = f.default === true;
            else req[f.key] = f.default !== undefined ? f.default : '';
        }
        return req;
    }

    function newCost(type) {
        const spec = COST_TYPES[type] || COST_TYPES.item;
        const cost = { type: COST_TYPES[type] ? type : 'item' };
        for (const f of spec.fields) cost[f.key] = f.default !== undefined ? f.default : '';
        return cost;
    }

    function uniqueId(base, taken) {
        let id = base;
        let n = 2;
        while (taken.has(id)) id = base + '_' + n++;
        return id;
    }

    function newNode(tree, pageId, col, row) {
        const t = tree || newTree();
        const page = pageId || (t.pages && t.pages[0] ? t.pages[0].id : DEFAULT_PAGE);
        const taken = new Set((t.nodes || []).map((n) => n.id));
        const cell = (Number.isInteger(col) && Number.isInteger(row)) ? { col, row } : nextFreeCell(t, page);
        return {
            id: uniqueId('node', taken),
            title: '新节点',
            desc: '',
            page,
            col: cell.col,
            row: cell.row,
            icon: DEFAULT_ICON,
            requirements: [],
            costs: [],
            rewards: [],
            quest: false,
            visibleWhen: [],
            resetAfterTicks: 0,
            resetWhen: [],
            onUnlock: [],
        };
    }

    function newResearch(tree, pageId) {
        const t = tree || newTree();
        const taken = new Set((t.research || []).map((r) => r.id));
        return {
            id: uniqueId('research', taken),
            title: '新研究项',
            desc: '',
            page: pageId || (t.pages && t.pages[0] ? t.pages[0].id : DEFAULT_PAGE),
            icon: DEFAULT_ICON,
            requirements: [],
            visibleWhen: [],
            costs: [newCost('item')],
            points: 0,
            cooldownTicks: 0,
        };
    }

    /** 手排布局的默认落点：页内按 row → col 扫描第一个空格。 */
    function nextFreeCell(tree, pageId) {
        const used = new Set();
        for (const node of (tree && tree.nodes) || []) {
            if (node.page === pageId) used.add(Math.trunc(node.col) + ',' + Math.trunc(node.row));
        }
        for (let row = 0; row < 256; row++) {
            for (let col = 0; col < 64; col++) {
                if (!used.has(col + ',' + row)) return { col, row };
            }
        }
        return { col: 0, row: 0 };
    }

    function pageUsage(tree, pageId) {
        return {
            nodes: ((tree && tree.nodes) || []).filter((n) => n && n.page === pageId),
            research: ((tree && tree.research) || []).filter((r) => r && r.page === pageId),
        };
    }

    /** 删除页签；仍有节点/研究项引用时拒绝，避免留下悬空 page 引用。 */
    function deletePage(tree, pageId) {
        if (!tree || !Array.isArray(tree.pages)) return { ok: false, error: '树没有 pages' };
        if (tree.pages.length <= 1) return { ok: false, error: '至少保留一个页签' };
        const usage = pageUsage(tree, pageId);
        if (usage.nodes.length || usage.research.length) {
            return {
                ok: false,
                error: `页签 ${pageId} 仍被 ${usage.nodes.length} 个节点、`
                    + `${usage.research.length} 个研究项引用，先移走再删`,
            };
        }
        const idx = tree.pages.findIndex((p) => p.id === pageId);
        if (idx < 0) return { ok: false, error: '找不到页签 ' + pageId };
        tree.pages.splice(idx, 1);
        return { ok: true };
    }

    function deleteNode(tree, nodeId) {
        if (!tree || !Array.isArray(tree.nodes)) return { ok: false, error: '树没有 nodes' };
        const idx = tree.nodes.findIndex((n) => n.id === nodeId);
        if (idx < 0) return { ok: false, error: '找不到节点 ' + nodeId };
        tree.nodes.splice(idx, 1);
        return { ok: true };
    }

    // ============================================================
    // 校验
    // ============================================================

    function reqFieldErrors(owner, req, spec, errors, warnings) {
        for (const f of spec.fields) {
            const v = req[f.key];
            if (f.type === 'int') {
                if (v === undefined || v === null || v === '') {
                    if (f.required !== false) errors.push(`${owner} 的 ${f.key} 不能为空`);
                    continue;
                }
                const n = Number(v);
                if (!Number.isInteger(n)) errors.push(`${owner} 的 ${f.key} 必须是整数: ${v}`);
                else if (f.min !== undefined && n < f.min) errors.push(`${owner} 的 ${f.key} 必须 ≥ ${f.min}: ${n}`);
            } else if (f.type === 'bool') {
                if (v !== undefined && typeof v !== 'boolean') warnings.push(`${owner} 的 ${f.key} 建议用 true/false: ${v}`);
            } else if (f.type === 'item' || f.type === 'rl') {
                const s = String(v == null ? '' : v).trim();
                if (!s) { if (f.required !== false) errors.push(`${owner} 的 ${f.key} 不能为空`); continue; }
                if (!isRl(s)) errors.push(`${owner} 的 ${f.key} 不是合法的资源 id（namespace:path）: ${s}`);
            } else if (f.type === 'stringList') {
                const list = idList(v);
                if (f.required && !list.length) warnings.push(`${owner} 的 ${f.key} 为空（运行期视为恒真）`);
            } else if (f.type === 'nodeRefs') {
                const list = idList(v);
                if (f.required && !list.length) warnings.push(`${owner} 的 ${f.key} 为空（运行期视为恒真）`);
            } else if (f.type === 'trigger') {
                if (!String(v == null ? '' : v).trim()) errors.push(`${owner} 的 ${f.key} 不能为空`);
            }
        }
    }

    function validateRequirement(owner, req, ctx, errors, warnings) {
        if (!isPlainObject(req)) {
            errors.push(owner + ' 的条件不是对象');
            return;
        }
        const type = String(req.type == null ? '' : req.type).trim();
        if (!type) {
            errors.push(owner + ' 的条件缺少 type');
            return;
        }
        const spec = REQUIREMENT_TYPES[type];
        if (!spec) {
            warnings.push(owner + ` 使用扩展条件类型 "${type}"（字段由提供方解释，静态不校验）`);
            return;
        }
        reqFieldErrors(owner, req, spec, errors, warnings);
        if (type === 'tech') {
            for (const id of idList(req.nodes)) {
                if (!ctx.nodeIds.has(id)) errors.push(`${owner} 的条件引用了不存在的节点: ${id}`);
            }
        } else if (type === 'quest') {
            for (const id of idList(req.quests)) {
                if (!isRl(id)) errors.push(`${owner} 的任务 id 不是 namespace:path: ${id}`);
                else checkQuestRef(owner, id, ctx, errors);
            }
        } else if (type === 'item') {
            checkItemRef(owner, String(req.item || '').trim(), '条件物品', ctx, errors);
        } else if (type === 'all' || type === 'any') {
            const children = Array.isArray(req.requirements) ? req.requirements : null;
            if (!children) {
                errors.push(`${owner} 的 ${type} 缺少 requirements 数组`);
                return;
            }
            if (!children.length) warnings.push(`${owner} 的 ${type} 没有任何子条件（运行期视为恒真）`);
            for (const child of children) validateRequirement(owner, child, ctx, errors, warnings);
        }
    }

    function validateCost(owner, cost, ctx, errors) {
        if (!isPlainObject(cost)) {
            errors.push(owner + ' 的成本不是对象');
            return;
        }
        const type = String(cost.type == null ? '' : cost.type).trim();
        const spec = COST_TYPES[type];
        if (!spec) {
            errors.push(`${owner} 的成本类型未知（运行期该节点解析失败）: ${type || '(空)'}`);
            return;
        }
        reqFieldErrors(owner, cost, spec, errors, []);
        if (type === 'item') checkItemRef(owner, String(cost.item || '').trim(), '成本物品', ctx, errors);
    }

    function checkItemRef(owner, id, role, ctx, errors) {
        if (!id) return;
        if (!isRl(id)) {
            errors.push(`${owner} 的${role}不是合法的资源 id: ${id}`);
            return;
        }
        const ns = id.split(':')[0];
        if (ctx.packNamespaces && ctx.packNamespaces.has(ns)
            && ctx.knownItems && !ctx.knownItems.has(id)) {
            errors.push(`${owner} 的${role} ${id} 不在本包 items/ 里（同包引用，注册后不存在）`);
        }
    }

    function checkQuestRef(owner, id, ctx, errors) {
        const ns = id.split(':')[0];
        if (!ctx.packNamespaces || !ctx.packNamespaces.has(ns)) return;
        if (ctx.knownQuests && ctx.knownQuests.has(id)) return;
        const techNode = id.slice(id.indexOf(':') + 1);
        if (techNode.startsWith('tech_') && ctx.nodeIds.has(techNode.slice(5))) return;
        errors.push(`${owner} 的任务引用 ${id} 在本包 quests/ 里不存在（tech 节点任务 id 形如 ns:tech_<node>）`);
    }

    function validateNode(node, ctx, errors, warnings) {
        if (!isPlainObject(node)) {
            errors.push('节点不是对象');
            return;
        }
        const id = String(node.id == null ? '' : node.id).trim();
        const owner = '节点 ' + (id || '(空)');
        if (!isId(id)) errors.push(`节点 id 非法（只允许小写字母/数字/_.-）: ${id || '(空)'}`);
        if (!isRl(String(node.icon == null ? '' : node.icon))) {
            errors.push(owner + ' 缺少合法的 icon（运行期解析失败会跳过该节点）');
        }
        const page = String(node.page == null ? '' : node.page).trim();
        if (page && !ctx.pageIds.has(page)) {
            warnings.push(`${owner} 的页签 "${page}" 未声明（运行期会自动补一个同名页签）`);
        }
        for (const key of ['col', 'row']) {
            const n = Number(node[key]);
            if (!Number.isInteger(n)) errors.push(`${owner} 的 ${key} 必须是整数: ${node[key]}`);
            else if (n < 0) warnings.push(`${owner} 的 ${key} 为负数: ${n}`);
        }
        const requirements = Array.isArray(node.requirements) ? node.requirements : [];
        for (const req of requirements) validateRequirement(owner, req, ctx, errors, warnings);
        const visibleWhen = Array.isArray(node.visibleWhen) ? node.visibleWhen : [];
        for (const req of visibleWhen) validateRequirement(owner + ' 的 visibleWhen', req, ctx, errors, warnings);
        const resetWhen = Array.isArray(node.resetWhen) ? node.resetWhen : [];
        for (const req of resetWhen) validateRequirement(owner + ' 的 resetWhen', req, ctx, errors, warnings);
        const costs = Array.isArray(node.costs) ? node.costs : [];
        for (const cost of costs) validateCost(owner, cost, ctx, errors);
        const rewards = Array.isArray(node.rewards) ? node.rewards : [];
        for (const reward of rewards) {
            if (!isPlainObject(reward)) { errors.push(owner + ' 的奖励不是对象'); continue; }
            if (!isRl(String(reward.item == null ? '' : reward.item))) {
                errors.push(owner + ' 的奖励物品 id 非法: ' + (reward.item || '(空)'));
            } else {
                checkItemRef(owner, String(reward.item).trim(), '奖励物品', ctx, errors);
            }
            const count = Number(reward.count == null ? 1 : reward.count);
            if (!Number.isInteger(count) || count <= 0) errors.push(owner + ' 的奖励数量必须 > 0: ' + reward.count);
        }
        if (node.resetAfterTicks !== undefined) {
            const n = Number(node.resetAfterTicks);
            if (!Number.isInteger(n)) errors.push(owner + ' 的 resetAfterTicks 必须是整数: ' + node.resetAfterTicks);
            else if (n < 0) warnings.push(owner + ' 的 resetAfterTicks 为负数（运行期按 0 处理）: ' + n);
        }
        const onUnlock = node.onUnlock;
        if (onUnlock !== undefined) {
            if (!Array.isArray(onUnlock)) errors.push(owner + ' 的 onUnlock 必须是字符串数组');
            else {
                for (const name of onUnlock) {
                    if (typeof name !== 'string' || !name.trim()) errors.push(owner + ' 的 onUnlock 含空触发器名');
                }
            }
        }
    }

    function validateResearch(entry, ctx, errors, warnings) {
        if (!isPlainObject(entry)) {
            errors.push('研究项不是对象');
            return;
        }
        const id = String(entry.id == null ? '' : entry.id).trim();
        const owner = '研究项 ' + (id || '(空)');
        if (!isId(id)) errors.push(`研究项 id 非法: ${id || '(空)'}`);
        const page = String(entry.page == null ? '' : entry.page).trim();
        if (page && !ctx.pageIds.has(page)) warnings.push(`${owner} 的页签 "${page}" 未声明（运行期自动补）`);
        const requirements = Array.isArray(entry.requirements) ? entry.requirements : [];
        for (const req of requirements) validateRequirement(owner, req, ctx, errors, warnings);
        const visibleWhen = Array.isArray(entry.visibleWhen) ? entry.visibleWhen : [];
        for (const req of visibleWhen) validateRequirement(owner + ' 的 visibleWhen', req, ctx, errors, warnings);
        const costs = Array.isArray(entry.costs) ? entry.costs : [];
        if (!costs.length) errors.push(owner + ' 缺少 costs（研究项必须消耗点什么）');
        for (const cost of costs) validateCost(owner, cost, ctx, errors);
        if (entry.points !== undefined) {
            const n = Number(entry.points);
            if (!Number.isInteger(n)) errors.push(owner + ' 的 points 必须是整数: ' + entry.points);
        }
        if (entry.cooldownTicks !== undefined) {
            const n = Number(entry.cooldownTicks);
            if (!Number.isInteger(n) || n < 0) errors.push(owner + ' 的 cooldownTicks 必须是 ≥0 的整数: ' + entry.cooldownTicks);
        }
    }

    function validateTable(table, errors) {
        if (table === undefined || table === null) return;
        if (!isPlainObject(table)) { errors.push('table 必须是对象'); return; }
        const maxTier = asInt(table.maxTier, 1);
        if (maxTier < 1) errors.push('table.maxTier 必须 ≥ 1: ' + table.maxTier);
        const upgrades = Array.isArray(table.upgrades) ? table.upgrades : [];
        const targets = new Set();
        for (const upgrade of upgrades) {
            if (!isPlainObject(upgrade)) { errors.push('研究台升级项不是对象'); continue; }
            const to = asInt(upgrade.to, NaN);
            if (!Number.isInteger(to) || to < 2 || to > maxTier) errors.push('研究台升级目标层级越界: ' + upgrade.to);
            else if (targets.has(to)) errors.push('研究台升级目标层级重复: ' + to);
            else targets.add(to);
            const costs = Array.isArray(upgrade.costs) ? upgrade.costs : [];
            if (!costs.length) errors.push('研究台升级到 ' + to + ' 缺少成本');
            for (const cost of costs) validateCost('研究台升级到 ' + to, cost, {
                nodeIds: new Set(), researchIds: new Set(), pageIds: new Set(),
                packNamespaces: null, knownItems: null, knownQuests: null,
            }, errors);
        }
        for (let tier = 1; tier < maxTier; tier++) {
            if (!targets.has(tier + 1)) errors.push(`研究台缺少 ${tier} → ${tier + 1} 的升级项`);
        }
    }

    /** 同一 id 出现多次时报一次（重复会连带让运行期只保留一个节点，必须拦住）。 */
    function reportDuplicates(list, label, errors) {
        const counts = new Map();
        for (const entry of list) {
            const id = isPlainObject(entry) ? String(entry.id == null ? '' : entry.id).trim() : '';
            if (!id) continue;
            counts.set(id, (counts.get(id) || 0) + 1);
        }
        for (const [id, n] of counts) {
            if (n > 1) errors.push(`重复${label} id: ${id}（出现 ${n} 次）`);
        }
    }

    function collectTechIds(reqs, out) {
        for (const req of reqs || []) {
            if (!isPlainObject(req)) continue;
            if (req.type === 'tech') out.push(...idList(req.nodes));
            else if (req.type === 'all' || req.type === 'any') collectTechIds(req.requirements, out);
        }
    }

    function checkCycles(tree, errors) {
        const byId = new Map();
        for (const node of (tree.nodes || [])) if (isPlainObject(node)) byId.set(node.id, node);
        const visiting = new Set();
        const done = new Set();
        function visit(id) {
            if (done.has(id)) return false;
            if (visiting.has(id)) return true;
            visiting.add(id);
            const node = byId.get(id);
            if (node) {
                const ids = [];
                collectTechIds(node.requirements, ids);
                for (const dep of ids) if (visit(dep)) return true;
            }
            visiting.delete(id);
            done.add(id);
            return false;
        }
        for (const id of byId.keys()) {
            if (visit(id)) { errors.push(`条件依赖存在环（涉及节点 ${id}）`); return; }
        }
    }

    function layoutCollisions(tree) {
        const cells = new Map();
        for (const node of (tree.nodes || [])) {
            if (!isPlainObject(node)) continue;
            const key = String(node.page) + ':' + Math.trunc(Number(node.col) || 0)
                + ',' + Math.trunc(Number(node.row) || 0);
            if (!cells.has(key)) cells.set(key, []);
            cells.get(key).push(String(node.id || '(空)'));
        }
        const out = [];
        for (const [key, ids] of cells) {
            if (ids.length > 1) {
                const [page, cell] = key.split(':');
                out.push(`页签 ${page} 的 (${cell}) 重叠: ${ids.join(' / ')}`);
            }
        }
        return out;
    }

    /**
     * 校验整棵树。
     * @param {Object} tree
     * @param {{knownItems?:Set<string>, knownQuests?:Set<string>, packNamespaces?:Set<string>}} [opts]
     *   knownItems / knownQuests 用于同包引用检查（外部命名空间不判死）。
     */
    function validateTree(tree, opts = {}) {
        const errors = [];
        const warnings = [];
        if (!isPlainObject(tree)) {
            return { valid: false, errors: ['树数据不是对象'], warnings };
        }
        const pages = Array.isArray(tree.pages) ? tree.pages : [];
        const nodes = Array.isArray(tree.nodes) ? tree.nodes : [];
        const research = Array.isArray(tree.research) ? tree.research : [];
        const pageIds = new Set();
        for (const page of pages) {
            if (!isPlainObject(page)) { errors.push('页签不是对象'); continue; }
            const id = String(page.id == null ? '' : page.id).trim();
            if (!isId(id)) errors.push(`页签 id 非法: ${id || '(空)'}`);
            else if (pageIds.has(id)) errors.push('重复页签 id: ' + id);
            else pageIds.add(id);
        }
        if (!pages.length) warnings.push('没有声明 pages（运行期会补一个 main 页签）');

        const nodeIds = new Set();
        for (const node of nodes) {
            const id = isPlainObject(node) ? String(node.id == null ? '' : node.id).trim() : '';
            if (id) nodeIds.add(id);
        }
        const researchIds = new Set();
        for (const entry of research) {
            const id = isPlainObject(entry) ? String(entry.id == null ? '' : entry.id).trim() : '';
            if (id) researchIds.add(id);
        }
        const ctx = {
            pageIds,
            nodeIds,
            researchIds,
            knownItems: opts.knownItems || null,
            knownQuests: opts.knownQuests || null,
            packNamespaces: opts.packNamespaces || null,
        };
        // ctx.nodeIds 先装满再校验：条件引用要判"节点存在"，不能依赖遍历顺序
        reportDuplicates(nodes, '节点', errors);
        reportDuplicates(research, '研究项', errors);
        for (const node of nodes) validateNode(node, ctx, errors, warnings);
        for (const entry of research) validateResearch(entry, ctx, errors, warnings);
        validateTable(tree.table, errors);
        if (isPlainObject(tree.points) && tree.points.questReward !== undefined) {
            const n = Number(tree.points.questReward);
            if (!Number.isInteger(n) || n < 0) errors.push('points.questReward 必须是 ≥0 的整数: ' + tree.points.questReward);
        }
        checkCycles(tree, errors);
        for (const msg of layoutCollisions(tree)) warnings.push(msg);
        return { valid: errors.length === 0, errors, warnings };
    }

    // ============================================================
    // 序列化（稳定 diff：固定字段顺序；保留未知字段，避免保存即丢数据）
    // ============================================================

    function cleanRequirement(req) {
        if (!isPlainObject(req)) return req;
        const out = {};
        if (req.type !== undefined) out.type = req.type;
        for (const [k, v] of Object.entries(req)) {
            if (k === 'type' || k.startsWith('_')) continue;
            out[k] = (k === 'requirements' && Array.isArray(v)) ? v.map(cleanRequirement) : v;
        }
        return out;
    }

    function cleanCost(cost) {
        if (!isPlainObject(cost)) return cost;
        const out = {};
        if (cost.type !== undefined) out.type = cost.type;
        for (const [k, v] of Object.entries(cost)) {
            if (k === 'type' || k.startsWith('_')) continue;
            out[k] = v;
        }
        return out;
    }

    function cleanNode(node) {
        const out = {};
        out.id = node.id;
        if (node.title !== undefined) out.title = node.title;
        if (node.desc) out.desc = node.desc;
        out.page = node.page;
        out.col = asInt(node.col, 0);
        out.row = asInt(node.row, 0);
        out.icon = node.icon;
        out.requirements = (node.requirements || []).map(cleanRequirement);
        out.costs = (node.costs || []).map(cleanCost);
        out.rewards = (node.rewards || []).map((r) => {
            const reward = { item: r.item };
            if (r.count !== undefined) reward.count = asInt(r.count, 1);
            return reward;
        });
        if (node.quest === true) out.quest = true;
        if (Array.isArray(node.visibleWhen) && node.visibleWhen.length) {
            out.visibleWhen = node.visibleWhen.map(cleanRequirement);
        }
        if (Number(node.resetAfterTicks) > 0) out.resetAfterTicks = asInt(node.resetAfterTicks, 0);
        if (Array.isArray(node.resetWhen) && node.resetWhen.length) {
            out.resetWhen = node.resetWhen.map(cleanRequirement);
        }
        if (Array.isArray(node.onUnlock) && node.onUnlock.length) out.onUnlock = idList(node.onUnlock);
        for (const [k, v] of Object.entries(node)) {
            if (k.startsWith('_') || KNOWN_NODE_KEYS.includes(k)) continue;
            out[k] = v;
        }
        return out;
    }

    function cleanPage(page) {
        const out = { id: page.id, title: page.title };
        if (page.icon) out.icon = page.icon;
        for (const [k, v] of Object.entries(page)) {
            if (k.startsWith('_') || k === 'id' || k === 'title' || k === 'icon') continue;
            out[k] = v;
        }
        return out;
    }

    function cleanResearch(entry) {
        const out = {};
        out.id = entry.id;
        if (entry.title !== undefined) out.title = entry.title;
        if (entry.desc) out.desc = entry.desc;
        out.page = entry.page;
        if (entry.icon) out.icon = entry.icon;
        out.requirements = (entry.requirements || []).map(cleanRequirement);
        if (Array.isArray(entry.visibleWhen) && entry.visibleWhen.length) {
            out.visibleWhen = entry.visibleWhen.map(cleanRequirement);
        }
        out.costs = (entry.costs || []).map(cleanCost);
        if (entry.points !== undefined) out.points = asInt(entry.points, 0);
        if (Number(entry.cooldownTicks) > 0) out.cooldownTicks = asInt(entry.cooldownTicks, 0);
        for (const [k, v] of Object.entries(entry)) {
            if (k.startsWith('_') || KNOWN_RESEARCH_KEYS.includes(k)) continue;
            out[k] = v;
        }
        return out;
    }

    /** 编辑器内存对象 → techs/tree.json 的稳定形状（未知顶层字段原样保留）。 */
    function buildTreeJson(tree) {
        const out = {};
        out.title = tree.title;
        out.points = isPlainObject(tree.points) ? Object.assign({}, tree.points) : { questReward: 1 };
        out.pages = (tree.pages || []).map(cleanPage);
        out.research = (tree.research || []).map(cleanResearch);
        const table = isPlainObject(tree.table) ? tree.table : { maxTier: 1, upgrades: [] };
        out.table = {
            maxTier: asInt(table.maxTier, 1),
            upgrades: (table.upgrades || []).map((u) => ({
                to: asInt(u.to, 2),
                costs: (u.costs || []).map(cleanCost),
            })),
        };
        out.nodes = (tree.nodes || []).map(cleanNode);
        for (const [k, v] of Object.entries(tree)) {
            if (k.startsWith('_') || KNOWN_TREE_KEYS.includes(k)) continue;
            out[k] = v;
        }
        return out;
    }

    // ============================================================
    // 触发器名册（编辑器用；运行期由 TechHooks.TriggerEvent / 命令置位）
    // ============================================================

    function collectTriggerNames(tree) {
        return collectTriggerUsage(tree)
            .map((u) => ({ name: u.name, count: u.count }))
            .sort((a, b) => a.name.localeCompare(b.name));
    }

    /**
     * 触发器名册（带使用位置）：编辑器用来回答「这个名字谁在等它」。
     * 运行期只有两处会置位同名的 TechHooks.TriggerEvent：程序 post + /techtree trigger。
     */
    function collectTriggerUsage(tree) {
        const map = new Map();
        const seen = new Set();
        const add = (name, owner, ownerId, field) => {
            const n = String(name == null ? '' : name).trim();
            if (!n) return;
            const key = n + '\u0000' + owner + '\u0000' + ownerId + '\u0000' + field;
            if (seen.has(key)) return;
            seen.add(key);
            if (!map.has(n)) map.set(n, { name: n, count: 0, uses: [] });
            const entry = map.get(n);
            entry.count++;
            entry.uses.push({ owner, id: ownerId, field });
        };
        const walk = (reqs, owner, ownerId, field) => {
            for (const req of reqs || []) {
                if (!isPlainObject(req)) continue;
                if (req.type === 'trigger') add(req.name, owner, ownerId, field);
                else if (req.type === 'all' || req.type === 'any') walk(req.requirements, owner, ownerId, field);
            }
        };
        for (const node of (tree && tree.nodes) || []) {
            if (!isPlainObject(node)) continue;
            const id = String(node.id || '');
            walk(node.requirements, 'node', id, 'requirements');
            walk(node.visibleWhen, 'node', id, 'visibleWhen');
            walk(node.resetWhen, 'node', id, 'resetWhen');
            for (const name of node.onUnlock || []) add(name, 'node', id, 'onUnlock');
        }
        for (const entry of (tree && tree.research) || []) {
            if (!isPlainObject(entry)) continue;
            const id = String(entry.id || '');
            walk(entry.requirements, 'research', id, 'requirements');
            walk(entry.visibleWhen, 'research', id, 'visibleWhen');
        }
        return Array.from(map.values()).sort((a, b) => a.name.localeCompare(b.name));
    }

    const TechSchemaAPI = {
        DEFAULT_ICON,
        DEFAULT_PAGE,
        ID_RE,
        RL_RE,
        REQUIREMENT_TYPES,
        COST_TYPES,
        isPlainObject,
        isId,
        isRl,
        newTree,
        newPage,
        newNode,
        newResearch,
        newRequirement,
        newCost,
        nextFreeCell,
        pageUsage,
        deletePage,
        deleteNode,
        validateTree,
        buildTreeJson,
        collectTriggerNames,
        collectTriggerUsage,
        layoutCollisions,
        clone,
    };

    if (typeof module === 'object' && module.exports) module.exports = TechSchemaAPI;
    if (typeof self !== 'undefined') self.TechSchema = TechSchemaAPI;
})();
