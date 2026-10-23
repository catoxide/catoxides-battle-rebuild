// gitHistory.test.js - 只读提交回溯（第 2 步）核心逻辑
// 用「假 git」：按参数前缀返回预置输出，验证解析、校验与诚实降级，不依赖真实仓库
'use strict';
const assert = require('assert');
const G = require('../src/core/gitHistory');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); console.log('  ✓ ' + name); passed++; }
    catch (e) { console.log('  ✗ ' + name + '\n      ' + (e && e.message)); failed++; }
}

const REC = '\x1e', FLD = '\x1f';

/** 造一条 git log --name-status 记录 */
function logRec(sha, subj, parents, files) {
    return REC + [sha, sha.slice(0, 7), 'catoxide', 'c@example.com', '2026-10-24T06:13:40+08:00', subj, parents.join(' ')].join(FLD)
        + '\n\n' + files.map((f) => f.join('\t')).join('\n') + '\n\n';
}

/**
 * 假 git：脚本 = { 命令前缀串: {code,stdout,stderr} }
 * 命中规则：取 args.join(' ')，按最长前缀匹配；未命中返回空输出
 * calls 存 join 后的串（便于正则断言），raw 存原始 argv（验证永远不走 shell）
 */
function fakeGit(script) {
    const calls = [];
    const raw = [];
    const keys = Object.keys(script).sort((a, b) => b.length - a.length);
    return {
        calls, raw,
        git: (args) => {
            assert.ok(Array.isArray(args), 'git 执行器必须收到 argv 数组（不能拼成 shell 字符串）');
            assert.ok(args.every((a) => typeof a === 'string'));
            raw.push(args);
            const joined = args.join(' ');
            calls.push(joined);
            for (const k of keys) if (joined.startsWith(k)) {
                const r = script[k];
                return typeof r === 'function' ? r(args) : Object.assign({ code: 0, stdout: '', stderr: '' }, r);
            }
            return { code: 0, stdout: '', stderr: '' };
        },
    };
}

const NOT_A_REPO = { 'rev-parse --is-inside-work-tree': { code: 128, stdout: '', stderr: 'fatal: not a git repository (or any of the parent directories): .git' } };

const SHA_A = 'a'.repeat(40), SHA_B = 'b'.repeat(40), SHA_ROOT = 'c'.repeat(40);

// ==================== 1. 仓库信息 ====================

test('1. 不是 git 仓库 → isRepo:false，note 说清原因（不显示成「无历史」）', () => {
    const f = fakeGit(NOT_A_REPO);
    const info = G.repoInfo({ root: 'X:/nope', git: f.git });
    assert.strictEqual(info.isRepo, false);
    assert.ok(/不是 git 仓库/.test(info.error), info.error);
    G.resetRepoCache();
    const h = G.history({ root: 'X:/nope', git: f.git }, { paths: ['contentpacks-source/x'] });
    assert.strictEqual(h.ok, false);
    assert.ok(/不是 git 仓库/.test(h.note));
});

test('2. 正常仓库 → branch + head 解析；分离 HEAD 时 branch 为 null', () => {
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'rev-parse --show-toplevel': { stdout: 'X:/repo\n' },
        'rev-parse --abbrev-ref HEAD': { stdout: 'Neoforge-1.21.1\n' },
        'log -1': { stdout: [SHA_A, 'aaa1111', 'catoxide', 'c@e.com', '2026-10-24T06:13:40+08:00', '初始提交', 'e95fd7d ' + 'd'.repeat(33)].join(FLD) + '\n' },
    });
    G.resetRepoCache();
    const info = G.repoInfo({ root: 'X:/repo', git: f.git });
    assert.strictEqual(info.isRepo, true);
    assert.strictEqual(info.branch, 'Neoforge-1.21.1');
    assert.strictEqual(info.head.sha, SHA_A);
    assert.strictEqual(info.head.merge, true);   // 两个父 → merge
    assert.ok(!f.calls.some((c) => /[;&|]/.test(c)), '命令串里不该出现 shell 元字符');
    G.resetRepoCache();
});

test('2b. 真实默认执行器存在且以 argv 数组调用（不经 shell）', () => {
    // defaultGit 不可从外部注入，这里只验证「未提供 ctx.git 时不会崩」：
    // 用一个不存在的 root，返回码非 0 → isRepo:false，且不抛异常
    G.resetRepoCache();
    const info = G.repoInfo({ root: process.cwd() });
    assert.ok(info && typeof info.isRepo === 'boolean');
    G.resetRepoCache();
});

// ==================== 2. 入参校验 ====================

