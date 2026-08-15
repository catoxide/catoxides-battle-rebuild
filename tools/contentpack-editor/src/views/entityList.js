// entityList.js - 中栏：实体列表
// 依赖 window.api、window.showError / window.AppState（app.js 定义）
// 暴露 window.EntityList.init({ onSelect, onNew }) / loadEntities(packDir)

window.EntityList = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));
    let handlers = { onSelect: null, onNew: null };
    let currentPackDir = null;

    // 加载 modelThumb 模块（ES module 动态导入，普通 script 里用）
    let thumbModulePromise = null;
    function getThumbModule() {
        if (!thumbModulePromise) {
            thumbModulePromise = import('../lib/modelThumb.js').catch((e) => {
                console.error('modelThumb 加载失败:', e);
                thumbModulePromise = null;
                return null;
            });
        }
        return thumbModulePromise;
    }

    // 实体缩略图：entity 类型 → 3D 骨骼预览；item 类型 → 贴图
    async function drawEntityThumb(canvas, entity) {
        if (!canvas) return;
        const mod = await getThumbModule();
        if (!mod) return;
        const modelType = (entity.model && entity.model.type) || 'entity';
        if (modelType === 'item') {
            // 物品 → 贴图
            if (entity.texture) {
                const tres = await window.api.readTexture(entity.texture);
                if (tres.ok && tres.data) mod.drawTextureThumb(canvas, tres.data);
            }
            return;
        }
        // 实体 → 3D 模型
        if (entity.model && entity.model.id) {
            const mres = await window.api.resolveModelFromId(entity.model.id, modelType);
            if (mres.ok && mres.data) {
                mod.drawModelThumb(canvas, mres.data);
            }
        }
    }

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
                <div class="li-main">
                    <canvas class="li-thumb" width="56" height="56"></canvas>
                    <div class="li-text">
                        <div class="li-title"></div>
                        <div class="li-sub"></div>
                    </div>
                </div>
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
            // 异步解析实体 → 渲染缩略图（3D 模型或贴图）
            (async () => {
                try {
                    const ent2 = await window.api.readEntity(packDir, ent.id);
                    if (ent2 && ent2.ok) drawEntityThumb(item.querySelector('.li-thumb'), ent2.data);
                } catch {}
            })();
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
