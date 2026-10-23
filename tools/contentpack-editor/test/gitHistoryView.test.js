// gitHistoryView.test.js - 提交回溯视图（第 2 步，只读）的离线冒烟测试
// 与 deployView.test.js 同款思路：最小 DOM 桩 + vm 跑视图源码，断言后端事实确实落到文案上。
// 另加一条文案完整性检查：视图里引用的 i18n 键必须中英文各有一份（漏一个 = 切语言后显示键名）。
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let passed = 0, failed = 0;
const CASES = [];
function test(name, fn) { CASES.push([name, fn]); }
async function runCases() {
    for (const [name, fn] of CASES) {
        try {
            await fn();
            passed++; console.log(`  ✓ ${name}`);
        } catch (e) {
            failed++; console.log(`  ✗ ${name}\n    ${e.stack ? e.stack.split('\n').slice(0, 3).join('\n    ') : e.message}`);
        }
    }
}

// ---------- 最小 DOM 桩 ----------
function makeNode(tag) {
    const node = {
        tagName: tag, children: [], className: '', textContent: '', title: '',
        disabled: false, style: {}, _html: '',
        classList: {
            add(c) { const s = new Set((node.className || '').split(/\s+/).filter(Boolean)); s.add(c); node.className = [...s].join(' '); },
            remove(c) { const s = new Set((node.className || '').split(/\s+/).filter(Boolean)); s.delete(c); node.className = [...s].join(' '); },
            contains(c) { return (node.className || '').split(/\s+/).includes(c); },
            toggle(c, on) { on ? this.add(c) : this.remove(c); },
        },
        appendChild(child) { node.children.push(child); return child; },
        addEventListener() { },
        get childNodes() { return node.children; },
    };
    Object.defineProperty(node, 'innerHTML', {
        get() { return node._html; },
        set(v) { node._html = v; if (v === '') node.children.length = 0; },
    });
    return node;
}
function textOf(node) {
    let s = node.textContent || '';
    for (const c of node.children) s += ' ' + textOf(c);
    return s.replace(/\s+/g, ' ').trim();
}

const ids = {};
for (const id of ['historyOverlay', 'historyBody', 'historyStatus', 'historyTitle', 'historyRepo',
    'btnHistoryClose', 'btnHistoryRefresh']) {
    ids[id] = makeNode('div');
    ids[id].id = id;
}
const documentStub = {
    createElement: makeNode,
    getElementById: (id) => ids[id] || null,
    addEventListener: () => { },
    querySelectorAll: () => [],
};

const ZH = {
    'history.title': '提交回溯（只读）', 'history.refresh': '⟳ 刷新', 'history.close': '✕ 关闭',
    'history.repo': '分支', 'history.detached': '（分离 HEAD）',
    'history.tracked': '已跟踪 {n} 个文件', 'history.untracked': '未跟踪（git 里还没有它）',
    'history.ignored': '被忽略 ← {rule}', 'history.clean': '工作区干净：这些路径没有未提交改动',
    'history.dirty': '工作区未提交：{changed} 处改动 · {untracked} 处未跟踪',
    'history.dirtyHint': '现在若回溯会丢掉上面这些改动。',
    'history.commits': '提交历史', 'history.none': '这些路径在 git 里没有提交记录',
    'history.files': '{n} 个文件', 'history.merge': '合并提交（差异按第一父计算）',
    'history.more': '加载更早的 {n} 条', 'history.loading': '读取中…',
    'history.loadingDiff': '差异读取中…', 'history.failed': '读取失败：',
    'history.noTarget': '未选择包', 'history.diffTruncated': '差异已截断',
    'history.binary': '含二进制文件', 'history.noDiff': '（没有文本差异）',
};
const windowStub = {
    I18n: {
        t: (k) => ZH[k] || k,
        tf: (k, p) => Object.entries(p || {}).reduce((a, [kk, v]) => a.replace('{' + kk + '}', v), ZH[k] || k),
    },
};
const calls = [];
const SHA_A = 'a'.repeat(40), SHA_B = 'b'.repeat(40);
windowStub.api = {
    gitHistory: async (p) => { calls.push(['history', p]); return windowStub.__history; },
    gitWorkingTree: async (p) => { calls.push(['work', p]); return windowStub.__work; },
    gitCommit: async (p) => { calls.push(['commit', p]); return windowStub.__commit; },
};

const sandbox = { window: windowStub, document: documentStub, console };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'src', 'views', 'gitHistoryView.js'), 'utf8'), sandbox);
const VIEW = windowStub.GitHistoryView;

