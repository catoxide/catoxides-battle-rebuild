// contractView.js - 动画契约（编辑器契约）视图
// 契约由包声明（MANIFEST: Module-Editor-Schema → 包源目录下的 JSON），编辑器只读 + 盘点 + 校验：
//   中栏 window.ContractList.load(packDir)     → 该包契约的模型/动画集列表（✓/⚠/✗）
//   右栏 window.ContractEditor.open({set,report}) → 集详情（模型/贴图/必备动画表/接线/错误警告）
// 无契约的包显示提示（自由编辑模式）；契约文件在包源目录里直接编辑，编辑器 v1 不提供写。
// 依赖 window.api / window.AppState / window.applyEditorVisibility（app.js 定义）

window.ContractList = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : k);
    let lastPackDir = null;
    let activeSetId = null;

    async function load(packDir) {
        lastPackDir = packDir;
        const listEl = document.getElementById('entityList');
        listEl.innerHTML = '';
        if (!packDir) return;
        let res;
        try { res = await window.api.readContract(packDir); }
        catch (e) { res = { ok: false, error: String((e && e.message) || e) }; }
        if (!res || !res.ok) {
            const msg = (res && res.error === 'no-contract')
                ? t('contract.none')
                : t('contract.error') + ((res && (res.error || (res.errors || []).join('；'))) || '');
            const item = document.createElement('div');
            item.className = 'list-item muted';
            item.textContent = msg;
            listEl.appendChild(item);
            return;
        }
        const report = res.data;
        for (const set of report.sets) {
            const icon = set.errors.length ? '✗' : (set.warnings.length ? '⚠' : '✓');
            const item = document.createElement('div');
            // 0.2.1：当前打开的集高亮（刷新/改语言后能看出当前查看的是哪一个）
            item.className = 'list-item' + (set.id === activeSetId ? ' active' : '');
            item.dataset.setId = set.id;
            item.innerHTML = `
                <div class="li-main">
                    <div class="li-thumb" style="display:flex;align-items:center;justify-content:center;font-size:22px">${icon}</div>
                    <div class="li-text">
                        <div class="li-title"></div>
                        <div class="li-sub"></div>
                    </div>
                </div>`;
            item.querySelector('.li-title').textContent = set.label;
            item.querySelector('.li-sub').textContent =
                tf('contract.summary', { n: set.animations.length, e: set.errors.length, w: set.warnings.length });
            item.addEventListener('click', () => {
                window.AppState.activeFrame = 'contract';
                setActive(set.id);
                window.ContractEditor.open({ set, report });
            });
            listEl.appendChild(item);
        }
    }

    function setActive(setId) {
        activeSetId = setId || null;
        document.querySelectorAll('#entityList .list-item').forEach((el) => {
            el.classList.toggle('active', !!activeSetId && el.dataset.setId === activeSetId);
        });
    }

    return { load, setActive };
})();

