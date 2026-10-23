// itemBlockList.js - 中栏：物品/方块列表（与实体列表共用 #entityList 容器）
// 依赖 window.api、window.showError / window.AppState（app.js 定义）
// 暴露 window.ItemBlockList.init({ onSelect, onNew }) / loadItemsAndBlocks(packDir, type)

window.ItemBlockList = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));
    let handlers = { onSelect: null, onNew: null };
    let currentPackDir = null;
    let currentType = 'item';

    const KIND_LABEL = { item: '物品', block: '方块' };
    const KIND_ICON = { item: '🧩', block: '🧱' };

    async function loadItemsAndBlocks(packDir, type) {
        currentPackDir = packDir;
        currentType = type === 'block' ? 'block' : 'item';
        const listEl = document.getElementById('entityList');
        listEl.innerHTML = '';
        if (!packDir) return;
        const res = await window.api.listItemBlocks(packDir);
        if (!res.ok) { window.showError('加载' + KIND_LABEL[currentType] + '列表失败: ' + res.error); return; }
        const all = res.data || [];
        const items = all.filter((x) => x.kind === currentType);
        window.AppState.itemBlocks = items;
        if (!items.length) {
            const empty = document.createElement('div');
            empty.className = 'li-sub';
            empty.style.padding = '12px';
            empty.textContent = '（还没有' + KIND_LABEL[currentType] + ' —— 点上方「+ 新建」）';
            listEl.appendChild(empty);
            return;
        }
        for (const it of items) {
            const el = document.createElement('div');
            el.className = 'list-item';
            el.innerHTML = `
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
            const title = el.querySelector('.li-title');
            title.textContent = (KIND_ICON[it.kind] || '') + ' ' + (it.displayName || it.id);
            el.querySelector('.li-sub').textContent = (it.namespace ? it.namespace + ':' : 'catoxidesbattlerebuild:') + it.id;
            if (it.staged) {
                const tag = document.createElement('span');
                tag.className = 'li-tag';
                tag.textContent = t('dyn.draft');
                title.appendChild(tag);
            }
            el.querySelector('.act-edit').addEventListener('click', (e) => {
                e.stopPropagation();
                if (handlers.onSelect) handlers.onSelect(currentPackDir, it);
            });
            el.querySelector('.act-del').addEventListener('click', async (e) => {
                e.stopPropagation();
                if (!confirm('确认删除' + KIND_LABEL[it.kind] + ' ' + it.id + '？（正式包与草稿都会删除）')) return;
                const r = await window.api.deleteItemBlock(currentPackDir, it.kind, it.id);
                if (!r.ok) { window.showError('删除失败: ' + r.error); return; }
                window.showStatus('已删除 ' + it.id, true);
                await loadItemsAndBlocks(currentPackDir, currentType);
            });
            el.addEventListener('click', () => {
                if (handlers.onSelect) handlers.onSelect(currentPackDir, it);
            });
            listEl.appendChild(el);
        }
    }

    function init(cb) {
        handlers = cb || {};
    }

    return { init, loadItemsAndBlocks };
})();