const PACK = {
    dirName: 'melee-combat-pack', jarName: 'melee-combat-pack.jar',
    source: { dir: '/proj/contentpacks-source/melee-combat-pack' },
    built: { exists: true, path: '/proj/contentpacks/melee-combat-pack.jar' },
    archived: { exists: false },
};
function historyFixture(over = {}) {
    return Object.assign({
        ok: true, isRepo: true, root: '/proj', branch: 'Neoforge-1.21.1', detached: false,
        head: { sha: SHA_A, abbrev: 'aaa1111', author: 'catoxide', date: '2026-10-24T06:13:40+08:00', subject: 'HEAD 提交' },
        paths: ['contentpacks-source/melee-combat-pack', 'contentpacks/melee-combat-pack.jar'],
        notes: [], limit: 40, truncated: false, note: null,
        pathStates: [
            { rel: 'contentpacks-source/melee-combat-pack', tracked: true, trackedCount: 34, untrackedCount: 2, ignored: false, ignoreRule: null, neverCommitted: false },
            { rel: 'contentpacks/melee-combat-pack.jar', tracked: true, trackedCount: 1, untrackedCount: 0, ignored: false, ignoreRule: null, neverCommitted: false },
        ],
        commits: [
            {
                sha: SHA_A, abbrev: 'aaa1111', author: 'catoxide', date: '2026-10-24T06:13:40+08:00',
                subject: '修复挥剑动画', merge: false, fileCount: 3,
                files: [{ status: 'M', path: 'contentpacks-source/melee-combat-pack/spark_animations/item/ns/sword/out.json', oldPath: null }],
            },
            { sha: SHA_B, abbrev: 'bbb2222', author: 'catoxide', date: '2026-10-14T21:05:35+08:00', subject: '内容包编辑器 W 系', merge: true, fileCount: 21, files: [] },
        ],
    }, over);
}
function workFixture(over = {}) {
    return Object.assign({
        ok: true, isRepo: true, dirty: true, changedCount: 2, untrackedCount: 1,
        changes: [
            { code: 'M', path: 'contentpacks-source/melee-combat-pack/spark_animations/item/ns/sword/out.json', oldPath: null, staged: false, unstaged: true, label: '已修改' },
            { code: 'A', path: 'contentpacks-source/melee-combat-pack/MANIFEST.MF', oldPath: null, staged: true, unstaged: false, label: '新增（已暂存）' },
        ],
        untracked: ['contentpacks-source/melee-combat-pack/scratch/'],
        worktreeSummary: '1 file changed, 4 insertions(+), 2 deletions(-)',
        stagedSummary: '1 file changed, 1 insertion(+)',
        note: null,
    }, over);
}

console.log('=== 提交回溯视图测试 ===');

test('1. 视图挂载并暴露 init/open/close/refresh/rerender/isOpen', () => {
    assert.strictEqual(typeof VIEW, 'object');
    for (const k of ['init', 'open', 'close', 'refresh', 'rerender', 'isOpen']) {
        assert.strictEqual(typeof VIEW[k], 'function', k + ' 应为函数');
    }
});

test('2. open(pack) 走 git 路径：源码目录 + 产物 jar 都交给后端，前端不拼 git 命令', async () => {
    windowStub.__history = { ok: true, data: historyFixture() };
    windowStub.__work = { ok: true, data: workFixture() };
    VIEW.init();
    VIEW.open(PACK);
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    const h = calls.find((c) => c[0] === 'history');
    assert.ok(h, '应调用 gitHistory');
    assert.strictEqual(h[1].packDir, PACK.source.dir);
    assert.strictEqual(h[1].jarPath, PACK.built.path);
    assert.strictEqual(h[1].limit, 40);
    assert.ok(!('cmd' in h[1]) && !('args' in h[1]), '视图不得下发命令字符串');
});

test('3. 提交列表落文案：分支 / 短 sha / 日期 / 作者 / 主题 / 文件数 / 已跟踪计数', async () => {
    const text = textOf(ids.historyBody);
    assert.ok(text.includes('Neoforge-1.21.1'), text.slice(0, 200));
    assert.ok(text.includes('aaa1111'), '短 sha');
    assert.ok(text.includes('修复挥剑动画'), '主题');
    assert.ok(text.includes('2026-10-24 06:13'), '日期');
    assert.ok(text.includes('catoxide'), '作者');
    assert.ok(text.includes('3 个文件'), '文件数');
    assert.ok(text.includes('已跟踪 34 个文件'), '跟踪计数');
    assert.ok(text.includes('合并提交'), 'merge 提交要标注');
});

test('4. 工作区未提交改动单独列出，并给出「回溯会丢东西」的提示', async () => {
    const text = textOf(ids.historyBody);
    assert.ok(text.includes('工作区未提交：2 处改动 · 1 处未跟踪'), text.slice(0, 300));
    assert.ok(text.includes('sword/out.json'), '改动文件名');
    assert.ok(text.includes('?? contentpacks-source/melee-combat-pack/scratch/'), '未跟踪目录');
    assert.ok(text.includes('git diff: 1 file changed'), 'diff 摘要');
    assert.ok(text.includes('现在若回溯会丢掉上面这些改动'), '安全提示');
});

