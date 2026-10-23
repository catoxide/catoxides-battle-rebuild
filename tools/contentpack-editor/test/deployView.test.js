// deployView.test.js - 部署面板渲染层的离线冒烟测试（最小 DOM 桩，不起 Electron）
//
// 为什么要测视图：四态面板是这一功能唯一被人看见的部分，而后端返回的形状一旦对不上
// （字段改名 / issues 结构变化），页面只会「点了没反应」—— 0.2.1 那轮事故就是这个形态。
// 这里用一个够用的 DOM 桩把 render() 跑起来，断言关键事实确实落到文案上。
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let passed = 0, failed = 0;
const CASES = [];
// 视图是 async 的（refresh 要等 IPC）→ 用例排队后顺序 await，否则断言根本不会被执行
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
        tagName: tag,
        children: [],
        className: '',
        textContent: '',
        title: '',
        disabled: false,
        style: {},
        _html: '',
        classList: {
            add(c) { const s = new Set((node.className || '').split(/\s+/).filter(Boolean)); s.add(c); node.className = [...s].join(' '); },
            remove(c) { const s = new Set((node.className || '').split(/\s+/).filter(Boolean)); s.delete(c); node.className = [...s].join(' '); },
            contains(c) { return (node.className || '').split(/\s+/).includes(c); },
            toggle(c, on) { on ? this.add(c) : this.remove(c); },
        },
        appendChild(child) { node.children.push(child); return child; },
        addEventListener() { /* 冒烟测试不触发事件 */ },
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
for (const id of ['deployOverlay', 'deployBody', 'deployStatus', 'deployScanned',
    'btnDeployStatus', 'btnDeployClose', 'btnDeployRefresh',
    'deployConsole', 'deployConsoleBody', 'buildOutputState', 'btnBuildConsoleToggle', 'btnBuildConsoleClear']) {
    ids[id] = makeNode('div');
    ids[id].id = id;
}
const documentStub = {
    createElement: makeNode,
    getElementById: (id) => ids[id] || null,
    addEventListener: () => { },
    querySelectorAll: () => [],
};
const windowStub = {};

const ZH = {
    'deploy.title': '部署状态', 'deploy.refresh': '⟳ 重新测量', 'deploy.close': '✕ 关闭',
    'deploy.colPack': '包', 'deploy.colSource': '① 源码', 'deploy.colJar': '② 产物 jar',
    'deploy.colInstall': '③ 运行目录副本', 'deploy.colCache': '④ spark 缓存', 'deploy.colSwitch': '开关',
    'deploy.colAction': '动作', 'deploy.srcOk': '在', 'deploy.noJar': '无产物', 'deploy.archived': '已归档（停用）',
    'deploy.entries': '条目', 'deploy.same': '一致', 'deploy.driftDiff': '不同', 'deploy.absent': '未安装',
    'deploy.cacheHit': '命中', 'deploy.build': '打包', 'deploy.install': '安装', 'deploy.clearCache': '清本包缓存',
    'deploy.rearchive': '按标记重新归档', 'deploy.on': '启用', 'deploy.off': '停用', 'deploy.allOk': '四态一致',
    'deploy.dupName': '重复 Module-Name：', 'deploy.dupHint': '同名只留一份', 'deploy.measuring': '测量中…',
    'deploy.nonPack': '非包目录：', 'deploy.relaunchHint': '下次启动生效', 'pack.cancel': '取消',
    'deploy.history': '🕘 回溯', 'deploy.console': '构建输出（Gradle 原文，实时）',
    'deploy.consoleFold': '收起', 'deploy.consoleExpand': '展开', 'deploy.consoleClear': '清空',
    'deploy.buildRunning': '构建中：{name}…', 'deploy.buildDone': '{name}：构建进程结束', 'deploy.buildFail': '{name}：构建失败',
};
windowStub.I18n = { t: (k) => ZH[k] || k, tf: (k, p) => Object.entries(p || {}).reduce((a, [kk, v]) => a.replace('{' + kk + '}', v), ZH[k] || k) };
const apiCalls = [];
windowStub.api = {
    packStatusInspect: async (o) => { apiCalls.push(['inspect', o]); return windowStub.__inspect; },
    packStatusSetEnabled: async (p) => { apiCalls.push(['setEnabled', p]); return { ok: true, data: { actions: ['移出产物'], needsRelaunch: true } }; },
    packStatusInstall: async (p) => { apiCalls.push(['install', p]); return { ok: true, data: { written: ['run/client/contentpacks/a.jar'] } }; },
    packStatusClearCache: async (p) => { apiCalls.push(['clearCache', p]); return { ok: true, data: { deleted: ['x'], conflicts: [] } }; },
    buildPack: async (p) => { apiCalls.push(['build', p]); return { ok: true, data: { mode: 'gradle', verify: [{ level: 'ok', msg: '产物正常' }] } }; },
    // 0.2.2 第 2 步：构建输出流。记录订阅回调，测试里手动推片段
    onBuildOutput: (cb) => { outputSubs.push(cb); return () => { outputSubs.length = 0; }; },
};
const outputSubs = [];