test('3. 路径校验拒绝越界写法（.. / 绝对路径 / 以 - 开头 / 控制字符）', () => {
    for (const bad of ['../etc/passwd', 'X:/Windows/x', '/etc/hosts', '--output=/tmp/x', 'a\x00b', 'a//b', '']) {
        assert.throws(() => G.safeRel(bad), `应拒绝 ${JSON.stringify(bad)}`);
    }
    assert.strictEqual(G.safeRel('contentpacks-source\\melee-combat-pack'), 'contentpacks-source/melee-combat-pack');
    assert.strictEqual(G.safeRel('./contentpacks'), 'contentpacks');
});

test('4. 提交号只接受十六进制；limit 收敛到 [1,200]', () => {
    assert.strictEqual(G.safeSha(SHA_A), SHA_A);
    assert.strictEqual(G.safeSha('abc1234'), 'abc1234');
    for (const bad of ['HEAD', 'a'.repeat(41), 'zzzzzzz', '--help', 'HEAD~5']) assert.throws(() => G.safeSha(bad), bad);
    assert.strictEqual(G.safeLimit(undefined), G.LIMITS.DEFAULT_LIMIT);
    assert.strictEqual(G.safeLimit(9999), G.LIMITS.MAX_LIMIT);
    assert.strictEqual(G.safeLimit(-3), G.LIMITS.DEFAULT_LIMIT);
});

// ==================== 3. 历史解析 ====================

test('5. git log --name-status 解析出提交 + 文件（含重命名），字段完整', () => {
    const out = logRec(SHA_A, '修复挥剑动画', ['e95fd7d'], [
        ['M', 'contentpacks-source/melee-combat-pack/MANIFEST.MF'],
        ['R100', 'old/sword.json', 'new/sword.json'],
        ['A', 'contentpacks-source/melee-combat-pack/spark_models/item/ns/sword.json'],
    ]) + logRec(SHA_B, '上一版', [SHA_ROOT], [['D', 'contentpacks/x.txt']]);
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'rev-parse --show-toplevel': { stdout: 'X:/repo\n' },
        'rev-parse --abbrev-ref HEAD': { stdout: 'main\n' },
        'log -1': { stdout: [SHA_A, 'aaa1111', 'catoxide', 'c@e.com', '2026-10-24T06:13:40+08:00', 'HEAD 提交', ''].join(FLD) + '\n' },
        'log --format=': { stdout: out },
        'ls-files --': { stdout: 'contentpacks-source/melee-combat-pack/MANIFEST.MF\n' },
    });
    G.resetRepoCache();
    const h = G.history({ root: 'X:/repo', git: f.git }, { paths: ['contentpacks-source/melee-combat-pack'] });
    assert.strictEqual(h.ok, true);
    assert.strictEqual(h.commits.length, 2);
    assert.strictEqual(h.commits[0].subject, '修复挥剑动画');
    assert.deepStrictEqual(h.commits[0].files[1], { status: 'R100', path: 'new/sword.json', oldPath: 'old/sword.json' });
    assert.strictEqual(h.commits[1].files[0].status, 'D');
    assert.strictEqual(h.truncated, false);
    // 只读：绝不允许出现写操作
    for (const c of f.calls) assert.ok(!/\b(commit|checkout|restore|reset|clean|merge|revert|stash|push)\b/.test(c), '出现写命令: ' + c);
});

test('6. 命中 -n 上限 → truncated:true 且 note 说明「可能还有更早的」', () => {
    const five = [1, 2, 3, 4, 5].map((i) => logRec(String(i).repeat(40), `c${i}`, [], [['M', 'x']])).join('');
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'rev-parse --show-toplevel': { stdout: 'X:/repo\n' },
        'log --format=': { stdout: five },
        'ls-files --': { stdout: 'x\n' },
    });
    G.resetRepoCache();
    const h = G.history({ root: 'X:/repo', git: f.git }, { paths: ['x'], limit: 5 });
    assert.strictEqual(h.commits.length, 5);
    assert.strictEqual(h.truncated, true);
    assert.ok(/更早/.test(h.note));
    assert.ok(f.calls.some((c) => /-n 5\b/.test(c)), 'limit 要真的传给 git');
});

