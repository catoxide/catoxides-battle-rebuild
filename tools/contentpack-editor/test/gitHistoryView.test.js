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
// 与 src/index.html 的回溯面板逐一对应（那边没有 #historyTitle：标题 span 归 i18n 管）
for (const id of ['historyOverlay', 'historyBody', 'historyStatus', 'historyRepo', 'historyNote',
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
    'history.targetSource': '源码目录', 'history.targetJar': '产物',
    'pack.cancel': '取消',
    // 第 3 步：回滚预览 / 确认 / 撤销
    'history.rollbackTo': '⤺ 回到这次', 'history.rollbackToHint': '先预览再确认',
    'history.planning': '预览中（只算不动）…', 'history.planFailed': '预览失败：',
    'history.planRefused': '已拒绝执行，工作区未做任何改动',
    'history.restoreN': '改写/找回 {n} 个', 'history.removeN': '删除 {n} 个',
    'history.removeUntrackedN': '其中 {n} 个是未跟踪文件', 'history.unchangedN': '不动 {n} 个',
    'history.skippedN': '{n} 项不按文件删除', 'history.planScope': '范围：',
    'history.planSafety': '执行顺序：归档 → 强制检查点提交 → 范围内 restore。',
    'history.detachedWarn': '游离 HEAD，已拒绝。', 'history.conflictWarn': '范围内有未解决冲突。',
    'history.planRestore': '会被改写/找回（{n}）', 'history.planRemove': '会被删除（{n}）',
    'history.planUnchanged': '保持不动（{n}）', 'history.moreFiles': '个文件未列出',
    'history.nothingToDo': '范围内已经和这次提交一致', 'history.noChangeRun': '什么都没动',
    'history.confirmRollback': '确认回滚', 'history.rolling': '回滚执行中…',
    'history.confirmLabel': '把 {name} 回到 {sha}？改写 {r}、删除 {d}（未跟踪 {u}）。',
    'history.didRollback': '已回到 {sha}', 'history.archivedAt': '归档位置：',
    'history.checkpoint': '检查点提交：', 'history.checkpointClean': '（沿用原 HEAD）',
    'history.undoLabel': '上一次回滚：{name} → {to}，锚点 {sha}。',
    'history.undo': '↩ 撤销这次回滚', 'history.undoDone': '已撤销：还原到检查点 {sha}',
    'history.undoFailed': '撤销失败：',
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
    gitRollbackPlan: async (p) => { calls.push(['plan', p]); return windowStub.__plan; },
    gitRollback: async (p) => { calls.push(['rollback', p]); return windowStub.__rollback; },
    gitRollbackUndo: async (p) => { calls.push(['undo', p]); return windowStub.__undo; },
};
// 面板确认对话框：默认「按下确认」，测取消时改成返回 cancel
windowStub.askChoice = async () => (windowStub.__choice === undefined ? ZH['history.confirmRollback'] : windowStub.__choice);

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

test('10. open/close 只切 hidden 类', async () => {
    VIEW.close();
    assert.strictEqual(VIEW.isOpen(), false);
    VIEW.open();                       // 不带包：沿用上一次的目标，不重置状态
    assert.ok(!ids.historyOverlay.classList.contains('hidden'), 'open 后应可见');
    VIEW.close();
    assert.strictEqual(VIEW.isOpen(), false, 'close 后应隐藏');
    await settle();                    // 把这一轮测量跑完再交给下一条，别把 busy 漏给后面的用例
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

// ---------- 第 3 步：回滚预览 / 执行 / 撤销 ----------

function planFixture(over = {}) {
    return Object.assign({
        ok: true, isRepo: true, root: '/proj', branch: 'Neoforge-1.21.1', detached: false,
        head: SHA_A, headMeta: null, target: SHA_B,
        targetMeta: { sha: SHA_B, short: 'bbb2222', date: '2026-10-14T21:05:35+08:00', subject: '内容包编辑器 W 系' },
        paths: ['contentpacks-source/melee-combat-pack', 'contentpacks/melee-combat-pack.jar'],
        restore: ['contentpacks-source/melee-combat-pack/entities/a.json', 'contentpacks-source/melee-combat-pack/gone.json'],
        remove: ['contentpacks-source/melee-combat-pack/scratch/tmp.json', 'contentpacks/melee-combat-pack.jar'],
        removeUntracked: ['contentpacks-source/melee-combat-pack/scratch/tmp.json'],
        removeTracked: ['contentpacks/melee-combat-pack.jar'],
        removeSkipped: [], unchanged: ['contentpacks-source/melee-combat-pack/MANIFEST.MF'],
        restoreCount: 2, removeCount: 2, removeSkippedCount: 0, unchangedCount: 1, totalNow: 4,
        worktreeRaw: '', tooMany: false, conflict: false, note: null,
    }, over);
}
const COMMIT_B = { sha: SHA_B, abbrev: 'bbb2222', author: 'catoxide', date: '2026-10-14T21:05:35+08:00', subject: '内容包编辑器 W 系', merge: true, fileCount: 21, files: [] };

async function settle(n = 4) { for (let i = 0; i < n; i++) await new Promise((r) => setTimeout(r, 0)); }

test('12. 每条提交都有「⤺ 回到这次」，且视图只交坐标、不交路径清单或命令', async () => {
    windowStub.__history = { ok: true, data: historyFixture() };
    windowStub.__work = { ok: true, data: workFixture() };
    VIEW.open(PACK);
    await settle();
    const text = textOf(ids.historyBody);
    const occurrences = (text.match(/⤺ 回到这次/g) || []).length;
    assert.strictEqual(occurrences, 2, '两条提交应有两个回滚入口，实际 ' + occurrences);
    // 头栏必须写出是哪个包：这里从第 3 步起会真的动工作区，看不出目标的确认框等于盲签
    assert.ok((ids.historyRepo.textContent || '').includes('melee-combat-pack'), '头栏要有包名，实际：' + ids.historyRepo.textContent);
    await VIEW._startRollback(COMMIT_B);
    const p = calls.filter((c) => c[0] === 'plan').pop();
    assert.ok(p, '应调用 gitRollbackPlan');
    assert.strictEqual(p[1].sha, SHA_B);
    assert.strictEqual(p[1].packDir, PACK.source.dir);
    assert.strictEqual(p[1].jarPath, PACK.built.path);
    assert.strictEqual(p[1].label, 'melee-combat-pack');
    assert.ok(!('paths' in p[1]), '路径由主进程从坐标换算，前端不下发');
    assert.ok(!('acknowledge' in p[1]), '预览不该带确认参数（它不动东西）');
});

test('13. 预览只把后端算好的数字摆出来：改写/删除/未跟踪/不动 + 固定执行顺序', async () => {
    windowStub.__plan = { ok: true, data: planFixture() };
    VIEW.open(PACK);                            // 重开一次：清掉上一条留下的 pending，并保证不在测量中
    await settle();
    await VIEW._startRollback(COMMIT_B);
    const text = textOf(ids.historyBody);
    assert.ok(text.includes('改写/找回 2 个'), text.slice(0, 400));
    assert.ok(text.includes('删除 2 个'), text);
    assert.ok(text.includes('其中 1 个是未跟踪文件'), '未跟踪的那部分必须单独说出来：' + text);
    assert.ok(text.includes('不动 1 个'), text);
    assert.ok(text.includes('执行顺序：归档 → 强制检查点提交 → 范围内 restore'), '安全顺序要写在确认之前');
    assert.ok(text.includes('bbb2222'), '目标提交短号');
    assert.ok(text.includes('HEAD aaaaaaaa'), '当前 HEAD 要写出来，确认时才知道自己在动哪一份');
    assert.ok(text.includes('会被删除（2）'), '文件名清单分节');
    assert.ok(text.includes('contentpacks/melee-combat-pack.jar'), '具体文件名列出来');
    assert.ok(text.includes('确认回滚'), '给确认按钮');
    assert.ok(text.includes('取消'), '也给取消');
});

test('14. 预览被后端拒绝（上限/冲突/游离 HEAD）：显示原因 + 「未做任何改动」，不给确认按钮', async () => {
    windowStub.__plan = { ok: false, error: '超过单次上限', data: planFixture({ ok: false, tooMany: true, note: '本次要触碰 501 个文件，超过单次上限 500', restore: [], remove: [], restoreCount: 501, removeCount: 0 }) };
    VIEW.open(PACK);
    await settle();
    await VIEW._startRollback(COMMIT_B);
    const text = textOf(ids.historyBody);
    assert.ok(text.includes('超过单次上限 500'), text.slice(0, 400));
    assert.ok(text.includes('已拒绝执行，工作区未做任何改动'), text);
    assert.ok(!text.includes('确认回滚'), '拒绝时不能还留着执行按钮：' + text.slice(-300));
});

test('15. 确认执行：acknowledge 与 expectedHead 都来自预览结果；成功后给出撤销锚点并重测', async () => {
    VIEW.open(PACK);
    await settle();
    windowStub.__plan = { ok: true, data: planFixture() };
    windowStub.__rollback = {
        ok: true,
        data: Object.assign(planFixture(), {
            stage: 'apply', archiveDir: 'C:/userData/rollback-archive/2026-10-24T08-00-00_melee',
            checkpointSha: SHA_A, checkpointCommitted: false, checkpointSkipped: '范围内没有未提交改动，检查点即当前 HEAD',
            restored: planFixture().restore, removed: planFixture().remove, removedByGitCount: 1,
            restoredCount: 2, removedCount: 2, failed: [], noChange: false,
            note: '已回到 bbb2222：改写 2 个、删除 2 个（其中 1 个由 git restore 顺带清掉）。回滚前的状态有两份备份。',
        }),
    };
    await VIEW._startRollback(COMMIT_B);
    await VIEW._confirmRollback(COMMIT_B);
    await settle();
    const rb = calls.filter((c) => c[0] === 'rollback').pop();
    assert.ok(rb, '应调用 gitRollback');
    assert.strictEqual(rb[1].acknowledge, true, '显式确认');
    assert.strictEqual(rb[1].expectedHead, SHA_A, '把预览时看到的 HEAD 交回复验');
    assert.strictEqual(rb[1].sha, SHA_B);
    const st = textOf(ids.historyStatus);
    assert.ok(st.includes('已回到 bbb2222：改写 2 个'), st);
    assert.ok(st.includes('C:/userData/rollback-archive'), '归档位置要能复制出来：' + st);
    assert.ok(st.includes('检查点提交：' + SHA_A.slice(0, 8)) && st.includes('（沿用原 HEAD）'), st);
    const body = textOf(ids.historyBody);
    assert.ok(body.includes('↩ 撤销这次回滚'), '给出撤销入口：' + body.slice(-300));
    assert.ok(body.includes('上一次回滚：melee-combat-pack → bbb2222'), body.slice(-300));
    assert.ok(calls.filter((c) => c[0] === 'history').length >= 2, '回滚后重新测量过');
    assert.strictEqual(VIEW._state().pending, null, '预览状态用完即弃');
    assert.strictEqual(VIEW._state().undoAnchor.checkpointSha, SHA_A);
});

test('16. 点「取消」不执行；后端拒绝时把阶段与归档位置说出来，且不留撤销锚点', async () => {
    VIEW.open(PACK);
    await settle();
    windowStub.__plan = { ok: true, data: planFixture() };
    windowStub.__choice = ZH['pack.cancel'];
    await VIEW._startRollback(COMMIT_B);
    const before = calls.filter((c) => c[0] === 'rollback').length;
    await VIEW._confirmRollback(COMMIT_B);
    assert.strictEqual(calls.filter((c) => c[0] === 'rollback').length, before, '取消后一次都不该执行');
    delete windowStub.__choice;

    VIEW.open(PACK);                            // 重新预览一次（同一个 sha 再点算「收起预览」）
    await settle();
    windowStub.__rollback = { ok: false, error: '索引里有本次范围之外的已暂存改动', data: { stage: 'checkpoint', archiveDir: 'C:/ud/rollback-archive/x', note: '索引里有本次范围之外的已暂存改动', failed: [] } };
    await VIEW._startRollback(COMMIT_B);
    await VIEW._confirmRollback(COMMIT_B);
    const st = textOf(ids.historyStatus);
    assert.ok(st.includes('阶段：checkpoint'), st);
    assert.ok(st.includes('C:/ud/rollback-archive/x'), '失败也要告诉用户档案在哪：' + st);
    assert.strictEqual(VIEW._state().undoAnchor, null, '失败不留撤销锚点');
});

test('17. 撤销：锚点来自上一次执行结果，成功后清掉并再测一次', async () => {
    VIEW.open(PACK);
    await settle();
    windowStub.__plan = { ok: true, data: planFixture() };
    windowStub.__rollback = { ok: true, data: Object.assign(planFixture(), { stage: 'apply', checkpointSha: SHA_A, checkpointCommitted: true, archiveDir: 'C:/ud/a', note: '已回到 bbb2222', failed: [] }) };
    await VIEW._startRollback(COMMIT_B);
    await VIEW._confirmRollback(COMMIT_B);
    await settle();
    windowStub.__undo = { ok: true, data: { ok: true, stage: 'undo', isUndo: true, note: '已还原到检查点 aaaaaaaa（也就是回滚前的状态）', restoredCount: 2, removedCount: 1, failed: [] } };
    const before = calls.filter((c) => c[0] === 'undo').length;
    await VIEW._undoRollback();
    await settle();
    assert.strictEqual(calls.filter((c) => c[0] === 'undo').length, before + 1);
    const u = calls.filter((c) => c[0] === 'undo').pop();
    assert.strictEqual(u[1].checkpointSha, SHA_A, '撤销目标是刚才那次检查点');
    assert.strictEqual(u[1].packDir, PACK.source.dir);
    assert.ok(textOf(ids.historyStatus).includes('已撤销'), textOf(ids.historyStatus));
    assert.strictEqual(VIEW._state().undoAnchor, null, '撤销过一次后锚点失效');
    assert.ok(!textOf(ids.historyBody).includes('↩ 撤销这次回滚'), '撤销入口跟着消失');
});

test('18. 没有锚点时撤销是空操作（不误调后端）', async () => {
    VIEW.open(PACK);
    await settle();
    assert.strictEqual(VIEW._state().undoAnchor, null);
    const before = calls.filter((c) => c[0] === 'undo').length;
    await VIEW._undoRollback();
    assert.strictEqual(calls.filter((c) => c[0] === 'undo').length, before);
});

test('19. 换包时丢掉上一个包的预览与撤销锚点（锚点绑的是路径，跨包用不得）', async () => {
    VIEW.open(PACK);
    await settle();
    windowStub.__plan = { ok: true, data: planFixture() };
    windowStub.__rollback = { ok: true, data: Object.assign(planFixture(), { stage: 'apply', checkpointSha: SHA_A, checkpointCommitted: true, archiveDir: 'C:/ud/a', note: '已回到 bbb2222', failed: [] }) };
    await VIEW._startRollback(COMMIT_B);
    await VIEW._confirmRollback(COMMIT_B);
    await settle();
    assert.ok(VIEW._state().undoAnchor, '先做出一个可用锚点');
    VIEW.open({ dirName: 'other-pack', jarName: 'other.jar', source: { dir: '/proj/contentpacks-source/other' }, built: { exists: false }, archived: { exists: false } });
    await settle();
    const s = VIEW._state();
    assert.strictEqual(s.pending, null);
    assert.strictEqual(s.undoAnchor, null);
    assert.strictEqual(s.target.dirName, 'other-pack');
});

test('20. 上一轮测量没跑完就再开一次：结束后要按最新目标补测，不能停在旧数据上', async () => {
    // 真实触发路径：连着点两个包 / 测量中点了刷新。第一次还占着 busy，第二次若被丢掉，
    // 页面就一直显示上一个包的提交 —— 用户看到的是一份「看起来正常但属于别的包」的历史。
    windowStub.__history = { ok: true, data: historyFixture({ commits: [], note: '旧包：没有提交' }) };
    windowStub.__work = { ok: true, data: workFixture({ dirty: false, changes: [], changedCount: 0, untracked: [], untrackedCount: 0 }) };
    VIEW.open(PACK);
    windowStub.__history = { ok: true, data: historyFixture() };
    VIEW.open(PACK);                            // 同步紧接的第二次：第一次仍在测量中
    await settle(6);
    const s = VIEW._state();
    assert.ok(s.data && s.data.ok, '最后一次 open 的目标数据必须落地');
    assert.strictEqual((s.data.commits || []).length, 2, '补测拿到的是新目标的两条提交');
    assert.strictEqual((textOf(ids.historyBody).match(/⤺ 回到这次/g) || []).length, 2, '补测结果要渲染出来：每条提交一个回滚入口');
});

runCases().then(() => {
    console.log(`\n=== gitHistoryView 测试: ${passed} passed, ${failed} failed ===`);
    process.exit(failed ? 1 : 0);
}).catch((e) => { console.error(e); process.exit(1); });
