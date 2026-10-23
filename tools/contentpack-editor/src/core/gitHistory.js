// gitHistory.js - 只读 git 提交回溯（0.2.2 第 2 步）
// 依赖：child_process（可经 ctx.git 注入替换，测试用假 git）
// 暴露：repoInfo / pathStates / history / commitDetail / workingTree / toRepoRel / isRepoPath
//
// 铁律（用户要求）：本模块只跑「只读」命令 ——
//   rev-parse / log / show / diff / status / ls-files / check-ignore
// 绝不 commit / checkout / restore / reset / clean。回溯动作留在第 3 步单独实现，
// 且按用户定的规则：回溯前先把当前状态归档（或强制 checkpoint 提交）。
//
// 数字诚实：提交数、文件数、差异字节数全部来自 git 的输出行数，读不到就 ok:false + note，
// 不猜、不补、不把「没有记录」显示成「没有改动」。
'use strict';

const { spawnSync } = require('child_process');

const GIT_BIN = 'git';
const MAX_BUFFER = 32 * 1024 * 1024;
const DEFAULT_LIMIT = 40;
const MAX_LIMIT = 200;
const DEFAULT_DIFF_BYTES = 200 * 1024;
const MAX_PATHS = 8;

// 记录分隔符 / 字段分隔符：用 ASCII 控制字符，正常提交信息里不会出现
const REC = '\x1e';
const FLD = '\x1f';
const LOG_FMT = REC + ['%H', '%h', '%an', '%ae', '%ad', '%s', '%P'].join(FLD);
const SHORT_FMT = ['%H', '%h', '%an', '%ae', '%ad', '%s', '%P'].join(FLD);

// ==================== 命令执行 ====================

/** 默认 git 执行器：同步、无 shell、UTF-8 解码、关分页与彩色 */
function defaultGit(args, cwd) {
    try {
        const r = spawnSync(GIT_BIN, ['-c', 'core.quotepath=false', '-c', 'color.ui=false', ...args], {
            cwd: cwd || undefined, encoding: 'utf8', maxBuffer: MAX_BUFFER, windowsHide: true,
        });
        if (r.error) {
            return { code: -1, stdout: '', stderr: `git 无法执行：${r.error.message || r.error}` };
        }
        return { code: r.status, stdout: r.stdout || '', stderr: r.stderr || '' };
    } catch (e) {
        return { code: -1, stdout: '', stderr: `git 无法执行：${e.message || e}` };
    }
}

function run(ctx, args) {
    const exec = ctx && typeof ctx.git === 'function' ? ctx.git : defaultGit;
    const r = exec(args, ctx && ctx.root ? ctx.root : undefined);
    return {
        code: r && typeof r.code === 'number' ? r.code : -1,
        stdout: (r && r.stdout) || '',
        stderr: (r && r.stderr) || '',
    };
}

// ==================== 入参校验 ====================

/** 仓库内相对路径（posix）。拒绝绝对路径、.. 上跳、以 - 开头（会被 git 当选项）、控制字符 */
function safeRel(p) {
    if (typeof p !== 'string') throw new Error('路径必须是字符串');
    const s = p.trim().replace(/\\/g, '/').replace(/^(?:\.\/)+/, '');
    if (!s || s === '.' || s === '/') throw new Error('路径为空（不接受仓库根本身：回溯视图按包路径核对）');
    if (s.length > 400) throw new Error('路径过长');
    if (s.startsWith('/') || /^[A-Za-z]:/.test(s)) throw new Error('必须是仓库内相对路径，收到绝对路径');
    if (s.startsWith('-')) throw new Error('路径不能以 - 开头');
    if (/[\x00-\x1f\x7f]/.test(s)) throw new Error('路径含控制字符');
    const segs = s.split('/');
    for (const seg of segs) {
        if (seg === '..') throw new Error('路径不能包含 ..');
        if (seg === '') throw new Error('路径有连续分隔符');
    }
    return segs.join('/');
}

function safeRelList(paths) {
    if (!Array.isArray(paths) || !paths.length) throw new Error('至少要有一个路径');
    if (paths.length > MAX_PATHS) throw new Error(`一次最多核对 ${MAX_PATHS} 个路径`);
    return [...new Set(paths.filter((p) => p !== null && p !== undefined).map(safeRel))];
}

