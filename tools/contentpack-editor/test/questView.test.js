// questView.test.js - 任务列表/编辑器的离线冒烟测试（DOM 桩 + vm 跑视图源码）
// 为什么要有：0.2.1 那轮事故的形态之一是「按钮看起来在、点了没反应」（绑定漏了 / id 改名）。
// i18nCoverage 只能保证文案齐，没法证明「填表 → 保存 → IPC 参数」这条链还在。
// 这里断言的是后端收到的事实：保存的 JSON 字段、未知组件保留、删除/列表的调用参数。
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.join(__dirname, '..', 'src');

let passed = 0, failed = 0;

// ---------- 最小 DOM 桩 ----------
function makeNode(tag) {
    const node = {
        tagName: tag, children: [], className: '', textContent: '', title: '',
        disabled: false, style: {}, value: '', type: '', checked: false, placeholder: '', rows: 0,
        _listeners: {},
        appendChild(child) { node.children.push(child); return child; },
        addEventListener(type, fn) { node._listeners[type] = fn; },
        classList: {
            add(c) { const s = new Set(String(node.className || '').split(/\s+/).filter(Boolean)); s.add(c); node.className = [...s].join(' '); },
            remove(c) { const s = new Set(String(node.className || '').split(/\s+/).filter(Boolean)); s.delete(c); node.className = [...s].join(' '); },
            contains(c) { return String(node.className || '').split(/\s+/).includes(c); },
            toggle(c, on) { on ? this.add(c) : this.remove(c); },
        },
        querySelector(selector) {
            let found = null;
            walk(node, (n) => {
                if (!found && n !== node && matches(n, selector)) found = n;
            });
            return found;
        },
    };
    Object.defineProperty(node, 'innerHTML', {
        get() { return ''; },
        set(v) { if (v === '') node.children.length = 0; },
    });
    return node;
}

function walk(node, fn) {
    fn(node);
    for (const c of node.children) walk(c, fn);
}

function matches(node, selector) {
    if (selector === 'input') return node.tagName === 'input';
    if (selector === 'select') return node.tagName === 'select';
    if (selector === 'textarea') return node.tagName === 'textarea';
    if (selector.startsWith('.')) return String(node.className || '').split(/\s+/).includes(selector.slice(1));
    return node.tagName === selector;
}

function findAll(root, predicate) {
    const out = [];
    walk(root, (n) => { if (n !== root && predicate(n)) out.push(n); });
    return out;
}

function textOf(node) {
    let s = node.textContent || '';
    for (const c of node.children) s += ' ' + textOf(c);
    return s.replace(/\s+/g, ' ').trim();
}

function fire(node, type, ev) {
    const fn = node._listeners[type];
    return fn ? fn(ev || { target: node, stopPropagation() { } }) : undefined;
}

// ---------- 视图装载 ----------
const IDS = ['questMount', 'questTitle', 'questBadge', 'btnQuestSave', 'btnQuestDelete',
    'entityList', 'questReport'];

function newEnv(api, opts) {
    const ids = {};
    for (const id of IDS) { ids[id] = makeNode('div'); ids[id].id = id; }
    const documentStub = {
        createElement: makeNode,
        getElementById: (id) => ids[id] || null,
        addEventListener: () => { },
        querySelectorAll: () => [],
    };
    const errors = [];
    const statuses = [];
    const sandbox = {
        console,
        document: documentStub,
        confirm: () => !!(opts && opts.confirmAnswer),
        api,
        AppState: {},
        showError: (m) => errors.push(m),
        showStatus: (m, ok) => statuses.push({ m, ok }),
        I18n: {
            getLang: () => ((opts && opts.lang) || 'zh'),
            t: (k) => k,
            tf: (k, p) => k + ' ' + JSON.stringify(p || {}),
        },
    };
    sandbox.window = sandbox;
    sandbox.self = sandbox;
    vm.createContext(sandbox);
    for (const f of ['core/questSchema.js', 'views/questEditor.js', 'views/questList.js']) {
        vm.runInContext(fs.readFileSync(path.join(SRC, f), 'utf8'), sandbox, { filename: f });
    }
    return { sb: sandbox, ids, errors, statuses };
}

function apiStub(overrides) {
    const calls = [];
    const base = {
        listQuests: async () => ({ ok: true, data: [] }),
        readQuest: async () => ({ ok: true, data: null }),
        saveQuest: async (dir, def) => { calls.push({ ch: 'saveQuest', dir, def }); return { ok: true, data: {} }; },
        deleteQuest: async (dir, id) => { calls.push({ ch: 'deleteQuest', dir, id }); return { ok: true }; },
    };
    return { api: Object.assign(base, overrides || {}), calls };
}

