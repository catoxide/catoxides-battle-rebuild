// gitRollback.js - 提交回溯的「动作」（0.2.2 第 3 步）
// 依赖：core/gitHistory.js（同一 git 执行入口）、fs/path/crypto（均可经 ctx 注入，测试用）
// 暴露：plan / run / undo / archiveRoot / LIMITS
//
// 用户定的安全规则（原话）：「能不能 回溯保留 或者 至少在我们的 app 里 回溯的时候强制提交？」
// 这里两条同时落实，顺序固定，缺一不可：
//   ① plan   —— 只读算清「哪些文件会被改写、哪些会被删除」，超出上限就拒绝执行（不做半套）
//   ② 归档   —— 把会被触碰的每个文件的当前内容逐字节复制到 userData/rollback-archive/<时间戳>/
//   ③ 强制提交 —— git add -A -- <范围> && git commit：把当前状态钉进 git 历史，既是保险也是「撤销」的锚点
//   ④ restore —— git restore --source=<目标> --worktree -- <范围>，再删掉「目标里没有、现在有」的文件
//
// 四条硬约束：
//   · 范围永远来自调用方给的路径集合（包目录 + 产物 jar），绝不整树 checkout/reset/clean
//   · 索引里若已有范围之外的已暂存改动 → 直接中止（不能把别人的 WIP 卷进检查点提交）
//   · 不加 --no-verify：钩子失败就是失败，不绕过
//   · 写操作之前任何一步失败，工作区都还是原样（归档/提交都在 restore 之前）
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const GitHistory = require('./gitHistory');

const MAX_FILES = 500;              // 一次回滚最多触碰的文件数；超了就让人去 git 里做
const MAX_COMMIT_MSG = 4000;
const EDITOR_STATE = 'ContentPack Editor';

const run = (ctx, args) => GitHistory.runGit(ctx, args);

/** git 打印的路径相对于工作目录，而编辑器项目根可能只是仓库的一个子目录。
 *  统一切到仓库根再跑，路径才彼此对得上 —— 否则「范围外的暂存改动」会被漏判，
 *  检查点提交就会把无关改动一起带走。 */
function atRepoRoot(ctx, info) {
    return info && info.isRepo && info.root ? Object.assign({}, ctx, { root: info.root }) : ctx;
}

// ==================== 入参 ====================

/** 仓库内相对路径列表（复用 gitHistory 的校验：拒绝绝对路径 / .. / 以 - 开头 / 控制字符） */
function relList(paths) {
    if (!Array.isArray(paths) || !paths.length) throw new Error('至少要有一个路径');
    const rels = [...new Set(paths.filter((p) => p !== null && p !== undefined).map(GitHistory.safeRel))];
    if (!rels.length) throw new Error('路径全部为空');
    // 目录套目录时只留最外层：否则 git 把同一批文件算两遍，计划里的数字就不准了
    return rels.filter((r) => !rels.some((o) => o !== r && r.startsWith(o + '/')));
}

/** 路径是否落在本次范围内（检查点提交前用来挡住范围外的暂存改动） */
function inScope(p, rels) {
    const s = String(p || '').replace(/\\/g, '/');
    return rels.some((r) => s === r || s.startsWith(r + '/'));
}

/** porcelain v1 的 XY 两位里出现 U，或 AA/DD —— 就是没解决的冲突 */
function isConflictLine(line) {
    const xy = String(line || '').slice(0, 2);
    return /U/.test(xy) || xy === 'AA' || xy === 'DD';
}

function absOf(root, rel) {
    return path.join(root, ...String(rel).split('/'));
}

function stamp(d) {
    const iso = (d instanceof Date ? d : new Date()).toISOString().replace(/[:.]/g, '-');
    return iso.slice(0, 19);   // 2026-10-24T07-30-12（UTC，秒级；重名时再补 -2）
}

/** 归档目录名里的包名：路径分隔符必须消失（它会被拼进 path.join，留着 '..'+''/'/' 就能上跳）；
 *  中文/字母/数字/点/横线/下划线保留，其余折叠成一个 '-' */
function safeLabel(s) {
    const cleaned = String(s || '')
        .replace(/[^\w.\u4e00-\u9fff-]+/g, '-')
        .replace(/^[.-]+/, '')
        .slice(0, 60);
    return cleaned || 'pack';
}

