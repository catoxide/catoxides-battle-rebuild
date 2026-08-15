// entityList.js - 中栏：实体列表
// 依赖 window.api、window.showError / window.AppState（app.js 定义）
// 暴露 window.EntityList.init({ onSelect, onNew }) / loadEntities(packDir)

window.EntityList = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));
    let handlers = { onSelect: null, onNew: null };
    let currentPackDir = null;

    async function loadEntities(packDir) {
        currentPackDir = packDir;
        const listEl = document.getElementById('entityList');
        listEl.innerHTML = '';
        if (!packDir) return;
        const res = await window.api.listEntities(packDir);
        if (!res.ok) { window.showError(t('dyn.loadEntitiesFail') + res.error); return; }
        const entities = res.data || [];
        window.AppState.entities = entities;
        for (const ent of entities) {
            const item = document.createElement('div');
            item.className = 'list-item';
            item.innerHTML = `
                <div class="li-title"></div>
                <div class="li-sub"></div>
                <div class="li-actions">
                    <button class="btn ghost small act-edit">${t('dyn.edit')}</button>
                    <button class="btn ghost small act-del" style="color:var(--err)">${t('dyn.delete')}</button>
                </div>`;
            item.querySelector('.li-title').textContent = ent.displayName || ent.id;
            item.querySelector('.li-sub').textContent = ent.namespace ? (ent.namespace + ':' + ent.id) : ('catoxidesbattlerebuild:' + ent.id);
            if (ent.staged) {
                const tag = document.createElement('span');
                tag.className = 'li-tag';
                tag.textContent = t('dyn.draft');
                item.querySelector('.li-title').appendChild(tag);
            }
            item.querySelector('.act-edit').addEventListener('click', (e) => {
                e.stopPropagation();
                if (handlers.onSelect) handlers.onSelect(currentPackDir, ent);
            });
            item.querySelector('.act-del').addEventListener('click', async (e) => {
                e.stopPropagation();
                if (!confirm(tf('dyn.deleteConfirm', { id: ent.id }))) return;
                const res = await window.api.deleteEntity(currentPackDir, ent.id);
                if (!res.ok) { window.showError(t('common.saveFailed') + res.error); return; }
                // 尝试删除对应部位配置（namespace 未知时用包名尝试）
                const ns = ent.namespace || currentPackDir.split(/[\\/]/).pop();
                await window.api.deleteHealth(currentPackDir, ns, ent.id);
                window.showStatus(tf('dyn.deleted', { id: ent.id }), true);
                await loadEntities(currentPackDir);
            });
            item.addEventListener('click', () => {
                if (handlers.onSelect) handlers.onSelect(currentPackDir, ent);
            });
            listEl.appendChild(item);
        }
    }

    function init(cb) {
        handlers = cb || {};
        document.getElementById('btnNewEntity').addEventListener('click', () => {
            if (handlers.onNew) handlers.onNew(currentPackDir);
        });
    }

    return { init, loadEntities };
})();