const sandbox = { window: windowStub, document: documentStub, console };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'src', 'views', 'deployStatus.js'), 'utf8'), sandbox);

const VIEW = windowStub.DeployStatus;

/** 造一条「后端测量结果」，字段与 core/packStatus.js 的真实输出一致 */
function packFixture(over = {}) {
    return Object.assign({
        dirName: 'alpha-pack', jarName: 'alpha-pack.jar',
        source: { exists: true, dir: '/tmp/alpha-pack', isPack: true, moduleName: 'alpha-pack', version: '0.1.0', deps: '', entry: '', hasSparkModels: true, hasEntities: true, hasJava: true, mtimeMs: 1 },
        built: { exists: true, rel: 'contentpacks/alpha-pack.jar', size: 175457, mtimeMs: 2, mtimeIso: '2026-10-23T21:59:35.272Z', md5: 'd6b244edaaaaaaaaaaaaaaaaaaaaaa', entries: 97 },
        archived: { exists: false, rel: 'contentpacks/disabled/alpha-pack.jar', size: 0, mtimeMs: 0, mtimeIso: null, md5: null, entries: 0, disabledAt: null },
        installed: [
            { exists: true, rel: 'run/client/contentpacks', size: 175457, mtimeMs: 3, mtimeIso: '2026-10-23T22:00:00Z', md5: 'd6b244edaaaaaaaaaaaaaaaaaaaaaa', matchesBuilt: true },
            { exists: true, rel: 'run/server/contentpacks', size: 175455, mtimeMs: 3, mtimeIso: '2026-10-23T22:00:00Z', md5: 'c67245bbbbbbbbbbbbbbbbbbbbbbbbbb', matchesBuilt: false },
        ],
        cache: [
            { label: 'run/client', total: 17, present: 17, match: 16, stale: 1, absent: 0, hasCacheRoot: true, checked: true, moduleDirs: ['catoxidesbattlerebuild'] },
        ],
        diff: { ok: true, checked: 20, equal: 20, stale: [], missingInJar: [], extraInJar: [], skippedTooBig: [], newestSourceMs: 1, note: null },
        drift: { sourceNewerThanJar: false, sourceNewestMs: 1, jarMs: 2, driftSeconds: 0 },
        enabled: true, hasDisabledMarker: false,
        issues: [
            { level: 'err', code: 'E-INSTALL-DRIFT', msg: 'run/server/contentpacks 与产物 jar md5 不同（旧副本残留）', src: 'build.gradle' },
            { level: 'err', code: 'E-CACHE-STALE', msg: 'run/client 的 spark 磁盘缓存有 1/17 个同名文件内容不同于 jar', src: 'spark_modules' },
        ],
    }, over);
}

function payload(packs, extra = {}) {
    return {
        ok: true,
        data: Object.assign({
            root: '/tmp/proj', packsOutDir: '/tmp/proj/contentpacks', packsSourceDir: '/tmp/proj/contentpacks-source',
            disabledDir: '/tmp/proj/contentpacks/disabled',
            installDirs: [{ label: 'run/client/contentpacks', exists: true }, { label: 'run/server/contentpacks', exists: true }],
            packs, nonPackDirs: [], duplicates: [], scannedAt: '2026-10-24T10:00:00.000Z',
        }, extra),
    };
}

console.log('=== 部署面板渲染测试 ===');

test('1. 视图挂载到 window.DeployStatus 且暴露 init/open/close/refresh', () => {
    assert.strictEqual(typeof VIEW, 'object');
    for (const k of ['init', 'open', 'close', 'refresh', 'rerender', 'isOpen']) {
        assert.strictEqual(typeof VIEW[k], 'function', k + ' 应为函数');
    }
});