/** 提交号只接受十六进制，杜绝把 --output=/rev^{/ 之类当参数递给 git */
function safeSha(sha) {
    if (typeof sha !== 'string') throw new Error('提交号必须是字符串');
    const s = sha.trim();
    if (!/^[0-9a-fA-F]{4,40}$/.test(s)) throw new Error(`提交号格式非法（仅接受 4-40 位十六进制）: ${s}`);
    return s.toLowerCase();
}

function safeLimit(n) {
    const v = Number(n);
    if (!Number.isFinite(v) || v <= 0) return DEFAULT_LIMIT;
    return Math.min(MAX_LIMIT, Math.floor(v));
}

// ==================== 仓库信息 ====================

/** abs 路径 → 仓库相对路径（不在仓库内返回 {ok:false}，这是诚实状态而不是错误） */
function toRepoRel(ctx, absPath) {
    const info = repoInfo(ctx);
    if (!info.isRepo) return { ok: false, rel: null, reason: info.error || '当前项目根不是 git 仓库' };
    const path = require('path');
    if (!absPath || typeof absPath !== 'string') return { ok: false, rel: null, reason: '路径为空' };
    const abs = path.resolve(absPath);
    const root = path.resolve(info.root);
    const rel = path.relative(root, abs);
    if (!rel) return { ok: true, rel: '', inside: true };
    if (rel.startsWith('..') || path.isAbsolute(rel)) {
        return { ok: false, rel: null, reason: `${abs} 不在仓库 ${root} 内`, outside: true };
    }
    return { ok: true, rel: rel.split(path.sep).join('/'), inside: true };
}

let repoCache = null;   // {key, value, at}；只缓存 5 秒：换 ROOT 或刚提交过都能自然失效

function resetRepoCache() { repoCache = null; }

function repoInfo(ctx) {
    const key = ctx && ctx.root;
    if (repoCache && repoCache.key === key && Date.now() - repoCache.at < 5000) return repoCache.value;
    const out = { ok: false, isRepo: false, root: null, branch: null, detached: false, head: null, error: null, gitError: null };
    const probe = run(ctx, ['rev-parse', '--is-inside-work-tree']);
    if (probe.code !== 0) {
        out.error = '当前项目根不是 git 仓库';
        out.gitError = (probe.stderr || probe.stdout || '').trim() || `rev-parse 退出码 ${probe.code}`;
        if (/不是 git 仓库|not a git repository/i.test(out.gitError)) out.error = '当前项目根不是 git 仓库';
        else if (/git 无法执行/.test(out.gitError)) out.error = out.gitError;
        remember(ctx, out);
        return out;
    }
    const top = run(ctx, ['rev-parse', '--show-toplevel']);
    if (top.code === 0) out.root = top.stdout.trim();
    const br = run(ctx, ['rev-parse', '--abbrev-ref', 'HEAD']);
    if (br.code === 0) {
        const name = br.stdout.trim();
        if (name === 'HEAD') { out.detached = true; out.branch = null; } else out.branch = name;
    }
    const h = run(ctx, ['log', '-1', `--format=${SHORT_FMT}`, '--date=iso-strict']);
    if (h.code === 0 && h.stdout.trim()) out.head = parseFields(h.stdout.split('\n')[0], h);
    out.ok = true;
    out.isRepo = true;
    remember(ctx, out);
    return out;
}

function remember(ctx, value) {
    repoCache = { key: ctx && ctx.root, value, at: Date.now() };
}

function parseFields(line, r) {
    const f = String(line == null ? '' : line).split(FLD);
    if (f.length < 7) return null;
    const [sha, abbrev, author, email, date, subject, parents] = f;
    if (!/^[0-9a-f]{7,40}$/.test(sha)) return null;
    return {
        sha, abbrev, author, email, date, subject,
        parents: (parents || '').trim().split(/\s+/).filter(Boolean),
        merge: (parents || '').trim().split(/\s+/).filter(Boolean).length > 1,
        r,
    };
}

// ==================== 路径跟踪状态 ====================

