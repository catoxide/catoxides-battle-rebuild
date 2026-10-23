// gitRollback.test.js - 回溯动作（0.2.2 第 3 步）核心逻辑
//
// 这里用「真实 git + 真实临时仓库」，不是假 git：第 3 步会写工作区、会提交、会删文件，
// 这些语义（ls-tree / diff 对未跟踪文件的处理、restore 的 pathspec 递归范围、add -A 的收录边界）
// 只有真 git 说得清。临时仓库建在 os.tmpdir() 下，绝不碰用户工程；
// 同时把 GIT_CONFIG_GLOBAL / NOSYSTEM 指向不存在的位置，避开个人全局配置里的签名与 hooks。
// 只有「单次文件上限」那条用假 git —— 真造 501 个文件只为验证一个比较运算，不值得。
'use strict';
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

process.env.GIT_AUTHOR_NAME = process.env.GIT_COMMITTER_NAME = 'Editor Rollback Test';
process.env.GIT_AUTHOR_EMAIL = process.env.GIT_COMMITTER_EMAIL = 'editor@example.invalid';
process.env.GIT_CONFIG_NOSYSTEM = '1';

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'cpe-rollback-'));
process.env.GIT_CONFIG_GLOBAL = path.join(TMP, 'no-such-gitconfig');

const GitHistory = require('../src/core/gitHistory');
const RB = require('../src/core/gitRollback');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); console.log('  ✓ ' + name); passed++; }
    catch (e) {
        // 第一行就是断言消息（含 stage/note），丢了它等于丢了失败原因
        const detail = (e && e.message ? String(e.message) : String(e)).split('\n').slice(0, 4).join('\n      ');
        console.log('  ✗ ' + name + '\n      ' + detail);
        failed++;
    }
}

// ==================== 真 git 脚手架 ====================

const seenArgv = [];     // 原始 argv（数组），用来判定「跑的是哪个子命令」
const seenJoined = [];   // join 后的串，便于前缀断言

/** 剥掉 git 的全局选项，取出子命令名（-c 的值不是子命令，别把它当成命令名糊弄过去） */
function subcommand(args) {
    const a = args.slice();
    while (a.length) {
        const x = a.shift();
        if (x === '-c' || x === '-C' || x === '--git-dir' || x === '--work-tree' || x === '--namespace') { a.shift(); continue; }
        if (x.startsWith('-')) continue;
        return x;
    }
    return '';
}

/** 给模块用的执行器：不抛，返回 {code,stdout,stderr}；同时记录 argv 供只读承诺断言 */
function runner(args, cwd) {
    assert.ok(Array.isArray(args), 'git 执行器必须收到 argv 数组（不能拼成 shell 字符串）');
    assert.ok(args.every((a) => typeof a === 'string'), 'argv 每一项必须是字符串');
    seenArgv.push(args.slice());
    seenJoined.push(args.join('\0'));
    const r = spawnSync('git',
        ['-c', 'core.quotepath=false', '-c', 'color.ui=false', '-c', 'core.autocrlf=false', ...args],
        { cwd: cwd || undefined, encoding: 'utf8', windowsHide: true, maxBuffer: 32 * 1024 * 1024 });
    if (r.error) return { code: -1, stdout: '', stderr: String(r.error.message || r.error) };
    return { code: r.status, stdout: r.stdout || '', stderr: r.stderr || '' };
}

/** 脚手架用：失败即抛，避免测试在坏仓库上跑出假绿 */
function g(args, cwd) {
    const r = runner(args, cwd);
    if (r.code !== 0) throw new Error(`git ${args.join(' ')} 失败（${r.code}）：${(r.stderr || r.stdout).trim()}`);
    return r.stdout.trim();
}
function gSoft(args, cwd) { return runner(args, cwd); }

function w(dir, rel, content) {
    const abs = path.join(dir, ...rel.split('/'));
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
}
function read(dir, rel) {
    try { return fs.readFileSync(path.join(dir, ...rel.split('/')), 'utf8'); } catch { return null; }
}
function exists(dir, rel) { return fs.existsSync(path.join(dir, ...rel.split('/'))); }