window.ContractEditor = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : k);
    let isOpen = false;
    let packDir = null;
    let lastCtx = null;          // 0.2.1：缓存最近一次渲染的 context（切语言/刷新时重渲染）
    let currentSetId = null;
    let isBuilding = false;

    function esc(s) {
        return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
    }

    function open(ctx) {
        const set = ctx.set;
        const report = ctx.report;
        lastCtx = ctx;
        currentSetId = set.id;
        packDir = (window.AppState.pack && window.AppState.pack.dir) || null;
        if (window.ContractList && window.ContractList.setActive) window.ContractList.setActive(set.id);
        isOpen = true;
        window.AppState.activeFrame = 'contract';

        const frame = document.getElementById('contractEditorContent');
        frame.classList.remove('hidden');

        document.getElementById('contractTitle').textContent =
            t('contract.detailTitle') + ' — ' + set.label;
        const badge = document.getElementById('contractBadge');
        badge.textContent = set.errors.length
            ? set.errors.length + ' ✗'
            : (set.warnings.length ? set.warnings.length + ' ⚠' : t('contract.ok'));

        const dir = (report.contract.sets.find((s) => s.id === set.id) || {}).animations;
        let html = `
        <div class="card">
          <h2>${t('contract.model')}</h2>
          <div class="grid2">
            <div><label>${t('contract.model')}</label>
              <div class="mono">${esc(set.model.path)}
                <span class="li-tag ${set.model.exists ? 'ok' : 'err'}">${set.model.exists ? t('contract.exists') : t('contract.missing')}</span></div></div>
            <div><label>${t('contract.texture')}</label>
              <div class="mono">${set.model.texture ? esc(set.model.texture) : '—'}
                ${set.model.texture ? `<span class="li-tag ${set.model.textureExists ? 'ok' : 'err'}">${set.model.textureExists ? t('contract.exists') : t('contract.missing')}</span>` : ''}</div></div>
          </div>
        </div>
        <div class="card">
          <h2>${t('contract.anims')}</h2>
          <p class="note mono">${esc(dir ? dir.dir : '')}</p>
          <table class="anim-table">
            <thead><tr>
              <th>${t('contract.colAnim')}</th>
              <th>${t('contract.colLoop')}</th>
              <th>${t('contract.colDur')}</th>
              <th>${t('contract.colStatus')}</th>
            </tr></thead>
            <tbody>`;
        for (const a of set.animations) {
            let status;
            if (!a.exists) status = `<span class="li-tag err">${t('contract.missing')}</span>`;
            else if (a.parseError) status = '<span class="li-tag err">JSON ✗</span>';
            else if (a.missingBundled.length) status = `<span class="li-tag err">${a.missingBundled.length} ✗</span>`;
            else status = '<span class="li-tag ok">✓</span>';
            const loop = a.loopRequired
                ? (a.exists && a.loopActual && a.loopActual !== a.loopRequired
                    ? esc(a.loopRequired + ' → ' + a.loopActual)
                    : esc(a.loopRequired))
                : '—';
            html += `<tr>
                <td class="mono">${esc(a.name)}${a.bundle.length ? ` <span class="note">(${tf('contract.bundle', { n: a.bundle.length })})</span>` : ''}</td>
                <td>${loop}</td>
                <td>${a.duration != null ? esc(String(a.duration)) : '—'}</td>
                <td>${status}</td>
            </tr>`;
        }
        html += '</tbody></table></div>';

        // 2026-10-14 P4：TimeTape 状态机视图（轨/纸带/转移；只读盘点 + 可视化编辑入口 W2）
        const sm = report.stateMachine;
        if (sm) {
            html += `<div class="card">
              <h2>${t('contract.stateMachine')} <span class="note mono">${esc(sm.path)}</span>
              <button class="btn ghost small" id="btnEditTape">${t('tape.editBtn')}</button></h2>`;
            for (const track of Object.values(sm.tracks)) {
                html += `<h3>track: ${esc(track.id)} <span class="note">${t('contract.initial')}: <b>${esc(track.initial || '—')}</b></span></h3>
                <table class="anim-table">
                <thead><tr>
                  <th>${t('contract.colState')}</th>
                  <th>${t('contract.colAnimShort')}</th>
                  <th>${t('contract.colTape')}</th>
                  <th>${t('contract.colExit')}</th>
                </tr></thead><tbody>`;
                for (const st of Object.values(track.states)) {
                    const anims = st.anims.length ? Array.from(new Set(st.anims)).join(', ') : '—';
                    const tape = st.segs.length
                        ? st.segs.map((g) => `${g.until}s${g.next ? ' → ' + esc(g.next) : ' (hold)'}`).join('; ')
                        : (st.anims.length ? '<span class="note">loop</span>' : '—');
                    const exits = st.exits.length
                        ? st.exits.map((e) => `${esc(e.when)} ⇒ <b>${esc(e.to)}</b>${e.priority ? ` (p${e.priority})` : ''}${e.after ? ` [≥${e.after}s]` : ''}`).join('<br>')
                        : '—';
                    html += `<tr>
                        <td class="mono"><b>${esc(st.id)}</b>${st.onEnter.length ? ` <span class="note">⚡${esc(st.onEnter.join(','))}</span>` : ''}</td>
                        <td class="mono">${esc(anims)}</td>
                        <td class="mono">${tape}</td>
                        <td class="mono">${exits}</td>
                    </tr>`;
                }
                html += '</tbody></table>';
            }
            html += '</div>';
        }

        const wiring = report.contract.wiring;
        if (wiring) {
            const smDesc = wiring.state_machine
                ? (typeof wiring.state_machine === 'object'
                    ? `${esc(String(wiring.state_machine.type || ''))} → ${esc(String(wiring.state_machine.path || ''))}`
                    : esc(String(wiring.state_machine)))
                : 'null';
            html += `
            <div class="card">
              <h2>${t('contract.wiring')}</h2>
              <div class="grid2">
                <div><label>referenced_by</label><div class="mono">${esc(wiring.referenced_by || '—')}</div></div>
                <div><label>state_machine</label><div class="mono">${esc(smDesc)}</div></div>
              </div>
            </div>`;
        }

        if (set.errors.length || set.warnings.length) {
            html += '<div class="card"><h2>' + t('contract.errors') + ' / ' + t('contract.warnings') + '</h2>';
            for (const e of set.errors) html += `<div class="note" style="color:var(--err,#e05252)">✗ ${esc(e)}</div>`;
            for (const w of set.warnings) html += `<div class="note" style="color:#d9a13b">⚠ ${esc(w)}</div>`;
            html += '</div>';
        }
        document.getElementById('contractBody').innerHTML = html;

        // 状态机可视化编辑器入口（W2）
        // 用 onclick 赋值而非 addEventListener：open()/refresh() 会被反复调用，
        // 旧写法每次叠加一个监听器 → 点一次按钮打开 N 次状态机编辑器。
        if (sm) {
            const editBtn = document.getElementById('btnEditTape');
            if (editBtn) {
                editBtn.onclick = () => {
                    const animNames = report.sets.flatMap((s) => s.animations.map((a) => a.name));
                    window.AppState.activeFrame = 'tape';
                    window.TapeEditor.open({
                        packName: packDir,
                        relPath: sm.path,
                        animNames,
                        title: set.label,
                    });
                };
            }
        } else {
            const editBtn = document.getElementById('btnEditTape');
            if (editBtn) editBtn.onclick = null;
        }

        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    function close() {
        isOpen = false;
        packDir = null;
        lastCtx = null;
        const frame = document.getElementById('contractEditorContent');
        if (frame) frame.classList.add('hidden');
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    /** 切语言后重渲染当前集（此前只重画列表，正文/标题停在旧语言） */
    function rerender() {
        if (isOpen && lastCtx) open(lastCtx);
    }

    async function refresh() {
        if (!packDir) return;
        let res;
        try { res = await window.api.readContract(packDir); }
        catch (e) { res = { ok: false, error: String((e && e.message) || e) }; }
        if (!res || !res.ok) {
            if (window.showError) window.showError(t('contract.error') + ((res && (res.error || (res.errors || []).join('；'))) || ''));
            return;
        }
        const report = res.data;
        if (window.ContractList && packDir) await window.ContractList.load(packDir);
        // 0.2.1：刷新后恢复用户当前查看的集（此前固定跳回第一个集）
        const set = (currentSetId && report.sets.find((s) => s.id === currentSetId)) || report.sets[0];
        if (!set) return;
        open({ set, report });
    }

    /** 打包：先行检查暂存草稿 → 调 pack:build → 完整呈现产物/输出/校验结果（此前丢弃全部结果） */
    async function build() {
        if (isBuilding) return;
        const dir = (window.AppState && window.AppState.pack && window.AppState.pack.dir) || packDir;
        if (!dir) return;
        // 目标包与打开时不一致（切过包）→ 以当前选中包为准，并提示
        if (packDir && dir !== packDir) {
            packDir = dir;
            if (window.showStatus) window.showStatus(t('contract.packChanged'), true);
        }
        const out = document.getElementById('contractOutput');
        const status = document.getElementById('contractStatus');
        const btn = document.getElementById('btnContractBuild');
        const t0 = Date.now();
        isBuilding = true;
        if (btn) { btn.disabled = true; btn.dataset.oldText = btn.textContent; btn.textContent = t('contract.building'); }
        if (out) { out.style.display = 'block'; out.textContent = t('contract.building') + '…'; }
        if (status) { status.className = 'status'; status.textContent = ''; }
        try {
            // 暂存草稿提醒（与实体/结构编辑器一致）
            let stagedCount = 0;
            try {
                const st = await window.api.stagingList(dir);
                if (st && st.ok && st.data && st.data.items) stagedCount = st.data.items.length;
                if (stagedCount && !confirm(tf('dyn.buildStagedWarn', { n: stagedCount }))) {
                    if (out) out.textContent = t('contract.buildCanceled');
                    return;
                }
            } catch { /* 暂存检查失败不阻断 */ }
            let res;
            try { res = await window.api.buildPack({ packDir: dir }); }
            catch (e) { res = { ok: false, error: String((e && e.message) || e) }; }
            if (!res) res = { ok: false, error: '主进程未响应' };
            const d = res.data || {};
            const lines = [];
            if (d.preflight) {
                const f = d.preflight.findings || [];
                const errs = f.filter((x) => x.level === 'error');
                const warns = f.filter((x) => x.level === 'warn');
                lines.push(tf('dyn.preflightSummary', { e: errs.length, w: warns.length }));
                for (const x of errs) lines.push(`[ERROR] ${x.code} ${x.msg}${x.src ? '  〔' + x.src + '〕' : ''}`);
                for (const x of warns) lines.push(`[WARN ] ${x.code} ${x.msg}${x.src ? '  〔' + x.src + '〕' : ''}`);
            }
            if (d.mode) lines.push(`[mode: ${d.mode}]`);
            if (d.autoCreated && d.autoCreated.length) lines.push(tf('dyn.autoScaffold', { files: d.autoCreated.join(', ') }));
            if (d.gradleTask) lines.push(`[task: ${d.gradleTask}]`);
            if (d.expectedJar) lines.push(`[jar: ${d.expectedJar}]`);
            if (d.jarPath) lines.push(`[path: ${d.jarPath}]`);
            if (d.stdout) lines.push(d.stdout);
            if (d.stderr) lines.push('[stderr]\n' + d.stderr);
            for (const v of d.verify || []) {
                lines.push(`[${String(v.level || '').toUpperCase()}] ${v.code || ''} ${v.msg || ''}${v.src ? '  〔' + v.src + '〕' : ''}`);
            }
            if (!res.ok) lines.push('[error] ' + (res.error || ''));
            lines.push(res.ok ? '=== ' + t('dyn.buildDoneShort') + ' ===' : '=== ' + t('dyn.buildFail') + ' ===');
            if (out) out.textContent = lines.join('\n');
            if (status) {
                status.className = 'status ' + (res.ok ? 'ok' : 'err');
                status.textContent = res.ok
                    ? tf('dyn.buildDone', { name: d.expectedJar || '' }) + `（${((Date.now() - t0) / 1000).toFixed(1)}s）`
                    : t('dyn.buildFail') + (res.error ? '：' + String(res.error).split('\n')[0] : '');
            }
        } finally {
            isBuilding = false;
            if (btn) { btn.disabled = false; if (btn.dataset.oldText) btn.textContent = btn.dataset.oldText; }
        }
    }

    return { open, close, refresh, rerender, build, hasActiveTab: () => isOpen, getPackDir: () => packDir };
})();
