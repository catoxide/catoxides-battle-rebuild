// techList.js - 中栏：科技树页签列表（与实体/结构/物品/任务列表共用 #entityList 容器）
// 数据源：main.js tech:read —— 包内 techs/tree.json（单文件 = 整棵树）。
// 列表项是「页签」，点进右栏编辑器后可在画布上手动排版节点。
// 暴露 window.TechList.init({onSelect, onNew}) / loadTree(packDir)

window.TechList = (function () {
    'use strict';
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : k);

    let handlers = { onSelect: null, onNew: null };
    let currentPackDir = null;

    async function loadTree(packDir) {
        currentPackDir = packDir;
        const listEl = document.getElementById('entityList');
        if (!listEl) return;
        listEl.innerHTML = '';
        if (!packDir) return;

        const res = await window.api.readTechTree(packDir);
        if (!res || !res.ok) {
            window.showError(t('tech.loadFail') + ((res && res.error) || ''));
            return;
        }
        const data = res.data || {};
        window.AppState.techTree = data.tree || null;
        window.AppState.techExists = !!data.exists;
        window.AppState.techRefs = data.refs || null;

        if (!data.exists || !data.tree) {
            const empty = document.createElement('div');
            empty.className = 'li-sub';
            empty.style.padding = '12px';
            empty.textContent = t('tech.empty');
            listEl.appendChild(empty);
            const openBtn = document.createElement('div');
            openBtn.className = 'list-item';
            const main = document.createElement('div');
            main.className = 'li-main';
            const textBox = document.createElement('div');
            textBox.className = 'li-text';
            const title = document.createElement('div');
            title.className = 'li-title';
            title.textContent = '+ ' + t('tech.createTree');
            textBox.appendChild(title);
            main.appendChild(textBox);
            openBtn.appendChild(main);
            openBtn.addEventListener('click', () => { if (handlers.onNew) handlers.onNew(currentPackDir); });
            listEl.appendChild(openBtn);
            return;
        }

        const tree = data.tree;
        const S = window.TechSchema;
        const pages = Array.isArray(tree.pages) ? tree.pages : [];
        for (const page of pages) {
            if (!page || !page.id) continue;
            const usage = S ? S.pageUsage(tree, page.id) : { nodes: [], research: [] };
            const row = document.createElement('div');
            row.className = 'list-item';
            const main = document.createElement('div');
            main.className = 'li-main';
            const textBox = document.createElement('div');
            textBox.className = 'li-text';
            const title = document.createElement('div');
            title.className = 'li-title';
            title.textContent = '📖 ' + (page.title || page.id);
            const sub = document.createElement('div');
            sub.className = 'li-sub';
            sub.textContent = page.id + ' · ' + tf('tech.pageCounts', {
                n: usage.nodes.length, r: usage.research.length,
            });
            textBox.appendChild(title);
            textBox.appendChild(sub);
            main.appendChild(textBox);
            row.appendChild(main);
            row.addEventListener('click', () => {
                if (handlers.onSelect) handlers.onSelect(currentPackDir, { tree, exists: true, pageId: page.id });
            });
            listEl.appendChild(row);
        }
    }

    function init(cb) {
        handlers = cb || {};
    }

    return { init, loadTree };
})();