let seq = 0;
function newRepo() {
    const dir = fs.mkdtempSync(path.join(TMP, `r${++seq}-`));
    g(['init', '-q'], dir);
    return dir;
}
function commit(dir, msg) {
    g(['add', '-A'], dir);
    g(['commit', '-q', '-m', msg], dir);
    return g(['rev-parse', 'HEAD'], dir);
}

const PACK = 'contentpacks-source/alpha';

/**
 * 两个提交 + 可选的未跟踪 / 脏工作区，覆盖回滚的四个方向：
 *   c1（目标）：manifest=v1、entities/a=A、gone=G、z=T
 *   c2（当前）：manifest=v2、entities/a=A、new=N，且 gone/z 已删
 *   → 回到 c1 需要：改写 manifest、找回 gone 与 z、删掉 new
 */
function scenario(o = {}) {
    const dir = newRepo();
    w(dir, `${PACK}/manifest.json`, 'v1');
    w(dir, `${PACK}/entities/a.json`, 'A');
    w(dir, `${PACK}/gone.json`, 'G');
    w(dir, `${PACK}/z.json`, 'T');
    w(dir, 'other/WIP.txt', 'base');
    const c1 = commit(dir, 'C1 目标提交');
    w(dir, `${PACK}/manifest.json`, 'v2');
    w(dir, `${PACK}/new.json`, 'N');
    fs.rmSync(path.join(dir, ...`${PACK}/gone.json`.split('/')));
    fs.rmSync(path.join(dir, ...`${PACK}/z.json`.split('/')));
    const c2 = commit(dir, 'C2 当前提交');
    if (o.untrackedZ) w(dir, `${PACK}/z.json`, 'LOCAL');
    if (o.stray) w(dir, `${PACK}/stray.json`, 'S');
    if (o.dirty) w(dir, `${PACK}/manifest.json`, 'v3');
    if (o.stagedOutside) { w(dir, 'other/WIP.txt', 'MINE'); g(['add', '--', 'other/WIP.txt'], dir); }
    GitHistory.resetRepoCache();
    return { dir, c1, c2, ctx: { root: dir, git: runner, archiveRoot: path.join(TMP, `archive-${seq}`) } };
}

function fresh() { seenArgv.length = 0; seenJoined.length = 0; GitHistory.resetRepoCache(); }
const wrote = (names) => seenArgv.map(subcommand).filter((s) => ['add', 'commit', 'restore', 'reset', 'checkout', 'clean', 'switch', 'rm', 'mv', 'merge', 'revert', 'stash', 'push', 'apply', 'cherry-pick'].includes(s));

console.log('=== gitRollback 测试（真实 git 临时仓库）===');

test('0. 环境前提：临时目录不在任何 git 仓库里（否则「非仓库」那几条会假绿）', () => {
    fresh();
    const probe = gSoft(['rev-parse', '--is-inside-work-tree'], TMP);
    assert.notStrictEqual(probe.code, 0, `${TMP} 竟然在一个 git 仓库里：${(probe.stdout || '').trim()} —— 请换 TMPDIR 再跑`);
});

// ==================== ① 只读计划 ====================

test('1. plan 四方对齐：改写 / 找回 / 删除 / 不动，各归各位且数字全来自 git', () => {
    const s = scenario();
    fresh();
    const pl = RB.plan(s.ctx, { sha: s.c1, paths: [PACK] });
    assert.strictEqual(pl.ok, true, pl.note + ' || ' + JSON.stringify(pl.failed));
    assert.strictEqual(pl.head, s.c2);
    assert.deepStrictEqual(pl.restore, [`${PACK}/gone.json`, `${PACK}/manifest.json`, `${PACK}/z.json`].sort());
    assert.deepStrictEqual(pl.remove, [`${PACK}/new.json`]);
    assert.deepStrictEqual(pl.unchanged, [`${PACK}/entities/a.json`]);
    assert.strictEqual(pl.inTarget.length, 4);
    assert.strictEqual(pl.tracked.length, 3);           // c2 里只剩 manifest / a / new
    assert.strictEqual(pl.untracked.length, 0);
    assert.strictEqual(pl.targetMeta.subject, 'C1 目标提交');
});