test('7. 无提交记录：未跟踪与「已跟踪但此路径无历史」用不同措辞', () => {
    const base = {
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'rev-parse --show-toplevel': { stdout: 'X:/repo\n' },
        'log --format=': { stdout: '' },
    };
    G.resetRepoCache();
    const untracked = fakeGit(Object.assign({}, base, { 'ls-files --': { stdout: '' }, 'ls-files --others': { stdout: 'contentpacks-source/new/MANIFEST.MF\n' }, 'check-ignore': { code: 1, stdout: '' } }));
    let h = G.history({ root: 'X:/repo', git: untracked.git }, { paths: ['contentpacks-source/new'] });
    assert.strictEqual(h.commits.length, 0);
    assert.ok(/未跟踪/.test(h.note), h.note);
    assert.strictEqual(h.pathStates[0].neverCommitted, true);
    assert.strictEqual(h.pathStates[0].untrackedCount, 1);

    G.resetRepoCache();
    const tracked = fakeGit(Object.assign({}, base, { 'ls-files --': { stdout: 'contentpacks/a.jar\n' }, 'ls-files --others': { stdout: '' } }));
    h = G.history({ root: 'X:/repo', git: tracked.git }, { paths: ['contentpacks/a.jar'] });
    assert.ok(!/未跟踪/.test(h.note), h.note);
    assert.strictEqual(h.pathStates[0].tracked, true);
});

test('8. 被 .gitignore 命中的路径单独标注，并给出规则原文', () => {
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'ls-files --': { stdout: '' },
        'ls-files --others': { stdout: '' },
        'check-ignore': { stdout: '.gitignore:12:build/\tbuild\r\n' },
    });
    G.resetRepoCache();
    const ps = G.pathStates({ root: 'X:/repo', git: f.git }, ['build']);
    assert.strictEqual(ps.paths[0].ignored, true);
    assert.strictEqual(ps.paths[0].neverCommitted, false);
    assert.strictEqual(ps.paths[0].ignoreRule, '.gitignore:12:build/');
});

test('8b. 只对原始路径问 check-ignore（实测陷阱：给产物 jar 补 "/" 再问，会被一行空格的规则误判成已忽略）', () => {
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'ls-files --': { stdout: 'contentpacks/a.jar\n' },
        'check-ignore': (args) => {
            const paths = args.slice(args.indexOf('--') + 1);
            const hit = paths.find((p) => p.endsWith('/'));
            return hit ? { code: 0, stdout: '.gitignore:58:\t' + hit + '\r\n' } : { code: 1, stdout: '' };
        },
    });
    G.resetRepoCache();
    const ps = G.pathStates({ root: 'X:/repo', git: f.git }, ['contentpacks/a.jar']);
    assert.strictEqual(ps.paths[0].tracked, true);
    assert.strictEqual(ps.paths[0].ignored, false, '不该因为补了 "/" 就报已忽略');
    assert.ok(f.calls.some((c) => /check-ignore -v -- contentpacks\/a\.jar$/.test(c)), '只应问原始路径，一次一个');
});

// ==================== 4. 单条提交详情 ====================

test('9. commitDetail：普通提交取 diff，增删行数按 +/- 统计且不把 +++/--- 计入', () => {
    const diff = [
        'diff --git a/x.json b/x.json',
        '--- a/x.json',
        '+++ b/x.json',
        '@@ -1,3 +1,4 @@',
        ' "a": 1,',
        '-"b": 2',
        '+"b": 3',
        '+"c": 4',
    ].join('\n');
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'show --no-patch': { stdout: [SHA_A, 'aaa1111', 'catoxide', 'c@e.com', '2026-10-24T06:13:40+08:00', '改一点', SHA_B].join(FLD) + '\n' },
        'show --format= --name-status': { stdout: 'M\tx.json\n' },
        'show --format= --stat': { stdout: ' x.json | 3 ++-\n 1 file changed, 2 insertions(+), 1 deletion(-)\n' },
        'diff --unified=3': { stdout: diff },
    });
    G.resetRepoCache();
    const d = G.commitDetail({ root: 'X:/repo', git: f.git }, { paths: ['contentpacks-source/p'], sha: SHA_A });
    assert.strictEqual(d.ok, true);
    assert.strictEqual(d.fileCount, 1);
    assert.strictEqual(d.additions, 2);
    assert.strictEqual(d.deletions, 1);
    assert.ok(/1 file changed/.test(d.summary));
    assert.ok(f.calls.some((c) => /^diff --unified=3 --no-color \S+ \S+ --/.test(c)), '应比对第一父与本提交');
});

test('10. 合并提交 --name-status 为空 → 回落第一父比对，不谎报「无改动」', () => {
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'show --no-patch': { stdout: [SHA_A, 'aaa1111', 'c', 'c@e', '2026', 'merge', SHA_B + ' ' + SHA_ROOT].join(FLD) + '\n' },
        'show --format= --name-status': { stdout: '\n' },
        'diff --name-status': { stdout: 'M\tp/a.json\nM\tp/b.json\n' },
        'show --format= --stat': { stdout: '' },
        'diff --unified=3': { stdout: '+x\n' },
    });
    G.resetRepoCache();
    const d = G.commitDetail({ root: 'X:/repo', git: f.git }, { paths: ['p'], sha: SHA_A });
    assert.strictEqual(d.files.length, 2);
    assert.ok(f.calls.some((c) => /^diff --name-status \S+ \S+ -- p$/.test(c)));
});