/** 归档根目录：由主进程传 userData/rollback-archive；没给就报错，不悄悄退回工程内 */
function archiveRoot(ctx) {
    const base = ctx && ctx.archiveRoot;
    if (!base) throw new Error('未配置归档目录（主进程应传 userData/rollback-archive）');
    return base;
}

function fsOf(ctx) {
    return (ctx && ctx.fs) || fs;
}

/** git 输出按行切（容忍 CRLF）；空行不算条目 */
function lines(text) {
    return String(text || '').split('\n').map((s) => s.replace(/\r$/, '')).filter((s) => s !== '');
}

/** 提交号 + 标题（预览和归档清单都要写清「回到哪一次」） */
function commitMeta(gctx, sha) {
    const r = run(gctx, ['show', '--no-patch', '--format=%H\x1f%h\x1f%ad\x1f%s', '--date=iso-strict', sha]);
    if (r.code !== 0) return null;
    const f = String(r.stdout || '').split('\n')[0].replace(/\r$/, '').split('\x1f');
    if (f.length < 4) return null;
    return { sha: f[0], short: f[1], date: f[2], subject: f[3] };
}

// ==================== ① 只读计划 ====================

/**
 * 算出「回到 sha 需要动哪些文件」—— 四方对齐，不靠猜：
 *   inTarget  ← git ls-tree -r <target>                          目标提交里该有的文件
 *   tracked   ← git ls-files                                     索引认为存在的文件
 *   untracked ← git ls-files --others --exclude-standard          工作区里 git 还不认识的文件
 *   changed   ← git diff --no-renames <target>                   内容/存在性与目标不一致的（只含跟踪文件）
 * 注意：git diff 看不见未跟踪文件，所以「未跟踪且在目标里」的一律列入改写 ——
 * 它们会先被检查点提交收进 git，再由 restore 覆盖成目标版本，本地那份归档 + 提交两处都有。
 */
function plan(ctx, opts = {}) {
    const rels = relList(opts.paths);
    const targetSha = GitHistory.safeSha(opts.sha);
    const info = GitHistory.repoInfo(ctx);
    const gctx = atRepoRoot(ctx, info);
    // repoInfo().head 是提交对象（{sha, abbrev, subject…}）或 null（空仓库）—— 这里只要 sha 字符串
    const headSha = info.head && info.head.sha ? info.head.sha : null;
    const out = {
        ok: false, isRepo: info.isRepo, root: info.root, branch: info.branch, detached: info.detached,
        head: headSha, headMeta: info.head || null, target: targetSha, targetMeta: null, paths: rels,
        inTarget: [], tracked: [], untracked: [], changed: [],
        restore: [], remove: [], removeSkipped: [], unchanged: [],
        restoreCount: 0, removeCount: 0, removeSkippedCount: 0, unchangedCount: 0, totalNow: 0,
        worktreeRaw: '', tooMany: false, conflict: false, note: null,
    };
    if (!info.isRepo) { out.note = info.error || '当前项目根不是 git 仓库'; return out; }
    if (!headSha) { out.note = '仓库还没有任何提交（空仓库）—— 回溯需要一个已存在的 HEAD'; return out; }

    const treeR = run(gctx, ['ls-tree', '-r', '--name-only', targetSha, '--'].concat(rels));
    if (treeR.code !== 0) {
        out.note = `目标提交读不到（git ls-tree）：${(treeR.stderr || '').trim() || '退出码 ' + treeR.code}`;
        return out;
    }
    out.targetMeta = commitMeta(gctx, targetSha);

    const trackedR = run(gctx, ['ls-files', '--'].concat(rels));
    const othersR = run(gctx, ['ls-files', '--others', '--exclude-standard', '--'].concat(rels));
    const diffR = run(gctx, ['diff', '--no-renames', '--name-only', targetSha, '--'].concat(rels));
    const statusR = run(gctx, ['status', '--porcelain', '--'].concat(rels));
    if (trackedR.code !== 0 || othersR.code !== 0 || diffR.code !== 0 || statusR.code !== 0) {
        out.note = `工作区盘点失败：${(diffR.stderr || othersR.stderr || trackedR.stderr || statusR.stderr || '').trim()
            || 'git ls-files / diff / status 有非 0 退出'}`;
        return out;
    }
    out.worktreeRaw = statusR.stdout;
    out.conflict = lines(out.worktreeRaw).some(isConflictLine);
    if (out.conflict) { out.note = '范围内有冲突未解决的文件，先处理冲突再回滚'; return out; }

    const inTarget = new Set(lines(treeR.stdout));
    const tracked = new Set(lines(trackedR.stdout));
    const untracked = new Set(lines(othersR.stdout).filter((p) => !tracked.has(p)));
    const changed = new Set(lines(diffR.stdout));
    const now = new Set([...tracked, ...untracked]);

    const restore = [];
    const unchanged = [];
    for (const f of [...inTarget].sort()) {
        if (!now.has(f) || changed.has(f) || untracked.has(f)) restore.push(f);
        else unchanged.push(f);
    }
    const wantRemove = [...now].filter((f) => !inTarget.has(f)).sort();

    // 删除只落在「确实存在、且是普通文件」的条目上：软链接不跟随，目录不当文件删
    const fsx = fsOf(ctx);
    const deletable = [];
    const skipped = [];
    for (const f of wantRemove) {
        let st = null;
        try { st = fsx.lstatSync(absOf(info.root, f)); } catch { st = null; }
        if (st && st.isFile()) deletable.push(f);
        else skipped.push({
            path: f,
            reason: st && st.isSymbolicLink() ? '软链接（不跟随删除）'
                : st && st.isDirectory() ? '目录（不按文件删除）'
                    : '工作区里其实不存在（只是索引/报告里的条目）',
        });
    }

    Object.assign(out, {
        ok: true,
        inTarget: [...inTarget].sort(), tracked: [...tracked].sort(),
        untracked: [...untracked].sort(), changed: [...changed].sort(),
        restore, remove: deletable, removeSkipped: skipped, unchanged,
        // 删除里再分一刀：git 已跟踪的 / git 还不认识的。面板要把后者单独讲清楚 ——
        // 「删掉你的临时文件」和「还原到那次提交」是两件事，不能混在一个数字里。
        removeUntracked: deletable.filter((f) => untracked.has(f)),
        removeTracked: deletable.filter((f) => !untracked.has(f)),
        restoreCount: restore.length, removeCount: deletable.length,
        removeSkippedCount: skipped.length, unchangedCount: unchanged.length, totalNow: now.size,
    });

    if (restore.length + deletable.length > MAX_FILES) {
        out.tooMany = true;
        out.note = `本次要触碰 ${restore.length + deletable.length} 个文件，超过单次上限 ${MAX_FILES} —— 回滚请缩小范围或在命令行分步做（半套回滚比不回滚更糟）`;
    }
    return out;
}