test('2. plan 一根毛都不动：没跑写命令，工作区内容原样', () => {
    const s = scenario({ dirty: true, stray: true });
    fresh();
    RB.plan(s.ctx, { sha: s.c1, paths: [PACK] });
    assert.deepStrictEqual(wrote(), [], 'plan 必须纯只读：' + seenArgv.map(subcommand).join(', '));
    assert.ok(seenArgv.some((a) => subcommand(a) === 'ls-files'), '盘点要真跑了 ls-files');
    assert.ok(seenArgv.some((a) => subcommand(a) === 'ls-tree'), '目标内容要真从 git 里读');
    assert.strictEqual(read(s.dir, `${PACK}/manifest.json`), 'v3');
    assert.strictEqual(exists(s.dir, `${PACK}/stray.json`), true);
    assert.strictEqual(exists(s.dir, `${PACK}/gone.json`), false);
});

test('3. 未跟踪文件：在目标里存在 → 算「改写」；不在目标里 → 算「删除」，并分两类给面板看', () => {
    const s = scenario({ untrackedZ: true, stray: true });
    fresh();
    const pl = RB.plan(s.ctx, { sha: s.c1, paths: [PACK] });
    assert.ok(pl.restore.includes(`${PACK}/z.json`), '本地那份 LOCAL 会被目标版本覆盖（归档 + 检查点两处都有备份）');
    assert.ok(pl.untracked.includes(`${PACK}/z.json`), 'git 里它仍未跟踪（c2 删过）');
    assert.deepStrictEqual(pl.remove, [`${PACK}/new.json`, `${PACK}/stray.json`].sort());
    assert.deepStrictEqual(pl.removeUntracked, [`${PACK}/stray.json`]);
    assert.deepStrictEqual(pl.removeTracked, [`${PACK}/new.json`]);
});

test('4. 嵌套路径只留最外层；非法路径与非法提交号一律拒绝', () => {
    const s = scenario();
    fresh();
    const pl = RB.plan(s.ctx, { sha: s.c1, paths: [PACK, `${PACK}/manifest.json`, 'other'] });
    assert.deepStrictEqual(pl.paths, [PACK, 'other'], '子路径被父路径吃掉，否则同一文件会被算两遍');
    assert.throws(() => RB.plan(s.ctx, { sha: s.c1, paths: ['../etc'] }), /不能包含 \.\./);
    assert.throws(() => RB.plan(s.ctx, { sha: s.c1, paths: ['D:/Windows'] }), /绝对路径/);
    assert.throws(() => RB.plan(s.ctx, { sha: s.c1, paths: ['--output=x'] }), /不能以 - 开头/);
    assert.throws(() => RB.plan(s.ctx, { sha: 'HEAD~5;rm -rf /', paths: [PACK] }), /十六进制/);
    assert.throws(() => RB.plan(s.ctx, { sha: s.c1, paths: [] }), /至少要有一个路径/);
});

test('5. 空仓库（有 .git、零提交）：plan 和 run 都说「需要一个已存在的 HEAD」', () => {
    const dir = newRepo();
    w(dir, `${PACK}/manifest.json`, 'v1');
    fresh();
    const ctx = { root: dir, git: runner, archiveRoot: path.join(TMP, 'archive-empty') };
    const pl = RB.plan(ctx, { sha: 'f'.repeat(40), paths: [PACK] });
    assert.strictEqual(pl.ok, false);
    assert.strictEqual(pl.isRepo, true, '是仓库，只是还没有提交 —— 两件事要分开说');
    assert.ok(/还没有任何提交/.test(pl.note), pl.note);
    const run = RB.run(ctx, { sha: 'f'.repeat(40), paths: [PACK], acknowledge: true });
    assert.strictEqual(run.ok, false);
    assert.strictEqual(run.stage, 'plan');
    assert.deepStrictEqual(wrote(), [], '空仓库更要一个字都不写');
});

