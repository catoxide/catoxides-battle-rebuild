// structureList.js - 中栏：建筑结构（建筑拼图）列表
// 依赖 window.api、window.showError / window.AppState（app.js 定义）
// 暴露 window.StructureList.init({ onSelect, onNew }) / loadStructures(packDir)

window.StructureList = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));
    let handlers = { onSelect: null, onNew: null };
    let currentPackDir = null;

    async function loadStructures(packDir) {
        currentPackDir = packDir;
        const listEl = document.getElementById('entityList'); // 中栏列表容器（两种内容类型共用）
        listEl.innerHTML = '';
        if (!packDir) return;
        const res = await window.api.listStructures(packDir);
        if (!res.ok) { window.showError(t('dyn.loadStructuresFail') + res.error); return; }
        const structures = res.data || [];
        window.AppState.structures = structures;
        if (!structures.length) {
            const empty = document.createElement('div');
            empty.className = 'list-empty';
            empty.textContent = t('dyn.noStructures');
            listEl.appendChild(empty);
            return;
        }
        for (const s of structures) {
            const item = document.createElement('div');
            item.className = 'list-item';
            item.innerHTML = `
                <div class="li-main">
                    <div class="li-text">
                        <div class="li-title"></div>
                        <div class="li-sub"></div>
                    </div>
                </div>
                <div class="li-actions">
                    <button class="btn ghost small act-edit">${t('dyn.edit')}</button>
                    <button class="btn ghost small act-del" style="color:var(--err)">${t('dyn.delete')}</button>
                </div>`;
            item.querySelector('.li-title').textContent = s.structureId;
            const ns = s.namespace || 'catoxidesbattlerebuild';
            // 插值必须走 tf（t 不替换 {n} → 此前列表显示 "{n} 片段 / {n} 池"）
            item.querySelector('.li-sub').textContent =
                `${ns}:${s.structureId} · ${tf('dyn.piecesCount', { n: s.pieces < 0 ? '?' : s.pieces })} / ${tf('dyn.poolsCount', { n: s.pools })}`;
            if (s.staged) {
                const tag = document.createElement('span');
                tag.className = 'li-tag';
                tag.textContent = t('dyn.draft');
                item.querySelector('.li-title').appendChild(tag);
            }
            if (s.error) {
                const err = document.createElement('div');
                err.className = 'li-sub';
                err.style.color = 'var(--err)';
                err.textContent = s.error;
                item.querySelector('.li-text').appendChild(err);
            }
            item.querySelector('.act-edit').addEventListener('click', (e) => {
                e.stopPropagation();
                if (handlers.onSelect) handlers.onSelect(currentPackDir, s);
            });
            item.querySelector('.act-del').addEventListener('click', async (e) => {
                e.stopPropagation();
                if (!confirm(tf('dyn.deleteStructureConfirm', { id: s.structureId }))) return;
                const res = await window.api.deleteStructure(currentPackDir, s.file, s.structureId);
                if (!res.ok) { window.showError(t('common.saveFailed') + res.error); return; }
                window.showStatus(tf('dyn.structureDeleted', { id: s.structureId }), true);
                await loadStructures(currentPackDir);
            });
            item.addEventListener('click', () => {
                if (handlers.onSelect) handlers.onSelect(currentPackDir, s);
            });
            listEl.appendChild(item);
        }
    }

    function init(cb) {
        handlers = cb || {};
    }

    return { init, loadStructures };
})();