// ==================== ② 归档 ====================

/** 把「当前这份」逐文件复制进 archiveDir/files/<rel>；清单记 md5 + 字节数，事后能核对 */
function archive(ctx, pl, archiveDir, label) {
    const fsx = fsOf(ctx);
    const dir = path.join(archiveDir, 'files');
    const touched = [...new Set([...pl.restore, ...pl.remove])];
    const items = [];
    const failed = [];
    for (const rel of touched) {
        const abs = absOf(pl.root, rel);
        let buf = null;
        try {
            const st = fsx.lstatSync(abs);
            if (!st.isFile()) { items.push({ path: rel, skipped: '非普通文件，未复制' }); continue; }
            buf = fsx.readFileSync(abs);
        } catch (e) {
            items.push({ path: rel, skipped: `读不到：${String(e.message || e)}` });
            continue;
        }
        try {
            const dest = path.join(dir, ...rel.split('/'));
            fsx.mkdirSync(path.dirname(dest), { recursive: true });
            fsx.writeFileSync(dest, buf);
            items.push({ path: rel, bytes: buf.length, md5: crypto.createHash('md5').update(buf).digest('hex') });
        } catch (e) {
            failed.push({ path: rel, error: String(e.message || e) });
        }
    }
    const manifest = {
        at: new Date().toISOString(),
        editor: EDITOR_STATE,
        label: label || null,
        root: pl.root,
        branch: pl.branch,
        detached: pl.detached,
        headBefore: pl.head,
        target: pl.target,
        targetMeta: pl.targetMeta,
        paths: pl.paths,
        stats: {
            restore: pl.restoreCount, remove: pl.removeCount,
            unchanged: pl.unchangedCount, skipped: pl.removeSkippedCount,
        },
        restore: pl.restore,
        remove: pl.remove,
        removeSkipped: pl.removeSkipped,
        files: items,
        copyFailed: failed,
        // 当时的 git status 原样存一份：干净也记（空串），档案要能回答「那会儿工作区到底什么样」
        worktreeStatus: typeof pl.worktreeRaw === 'string' ? pl.worktreeRaw : null,
        checkpointSha: null,     // 提交成功后由 run() 回填
    };
    fsx.writeFileSync(path.join(archiveDir, 'manifest.json'), JSON.stringify(manifest, null, 2), 'utf8');
    if (pl.worktreeRaw) fsx.writeFileSync(path.join(archiveDir, 'worktree-status.txt'), pl.worktreeRaw, 'utf8');
    return { manifest, copied: items.filter((i) => i.md5).length, skipped: items.filter((i) => i.skipped).length, failed };
}