test('6. 范围内有未解决冲突（UU）→ 拒绝回滚，不让人在冲突上叠一层', () => {
    const dir = newRepo();
    w(dir, `${PACK}/x.json`, 'one');
    w(dir, `${PACK}/y.json`, 'Y');
    const target = commit(dir, 'base');
    const base = g(['rev-parse', '--abbrev-ref', 'HEAD'], dir);
    g(['checkout', '-q', '-b', 'side'], dir);
    w(dir, `${PACK}/x.json`, 'side 改的');
    commit(dir, 'side');
    g(['checkout', '-q', base], dir);
    w(dir, `${PACK}/x.json`, 'main 改的');
    commit(dir, 'main 改的');
    const merged = gSoft(['merge', '-q', 'side'], dir);
    assert.notStrictEqual(merged.code, 0, '这个场景必须真造出冲突来，否则测试是空的');
    fresh();   // 清掉脚手架自己的 merge/checkout，只留模块跑过的
    const ctx = { root: dir, git: runner, archiveRoot: path.join(TMP, 'archive-conflict') };
    const pl = RB.plan(ctx, { sha: target, paths: [PACK] });
    assert.strictEqual(pl.conflict, true);
    assert.strictEqual(pl.ok, false);
    assert.ok(/冲突/.test(pl.note), pl.note);
    const run = RB.run(ctx, { sha: target, paths: [PACK], acknowledge: true });
    assert.strictEqual(run.stage, 'plan');
    assert.deepStrictEqual(wrote(), [], '冲突场景下不写任何东西');
});

// ==================== ② → ④ 执行 ====================

test('7. 未确认（acknowledge）时什么都不做，连归档目录都不建', () => {
    const s = scenario();
    fresh();
    const pl = RB.run(s.ctx, { sha: s.c1, paths: [PACK], expectedHead: s.c2 });
    assert.strictEqual(pl.ok, false);
    assert.strictEqual(pl.stage, 'confirm');
    assert.strictEqual(pl.archiveDir, undefined);
    assert.strictEqual(read(s.dir, `${PACK}/manifest.json`), 'v2', '工作区必须原样');
});

test('8. HEAD 与预览时不一致 → 拒绝执行（期间别人动过仓库）', () => {
    const s = scenario();
    fresh();
    const pl = RB.run(s.ctx, { sha: s.c1, paths: [PACK], acknowledge: true, expectedHead: s.c1 });
    assert.strictEqual(pl.ok, false);
    assert.ok(/HEAD 已变/.test(pl.note), pl.note);
    assert.strictEqual(read(s.dir, `${PACK}/manifest.json`), 'v2');
    assert.deepStrictEqual(wrote(), []);
});

test('9. 范围内本就和目标一致 → noChange：不归档、不提交、不写工作区', () => {
    const s = scenario();
    fresh();
    const pl = RB.run(s.ctx, { sha: s.c2, paths: [PACK], acknowledge: true, expectedHead: s.c2 });
    assert.strictEqual(pl.ok, true);
    assert.strictEqual(pl.noChange, true, pl.note);
    assert.strictEqual(pl.archiveDir, undefined);
    assert.deepStrictEqual(wrote(), []);
});

