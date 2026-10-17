// projectRoot.test.js - 0.2.1 反馈机事故回归：无 mod 工程时必须落"独立工作区"
//
// 背景：反馈同学只拿到 portable exe，机器上没有任何 mod 工程 →
// 之前 tryResolveProjectRoot() 返回 null，渲染端 init() 在 setupProjectRoot() 后 return
// → 顶栏「源码: …」缺失 + 包库/新建包/+新建 三个按钮全部无响应。
// 现在无工程时自动建工作区（含 contentpacks-source / contentpacks），编辑器始终可用。
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');
const PR = require('../src/core/projectRoot');

let passed = 0, failed = 0;
function t(name, fn) {
    try { fn(); console.log('  ✓ ' + name); passed++; }
    catch (e) { console.error('  ✗ ' + name + ': ' + e.message); failed++; }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'projectroot-test-'));
const mkRoot = (dir) => {
    fs.mkdirSync(path.join(dir, 'contentpacks-source'), { recursive: true });
    fs.mkdirSync(path.join(dir, 'contentpacks'), { recursive: true });
    return dir;
};

t('① 配置里的有效项目根优先（source=config）', () => {
    const cfgRoot = mkRoot(path.join(tmp, 'real-project'));
    const docs = path.join(tmp, 'docs-1');
    const r = PR.resolveProjectRoot({ configRoot: cfgRoot, documentsDir: docs, userDataDir: path.join(tmp, 'ud-1') });
    assert.strictEqual(r.root, cfgRoot);
    assert.strictEqual(r.source, 'config');
    assert.ok(!fs.existsSync(path.join(docs, PR.WORKSPACE_NAME)), '有真工程时不应创建工作区');
});

t('② 配置指向不存在的目录 → 走自动探测（source=auto）', () => {
    const autoRoot = mkRoot(path.join(tmp, 'auto-project'));
    let detectCalls = 0;
    const r = PR.resolveProjectRoot({
        configRoot: path.join(tmp, 'gone-project'),
        detectRoot: () => { detectCalls++; return autoRoot; },
        documentsDir: path.join(tmp, 'docs-2'),
        userDataDir: path.join(tmp, 'ud-2'),
    });
    assert.strictEqual(r.root, autoRoot);
    assert.strictEqual(r.source, 'auto');
    assert.strictEqual(detectCalls, 1);
});

t('③ 无配置 + 探测失败 → 在文档目录建独立工作区（source=workspace-documents）', () => {
    const docs = path.join(tmp, 'docs-3');
    fs.mkdirSync(docs, { recursive: true });
    const r = PR.resolveProjectRoot({
        configRoot: null,
        detectRoot: () => null,
        documentsDir: docs,
        userDataDir: path.join(tmp, 'ud-3'),
    });
    assert.strictEqual(r.source, 'workspace-documents');
    assert.strictEqual(r.root, path.join(docs, PR.WORKSPACE_NAME));
    for (const sub of PR.REQUIRED_SUBDIRS) {
        assert.ok(fs.statSync(path.join(r.root, sub)).isDirectory(), sub + ' 应被创建');
    }
    assert.strictEqual(r.created.length, PR.REQUIRED_SUBDIRS.length, '两目录都应是本次新建');
});

t('③b 工作区已存在 → 复用不报错、created 为空（幂等）', () => {
    const docs = path.join(tmp, 'docs-3');
    const r = PR.resolveProjectRoot({ documentsDir: docs, userDataDir: path.join(tmp, 'ud-3') });
    assert.strictEqual(r.root, path.join(docs, PR.WORKSPACE_NAME));
    assert.strictEqual(r.created.length, 0);
});

t('③c 工作区目录已存在但不可写（探写失败）→ 回退 userData', () => {
    const docs = path.join(tmp, 'docs-ro');
    const ws = path.join(docs, PR.WORKSPACE_NAME);
    for (const sub of PR.REQUIRED_SUBDIRS) fs.mkdirSync(path.join(ws, sub), { recursive: true });
    const ud = path.join(tmp, 'ud-ro');
    fs.mkdirSync(ud, { recursive: true });
    // 只有 documents 工作区拒写：模拟只读盘/安全软件拦写（目录存在但写不进去）
    const denyFs = Object.create(fs);
    denyFs.writeFileSync = (p, ...rest) => {
        if (String(p).startsWith(ws)) throw new Error('EACCES: permission denied');
        return fs.writeFileSync(p, ...rest);
    };
    const r = PR.resolveProjectRoot({
        configRoot: null, detectRoot: () => null,
        documentsDir: docs, userDataDir: ud, fsImpl: denyFs,
    });
    assert.strictEqual(r.source, 'workspace-userdata', '不可写的文档工作区应被跳过');
    assert.strictEqual(r.root, path.join(ud, PR.WORKSPACE_NAME));
});

t('④ 文档目录不可写（是文件）→ 回退 userData 工作区', () => {    const docsBlocker = path.join(tmp, 'docs-blocked');
    fs.writeFileSync(docsBlocker, 'x'); // 文件，无法在其中建目录
    const ud = path.join(tmp, 'ud-4');
    fs.mkdirSync(ud, { recursive: true });
    const r = PR.resolveProjectRoot({
        configRoot: null,
        detectRoot: () => null,
        documentsDir: docsBlocker,
        userDataDir: ud,
    });
    assert.strictEqual(r.source, 'workspace-userdata');
    assert.strictEqual(r.root, path.join(ud, PR.WORKSPACE_NAME));
    assert.ok(fs.existsSync(path.join(r.root, 'contentpacks-source')));
});

t('⑤ 全部候选不可用 → root=null + error（渲染端只提示，不再死锁）', () => {
    const blocker = path.join(tmp, 'all-blocked');
    fs.writeFileSync(blocker, 'x');
    const r = PR.resolveProjectRoot({ configRoot: null, detectRoot: () => null, documentsDir: blocker, userDataDir: blocker });
    assert.strictEqual(r.root, null);
    assert.strictEqual(r.source, null);
    assert.ok(typeof r.error === 'string' && r.error.length > 0);
});

t('detectRoot 抛异常不传播（探测失败只是换候选）', () => {
    const ud = path.join(tmp, 'ud-6');
    fs.mkdirSync(ud, { recursive: true });
    const r = PR.resolveProjectRoot({
        detectRoot: () => { throw new Error('boom'); },
        documentsDir: null,
        userDataDir: ud,
    });
    assert.strictEqual(r.source, 'workspace-userdata');
});

t('isProjectRoot：只有 contentpacks-source 也认（与 main.js 原判定一致）', () => {
    const onlySource = path.join(tmp, 'only-source');
    fs.mkdirSync(path.join(onlySource, 'contentpacks-source'), { recursive: true });
    assert.strictEqual(PR.isProjectRoot(fs, onlySource), true);
    assert.strictEqual(PR.isProjectRoot(fs, path.join(tmp, 'nowhere')), false);
});

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`\n=== projectRoot 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