/**
 * 每个路径的 git 状态：已跟踪 / 未跟踪 / 被忽略 —— 三者是不同事实，必须分开说。
 * 「未跟踪」= git 里根本没有历史；「被忽略」= .gitignore 命中，历史永远不会有。
 */
function pathStates(ctx, paths) {
    const rel = safeRelList(paths);
    const info = repoInfo(ctx);
    if (!info.isRepo) return { ok: false, isRepo: false, note: info.error, paths: [] };
    const out = [];
    for (const p of rel) {
        const t = run(ctx, ['ls-files', '--', p]);
        const trackedCount = t.code === 0 ? countLines(t.stdout) : 0;
        const u = run(ctx, ['ls-files', '--others', '--exclude-standard', '--', p]);
        const untrackedCount = u.code === 0 ? countLines(u.stdout) : 0;
        let ignored = false, ignoreRule = null;
        // 只探原始路径：给它补 '/'（当作目录再问一次）会被某些怪异规则（如一行空格）
        // 误判成「已忽略」，而实际是「已跟踪的产物 jar」。
        const ci = run(ctx, ['check-ignore', '-v', '--', p]);
        if (ci.code === 0 && ci.stdout.trim()) {
            ignored = true;
            const first = ci.stdout.split('\n').find((l) => l.trim());
            if (first) ignoreRule = first.split('\t')[0].trim() || first.trim();
        }
        out.push({
            rel: p, tracked: trackedCount > 0, trackedCount,
            untrackedCount, ignored, ignoreRule,
            // git 里既无记录、也没被忽略 → 从未提交（新包 / 手工拷进来的目录）
            neverCommitted: trackedCount === 0 && !ignored,
        });
    }
    return { ok: true, isRepo: true, branch: info.branch, paths: out };
}

// ==================== 提交历史 ====================

/**
 * 只读提交列表：git log --format=<带分隔符> --name-status -- <paths>
 * @returns {{ok, isRepo, commits: Array<{sha,abbrev,author,email,date,subject,parents,merge,files,fileCount}>,
 *            truncated, limit, paths, note}}
 */
function history(ctx, opts = {}) {
    const rel = safeRelList(opts.paths);
    const limit = safeLimit(opts.limit);
    const info = repoInfo(ctx);
    if (!info.isRepo) {
        return { ok: false, isRepo: false, commits: [], paths: rel, limit, note: info.error, gitError: info.gitError || null };
    }
    const args = ['log', `--format=${LOG_FMT}`, '--date=iso-strict', '--name-status', '-n', String(limit), '--'];
    const r = run(ctx, args.concat(rel));
    if (r.code !== 0) {
        return {
            ok: false, isRepo: true, commits: [], paths: rel, limit,
            note: `git log 失败：${(r.stderr || r.stdout || '').trim() || '退出码 ' + r.code}`,
        };
    }
    const commits = parseLog(r.stdout);
    const ps = pathStates(ctx, rel);
    const more = commits.length === limit;   // 命中 -n 上限：如实标注「可能还有更早的没列出」
    return {
        ok: true, isRepo: true, root: info.root, branch: info.branch, detached: info.detached, head: info.head,
        paths: rel, limit, commits, pathStates: ps.paths,
        truncated: more,
        note: commits.length === 0
            ? (ps.paths.some((p) => p.neverCommitted) ? '这些路径在 git 里没有提交记录（未跟踪）' : '这些路径在 git 里没有提交记录')
            : (more ? `只显示最近 ${limit} 条，可能还有更早的` : null),
    };
}

/** 解析 --format=<REC>… 输出：每段以 REC 开头，首行是字段，其余行是 name-status */
function parseLog(stdout) {
    const chunks = String(stdout).split(REC);
    const out = [];
    for (const chunk of chunks) {
        if (!chunk.trim()) continue;
        const nl = chunk.indexOf('\n');
        const headLine = nl === -1 ? chunk : chunk.slice(0, nl);
        const body = nl === -1 ? '' : chunk.slice(nl + 1);
        const meta = parseFields(headLine);
        if (!meta) continue;
        const { sha, abbrev, author, email, date, subject, parents, merge } = meta;
        const files = parseNameStatus(body);
        out.push({ sha, abbrev, author, email, date, subject, parents, merge, files, fileCount: files.length });
    }
    return out;
}