test('10. 干净工作区回滚：归档 + 检查点 + restore + 删除，四步都留下实证', () => {
    const s = scenario();
    fresh();
    const pl = RB.run(s.ctx, { sha: s.c1, paths: [PACK], acknowledge: true, expectedHead: s.c2, label: 'alpha-pack' });
    assert.strictEqual(pl.ok, true, pl.note + ' || ' + JSON.stringify(pl.failed));
    assert.strictEqual(pl.stage, 'apply');
    // ② 归档：回滚前那份逐字节可核对
    const mfPath = path.join(pl.archiveDir, 'manifest.json');
    assert.ok(fs.existsSync(mfPath), '归档清单必须存在：' + pl.archiveDir);
    const mf = JSON.parse(fs.readFileSync(mfPath, 'utf8'));
    assert.strictEqual(mf.headBefore, s.c2);
    assert.strictEqual(mf.target, s.c1);
    assert.strictEqual(mf.checkpointSha, pl.checkpointSha, '清单要写清撤销锚点');
    assert.strictEqual(mf.label, 'alpha-pack');
    assert.ok(mf.worktreeStatus === '' || typeof mf.worktreeStatus === 'string', '清单里存了当时的 git status');
    const archived = path.join(pl.archiveDir, 'files', ...`${PACK}/manifest.json`.split('/'));
    assert.strictEqual(fs.readFileSync(archived, 'utf8'), 'v2', '归档的必须是回滚前那份');
    assert.ok(mf.files.some((f) => f.path === `${PACK}/manifest.json` && f.md5 && f.bytes === 2), '清单里有 md5 与字节数');
    // ③ 检查点：干净时提交里没有本次改动 → 不造空提交，锚点仍是 HEAD
    assert.strictEqual(pl.checkpointCommitted, false, '干净时不该造空提交');
    assert.strictEqual(pl.checkpointSha, s.c2);
    // ④ 落地
    assert.strictEqual(read(s.dir, `${PACK}/manifest.json`), 'v1');
    assert.strictEqual(read(s.dir, `${PACK}/gone.json`), 'G', '目标里有、现在没有的要找回来');
    assert.strictEqual(read(s.dir, `${PACK}/z.json`), 'T');
    assert.strictEqual(exists(s.dir, `${PACK}/new.json`), false, '目标里没有的要删掉');
    // 实测行为：目录 pathspec + --source 会连带清掉「目标里没有」的工作区文件，
    // 所以这一项是 git 删的，不是编辑器删的 —— 数字要报对，不能都算在自己头上
    assert.strictEqual(pl.removedByGitCount, 1, JSON.stringify(pl.removedByGitCount));
    assert.strictEqual(read(s.dir, 'other/WIP.txt'), 'base', '范围外一个字都不碰');
    assert.strictEqual(read(s.dir, `${PACK}/entities/a.json`), 'A');
    assert.deepStrictEqual(wrote().sort(), ['add', 'restore'], '写操作只有 add / restore（干净时无需 commit）');
});

test('11. 脏工作区回滚：先强制 checkpoint 提交，当前状态进了 git 历史之后才被改写', () => {
    const s = scenario({ dirty: true, untrackedZ: true, stray: true });
    fresh();
    const before = g(['rev-parse', 'HEAD'], s.dir);
    const pl = RB.run(s.ctx, { sha: s.c1, paths: [PACK], acknowledge: true, expectedHead: s.c2, now: new Date('2026-10-24T07:30:12.000Z') });
    assert.strictEqual(pl.ok, true, pl.note + ' || ' + JSON.stringify(pl.failed));
    assert.strictEqual(pl.checkpointCommitted, true, '有未提交改动时必须造检查点提交');
    assert.notStrictEqual(pl.checkpointSha, before);
    const subj = g(['log', '-1', '--format=%s'], s.dir);
    assert.ok(/^editor 回滚前检查点：/.test(subj), subj);
    assert.ok(g(['log', '-1', '--format=%b'], s.dir).includes('ContentPack Editor'), '提交正文要写明是谁干的、回到哪一次');
    assert.strictEqual(g(['rev-parse', 'HEAD^'], s.dir), before, '检查点必须直接叠在原来的 HEAD 上');
    // 检查点里存的就是回滚前那份（含未跟踪文件）—— 这正是用户要的「回溯保留」
    assert.strictEqual(g(['show', `HEAD:./${PACK}/manifest.json`], s.dir).trim(), 'v3');
    assert.strictEqual(g(['show', `HEAD:./${PACK}/stray.json`], s.dir).trim(), 'S', '未跟踪文件也被检查点收进 git 之后才删除');
    assert.strictEqual(g(['show', `HEAD:./${PACK}/z.json`], s.dir).trim(), 'LOCAL', '本地那份未跟踪内容同样被保住');
    // 落地
    assert.strictEqual(read(s.dir, `${PACK}/manifest.json`), 'v1');
    assert.strictEqual(exists(s.dir, `${PACK}/stray.json`), false);
    assert.strictEqual(pl.removeUntracked.length, 1);
    // 没给 label 时用路径末段（安全化后）：归档名 = UTC 时间戳 + 包名
    assert.ok(/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}_alpha$/.test(pl.archiveDir), pl.archiveDir);
    const mf = JSON.parse(fs.readFileSync(path.join(pl.archiveDir, 'manifest.json'), 'utf8'));
    assert.strictEqual(mf.checkpointSha, pl.checkpointSha, '清单回填了检查点号');
    assert.deepStrictEqual(wrote().sort(), ['add', 'commit', 'restore']);
});

