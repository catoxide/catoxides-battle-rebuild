// gitHistoryView.js - 顶栏「🕘 提交回溯」（0.2.2 第 2 步只读 + 第 3 步回滚动作）
// 依赖：window.api（gitInfo/gitHistory/gitCommit/gitWorkingTree + gitRollbackPlan/gitRollback/gitRollbackUndo）、window.I18n
// 暴露：window.GitHistoryView.init() / open(pack) / openDir(dirName, packDir) / close() / refresh() / isOpen() / rerender()
//
// 这一层摆的是 git 的实测结果：提交列表、每条提交在该包路径上的改动、工作区未提交改动。
// 差异文本来自后端 git diff 原文，前端不重排、不补全。
// 回滚走「先预览、再确认、最后执行」三步：预览的数字全部来自 core/gitRollback.js 的只读 plan，
// 前端一个数都不算；执行由主进程按「归档 → 强制检查点提交 → restore」的固定顺序做。
window.GitHistoryView = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : k);

    let target = null;        // {dirName, jarName, packDir, jarPath}
    let data = null;          // gitHistory 结果
    let work = null;          // workingTree 结果
    let limit = 40;
    let busy = false;
    let openSha = null;       // 当前展开差异的提交
    let detail = null;        // 展开提交的 commitDetail 结果
    let pending = null;       // 第 3 步：{sha, plan, loading} —— 只算不动的预览结果
    let rolling = false;      // 回滚进行中（同一时刻只允许一次，别和归档/提交抢锁）
    let queued = false;       // 上一轮还没测完时又来了一次 open/refresh：本轮结束就按最新目标补测一次
    let undoAnchor = null;    // 最近一次回滚的检查点：{checkpointSha, archiveDir, target, dirName, packDir, jarPath}
    const MAX_DIFF_CHARS = 120000;   // 渲染封顶（后端已按字节截断，这里再防一次超长卡页面）
    const MAX_PLAN_LIST = 60;        // 预览里最多列多少个文件名；数字仍按后端全量显示

    const el = (tag, cls, text) => {
        const n = document.createElement(tag);
        if (cls) n.className = cls;
        if (text !== undefined && text !== null) n.textContent = String(text);
        return n;
    };

    function bar(msg, ok) {
        const b = document.getElementById('historyStatus');
        if (!b) return;
        b.className = 'status ' + (ok ? 'ok' : 'err');
        b.textContent = String(msg || '');
    }

    function fmtTime(iso) {
        if (!iso) return '—';
        return String(iso).replace('T', ' ').slice(0, 16);
    }

    function payload() {
        if (!target) return null;
        return { packDir: target.packDir, jarPath: target.jarPath, limit };
    }

    // ===== 数据 =====

    async function load() {
        if (!target) return;
        if (busy) { queued = true; return; }   // 上一轮还在测：记下「欠一次重测」，本轮结束立刻按最新目标再来，别把这次请求丢掉
        busy = true;
        render();
        const p = payload();
        try {
            const [h, w] = await Promise.all([
                window.api.gitHistory(p),
                window.api.gitWorkingTree(p),
            ]);
            if (!h) { data = null; work = null; bar('gitHistory IPC 无响应'); return; }
            data = h.ok ? h.data : { ok: false, note: h.error, commits: [], pathStates: [] };
            work = w && w.ok ? w.data : { ok: false, note: w && w.error, dirty: false, changes: [], untracked: [] };
            if (!h.ok) bar(h.error || t('history.failed'));
        } catch (e) {
            data = { ok: false, commits: [], pathStates: [], note: String((e && e.message) || e) };
            work = null;
            bar(t('history.failed') + String((e && e.message) || e));
        } finally {
            busy = false;
            render();
            // 期间换过包/点过刷新：按最新目标补测，否则页面会停在旧包的数据上
            if (queued) { queued = false; load(); }
        }
    }

    async function loadDiff(sha) {
        const p = payload();
        if (!p) return;
        detail = null;
        openSha = sha;
        render();
        try {
            const res = await window.api.gitCommit(Object.assign({}, p, { sha }));
            detail = res && res.ok ? res.data : { ok: false, note: (res && res.error) || t('history.failed'), files: [], diff: '' };
        } catch (e) {
            detail = { ok: false, note: String((e && e.message) || e), files: [], diff: '' };
        }
        render();
    }

    // ===== 第 3 步：回滚（预览 → 确认 → 执行 → 可撤销） =====

    function rollbackPayload(extra) {
        if (!target) return null;
        return Object.assign({ packDir: target.packDir, jarPath: target.jarPath, label: target.dirName }, extra || {});
    }

    /** 只读预览：主进程按 plan 算好「会改写哪些、会删哪些」，前端只显示、不推算 */
    async function startRollback(c) {
        if (rolling || busy) return;
        if (pending && pending.sha === c.sha) { pending = null; render(); return; }
        const p = rollbackPayload({ sha: c.sha });
        if (!p) return;
        pending = { sha: c.sha, plan: null, loading: true };
        openSha = c.sha;
        render();
        let plan;
        try {
            const res = await window.api.gitRollbackPlan(p);
            if (res && res.ok) plan = res.data;
            else plan = Object.assign({ ok: false, note: (res && res.error) || t('history.planFailed') }, (res && res.data) || {});
        } catch (e) {
            plan = { ok: false, note: String((e && e.message) || e) };
        }
        if (pending && pending.sha === c.sha) pending = { sha: c.sha, plan, loading: false };
        render();
    }

    async function confirmRollback(c) {
        if (rolling || !pending || pending.sha !== c.sha || !pending.plan) return;
        const plan = pending.plan;
        const touch = (plan.restoreCount || 0) + (plan.removeCount || 0);
        const label = tf('history.confirmLabel', {
            name: target ? target.dirName : '', sha: c.abbrev,
            r: plan.restoreCount || 0, d: plan.removeCount || 0, u: (plan.removeUntracked || []).length,
        });
        let choice = null;
        if (window.askChoice) {
            choice = await window.askChoice({
                title: t('history.confirmRollback'), label: label + '\n\n' + t('history.planSafety'),
                options: [t('history.confirmRollback'), t('pack.cancel')], value: t('history.confirmRollback'),
            });
        } else {
            choice = window.confirm(label + '\n' + t('history.planSafety')) ? t('history.confirmRollback') : null;
        }
        if (!choice || choice === t('pack.cancel')) return;

        rolling = true;
        render();
        bar(t('history.rolling'));
        try {
            const res = await window.api.gitRollback(rollbackPayload({
                sha: c.sha, acknowledge: true, expectedHead: plan.head,
            }));
            const d = res && res.data ? res.data : null;
            if (!res || !res.ok) {
                let msg = ((res && res.error) || t('history.failed')) + (d && d.stage ? `（阶段：${d.stage}）` : '');
                if (d && d.archiveDir) msg += '\n' + t('history.archivedAt') + d.archiveDir;
                if (d && Array.isArray(d.failed) && d.failed.length) msg += '\n' + d.failed.slice(0, 5).map((f) => '· ' + (f.path || f.scope || '') + '：' + f.error).join('\n');
                bar(msg);
                pending = null;
            } else if (d && d.noChange) {
                pending = null;
                bar(t('history.noChangeRun'), true);
            } else {
                undoAnchor = {
                    checkpointSha: d.checkpointSha, archiveDir: d.archiveDir, target: d.target,
                    targetShort: d.targetMeta ? d.targetMeta.short : String(d.target || '').slice(0, 7),
                    dirName: target.dirName, packDir: target.packDir, jarPath: target.jarPath,
                    restored: d.restoredCount, removed: d.removeCount,
                };
                pending = null;
                bar((d.note || tf('history.didRollback', { sha: undoAnchor.targetShort })) +
                    '\n' + t('history.checkpoint') + String(d.checkpointSha || '').slice(0, 8) +
                    (d.checkpointCommitted ? '' : t('history.checkpointClean')) +
                    '\n' + t('history.archivedAt') + (d.archiveDir || '—'), true);
                await load();
                refreshDeploy();
            }
        } catch (e) {
            bar(t('history.failed') + String((e && e.message) || e));
        } finally {
            rolling = false;
            render();
        }
    }

    /** 撤销 = 把工作区还原到刚才那次检查点（回滚前的状态）；不产生新提交 */
    async function undoRollback() {
        if (rolling || !undoAnchor) return;
        rolling = true;
        render();
        try {
            const res = await window.api.gitRollbackUndo({
                packDir: undoAnchor.packDir, jarPath: undoAnchor.jarPath,
                checkpointSha: undoAnchor.checkpointSha, label: undoAnchor.dirName,
            });
            const d = res && res.data ? res.data : null;
            if (!res || !res.ok) {
                bar(t('history.undoFailed') + ((res && res.error) || (d && d.note) || t('history.failed')));
            } else {
                bar(tf('history.undoDone', { sha: String(undoAnchor.checkpointSha || '').slice(0, 8) }) + '\n' + (d.note || ''), true);
                undoAnchor = null;
                await load();
                refreshDeploy();
            }
        } catch (e) {
            bar(t('history.undoFailed') + String((e && e.message) || e));
        } finally {
            rolling = false;
            render();
        }
    }

    /** 部署面板就在上一层：回滚改了磁盘，它的数字必须跟着重测 */
    function refreshDeploy() {
        try {
            if (window.DeployStatus && typeof window.DeployStatus.refresh === 'function') window.DeployStatus.refresh(true);
        } catch { /* 面板没挂载不影响回溯本身 */ }
    }

    /** 回滚预览：数字与清单全部来自后端只读 plan，前端只做展示与确认 */
    function planBlock(c) {
        const box = el('div', 'gh-plan');
        if (pending && pending.loading) {
            box.appendChild(el('div', 'dp-dim', t('history.planning')));
            return box;
        }
        const p = pending && pending.plan;
        if (!p) { box.appendChild(el('div', 'dp-err', t('history.planFailed'))); return box; }
        if (!p.ok) {
            box.appendChild(el('div', 'dp-err', p.note || t('history.planFailed')));
            if (p.head) box.appendChild(el('div', 'dp-sub dp-mono', `HEAD ${String(p.head).slice(0, 8)}`));
            box.appendChild(el('div', 'dp-sub', t('history.planRefused')));
            return box;
        }
        const sum = el('div', 'gh-plan-sum');
        sum.appendChild(el('span', 'dp-warn', tf('history.restoreN', { n: p.restoreCount })));
        sum.appendChild(el('span', p.removeCount ? 'dp-err' : 'dp-dim', ' · ' + tf('history.removeN', { n: p.removeCount })));
        if (p.removeUntracked && p.removeUntracked.length) {
            sum.appendChild(el('span', 'dp-err', ' · ' + tf('history.removeUntrackedN', { n: p.removeUntracked.length })));
        }
        sum.appendChild(el('span', 'dp-ok', ' · ' + tf('history.unchangedN', { n: p.unchangedCount })));
        if (p.removeSkippedCount) sum.appendChild(el('span', 'dp-dim', ' · ' + tf('history.skippedN', { n: p.removeSkippedCount })));
        box.appendChild(sum);
        box.appendChild(el('div', 'dp-sub dp-mono',
            `HEAD ${String(p.head).slice(0, 8)} → ${String(p.target).slice(0, 8)}${p.targetMeta ? ' · ' + p.targetMeta.subject : ''}`));
        box.appendChild(el('div', 'dp-sub dp-mono', t('history.planScope') + (p.paths || []).join(', ')));
        if (p.detached) box.appendChild(el('div', 'dp-err', t('history.detachedWarn')));
        if (p.conflict) box.appendChild(el('div', 'dp-err', t('history.conflictWarn')));
        box.appendChild(el('div', 'dp-sub gh-hint', t('history.planSafety')));
        for (const [key, arr, cls] of [
            ['history.planRestore', p.restore, 'dp-warn'],
            ['history.planRemove', p.remove, 'dp-err'],
            ['history.planUnchanged', p.unchanged, 'dp-dim'],
        ]) {
            if (!arr || !arr.length) continue;
            box.appendChild(el('div', 'gh-sec', tf(key, { n: arr.length })));
            for (const f of arr.slice(0, MAX_PLAN_LIST)) box.appendChild(el('div', 'dp-mono gh-change ' + cls, f));
            if (arr.length > MAX_PLAN_LIST) box.appendChild(el('div', 'dp-dim', `… ${arr.length - MAX_PLAN_LIST} ${t('history.moreFiles')}`));
        }
        if (!p.restoreCount && !p.removeCount) box.appendChild(el('div', 'dp-ok', t('history.nothingToDo')));

        const row = el('div', 'gh-plan-actions');
        const go = el('button', 'btn small danger', t('history.confirmRollback'));
        go.disabled = rolling || busy;
        go.addEventListener('click', () => confirmRollback(c));
        const no = el('button', 'btn small ghost', t('pack.cancel'));
        no.disabled = rolling || busy;
        no.addEventListener('click', () => { pending = null; render(); });
        row.appendChild(go);
        row.appendChild(no);
        box.appendChild(row);
        return box;
    }

    /** 回滚完留下的「撤销」入口：锚点是那次检查点提交，方向唯一 */
    function undoBar(box) {
        if (!undoAnchor) return;
        const wrap = el('div', 'gh-undo');
        wrap.appendChild(el('span', 'dp-sub', tf('history.undoLabel', {
            sha: String(undoAnchor.checkpointSha || '').slice(0, 8),
            to: undoAnchor.targetShort || '',
            name: undoAnchor.dirName || '',
        })));
        if (undoAnchor.archiveDir) wrap.appendChild(el('div', 'dp-sub dp-mono', t('history.archivedAt') + undoAnchor.archiveDir));
        const b = el('button', 'btn small accent', t('history.undo'));
        b.disabled = rolling || busy;
        b.addEventListener('click', () => undoRollback());
        wrap.appendChild(b);
        box.appendChild(wrap);
    }

    // ===== 渲染 =====

    function repoLine(box) {
        const head = el('div', 'gh-repo');
        if (!data || !data.ok) {
            head.appendChild(el('span', 'dp-err', (data && data.note) || t('history.loading')));
            box.appendChild(head);
            return;
        }
        head.appendChild(el('span', 'gh-badge', t('history.repo') + ' ' + (data.branch ? data.branch : (data.detached ? t('history.detached') : '—'))));
        if (data.root) head.appendChild(el('span', 'dp-dim', ' ' + data.root));
        if (data.head) head.appendChild(el('span', 'dp-sub dp-mono', ' HEAD ' + data.head.abbrev + ' · ' + fmtTime(data.head.date) + ' · ' + data.head.subject));
        box.appendChild(head);
    }

    function pathStateLine(box) {
        if (!data || !Array.isArray(data.pathStates) || !data.pathStates.length) return;
        const line = el('div', 'gh-paths');
        for (const ps of data.pathStates) {
            const b = el('span', 'gh-badge ' + (ps.tracked ? 'ok' : (ps.ignored ? 'dim' : 'warn')),
                ps.tracked ? tf('history.tracked', { n: ps.trackedCount })
                    : (ps.ignored ? tf('history.ignored', { rule: ps.ignoreRule || '' }) : tf('history.untracked')));
            b.title = ps.rel;
            line.appendChild(b);
        }
        if (Array.isArray(data.notes) && data.notes.length) {
            line.appendChild(el('span', 'dp-warn', ' ' + data.notes.join('；')));
        }
        box.appendChild(line);
    }

    function workingBlock(box) {
        if (!work) return;
        const wrap = el('div', 'gh-work ' + (work.dirty ? 'dirty' : 'clean'));
        const title = el('div', 'gh-sec');
        title.appendChild(el('span', work.dirty ? 'dp-warn' : 'dp-ok',
            work.dirty ? tf('history.dirty', { changed: work.changedCount, untracked: work.untrackedCount }) : t('history.clean')));
        wrap.appendChild(title);
        if (work.worktreeSummary) wrap.appendChild(el('div', 'dp-sub dp-mono', 'git diff: ' + work.worktreeSummary));
        if (work.stagedSummary) wrap.appendChild(el('div', 'dp-sub dp-mono', 'git diff --cached: ' + work.stagedSummary));
        for (const c of (work.changes || []).slice(0, 40)) {
            wrap.appendChild(el('div', 'dp-mono gh-change', `${c.code} ${c.label}  ${c.oldPath ? c.oldPath + ' → ' : ''}${c.path}`));
        }
        if ((work.changes || []).length > 40) wrap.appendChild(el('div', 'dp-dim', '…'));
        for (const u of (work.untracked || []).slice(0, 20)) wrap.appendChild(el('div', 'dp-mono dp-dim', '?? ' + u));
        if (work.dirty) wrap.appendChild(el('div', 'dp-warn gh-hint', t('history.dirtyHint')));
        box.appendChild(wrap);
    }

    function commitList(box) {
        const list = el('div', 'gh-list');
        const head = el('div', 'gh-sec', t('history.commits'));
        list.appendChild(head);
        if (!data || !data.ok) {
            list.appendChild(el('div', 'dp-dim', (data && data.note) || t('history.loading')));
            box.appendChild(list);
            return;
        }
        const commits = data.commits || [];
        if (!commits.length) {
            list.appendChild(el('div', 'dp-warn', data.note || t('history.none')));
            box.appendChild(list);
            return;
        }
        for (const c of commits) {
            const row = el('div', 'gh-commit' + (openSha === c.sha ? ' open' : ''));
            const line = el('div', 'gh-commit-line');
            const btn = el('button', 'gh-sha', c.abbrev);
            btn.addEventListener('click', () => {
                if (openSha === c.sha) { openSha = null; detail = null; render(); }
                else loadDiff(c.sha);
            });
            line.appendChild(btn);
            line.appendChild(el('span', 'dp-dim dp-mono', fmtTime(c.date)));
            line.appendChild(el('span', 'gh-author', c.author));
            line.appendChild(el('span', 'gh-subject', c.subject));
            line.appendChild(el('span', 'dp-dim', ' ' + tf('history.files', { n: c.fileCount })));
            const rb = el('button', 'btn small ghost', t('history.rollbackTo'));
            rb.disabled = busy || rolling || !data.root;
            rb.title = t('history.rollbackToHint');
            rb.addEventListener('click', () => startRollback(c));
            line.appendChild(rb);
            row.appendChild(line);
            if (c.merge) row.appendChild(el('div', 'dp-dim gh-merge', t('history.merge')));
            if (openSha === c.sha) row.appendChild(diffBlock(c));
            if (pending && pending.sha === c.sha) row.appendChild(planBlock(c));
            list.appendChild(row);
        }
        if (data.truncated) {
            const more = el('button', 'btn ghost small', tf('history.more', { n: limit }));
            more.disabled = busy;
            more.addEventListener('click', () => { limit += 40; load(); });
            list.appendChild(more);
        }
        box.appendChild(list);
    }

    function diffBlock(c) {
        const box = el('div', 'gh-diff-box');
        if (!detail || detail.sha !== c.sha) {
            box.appendChild(el('div', 'dp-dim', t('history.loadingDiff')));
            return box;
        }
        if (!detail.ok) {
            box.appendChild(el('div', 'dp-err', detail.note || t('history.failed')));
            return box;
        }
        const meta = el('div', 'dp-sub dp-mono');
        meta.textContent = `${detail.sha}  +${detail.additions} −${detail.deletions}` +
            (detail.summary ? '  ' + detail.summary : '') +
            (detail.binaryHint ? '  ' + t('history.binary') : '');
        box.appendChild(meta);
        for (const f of (detail.files || []).slice(0, 200)) {
            box.appendChild(el('div', 'dp-mono gh-change', `${f.status} ${f.oldPath ? f.oldPath + ' → ' : ''}${f.path}`));
        }
        if ((detail.files || []).length > 200) box.appendChild(el('div', 'dp-dim', '…'));
        if (detail.truncated) box.appendChild(el('div', 'dp-warn', t('history.diffTruncated')));
        const text = String(detail.diff || '');
        const pre = el('pre', 'gh-diff', text.length > MAX_DIFF_CHARS ? text.slice(0, MAX_DIFF_CHARS) + '\n…（前端二次截断）' : (text || t('history.noDiff')));
        box.appendChild(pre);
        return box;
    }

    function render() {
        const box = document.getElementById('historyBody');
        if (!box) return;
        box.innerHTML = '';
        // 头栏必须写出是哪个包：第 3 步起这里会真的改工作区，看不出在动哪个包的确认框是危险的。
        // 标题那个 span 归 data-i18n 管（切语言会重写），所以动态包名放头栏里原本空着的 #historyRepo。
        const label = document.getElementById('historyRepo');
        if (label) label.textContent = (target ? target.dirName + ' · ' : '') + t('history.title');
        if (!target) {
            box.appendChild(el('div', 'dp-dim', t('history.noTarget')));
            return;
        }
        const jarLine = el('div', 'gh-target');
        jarLine.appendChild(el('span', 'dp-sub dp-mono',
            `${t('history.targetSource')} ${target.packDir || '—'}` + (target.jarPath ? `  →  ${t('history.targetJar')} ${target.jarPath}` : '')));
        box.appendChild(jarLine);
        undoBar(box);
        repoLine(box);
        pathStateLine(box);
        workingBlock(box);
        commitList(box);
    }

    // ===== 开关 =====

    function isOpen() {
        const o = document.getElementById('historyOverlay');
        return !!o && !o.classList.contains('hidden');
    }

    function open(pack) {
        if (pack && pack.dirName) {
            target = {
                dirName: pack.dirName,
                jarName: pack.jarName,
                packDir: pack.source && pack.source.dir ? pack.source.dir : null,
                jarPath: pack.built && pack.built.exists ? pack.built.path : (pack.archived && pack.archived.exists ? pack.archived.path : null),
            };
            data = null; work = null; openSha = null; detail = null; limit = 40;
            pending = null; undoAnchor = null;   // 换了包：上一个检查点锚点不再适用于这个路径
        }
        const o = document.getElementById('historyOverlay');
        if (o) o.classList.remove('hidden');
        if (!target) { render(); return; }
        load();
    }

    function close() {
        const o = document.getElementById('historyOverlay');
        if (o) o.classList.add('hidden');
    }

    // 顶栏「🕘 提交回溯」入口：手里只有 AppState.pack（包目录），没有部署面板那份四态测量记录。
    // jarPath 留空即可 —— render() 与后端都按「没有 jar」处理（见 :425）。
    // 复用 open() 是为了不复制它的重置逻辑（换包必须清 pending/undoAnchor，否则锚点会套到别的路径上）。
    function openDir(dirName, packDir) {
        open({ dirName, source: { dir: packDir }, built: { exists: false } });
    }

    async function refresh() {
        if (!target) return;
        await load();
    }

    function init() {
        const btnClose = document.getElementById('btnHistoryClose');
        if (btnClose) btnClose.addEventListener('click', close);
        const btnRefresh = document.getElementById('btnHistoryRefresh');
        if (btnRefresh) btnRefresh.addEventListener('click', refresh);
        const o = document.getElementById('historyOverlay');
        if (o) o.addEventListener('mousedown', (e) => { if (e.target === o) close(); });
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && isOpen()) close();
        });
    }

    return {
        init, open, openDir, close, refresh, rerender: render, isOpen,
        _loadDiff: loadDiff,
        // 第 3 步的入口与状态：测试要能在不点按钮的情况下驱动它
        _startRollback: startRollback, _confirmRollback: confirmRollback, _undoRollback: undoRollback,
        _state: () => ({ target, data, work, limit, openSha, pending, rolling, undoAnchor }),
    };
})();