/** `M\tpath` / `R100\told\tnew` / `A\tpath` → [{status, path, oldPath}] */
function parseNameStatus(body) {
    const files = [];
    for (const line of String(body || '').split('\n')) {
        const s = line.replace(/\r$/, '');
        if (!s.trim()) continue;
        const bits = s.split('\t');
        if (bits.length < 2) continue;
        const status = bits[0].trim();
        files.push({
            status,
            path: bits.length >= 3 ? bits[2] : bits[1],
            oldPath: bits.length >= 3 ? bits[1] : null,
        });
    }
    return files;
}

// ==================== 单条提交详情 ====================

/**
 * 只读：某提交在指定路径上的改动（文件清单 + stat 摘要 + 文本 diff，字节数封顶）
 * diff 用 `git diff <sha>^ <sha>` 而不是 `git show <sha>`，因为合并提交 show 默认不输出差异；
 * 无父（根提交）时改用 `git show --format= <sha>`。
 */
function commitDetail(ctx, opts = {}) {
    const sha = safeSha(opts.sha);
    const rel = safeRelList(opts.paths);
    const info = repoInfo(ctx);
    if (!info.isRepo) return { ok: false, isRepo: false, note: info.error, files: [], diff: '' };

    const maxBytes = Number.isFinite(Number(opts.maxBytes)) && Number(opts.maxBytes) > 0
        ? Math.min(4 * 1024 * 1024, Math.floor(Number(opts.maxBytes))) : DEFAULT_DIFF_BYTES;

    const metaR = run(ctx, ['show', '--no-patch', `--format=${SHORT_FMT}`, '--date=iso-strict', sha]);
    if (metaR.code !== 0) {
        return { ok: false, isRepo: true, sha, note: `提交不存在或 git show 失败：${(metaR.stderr || '').trim() || '退出码 ' + metaR.code}`, files: [], diff: '' };
    }
    const meta = parseFields(metaR.stdout.split('\n')[0]);
    if (!meta) return { ok: false, isRepo: true, sha, note: '无法解析提交信息', files: [], diff: '' };

    const filesR = run(ctx, ['show', '--format=', '--name-status', sha, '--'].concat(rel));
    let files = filesR.code === 0 ? parseNameStatus(filesR.stdout) : [];
    // 合并提交 --name-status 为空是常态（差异相对第一父），此时按第一父重取一次
    if (!files.length && meta.parents.length > 1) {
        const viaDiff = run(ctx, ['diff', '--name-status', meta.parents[0], sha, '--'].concat(rel));
        if (viaDiff.code === 0) files = parseNameStatus(viaDiff.stdout);
    }
    const statR = run(ctx, ['show', '--format=', '--stat=120', sha, '--'].concat(rel));
    const statLines = statR.code === 0 ? statR.stdout.split('\n').map((s) => s.replace(/\r$/, '')).filter((s) => s.trim()) : [];
    const summary = statLines.length ? statLines[statLines.length - 1].trim() : '';

    let diffCmd;
    if (meta.parents.length >= 1) diffCmd = ['diff', '--unified=3', '--no-color', meta.parents[0], sha, '--'].concat(rel);
    else diffCmd = ['show', '--format=', '--unified=3', '--no-color', sha, '--'].concat(rel);
    const diffR = run(ctx, diffCmd);
    const raw = diffR.code === 0 ? diffR.stdout : '';
    const truncated = Buffer.byteLength(raw, 'utf8') > maxBytes;
    const diff = truncated ? sliceBytes(raw, maxBytes) : raw;

    return {
        ok: true, isRepo: true, sha, meta, files,
        fileCount: files.length,
        additions: countSide(raw, '+'), deletions: countSide(raw, '-'),
        summary, diff, diffBytes: Buffer.byteLength(raw, 'utf8'), truncated,
        note: diffR.code !== 0 ? `git diff 失败：${(diffR.stderr || '').trim()}` : null,
        binaryHint: files.some((f) => isBinaryPath(f.path)),
    };
}