test('11. 根提交（无父）走 git show；差异超字节封顶要标注截断', () => {
    const big = 'A'.repeat(3000);
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'show --no-patch': { stdout: [SHA_ROOT, 'ccc1111', 'c', 'c@e', '2026', 'root', ''].join(FLD) + '\n' },
        'show --format= --name-status': { stdout: 'A\tp/a.json\n' },
        'show --format= --stat': { stdout: '' },
        'show --format= --unified=3': { stdout: big },
    });
    G.resetRepoCache();
    const d = G.commitDetail({ root: 'X:/repo', git: f.git }, { paths: ['p'], sha: SHA_ROOT, maxBytes: 100 });
    assert.ok(f.calls.some((c) => /^show --format= --unified=3/.test(c)), '根提交必须走 show');
    assert.strictEqual(d.truncated, true);
    assert.ok(d.diff.length < 3000);
    assert.ok(/截断/.test(d.diff));
    assert.strictEqual(d.diffBytes, 3000);
});

test('12. 提交不存在（git show 非 0）→ ok:false + 原文，不返回空对象冒充「无差异」', () => {
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'show --no-patch': { code: 128, stdout: '', stderr: 'fatal: bad object deadbeef' },
    });
    G.resetRepoCache();
    const d = G.commitDetail({ root: 'X:/repo', git: f.git }, { paths: ['p'], sha: 'deadbeef' });
    assert.strictEqual(d.ok, false);
    assert.ok(/bad object/.test(d.note));
});

// ==================== 5. 工作区状态（回溯安全性前提） ====================

test('13. workingTree：区分 已暂存 / 未暂存 / 未跟踪，重命名取新路径', () => {
    const st = [
        ' M contentpacks-source/p/spark_animations/item/ns/sword/out.json',
        'M  contentpacks-source/p/MANIFEST.MF',
        'A  contentpacks-source/p/new.json',
        '?? contentpacks-source/p/scratch/',
        'R  old/n.json -> new/n.json',
    ].join('\n');
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'status --porcelain': { stdout: st },
        'diff --stat': { stdout: ' 2 files changed, 5 insertions(+), 1 deletion(-)\n' },
        'diff --cached --stat': { stdout: ' 1 file changed, 1 insertion(+)\n' },
    });
    G.resetRepoCache();
    const w = G.workingTree({ root: 'X:/repo', git: f.git }, { paths: ['contentpacks-source/p'] });
    assert.strictEqual(w.ok, true);
    assert.strictEqual(w.dirty, true);
    assert.strictEqual(w.changedCount, 4);
    assert.deepStrictEqual(w.untracked, ['contentpacks-source/p/scratch/']);
    const ren = w.changes.find((c) => c.code === 'R');
    assert.strictEqual(ren.path, 'new/n.json');
    assert.strictEqual(ren.oldPath, 'old/n.json');
    const un = w.changes.find((c) => c.code === 'M' && c.path.endsWith('MANIFEST.MF'));
    assert.strictEqual(un.staged, true);
    assert.strictEqual(un.unstaged, false);
    assert.strictEqual(w.worktreeSummary, '2 files changed, 5 insertions(+), 1 deletion(-)');
    assert.strictEqual(w.stagedSummary, '1 file changed, 1 insertion(+)');
});

test('14. 工作区干净 → dirty:false（回溯前唯一的放行信号）', () => {
    const f = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'status --porcelain': { stdout: '' },
        'diff --stat': { stdout: '' },
        'diff --cached --stat': { stdout: '' },
    });
    G.resetRepoCache();
    const w = G.workingTree({ root: 'X:/repo', git: f.git }, { paths: ['contentpacks-source/p'] });
    assert.strictEqual(w.dirty, false);
    assert.strictEqual(w.changedCount, 0);
});

// ==================== 6. 绝对路径 → 仓库相对 ====================

test('15. toRepoRel：仓库内转 posix 相对路径，仓库外如实说不在仓库内', () => {
    const base = {
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'rev-parse --show-toplevel': { stdout: 'X:/repo\n' },
    };
    G.resetRepoCache();
    const ctx = { root: 'X:/repo', git: fakeGit(base).git };
    assert.strictEqual(G.toRepoRel(ctx, 'X:/repo\\contentpacks-source\\p').rel, 'contentpacks-source/p');
    G.resetRepoCache();
    const outside = G.toRepoRel(ctx, 'D:/Other/place');
    assert.strictEqual(outside.ok, false);
    assert.ok(/不在仓库/.test(outside.reason));
});

console.log(`\n=== gitHistory 测试: ${passed} passed, ${failed} failed ===`);
process.exitCode = failed ? 1 : 0;