test('12. 撤销回滚 = 还原到检查点那份（含被删的未跟踪文件与本地改动）', () => {
    const s = scenario({ dirty: true, untrackedZ: true, stray: true });
    fresh();
    const pl = RB.run(s.ctx, { sha: s.c1, paths: [PACK], acknowledge: true, expectedHead: s.c2 });
    assert.strictEqual(pl.ok, true, pl.note + ' || ' + JSON.stringify(pl.failed));
    fresh();
    const un = RB.undo(s.ctx, { checkpointSha: pl.checkpointSha, paths: [PACK] });
    assert.strictEqual(un.ok, true, un.note);
    assert.strictEqual(un.isUndo, true);
    assert.strictEqual(read(s.dir, `${PACK}/manifest.json`), 'v3', '回到回滚前的那份本地改动');
    assert.strictEqual(read(s.dir, `${PACK}/stray.json`), 'S', '被删掉的未跟踪文件也回来了');
    assert.strictEqual(read(s.dir, `${PACK}/z.json`), 'LOCAL');
    assert.strictEqual(read(s.dir, `${PACK}/new.json`), 'N');
    assert.strictEqual(exists(s.dir, `${PACK}/gone.json`), false, '检查点里没有的文件，撤销不该顺手造出来');
    assert.ok(/已还原到检查点/.test(un.note), un.note);
});

test('13. 索引里有范围外的暂存改动 → 中止在检查点这一步，工作区未动', () => {
    const s = scenario({ stagedOutside: true });
    fresh();
    const pl = RB.run(s.ctx, { sha: s.c1, paths: [PACK], acknowledge: true, expectedHead: s.c2 });
    assert.strictEqual(pl.ok, false);
    assert.strictEqual(pl.stage, 'checkpoint', '必须在写工作区之前拦住');
    assert.ok(/范围之外/.test(pl.note), pl.note);
    assert.ok(String(pl.checkpointError).includes('other/WIP.txt'), pl.checkpointError);
    assert.strictEqual(read(s.dir, `${PACK}/manifest.json`), 'v2', 'restore 一步都没跑');
    assert.strictEqual(exists(s.dir, `${PACK}/new.json`), true);
    assert.ok(fs.existsSync(pl.archiveDir), '归档可以先留下（里面就是当时的状态，值得留着）');
    assert.ok(g(['diff', '--cached', '--name-only'], s.dir).includes('other/WIP.txt'), '别人那份暂存改动还在索引里');
    assert.deepStrictEqual(wrote(), [], '拦住得比 add 更早：连暂存都不该动');
});

test('14. 非 git 仓库 / 不存在的提交 / 缺归档目录：三种前置失败各说各话', () => {
    const plain = path.join(TMP, 'plain-' + (++seq));
    fs.mkdirSync(plain, { recursive: true });
    fresh();
    const a = RB.plan({ root: plain, git: runner, archiveRoot: TMP }, { sha: 'f'.repeat(40), paths: ['x'] });
    assert.strictEqual(a.ok, false);
    assert.strictEqual(a.isRepo, false);
    assert.ok(/不是 git 仓库/.test(a.note), a.note);

    const s = scenario();
    fresh();
    const b = RB.plan(s.ctx, { sha: 'deadbeef'.repeat(4) + 'dead', paths: [PACK] });
    assert.strictEqual(b.ok, false);
    assert.ok(/目标提交读不到/.test(b.note), b.note);

    fresh();
    const c = RB.run({ root: s.dir, git: runner }, { sha: s.c1, paths: [PACK], acknowledge: true });
    assert.strictEqual(c.ok, false);
    assert.strictEqual(c.stage, 'archive');
    assert.ok(/未配置归档目录/.test(c.note), c.note);
    assert.deepStrictEqual(wrote(), [], '归档目录都没配好之前不动 git');
});

