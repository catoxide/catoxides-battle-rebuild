// gitHistoryView.js - 顶栏「🕘 提交回溯」（0.2.2 第 2 步，只读）
// 依赖：window.api（preload 的 gitInfo/gitHistory/gitCommit/gitWorkingTree）、window.I18n
// 暴露：window.GitHistoryView.init() / open(pack) / close() / refresh() / isOpen() / rerender()
//
// 这一层只负责「摆出 git 的实测结果」：提交列表、每条提交在该包路径上的改动、
// 以及工作区未提交改动。差异文本来自后端 git diff 原文，前端不重排、不补全。
// 回溯动作（restore）不在这个视图里 —— 第 3 步实现，且必须先归档当前状态。
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
    const MAX_DIFF_CHARS = 120000;   // 渲染封顶（后端已按字节截断，这里再防一次超长卡页面）

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
        if (busy) return;
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
            row.appendChild(line);
            if (c.merge) row.appendChild(el('div', 'dp-dim gh-merge', t('history.merge')));
            if (openSha === c.sha) row.appendChild(diffBlock(c));
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
        const label = document.getElementById('historyTitle');
        if (label) label.textContent = (target ? target.dirName : '') + ' · ' + t('history.title');
        if (!target) {
            box.appendChild(el('div', 'dp-dim', t('history.noTarget')));
            return;
        }
        const jarLine = el('div', 'gh-target');
        jarLine.appendChild(el('span', 'dp-sub dp-mono', `源码目录 ${target.packDir}` + (target.jarPath ? `  →  产物 ${target.jarPath}` : '')));
        box.appendChild(jarLine);
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

    return { init, open, close, refresh, rerender: render, isOpen, _loadDiff: loadDiff, _state: () => ({ target, data, work, limit, openSha }) };
})();
