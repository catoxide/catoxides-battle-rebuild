// techView.test.js - 科技树列表/编辑器离线冒烟（DOM 桩 + vm 跑视图源码）
// 断言的是后端收到的事实：保存的 JSON、删除的参数、页签增删的落点，
// 而不是「按钮看起来在」——0.2.1 的教训是绑定漏了也能画出一模一样的界面。
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.join(__dirname, '..', 'src');

let passed = 0, failed = 0;
function test(name, fn) {
    return Promise.resolve()
        .then(fn)
        .then(() => { passed++; console.log(`  ✓ ${name}`); })
        .catch((e) => { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); });
}

// ---------- 最小 DOM 桩 ----------
function makeNode(tag) {
    const node = {
        tagName: tag, children: [], className: '', textContent: '', title: '',
        disabled: false, style: {}, value: '', type: '', checked: false, placeholder: '', rows: 0,
        min: '', max: '', step: '',
        _listeners: {},
        appendChild(child) { node.children.push(child); return child; },
        addEventListener(type, fn) { node._listeners[type] = fn; },
        setAttribute() { },
        classList: {
            add(c) { const s = new Set(String(node.className || '').split(/\s+/).filter(Boolean)); s.add(c); node.className = [...s].join(' '); },
            remove(c) { const s = new Set(String(node.className || '').split(/\s+/).filter(Boolean)); s.delete(c); node.className = [...s].join(' '); },
            contains(c) { return String(node.className || '').split(/\s+/).includes(c); },
            toggle(c, on) { on ? this.add(c) : this.remove(c); },
        },
        querySelector(selector) { return findAll(node, (n) => n !== node && matches(n, selector))[0] || null; },
        querySelectorAll(selector) {
            return findAll(node, (n) => n !== node && matches(n, selector));
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

function findAll(root, predicate) {
    const out = [];
    walk(root, (n) => { if (predicate(n)) out.push(n); });
    return out;
}

function matches(node, selector) {
    if (selector === 'input') return node.tagName === 'input';
    if (selector === 'select') return node.tagName === 'select';
    if (selector === 'textarea') return node.tagName === 'textarea';
    if (selector.startsWith('.')) return String(node.className || '').split(/\s+/).includes(selector.slice(1));
    return node.tagName === selector;
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

function click(root, text) {
    const btn = findAll(root, (n) => n.tagName === 'button' && textOf(n) === text)[0];
    assert.ok(btn, '找不到按钮: ' + text);
    return fire(btn, 'click');
}

// ---------- 视图装载 ----------
const IDS = ['techMount', 'techTitle', 'techBadge', 'btnTechNewNode', 'btnTechDelete', 'btnTechSave'];

function newEnv(api, opts = {}) {
    const ids = {};
    for (const id of IDS) { ids[id] = makeNode('div'); ids[id].id = id; }
    const documentStub = {
        createElement: makeNode,
        createElementNS: (ns, tag) => makeNode(tag),
        getElementById: (id) => ids[id] || null,
        addEventListener: () => { },
        querySelectorAll: () => [],
    };
    const errors = [];
    const statuses = [];
    const sandbox = {
        console,
        document: documentStub,
        confirm: () => opts.confirmAnswer !== false,
        api,
        AppState: {},
        showError: (m) => errors.push(m),
        showStatus: (m, ok) => statuses.push({ m, ok }),
        askText: opts.askText || (async () => null),
        I18n: {
            getLang: () => opts.lang || 'zh',
            t: (k) => k,
            tf: (k, p) => k + ' ' + JSON.stringify(p || {}),
        },
    };
    sandbox.window = sandbox;
    sandbox.self = sandbox;
    vm.createContext(sandbox);
    for (const f of ['core/techSchema.js', 'views/techEditor.js']) {
        vm.runInContext(fs.readFileSync(path.join(SRC, f), 'utf8'), sandbox, { filename: f });
    }
    sandbox.TechEditor.init({ onListChanged: opts.onListChanged || null });
    return { sb: sandbox, ids, errors, statuses };
}

function apiStub(overrides) {
    const calls = [];
    const base = {
        saveTechTree: async (dir, tree) => {
            calls.push({ ch: 'saveTechTree', dir, tree });
            return { ok: true, data: { warnings: [] } };
        },
    };
    return { api: Object.assign(base, overrides || {}), calls };
}

function sampleTree() {
    const S = require('../src/core/techSchema');
    const tree = S.newTree();
    tree.title = '测试树';
    tree.nodes = [S.newNode(tree, 'main', 0, 0)];
    tree.nodes[0].id = 'node_a';
    tree.nodes[0].title = 'A';
    return tree;
}

(async () => {
    console.log('=== 1. 打开与渲染 ===');
    await test('打开已有树：标题/页签/节点都落进 DOM', () => {
        const { api } = apiStub();
        const env = newEnv(api);
        env.sb.TechEditor.open({
            pack: { dir: 'X' }, tree: sampleTree(), exists: true,
            refs: { items: [], quests: [], namespaces: [] },
        });
        assert.ok(env.sb.TechEditor.hasActiveTab());
        assert.ok(env.ids.techTitle.textContent.includes('测试树'), env.ids.techTitle.textContent);
        const nodes = findAll(env.ids.techMount, (n) => String(n.className).split(/\s+/).includes('tech-node'));
        assert.strictEqual(nodes.length, 1);
        assert.ok(textOf(nodes[0]).includes('node_a'));
        assert.ok(env.ids.btnTechSave._listeners.click, '保存按钮应由 init 绑定');
    });

    await test('空数据开新树：默认 main 页签 + 空节点', () => {
        const { api } = apiStub();
        const env = newEnv(api);
        env.sb.TechEditor.open({ pack: { dir: 'X' }, tree: null, exists: false, refs: null });
        assert.ok(env.ids.techBadge.textContent.includes('tech.badge.new'));
        const nodes = findAll(env.ids.techMount, (n) => String(n.className).split(/\s+/).includes('tech-node'));
        assert.strictEqual(nodes.length, 0);
        assert.ok(textOf(env.ids.techMount).includes('tech.tab.layout'));
    });

    console.log('=== 2. 节点 / 页签增删 ===');
    await test('新建节点：落到当前页、自动选唯一 id、切到编辑页', () => {
        const { api } = apiStub();
        const env = newEnv(api);
        env.sb.TechEditor.open({ pack: { dir: 'X' }, tree: sampleTree(), exists: true });
        env.ids.btnTechNewNode._listeners.click();
        // 新节点后切到编辑页：切回布局页应能看到 2 张卡片
        click(env.ids.techMount, 'tech.tab.layout');
        const nodes = findAll(env.ids.techMount, (n) => String(n.className).split(/\s+/).includes('tech-node'));
        assert.strictEqual(nodes.length, 2);
    });

    await test('保存：只把树交给后端，_isNew 不落盘', async () => {
        const { api, calls } = apiStub();
        const env = newEnv(api);
        env.sb.TechEditor.open({ pack: { dir: 'X' }, tree: sampleTree(), exists: true });
        env.ids.btnTechNewNode._listeners.click();
        env.ids.btnTechSave._listeners.click();
        await new Promise((r) => setTimeout(r, 0));
        assert.strictEqual(calls.length, 1);
        assert.strictEqual(calls[0].dir, 'X');
        const json = calls[0].tree;
        assert.strictEqual(json.nodes.length, 2);
        assert.ok(!JSON.stringify(json).includes('_isNew'));
        assert.strictEqual(json.nodes[1].page, 'main');
        assert.ok(env.statuses.some((s) => s.m.includes('tech.saved')), JSON.stringify(env.statuses));
    });

    await test('删除节点：确认后节点消失', async () => {
        const { api, calls } = apiStub();
        const env = newEnv(api, { confirmAnswer: true });
        const S = env.sb.TechSchema;
        const tree = sampleTree();
        tree.nodes.push(S.newNode(tree, 'main', 1, 0));
        env.sb.TechEditor.open({ pack: { dir: 'X' }, tree, exists: true, nodeId: 'node_a' });
        env.ids.btnTechDelete._listeners.click();
        env.ids.btnTechSave._listeners.click();
        await new Promise((r) => setTimeout(r, 0));
        assert.strictEqual(calls.length, 1);
        assert.strictEqual(calls[0].tree.nodes.length, 1);
        assert.strictEqual(calls[0].tree.nodes[0].id, 'node');
    });

    await test('新建页签：askText 两次（id + 标题），空页签可删', async () => {
        const answers = ['p2', '第二页'];
        const { api } = apiStub();
        const env = newEnv(api, { askText: async () => answers.shift() || null });
        env.sb.TechEditor.open({ pack: { dir: 'X' }, tree: sampleTree(), exists: true });
        click(env.ids.techMount, '+ tech.newPage');
        await new Promise((r) => setTimeout(r, 0));
        await new Promise((r) => setTimeout(r, 0));
        assert.ok(!answers.length, '两次 askText 都应被消费');
        assert.ok(textOf(env.ids.techMount).includes('第二页'), '新页签应出现在页签条上');
        click(env.ids.techMount, 'tech.deletePage');
        await new Promise((r) => setTimeout(r, 0));
        assert.ok(!textOf(env.ids.techMount).includes('第二页'), '空页签删除后不应再显示');
    });

    console.log('=== 3. 校验页与触发器名册 ===');
    await test('校验页：合法树给出通过文案 + 触发器名册', () => {
        const { api } = apiStub();
        const env = newEnv(api);
        const tree = sampleTree();
        tree.nodes[0].requirements = [{ type: 'trigger', name: 'crafted_component' }];
        env.sb.TechEditor.open({ pack: { dir: 'X' }, tree, exists: true });
        click(env.ids.techMount, 'tech.tab.check');
        const text = textOf(env.ids.techMount);
        assert.ok(text.includes('tech.checkValid'), text);
        assert.ok(text.includes('crafted_component'), text);
        assert.ok(text.includes('TechHooks.TriggerEvent'), '应展示程序触发接口');
        assert.ok(text.includes('/techtree trigger'), '应展示游戏内命令接口');
    });

    await test('校验失败时保存被拦下，不发 IPC 且跳到校验页', async () => {
        const { api, calls } = apiStub();
        const env = newEnv(api);
        const tree = sampleTree();
        tree.nodes[0].costs = [{ type: 'unknown_cost', amount: 1 }];
        env.sb.TechEditor.open({ pack: { dir: 'X' }, tree, exists: true });
        env.ids.btnTechSave._listeners.click();
        await new Promise((r) => setTimeout(r, 0));
        assert.strictEqual(calls.length, 0, '校验失败不应写盘');
        assert.ok(env.errors.some((e) => e.includes('tech.saveInvalid')), JSON.stringify(env.errors));
        assert.ok(textOf(env.ids.techMount).includes('成本类型未知'), '应跳到校验页并显示具体错误');
    });

    console.log(`\n=== techView 测试: ${passed} passed, ${failed} failed ===`);
    process.exit(failed ? 1 : 0);
})();
