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

    async function load(packDir) {
        lastPackDir = packDir;
        const listEl = document.getElementById('entityList');
        listEl.innerHTML = '';
        if (!packDir) return;
        const res = await window.api.readContract(packDir);
        if (!res.ok) {
            const msg = res.error === 'no-contract'
                ? t('contract.none')
                : t('contract.error') + (res.error || (res.errors || []).join('；'));
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
            item.className = 'list-item';
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
                window.ContractEditor.open({ set, report });
            });
            listEl.appendChild(item);
        }
    }

    return { load };
})();

window.ContractEditor = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : k);
    let isOpen = false;
    let packDir = null;

    function esc(s) {
        return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
    }

    function open(ctx) {
        const set = ctx.set;
        const report = ctx.report;
        packDir = (window.AppState.pack && window.AppState.pack.dir) || null;
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
                        <td class="mono"><b>${esc(st.id)}</b>${st.onEnter.length ? ` <span class="note">⚡${st.onEnter.join(',')}</span>` : ''}</td>
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
                    ? `${wiring.state_machine.type} → ${wiring.state_machine.path || ''}`
                    : wiring.state_machine)
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
        if (sm) {
            const editBtn = document.getElementById('btnEditTape');
            if (editBtn) {
                editBtn.addEventListener('click', () => {
                    const animNames = report.sets.flatMap((s) => s.animations.map((a) => a.name));
                    window.AppState.activeFrame = 'tape';
                    window.TapeEditor.open({
                        packName: packDir,
                        relPath: sm.path,
                        animNames,
                        title: set.label,
                    });
                });
            }
        }

        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    function close() {
        isOpen = false;
        packDir = null;
        const frame = document.getElementById('contractEditorContent');
        if (frame) frame.classList.add('hidden');
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    }

    async function refresh() {
        if (!packDir) return;
        const res = await window.api.readContract(packDir);
        if (!res.ok) {
            if (window.showError) window.showError(t('contract.error') + (res.error || (res.errors || []).join('；')));
            return;
        }
        const report = res.data;
        // 重新打开第一个集（简单策略：当前视图刷新 = 重渲染首集）
        open({ set: report.sets[0], report });
        if (window.ContractList && packDir) window.ContractList.load(packDir);
    }

    async function build() {
        if (!packDir) return;
        const out = document.getElementById('contractOutput');
        const status = document.getElementById('contractStatus');
        out.style.display = 'block';
        out.textContent = '';
        status.className = 'status';
        status.textContent = '';
        const res = await window.api.buildPack({ packDir });
        out.textContent = res.ok
            ? (res.data && (res.data.output || res.data.log) || 'ok')
            : 'ERROR: ' + (res.error || '');
        status.className = 'status ' + (res.ok ? 'ok' : 'err');
        status.textContent = res.ok ? 'BUILD OK' : 'BUILD FAILED';
    }

    return { open, close, refresh, build, hasActiveTab: () => isOpen, getPackDir: () => packDir };
})();