// ==================== ③ 强制检查点提交 ====================

/**
 * 把当前状态钉进 git，返回 {ok, committed, sha, skippedReason}。
 * 中止条件都是「宁可不动」：游离 HEAD / 范围外有暂存改动 / add 或 commit 失败。
 * 中止时归档已写好、工作区未动 —— 没有半套状态遗留。
 */
function checkpoint(gctx, pl, opts = {}) {
    if (!pl.head) return { ok: false, committed: false, error: '读不到当前 HEAD，放弃提交' };
    if (pl.detached) return { ok: false, committed: false, error: '仓库处于游离 HEAD（detached）—— 检查点提交会落在悬空对象上，请先切回分支' };

    // 范围外的暂存改动：一次普通 commit 会把它们一起带走，绝对不行
    const stagedR = run(gctx, ['diff', '--cached', '--name-only']);
    if (stagedR.code !== 0) return { ok: false, committed: false, error: `读暂存区失败：${(stagedR.stderr || '').trim() || '退出码 ' + stagedR.code}` };
    const outside = lines(stagedR.stdout).filter((p) => !inScope(p, pl.paths));
    if (outside.length) {
        return {
            ok: false, committed: false,
            error: `索引里有本次范围之外的已暂存改动（${outside.length} 个，例如 ${outside.slice(0, 5).join(', ')}）—— 为避免把无关改动卷进检查点提交，已中止。请先 unstage 或单独提交它们。`,
            outsideStaged: outside,
        };
    }

    const addR = run(gctx, ['add', '-A', '--'].concat(pl.paths));
    if (addR.code !== 0) return { ok: false, committed: false, error: `git add 失败：${(addR.stderr || '').trim() || '退出码 ' + addR.code}` };

    // 范围内干净 → 没有可提交的东西，检查点就是当前 HEAD（撤销锚点依然成立）
    const anyStaged = run(gctx, ['diff', '--cached', '--quiet', '--'].concat(pl.paths));
    if (anyStaged.code === 0) return { ok: true, committed: false, sha: pl.head, skippedReason: '范围内没有未提交改动，检查点即当前 HEAD' };

    const msg = (opts.message || `editor 回滚前检查点：${pl.branch || 'HEAD'} → ${pl.targetMeta ? pl.targetMeta.short : String(pl.target).slice(0, 8)}`).slice(0, 200);
    const body = [
        `${EDITOR_STATE} 部署面板「回溯到此提交」生成。`,
        `回滚目标：${pl.target}${pl.targetMeta ? ' ' + pl.targetMeta.subject : ''}`,
        `归档：${opts.archiveDir || '（未记录）'}`,
        `范围：${pl.paths.join(', ')}`,
    ].join('\n').slice(0, MAX_COMMIT_MSG);
    const commitR = run(gctx, ['commit', '-m', msg, '-m', body]);
    if (commitR.code !== 0) {
        return {
            ok: false, committed: false,
            error: `检查点提交失败（钩子或 git 拒绝，未跳过校验）：${((commitR.stdout || '') + '\n' + (commitR.stderr || '')).trim().slice(0, 2000) || '退出码 ' + commitR.code}`,
        };
    }
    const headR = run(gctx, ['rev-parse', 'HEAD']);
    if (headR.code !== 0) return { ok: false, committed: false, error: '提交后读不到 HEAD' };
    return { ok: true, committed: true, sha: headR.stdout.trim().split('\n')[0], subject: msg, output: (commitR.stdout || '').trim() };
}

// ==================== ④ 落地 ====================

/**
 * restore 用范围路径一次到位：git restore 按 pathspec 递归，含「目标里有、现在没有」的文件。
 * 实测行为（真 git 临时仓库）：目录 pathspec + --source 会连带把「目标里没有」的工作区文件删掉 ——
 * 所以 remove 列表里的那些在 restore 之后常常已经没了。这里不假设它一定发生：
 * 文件还在就自己 unlink，已经不在了就记为「由 git 清掉」，两种世界都算数，报出来的数字都是真的。
 * （无论哪种，动手前都已归档 + 检查点提交，删掉的东西两处可寻）
 */