test('15. 撤销的锚点丢了就直说：找不到的检查点提交 → 拒绝，不瞎猜', () => {
    const s = scenario();
    fresh();
    const un = RB.undo(s.ctx, { checkpointSha: 'abcdef0123456789'.repeat(2), paths: [PACK] });
    assert.strictEqual(un.ok, false);
    assert.ok(/找不到，无法撤销/.test(un.note), un.note);
    assert.strictEqual(read(s.dir, `${PACK}/manifest.json`), 'v2', '工作区原样');
    assert.deepStrictEqual(wrote(), []);
});

test('16. 同秒重复回滚 → 归档目录加后缀，绝不覆盖上一份档案', () => {
    const s = scenario({ dirty: true });
    fresh();
    const now = new Date('2026-10-24T08:00:00.000Z');
    const a = RB.run(s.ctx, { sha: s.c1, paths: [PACK], acknowledge: true, now, label: 'alpha' });
    assert.strictEqual(a.ok, true, a.note);
    // 第二次：把工作区再改脏，否则和已回滚的目标一致 → 走的是 noChange 分支，压根不建档案
    w(s.dir, `${PACK}/manifest.json`, 'v5');
    GitHistory.resetRepoCache();
    const b = RB.run(s.ctx, { sha: s.c1, paths: [PACK], acknowledge: true, now, label: 'alpha' });
    assert.strictEqual(b.ok, true, b.note + ' || ' + JSON.stringify(b.failed));
    assert.notStrictEqual(a.archiveDir, b.archiveDir);
    assert.ok(/-2$/.test(b.archiveDir), b.archiveDir);
    assert.strictEqual(fs.readFileSync(path.join(a.archiveDir, 'files', ...`${PACK}/manifest.json`.split('/')), 'utf8'), 'v3', '第一份档案没被第二次覆盖');
    assert.strictEqual(fs.readFileSync(path.join(b.archiveDir, 'files', ...`${PACK}/manifest.json`.split('/')), 'utf8'), 'v5');
});

// ==================== 上限与只读承诺（假 git：只为验证算术，不真造 501 个文件）====================

function fakeGit(script) {
    const calls = [];
    const keys = Object.keys(script).sort((a, b) => b.length - a.length);
    return (args) => {
        calls.push(args.slice());
        const joined = args.join(' ');
        for (const k of keys) if (joined.startsWith(k)) {
            const r = script[k];
            return typeof r === 'function' ? r(args) : Object.assign({ code: 0, stdout: '', stderr: '' }, r);
        }
        return { code: 0, stdout: '', stderr: '' };
    };
}