// 基本页 input 顺序：id, title, location 勾选, X, Y, Z, priority
function basicInputs(ids) {
    return findAll(ids.questMount, (n) => n.tagName === 'input');
}

// 发放与目标页 input 顺序：giver center X/Y/Z, radius, goal item, count, label
function componentInputs(ids) {
    return findAll(ids.questMount, (n) => n.tagName === 'input');
}

function clickTab(ids, label) {
    const tabs = findAll(ids.questMount, (n) => n.tagName === 'button' && n.textContent === label);
    assert.ok(tabs.length, '找不到页签 ' + label);
    fire(tabs[0], 'click');
}

async function main() {
    const tests = [];
    const test = (name, fn) => tests.push([name, fn]);

    // ===== 编辑器：新建 → 校验拦截 → 填表 → 保存（暂存） =====
    test('新建任务：缺物品时保存被拦，补齐后落盘 JSON 字段正确', async () => {
        const { api, calls } = apiStub();
        const env = newEnv(api);
        env.sb.window.QuestEditor.init({});

        env.sb.window.QuestEditor.open({ pack: { dir: 'P' }, def: null, isNew: true });
        assert.ok(String(env.ids.questTitle.textContent).includes('quest.badge.new'), '标题应标「新建」');
        assert.strictEqual(env.sb.window.QuestEditor.hasActiveTab(), true);

        // 基本信息页
        let inputs = basicInputs(env.ids);
        assert.strictEqual(inputs.length, 9, '基本信息页应有 id/title/坐标勾选/XYZ/priority/可重复/冷却 九个 input');
        assert.strictEqual(inputs[0].disabled, false, '新建时 id 可编辑');
        inputs[0].value = 'q_view_test'; fire(inputs[0], 'input');
        inputs[1].value = '视图测试任务'; fire(inputs[1], 'input');

        // 发放节奏：默认一次性 + 冷却禁用；勾选可重复后冷却可填
        const repChk = inputs[7];
        const cdIn = inputs[8];
        assert.strictEqual(repChk.type, 'checkbox');
        assert.strictEqual(repChk.checked, false, '默认一次性任务');
        assert.strictEqual(cdIn.disabled, true, '一次性任务的冷却应禁用');
        repChk.checked = true; fire(repChk, 'change');
        assert.strictEqual(cdIn.disabled, false, '勾选可重复后冷却可填');
        cdIn.value = '1200'; fire(cdIn, 'input');

        // 目标还没填 → 保存必须被拦（不能写进暂存区）
        await fire(env.ids.btnQuestSave, 'click');
        assert.strictEqual(calls.length, 0, '校验失败时不应调用 saveQuest');
        assert.ok(env.errors.length, '应报错');
        assert.ok(env.errors[0].includes('goals[0].item'), '错误应指向缺 item：' + env.errors[0]);

        // 切到「发放与目标」填 item / count
        clickTab(env.ids, 'quest.tab.components');
        inputs = componentInputs(env.ids);
        assert.strictEqual(inputs.length, 7, 'giver 的 XYZ+radius + goal 的 item/count/label');
        inputs[4].value = 'minecraft:apple'; fire(inputs[4], 'input');
        inputs[5].value = '8'; fire(inputs[5], 'input');

        await fire(env.ids.btnQuestSave, 'click');
        assert.strictEqual(calls.length, 1, '合法定义应保存一次');
        // vm 里造的对象原型与测试域不同，deepStrictEqual 会因原型不等而假失败 → 先过一遍 JSON
        const def = JSON.parse(JSON.stringify(calls[0].def));
        assert.strictEqual(calls[0].dir, 'P');
        assert.strictEqual(def.id, 'q_view_test');
        assert.strictEqual(def.title, '视图测试任务');
        assert.deepStrictEqual(Object.keys(def), ['id', 'title', 'description', 'givers', 'goals',
            'conditions', 'failConditions', 'children', 'location', 'priority',
            'repeatable', 'cooldownTicks'], '键序 = Java 解析顺序');
        assert.strictEqual(def.repeatable, true, '勾选后应落盘 repeatable=true');
        assert.strictEqual(def.cooldownTicks, 1200, '冷却数字应落盘');
        assert.deepStrictEqual(def.goals[0], { type: 'item_present', item: 'minecraft:apple', count: 8 },
            '可选空 label 不应落盘：' + JSON.stringify(def.goals[0]));
        assert.deepStrictEqual(def.givers[0].center, [0, 64, 0]);
        assert.strictEqual(def.givers[0].radius, 10);
        assert.ok(!('_isNew' in def), '不能泄漏编辑器内部字段');
        assert.strictEqual(env.ids.questBadge.textContent, 'quest.badge.draft', '保存后徽标应变草稿');
    });

    // ===== 未知组件 + 包私有顶层字段 =====
    test('包 Java 提供的组件与包私有顶层字段：保存时原样保留', async () => {
        const { api, calls } = apiStub();
        const env = newEnv(api);
        env.sb.window.QuestEditor.init({});
        env.sb.window.QuestEditor.open({
            pack: { dir: 'P' },
            isNew: false,
            def: {
                id: 'q_unknown', title: '未知类型', description: '', givers: [],
                goals: [{ type: 'kill', entity: 'minecraft:zombie', count: 2 }],
                conditions: [], failConditions: [], children: [], location: null, priority: 0,
                rewards: [{ item: 'minecraft:emerald', count: 1 }],
            },
        });
        // 已有任务的 id 锁死（文件名与注册名都按它生成）
        assert.strictEqual(basicInputs(env.ids)[0].disabled, true, '已有任务的 id 输入应禁用');

        // 只读预览应能画出未知组件（不崩）
        clickTab(env.ids, 'quest.tab.preview');
        assert.ok(textOf(env.ids.questMount).includes('kill'), '预览应包含未知组件 JSON');

        await fire(env.ids.btnQuestSave, 'click');
        assert.strictEqual(calls.length, 1);
        const saved = JSON.parse(JSON.stringify(calls[0].def));
        assert.deepStrictEqual(saved.goals[0], { type: 'kill', entity: 'minecraft:zombie', count: 2 },
            '未知 goal 类型必须原样保留');
        assert.deepStrictEqual(saved.rewards, [{ item: 'minecraft:emerald', count: 1 }],
            '包私有顶层字段不能被保存抹掉');
    });

    // ===== 删除 =====
    test('删除任务：确认后按 id 调 deleteQuest 并清空编辑区', async () => {
        const { api, calls } = apiStub();
        const env = newEnv(api, { confirmAnswer: true });
        env.sb.window.QuestEditor.init({});
        env.sb.window.QuestEditor.open({
            pack: { dir: 'P' }, isNew: false,
            def: { id: 'q_del', title: 'X', goals: [{ type: 'item_present', item: 'minecraft:apple' }] },
        });
        await fire(env.ids.btnQuestDelete, 'click');
        assert.deepStrictEqual(calls, [{ ch: 'deleteQuest', dir: 'P', id: 'q_del' }]);
        assert.strictEqual(env.sb.window.QuestEditor.hasActiveTab(), false);
        assert.strictEqual(env.ids.questMount.children.length, 0);
    });

    // ===== 列表 =====
    test('任务列表：行文案来自后端摘要，点击选中/删除都走对参数', async () => {
        const { api, calls } = apiStub({
            listQuests: async () => ({
                ok: true,
                data: [{ id: 'q_a', title: 'A 任务', file: 'quests/q_a.json', giverCount: 1, goalCount: 2, childCount: 0, staged: true }],
            }),
        });
        const env = newEnv(api, { confirmAnswer: true });
        let selected = null;
        env.sb.window.QuestList.init({ onSelect: (dir, q) => { selected = { dir, q }; } });
        await env.sb.window.QuestList.loadQuests('P');

        assert.strictEqual(env.ids.entityList.children.length, 1);
        const row = env.ids.entityList.children[0];
        const text = textOf(row);
        assert.ok(text.includes('A 任务') && text.includes('q_a'), '行应显示标题与 id：' + text);
        assert.ok(text.includes('quest.counts'), '行应显示发放/目标/子任务统计');
        assert.ok(text.includes('"g":1') && text.includes('"o":2'), '统计数字应来自后端：' + text);

        fire(row, 'click');
        assert.deepStrictEqual(selected, { dir: 'P', q: (await api.listQuests()).data[0] });

        await fire(row.querySelector('.act-del'), 'click');
        assert.deepStrictEqual(calls, [{ ch: 'deleteQuest', dir: 'P', id: 'q_a' }]);
        assert.strictEqual(env.ids.entityList.children.length, 1, '删除后应重新拉列表');
    });

    test('任务列表：空包显示空态文案而不是白屏', async () => {
        const { api } = apiStub();
        const env = newEnv(api);
        await env.sb.window.QuestList.loadQuests('P');
        assert.strictEqual(env.ids.entityList.children.length, 1);
        assert.strictEqual(env.sb.window.AppState.quests.length, 0);
        assert.ok(textOf(env.ids.entityList).includes('quest.empty'));
    });

    for (const [name, fn] of tests) {
        try {
            await fn();
            passed++; console.log('  ✓ ' + name);
        } catch (e) {
            failed++; console.log('  ✗ ' + name + '\n    ' + (e.stack ? e.stack.split('\n').slice(0, 4).join('\n    ') : e.message));
        }
    }
    console.log(`\n=== questView 测试: ${passed} passed, ${failed} failed ===`);
    process.exit(failed ? 1 : 0);
}

main();