function apply(gctx, pl, opts = {}) {
    const fsx = fsOf(gctx);
    const restored = [];
    const removed = [];
    const removedByGit = [];
    const failed = [];

    if (pl.restoreCount) {
        const r = run(gctx, ['restore', `--source=${pl.target}`, '--worktree', '--'].concat(pl.paths));
        if (r.code !== 0) failed.push({ scope: pl.paths.join(', '), error: `git restore 失败：${(r.stderr || '').trim() || '退出码 ' + r.code}` });
        else restored.push(...pl.restore);
    }
    if (!opts.keepRemoved) {
        for (const rel of pl.remove) {
            const abs = absOf(pl.root, rel);
            let st = null;
            try { st = fsx.lstatSync(abs); } catch { st = null; }
            if (!st) { removed.push(rel); removedByGit.push(rel); continue; }
            if (!st.isFile()) { failed.push({ path: rel, error: `不是普通文件（类型变了），不删` }); continue; }
            try { fsx.unlinkSync(abs); removed.push(rel); }
            catch (e) { failed.push({ path: rel, error: `删除失败：${String(e.message || e)}` }); }
        }
    }
    return { restored, removed, removedByGit, failed };
}

// ==================== 对外：执行 / 撤销 ====================

/**
 * 一次完整回滚：plan → 归档 → 强制检查点提交 → restore + 删除。
 * opts.acknowledge 必须为 true（渲染端确认过）；opts.expectedHead 必须仍是当前 HEAD
 * —— 预览之后仓库被动过（别的会话提交/切分支了）就拒绝执行，让人重新预览。
 */
function runRollback(ctx, opts = {}) {
    let pl;
    try { pl = plan(ctx, { sha: opts.sha, paths: opts.paths }); }
    catch (e) { return { ok: false, stage: 'plan', note: `参数不合法：${String(e.message || e)}` }; }

    const gctx = atRepoRoot(ctx, { isRepo: pl.isRepo, root: pl.root });
    if (!pl.ok) return Object.assign(pl, { ok: false, stage: 'plan' });
    if (pl.tooMany) return Object.assign(pl, { ok: false, stage: 'plan' });
    if (opts.acknowledge !== true) {
        return Object.assign(pl, { ok: false, stage: 'confirm', note: '未确认：回滚会改写工作区，必须由面板明确确认后才执行（本次未做任何改动）' });
    }
    const expect = opts.expectedHead ? String(opts.expectedHead).trim().toLowerCase() : null;
    if (expect && pl.head && expect !== pl.head.toLowerCase()) {
        return Object.assign(pl, {
            ok: false, stage: 'plan',
            note: `HEAD 已变（预览时 ${expect.slice(0, 8)}，现在 ${String(pl.head).slice(0, 8)}）—— 期间仓库被改动过，请重新预览再回滚`,
        });
    }
    if (!pl.restoreCount && !pl.removeCount) {
        return Object.assign(pl, { ok: true, noChange: true, note: '范围内内容已与目标提交一致，无需回滚' });
    }

    const label = safeLabel(opts.label || (pl.paths[0] || 'pack').split('/').pop());
    let archiveDir;
    try {
        const base = archiveRoot(ctx);
        const fsx = fsOf(ctx);
        let dirName = `${stamp(opts.now)}_${label}`;
        let n = 2;
        while (fsx.existsSync(path.join(base, dirName))) dirName = `${stamp(opts.now)}_${label}-${n++}`;
        archiveDir = path.join(base, dirName);
        fsx.mkdirSync(path.join(archiveDir, 'files'), { recursive: true });
    } catch (e) {
        return Object.assign(pl, { ok: false, stage: 'archive', note: `归档目录准备失败：${String(e.message || e)}`, archiveDir });
    }

    // ② 归档（先于任何写操作）
    let arch;
    try { arch = archive(ctx, pl, archiveDir, label); }
    catch (e) { return Object.assign(pl, { ok: false, stage: 'archive', note: `归档失败：${String(e.message || e)}`, archiveDir }); }
    if (arch.failed.length) {
        return Object.assign(pl, {
            ok: false, stage: 'archive', archiveDir,
            note: `有 ${arch.failed.length} 个文件没能归档（${arch.failed.slice(0, 3).map((f) => `${f.path}：${f.error}`).join('；')}）—— 工作区未做任何改动，先解决读取问题`,
        });
    }

    // ③ 强制检查点提交（失败则到此为止：restore 还没开始）
    const cp = checkpoint(gctx, pl, { archiveDir, message: opts.message });
    if (!cp.ok) return Object.assign(pl, { ok: false, stage: 'checkpoint', archiveDir, note: cp.error, checkpointError: cp.error });
    try {
        const mfPath = path.join(archiveDir, 'manifest.json');
        const mf = JSON.parse(fsOf(ctx).readFileSync(mfPath, 'utf8'));
        mf.checkpointSha = cp.sha;
        mf.checkpointCommitted = cp.committed;
        mf.checkpointSkipped = cp.skippedReason || null;
        fsOf(ctx).writeFileSync(mfPath, JSON.stringify(mf, null, 2), 'utf8');
    } catch { /* 清单回填失败不影响回滚本身；返回里另有 checkpointSha，如实说明 */ }

    // ④ 落地
    const applied = apply(gctx, pl);
    const ok = applied.failed.length === 0;
    return Object.assign(pl, {
        ok, stage: 'apply', archiveDir,
        archived: arch.copied, archivedSkipped: arch.skipped,
        checkpointSha: cp.sha, checkpointCommitted: cp.committed, checkpointSkipped: cp.skippedReason || null,
        restoredCount: applied.restored.length, removedCount: applied.removed.length,
        removedByGitCount: applied.removedByGit.length,
        restored: applied.restored, removed: applied.removed, failed: applied.failed,
        note: ok
            ? `已回到 ${pl.targetMeta ? pl.targetMeta.short : String(pl.target).slice(0, 8)}：改写 ${applied.restored.length} 个、删除 ${applied.removed.length} 个`
            + `（其中 ${applied.removedByGit.length} 个由 git restore 顺带清掉，${applied.removed.length - applied.removedByGit.length} 个由编辑器删除；空目录保留）`
            + `。回滚前的状态有两份备份：检查点提交 ${String(cp.sha).slice(0, 8)} + 归档目录`
            : `部分失败（${applied.failed.length} 项）。已归档 + 已提交检查点，可用「撤销回滚」整体还原`,
        undoHint: { checkpointSha: cp.sha, archiveDir, paths: pl.paths },
    });
}