test('17. 超过单次文件上限 → 拒绝执行，并说明「半套回滚比不回滚更糟」', () => {
    const many = Array.from({ length: 501 }, (_, i) => `${PACK}/f${i}.json`).join('\n');
    const calls = [];
    seenArgv.length = 0;
    const fake = fakeGit({
        'rev-parse --is-inside-work-tree': { stdout: 'true\n' },
        'rev-parse --show-toplevel': { stdout: 'X:/repo\n' },
        'rev-parse --abbrev-ref HEAD': { stdout: 'main\n' },
        'log -1': { stdout: ['f'.repeat(40), 'fffffff', 'a', 'a@b', '2026-10-24T00:00:00+08:00', 'HEAD 提交', ''].join('\x1f') + '\n' },
        'show --no-patch': { stdout: ['e'.repeat(40), 'eeeeeee', 'a', 'a@b', '2026-10-20T00:00:00+08:00', '目标提交', ''].join('\x1f') + '\n' },
        'ls-tree': { stdout: many },
        'ls-files --others': { stdout: '' },
        'ls-files': { stdout: many },
        'diff --no-renames': { stdout: many },
        'status --porcelain': { stdout: '' },
    });
    const wrapped = (args, cwd) => { const r = fake(args, cwd); calls.push(args.slice()); return r; };
    GitHistory.resetRepoCache();
    const ctx = { root: 'X:/repo', git: wrapped, archiveRoot: 'X:/archive' };
    const pl = RB.plan(ctx, { sha: 'e'.repeat(40), paths: [PACK] });
    assert.strictEqual(pl.restoreCount, 501);
    assert.strictEqual(pl.tooMany, true);
    assert.ok(/超过单次上限 500/.test(pl.note), pl.note);
    GitHistory.resetRepoCache();
    seenArgv.length = 0;
    const run = RB.run(ctx, { sha: 'e'.repeat(40), paths: [PACK], acknowledge: true });
    assert.strictEqual(run.ok, false);
    assert.strictEqual(run.stage, 'plan');
    assert.deepStrictEqual(calls.map(subcommand).filter((s) => ['add', 'commit', 'restore'].includes(s)), [], '超限绝不能动手');
});

test('18. 项目根只是仓库的子目录时：路径仍以仓库根为准（否则范围外的暂存会被漏判）', () => {
    const s = scenario({ stagedOutside: true });
    fresh();
    const sub = { root: path.join(s.dir, 'contentpacks-source'), git: runner, archiveRoot: path.join(TMP, 'archive-sub') };
    const pl = RB.plan(sub, { sha: s.c1, paths: [PACK] });
    assert.strictEqual(pl.root.replace(/\\/g, '/'), s.dir.replace(/\\/g, '/'), 'root 归一到仓库根');
    assert.strictEqual(pl.restoreCount, 3);
    const run = RB.run(sub, { sha: s.c1, paths: [PACK], acknowledge: true, expectedHead: s.c2 });
    assert.strictEqual(run.ok, false, '范围外的 other/WIP.txt 暂存改动必须被拦住');
    assert.strictEqual(run.stage, 'checkpoint');
    assert.ok(String(run.checkpointError).includes('other/WIP.txt'), run.checkpointError);
});

// ==================== 小工具 ====================

test('19. inScope / stamp / safeLabel / 冲突判定：边界都要卡住', () => {
    assert.strictEqual(RB.inScope('contentpacks-source/alpha/x.json', [PACK]), true);
    assert.strictEqual(RB.inScope('contentpacks-source/alpha2/x.json', [PACK]), false, '前缀匹配必须落在目录边界上');
    assert.strictEqual(RB.inScope(PACK, [PACK]), true);
    assert.strictEqual(RB.stamp(new Date('2026-10-24T07:30:12.345Z')), '2026-10-24T07-30-12');
    const lb = RB.safeLabel('近距攻击 包/x \\ & ..');
    assert.strictEqual(lb.includes('/'), false, lb);
    assert.strictEqual(lb.includes('\\'), false, lb);
    assert.ok(lb.includes('近距攻击'), '中文留着，别压成一串横杠：' + lb);
    assert.strictEqual(RB.safeLabel('../../etc/passwd'), 'etc-passwd');
    assert.strictEqual(RB.safeLabel(''), 'pack');
    assert.strictEqual(RB.isConflictLine('UU foo.json'), true);
    assert.strictEqual(RB.isConflictLine('AA foo.json'), true);
    assert.strictEqual(RB.isConflictLine(' M foo.json'), false);
    assert.deepStrictEqual(RB.relList([PACK, `${PACK}/x`]), [PACK], '嵌套路径折叠到最外层');
});

console.log(`\n=== gitRollback 测试: ${passed} passed, ${failed} failed ===`);
try { fs.rmSync(TMP, { recursive: true, force: true, maxRetries: 3 }); } catch { /* 临时目录留给系统自己清 */ }
process.exit(failed ? 1 : 0);