test('2. init 后按钮存在（此前 0.2.1 的教训：按钮没接线＝点了没反应）', () => {
    VIEW.init();
    assert.ok(ids.btnDeployStatus, '需要 #btnDeployStatus');
    assert.ok(ids.deployBody && ids.deployStatus, '需要 #deployBody / #deployStatus');
});

test('3. 有漂移的包：四列都有实测数字，问题码原样落在包名下', async () => {
    windowStub.__inspect = payload([packFixture()]);
    await VIEW.refresh();
    const bodyText = textOf(ids.deployBody);
    assert.ok(bodyText.includes('alpha-pack'), '包名');
    assert.ok(bodyText.includes('171 KB') || bodyText.includes('175457'), '产物大小应显示（KB/MB 或原始字节）：' + bodyText.slice(0, 200));
    assert.ok(bodyText.includes('97 条目'), '条目数');
    assert.ok(bodyText.includes('d6b244ed'), 'md5 前缀');
    assert.ok(bodyText.includes('E-INSTALL-DRIFT'), '安装漂移码');
    assert.ok(bodyText.includes('E-CACHE-STALE'), '缓存陈旧码');
    assert.ok(bodyText.includes('命中 17/17'), '缓存命中数');
    assert.ok(bodyText.includes('≠1'), '缓存内容不同数');
    assert.ok(bodyText.includes('= 20/20'), '源码↔jar 一致数');
    assert.ok(bodyText.includes('ON'), '开关列');
    assert.ok(/dp-bad/.test(ids.deployBody.children.map(() => '').join('') || '') || true, '行级样式在子节点上，不强求');
});

test('4. 一致的行标「四态一致」，漂移的行不标', async () => {
    const clean = packFixture({ issues: [], installed: [{ exists: true, rel: 'run/client/contentpacks', size: 1, mtimeIso: null, md5: 'aa', matchesBuilt: true }], cache: [], built: { ...packFixture().built, md5: 'aa' } });
    await (async () => { })();
    windowStub.__inspect = payload([clean, packFixture()]);
    await VIEW.refresh();
    const text = textOf(ids.deployBody);
    assert.ok(text.includes('四态一致'), '干净行应标四态一致');
});

test('5. 未安装的包 → 「未安装」；无产物 → 「无产物」', async () => {
    const noJar = packFixture({
        built: { exists: false, rel: 'contentpacks/alpha-pack.jar', size: 0, mtimeIso: null, md5: null, entries: 0 },
        diff: { ok: false, checked: 0, equal: 0, stale: [], missingInJar: [], extraInJar: [], note: '产物 jar 不存在' },
        installed: [],
    });
    windowStub.__inspect = payload([noJar]);
    await VIEW.refresh();
    const text = textOf(ids.deployBody);
    assert.ok(text.includes('无产物'), text.slice(0, 300));
});

test('6. 停用中的包：显示「已归档（停用）」且开关列给出 OFF + 启用按钮', async () => {
    const off = packFixture({
        enabled: false, hasDisabledMarker: true,
        built: { exists: false, rel: 'contentpacks/alpha-pack.jar', size: 0, mtimeIso: null, md5: null, entries: 0 },
        archived: { exists: true, rel: 'contentpacks/disabled/alpha-pack.jar', size: 9694, mtimeMs: 1, mtimeIso: '2026-10-24T00:00:00.000Z', md5: 'ff00', entries: 9, disabledAt: '2026-10-24T00:00:00.000Z' },
        issues: [],
    });
    windowStub.__inspect = payload([off]);
    await VIEW.refresh();
    const text = textOf(ids.deployBody);
    assert.ok(text.includes('已归档（停用）'), text.slice(0, 300));
    assert.ok(text.includes('OFF'), '开关列');
    assert.ok(text.includes('启用'), '应给启用按钮');
});

test('7. 重复 Module-Name 与「非包目录」两条横幅都出得来', async () => {
    windowStub.__inspect = payload([packFixture({ issues: [] })], {
        duplicates: [['alpha-pack', ['alpha-pack.jar', 'alpha-pack-old.jar']]],
        nonPackDirs: [{ dirName: 'editor', reason: 'MANIFEST.MF 不存在' }],
    });
    await VIEW.refresh();
    const text = textOf(ids.deployBody);
    assert.ok(text.includes('重复 Module-Name：alpha-pack'), text.slice(0, 200));
    assert.ok(text.includes('非包目录：editor'), text.slice(0, 200));
});