test('5. 展开差异：文件清单 + 增删数 + 原文（后端截断标志要显示）', async () => {
    windowStub.__commit = {
        ok: true,
        data: {
            ok: true, sha: SHA_A, notes: [],
            meta: { sha: SHA_A, abbrev: 'aaa1111', subject: '修复挥剑动画' },
            files: [{ status: 'M', path: 'contentpacks-source/melee-combat-pack/spark_animations/item/ns/sword/out.json', oldPath: null }],
            fileCount: 1, additions: 4, deletions: 2, summary: '1 file changed, 4 insertions(+), 2 deletions(-)',
            diff: '--- a/out.json\n+++ b/out.json\n@@\n-"speed": 1.0\n+"speed": 1.4\n',
            diffBytes: 120, truncated: false, binaryHint: false, note: null,
        },
    };
    await VIEW._loadDiff(SHA_A);
    const text = textOf(ids.historyBody);
    assert.ok(text.includes('+4 −2'), text.slice(-400));
    assert.ok(text.includes('"speed": 1.4'), '差异原文');
    assert.ok(calls.some((c) => c[0] === 'commit' && c[1].sha === SHA_A), '应按 sha 请求差异');
});

test('6. 差异被截断 → 明说，不把半份差异当成全份', async () => {
    windowStub.__commit = { ok: true, data: Object.assign({}, windowStub.__commit.data, { diff: 'x'.repeat(50), truncated: true, diffBytes: 999999 }) };
    await VIEW._loadDiff(SHA_A);
    const text = textOf(ids.historyBody);
    assert.ok(text.includes('差异已截断'), text.slice(-300));
});

test('7. 无提交记录 / 未跟踪：显示后端 note，不显示成「无改动」', async () => {
    windowStub.__history = { ok: true, data: historyFixture({ commits: [], note: '这些路径在 git 里没有提交记录（未跟踪）', pathStates: [{ rel: 'contentpacks-source/new', tracked: false, trackedCount: 0, untrackedCount: 5, ignored: false, ignoreRule: null, neverCommitted: true }] }) };
    windowStub.__work = { ok: true, data: workFixture({ dirty: false, changedCount: 0, changes: [], untracked: ['contentpacks-source/new/'], untrackedCount: 1 }) };
    VIEW.open(PACK);
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    const text = textOf(ids.historyBody);
    assert.ok(text.includes('未跟踪（git 里还没有它）'), text.slice(0, 300));
    assert.ok(text.includes('这些路径在 git 里没有提交记录'), text.slice(0, 300));
});

test('8. 命中上限 → 给「加载更早」入口', async () => {
    windowStub.__history = { ok: true, data: historyFixture({ truncated: true, note: '只显示最近 40 条，可能还有更早的' }) };
    windowStub.__work = { ok: true, data: workFixture({ dirty: false, changes: [], changedCount: 0, untracked: [], untrackedCount: 0 }) };
    VIEW.open(PACK);
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    assert.ok(textOf(ids.historyBody).includes('加载更早的 40 条'), textOf(ids.historyBody).slice(-200));
});

test('9. 不是 git 仓库 / IPC 失败：原文显示，页面不崩', async () => {
    windowStub.__history = { ok: false, error: '当前项目根不是 git 仓库' };
    windowStub.__work = { ok: false, error: '当前项目根不是 git 仓库' };
    VIEW.open(PACK);
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    assert.ok(textOf(ids.historyBody).includes('当前项目根不是 git 仓库'), textOf(ids.historyBody).slice(0, 200));
    assert.ok(textOf(ids.historyStatus).includes('当前项目根不是 git 仓库'));
    // 失败后仍应可关闭
    VIEW.close();
    assert.strictEqual(VIEW.isOpen(), false);
});

test('10. open/close 只切 hidden 类', () => {
    VIEW.close();
    assert.strictEqual(VIEW.isOpen(), false);
    VIEW.open();                       // 不带包：沿用上一次的目标，不重置状态
    assert.ok(!ids.historyOverlay.classList.contains('hidden'), 'open 后应可见');
    VIEW.close();
    assert.strictEqual(VIEW.isOpen(), false, 'close 后应隐藏');
});

test('11. 视图引用的 i18n 键在 src/i18n.js 里中英文各有一份（漏译会在切语言时露出键名）', () => {
    const i18nSrc = fs.readFileSync(path.join(__dirname, '..', 'src', 'i18n.js'), 'utf8');
    const used = new Set();
    for (const f of ['gitHistoryView.js', 'deployStatus.js']) {
        const src = fs.readFileSync(path.join(__dirname, '..', 'src', 'views', f), 'utf8');
        for (const m of src.matchAll(/'((?:history|deploy)\.[A-Za-z0-9_]+)'/g)) used.add(m[1]);
    }
    assert.ok(used.size >= 20, '应扫到足够多的键：' + used.size);
    const missing = [];
    for (const k of used) {
        const re = new RegExp("'" + k.replace(/\./g, '\\.') + "':", 'g');
        const n = (i18nSrc.match(re) || []).length;
        if (n < 2) missing.push(`${k}（出现 ${n} 次）`);
    }
    assert.deepStrictEqual(missing, [], '以下键缺中文或缺英文：\n  ' + missing.join('\n  '));
});

runCases().then(() => {
    console.log(`\n=== gitHistoryView 测试: ${passed} passed, ${failed} failed ===`);
    process.exit(failed ? 1 : 0);
}).catch((e) => { console.error(e); process.exit(1); });