function sliceBytes(text, maxBytes) {
    const buf = Buffer.from(text, 'utf8').subarray(0, maxBytes);
    return buf.toString('utf8') + '\n…（差异已截断）';
}

function countSide(diffText, sign) {
    let n = 0;
    for (const line of String(diffText).split('\n')) {
        if (line.startsWith('+++') || line.startsWith('---')) continue;   // 文件头，不是增删行
        if (line.startsWith(sign)) n++;
    }
    return n;
}

const BINARY_EXT = /\.(png|jpg|jpeg|gif|webp|ico|bmp|tga|dds|ogg|wav|mp3|mp4|zip|jar|nbt|class|so|dll|exe|ttf|otf)$/i;
function isBinaryPath(p) { return BINARY_EXT.test(String(p || '')); }

// ==================== 工作区状态（回溯安全性的前提） ====================

/**
 * 指定路径当前未提交的改动 —— 第 3 步回溯前必须查它：
 * 工作区脏 → 直接 restore 会丢掉这些改动，所以要先归档 / 强制 checkpoint 提交。
 */
function workingTree(ctx, opts = {}) {
    const rel = safeRelList(opts.paths);
    const info = repoInfo(ctx);
    if (!info.isRepo) return { ok: false, isRepo: false, dirty: false, changes: [], untracked: [], note: info.error };

    const st = run(ctx, ['status', '--porcelain', '--untracked-files=normal', '--'].concat(rel));
    if (st.code !== 0) {
        return { ok: false, isRepo: true, dirty: false, changes: [], untracked: [], note: `git status 失败：${(st.stderr || '').trim()}` };
    }
    const changes = [];
    const untracked = [];
    for (const line of st.stdout.split('\n')) {
        const s = line.replace(/\r$/, '');
        if (!s.trim()) continue;
        const x = s[0], y = s[1];
        let p = s.slice(3);
        let oldPath = null;
        const arrow = p.indexOf(' -> ');
        if (arrow !== -1) { oldPath = p.slice(0, arrow); p = p.slice(arrow + 4); }
        const code = (x === ' ' ? '' : x) + (y === ' ' ? '' : y);
        if (code === '??') { untracked.push(p); continue; }
        changes.push({
            code, path: p, oldPath,
            staged: x !== ' ' && x !== '?',
            unstaged: y !== ' ',
            label: describeStatus(code),
        });
    }
    const statR = run(ctx, ['diff', '--stat=120', '--'].concat(rel));
    const cachedR = run(ctx, ['diff', '--cached', '--stat=120', '--'].concat(rel));
    const last = (r) => {
        if (r.code !== 0) return '';
        const lines = r.stdout.split('\n').map((s) => s.replace(/\r$/, '')).filter((s) => s.trim());
        return lines.length ? lines[lines.length - 1].trim() : '';
    };
    return {
        ok: true, isRepo: true,
        dirty: changes.length > 0 || untracked.length > 0,
        changes, untracked,
        worktreeSummary: last(statR), stagedSummary: last(cachedR),
        // 只读视图也要能数清：改动文件数 + 未跟踪文件数
        changedCount: changes.length, untrackedCount: untracked.length,
        note: null,
    };
}

function describeStatus(code) {
    const map = {
        M: '已修改', A: '新增（已暂存）', D: '删除', R: '重命名', C: '复制',
        T: '类型变化', U: '冲突', 'AM': '新增后改动', ' M': '已修改',
    };
    return map[code] || (code ? code : '无变化');
}

// ==================== 小工具 ====================

function countLines(text) {
    if (!text) return 0;
    let n = 0;
    for (const l of String(text).split('\n')) if (l.replace(/\r$/, '').trim() !== '') n++;
    return n;
}

module.exports = {
    repoInfo, resetRepoCache, toRepoRel, pathStates, history, commitDetail, workingTree,
    safeRel, safeSha, safeLimit, parseLog, parseNameStatus,
    // 第 3 步（gitRollback.js）复用同一个子进程入口：一条 git 执行路径，别搞两套
    runGit: run, defaultGit,
    LIMITS: { DEFAULT_LIMIT, MAX_LIMIT, DEFAULT_DIFF_BYTES, MAX_PATHS },
};