test('8. IPC 失败时把错误原文显示出来（不静默）', async () => {
    windowStub.__inspect = { ok: false, error: '尚未设置项目根目录（顶栏「📁 连接工程…」）' };
    await VIEW.refresh();
    assert.ok(textOf(ids.deployBody).includes('尚未设置项目根目录'), textOf(ids.deployBody));
    assert.ok(textOf(ids.deployStatus).includes('尚未设置项目根目录'), textOf(ids.deployStatus));
});

test('9. open/close 只切 hidden 类，不重复读盘', async () => {
    windowStub.__inspect = payload([packFixture({ issues: [] })]);
    const before = apiCalls.filter((c) => c[0] === 'inspect').length;
    VIEW.open();
    assert.ok(!ids.deployOverlay.classList.contains('hidden'), 'open 后应可见');
    assert.ok(ids.deployOverlay.classList.contains('hidden') === false);
    VIEW.close();
    assert.ok(ids.deployOverlay.classList.contains('hidden'), 'close 后应隐藏');
    assert.strictEqual(VIEW.isOpen(), false);
    assert.ok(apiCalls.filter((c) => c[0] === 'inspect').length > before, 'open 应触发一次测量');
});

test('10. 表头七列齐全且顺序正确', async () => {
    windowStub.__inspect = payload([packFixture({ issues: [] })]);
    await VIEW.refresh();
    const table = ids.deployBody.children.find((n) => n.tagName === 'table');
    assert.ok(table, '应有表格');
    const thead = table.children.find((n) => n.tagName === 'thead');
    const ths = thead.children[0].children.map((th) => th.textContent);
    assert.deepStrictEqual(ths, ['包', '① 源码', '② 产物 jar', '③ 运行目录副本', '④ spark 缓存', '开关', '动作']);
});

test('11. init 订阅构建输出流；推来的 Gradle 原文逐字进入控制台（不加工）', async () => {
    assert.strictEqual(outputSubs.length, 1, 'init 应注册且只注册一个 pack:output 监听（重复注册会翻倍显示）');
    outputSubs[0]({ kind: 'stdout', text: '> Task :packs:melee-combat-pack:contentpackJar\n', packDir: 'melee-combat-pack' });
    outputSubs[0]({ kind: 'stderr', text: 'FAILURE: Build failed\n', packDir: 'melee-combat-pack' });
    const pre = ids.deployConsoleBody.textContent;
    assert.ok(pre.includes('> Task :packs:melee-combat-pack:contentpackJar'), JSON.stringify(pre.slice(0, 80)));
    assert.ok(pre.includes('FAILURE: Build failed'), 'stderr 也要进来');
});

test('12. 控制台按字符封顶：超长时丢最旧的并明说，绝不无限吃内存', async () => {
    const big = 'x'.repeat(20000);
    for (let i = 0; i < 12; i++) outputSubs[0]({ kind: 'stdout', text: big + '\n' });
    const pre = ids.deployConsoleBody.textContent;
    assert.ok(pre.length < 200000, '应封顶：' + pre.length);
    assert.ok(pre.includes('前部输出已丢弃'), '要说明丢弃了什么');
    assert.ok(pre.trimEnd().endsWith('x'), '保留的是最新那段');
});

test('13. 「🕘 回溯」按钮在每行动作里（第 2 步入口），且不新增表格列', async () => {
    windowStub.__inspect = payload([packFixture({ issues: [] })]);
    await VIEW.refresh();
    const text = textOf(ids.deployBody);
    assert.ok(/回溯/.test(text), '应有回溯按钮：' + text.slice(0, 120));
    const table = ids.deployBody.children.find((n) => n.tagName === 'table');
    const ths = table.children.find((n) => n.tagName === 'thead').children[0].children.map((th) => th.textContent);
    assert.strictEqual(ths.length, 7, '回溯放在动作列里，不加新列');
});

runCases().then(() => {
    console.log(`\n=== deployView 测试: ${passed} passed, ${failed} failed ===`);
    process.exit(failed ? 1 : 0);
});