/**
 * 撤销上一次回滚：把范围内内容还原成检查点提交那一份。
 * 不再归档、不再提交 —— 检查点里的内容 git 本来就存着，方向唯一且可重算。
 */
function undo(ctx, opts = {}) {
    const rels = relList(opts.paths);
    const sha = GitHistory.safeSha(opts.checkpointSha);
    const info = GitHistory.repoInfo(ctx);
    if (!info.isRepo) return { ok: false, isRepo: false, paths: rels, note: info.error };
    const gctx = atRepoRoot(ctx, info);
    const exists = run(gctx, ['cat-file', '-e', `${sha}^{commit}`]);
    if (exists.code !== 0) return { ok: false, isRepo: true, paths: rels, note: `检查点提交 ${sha.slice(0, 8)} 在这个仓库里找不到，无法撤销` };

    // 以检查点为「目标」重新规划：restore = 检查点里有而现在缺/不一致的，remove = 现在有而检查点没有的
    const pl = plan(ctx, { sha, paths: rels });
    if (!pl.ok) return Object.assign(pl, { ok: false, stage: 'plan' });
    if (pl.tooMany) return Object.assign(pl, { ok: false, stage: 'plan' });
    const applied = apply(gctx, pl);
    return Object.assign(pl, {
        ok: applied.failed.length === 0, stage: 'undo', isUndo: true, checkpointSha: sha,
        restoredCount: applied.restored.length, removedCount: applied.removed.length,
        restored: applied.restored, removed: applied.removed, failed: applied.failed,
        note: applied.failed.length
            ? `撤销部分失败（${applied.failed.length} 项）；没删掉的文件仍在归档目录里`
            : `已还原到检查点 ${sha.slice(0, 8)}（也就是回滚前的状态）`,
    });
}

module.exports = {
    plan, run: runRollback, undo, archiveRoot,
    inScope, isConflictLine, stamp, safeLabel, relList,
    LIMITS: { MAX_FILES, MAX_COMMIT_MSG },
};
