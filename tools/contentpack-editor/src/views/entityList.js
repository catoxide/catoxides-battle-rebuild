// entityList.js - 中栏：实体列表
// 依赖 window.api、window.showError / window.AppState（app.js 定义）
// 暴露 window.EntityList.init({ onSelect, onNew }) / loadEntities(packDir)

window.EntityList = (function () {
    let handlers = { onSelect: null, onNew: null };
    let currentPackDir = null;

    async function loadEntities(packDir) {
        currentPackDir = packDir;
        const listEl = document.getElementById('entityList');
        listEl.innerHTML = '';
        if (!packDir) return;
        const res = await window.api.listEntities(packDir);
        if (!res.ok) { window.showError('加载实体列表失败: ' + res.error); return; }
        const entities = res.data || [];
        window.AppState.entities = entities;
        for (const ent of entities) {
            const item = document.createElement('div');
            item.className = 'list-item';
            item.innerHTML = `
                <div class="li-title"></div>
                <div class="li-sub"></div>
                <div class="li-actions">
                    <button class="btn ghost small act-edit">编辑</button>
                    <button class="btn ghost small act-del" style="color:var(--err)">删除</button>
                </div>`;
            item.querySelector('.li-title').textContent = ent.displayName || ent.id;
            item.querySelector('.li-sub').textContent = ent.namespace ? (ent.namespace + ':' + ent.id) : ('catoxidesbattlerebuild:' + ent.id);
            item.querySelector('.act-edit').addEventListener('click', (e) => {
                e.stopPropagation();
                if (handlers.onSelect) handlers.onSelect(currentPackDir, ent);
            });
            item.querySelector('.act-del').addEventListener('click', async (e) => {
                e.stopPropagation();
                if (!confirm(`删除实体 "${ent.id}"？（同时删除其部位配置）`)) return;
                const res = await window.api.deleteEntity(currentPackDir, ent.id);
                if (!res.ok) { window.showError('删除失败: ' + res.error); return; }
                // 尝试删除对应部位配置（namespace 未知时用包名尝试）
                const ns = ent.namespace || currentPackDir.split(/[\\/]/).pop();
                await window.api.deleteHealth(currentPackDir, ns, ent.id);
                window.showStatus(`实体 "${ent.id}" 已删除`, true);
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
