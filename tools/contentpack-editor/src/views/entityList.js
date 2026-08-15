// entityList.js - 中栏：实体列表
// 依赖 window.api、window.showError / window.AppState（app.js 定义）
// 暴露 window.EntityList.init({ onSelect, onNew }) / loadEntities(packDir)

window.EntityList = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));
    let handlers = { onSelect: null, onNew: null };
    let currentPackDir = null;

    // 绘制骨骼树缩略图到 canvas（横向树：根在左，子节点向右）
    function drawBoneThumb(canvas, tree) {
        const W = canvas.width, H = canvas.height;
        const ctx = canvas.getContext('2d');
        ctx.clearRect(0, 0, W, H);
        if (!tree || !tree.length) return;
        // 计算最大深度
        let maxDepth = 1;
        (function depth(nodes, d) {
            for (const n of nodes) {
                if (d > maxDepth) maxDepth = d;
                if (n.children && n.children.length) depth(n.children, d + 1);
            }
        })(tree, 1);
        const padX = 14, padY = 6;
        const spanX = (W - padX * 2) / (maxDepth || 1);
        const totalLeaves = countLeaves(tree);
        // 递归布局：返回每个节点的 y 中心
        const ys = new Map();
        let leafIdx = 0;
        function layout(nodes, depth) {
            const x = padX + spanX * (depth - 1) + spanX / 2;
            const out = [];
            for (const n of nodes) {
                if (n.children && n.children.length) {
                    const childYs = layout(n.children, depth + 1);
                    const y = (childYs[0] + childYs[childYs.length - 1]) / 2;
                    ys.set(n.name, { x, y });
                    // 连线
                    ctx.strokeStyle = 'rgba(137,180,250,0.55)';
                    ctx.lineWidth = 1;
                    for (const cy of childYs) {
                        ctx.beginPath();
                        ctx.moveTo(x + 3, y);
                        ctx.lineTo(padX + spanX * depth + spanX / 2 - 3, cy);
                        ctx.stroke();
                    }
                    out.push(y);
                } else {
                    const y = padY + (leafIdx / Math.max(1, totalLeaves - 1)) * (H - padY * 2);
                    leafIdx++;
                    ys.set(n.name, { x, y });
                    out.push(y);
                }
            }
            return out;
        }
        layout(tree, 1);
        // 画节点
        ctx.fillStyle = '#89b4fa';
        for (const [name, { x, y }] of ys) {
            ctx.beginPath();
            ctx.arc(x, y, 3, 0, Math.PI * 2);
            ctx.fill();
        }
    }

    function countLeaves(nodes) {
        let c = 0;
        for (const n of nodes) {
            if (n.children && n.children.length) c += countLeaves(n.children);
            else c++;
        }
        return c;
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
            // 异步解析模型 → 画缩略图（带缓存，主进程 modelCache）
            (async () => {
                try {
                    const ent2 = await window.api.readEntity(packDir, ent.id);
                    const mid = ent2 && ent2.model && ent2.model.id;
                    if (mid) {
                        const mres = await window.api.resolveModelFromId(mid, (ent2.model && ent2.model.type) || 'entity');
                        if (mres.ok && mres.data) {
                            drawBoneThumb(item.querySelector('.li-thumb'), mres.data.tree);
                        }
                    }
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
