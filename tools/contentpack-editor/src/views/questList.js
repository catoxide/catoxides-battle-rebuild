// questList.js - 中栏：任务列表（与实体/结构/物品列表共用 #entityList 容器）
// 数据源：main.js quest:list —— 包内 quests/*.json ∪ 暂存草稿，按「文件内容里的 id」索引
//（文件名不保证等于 id：历史文件 quests.json 就是这样）。
// 依赖 window.api / window.showError / window.showStatus / window.AppState（app.js 定义）
// 暴露 window.QuestList.init({onSelect, onNew}) / loadQuests(packDir)

window.QuestList = (function () {
    'use strict';
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : k);

    let handlers = { onSelect: null, onNew: null };
    let currentPackDir = null;

    async function loadQuests(packDir) {
        currentPackDir = packDir;
        const listEl = document.getElementById('entityList');
        listEl.innerHTML = '';
        if (!packDir) return;
        const res = await window.api.listQuests(packDir);
        if (!res || !res.ok) {
            window.showError(t('quest.loadFail') + ((res && res.error) || ''));
            return;
        }
        const quests = res.data || [];
        window.AppState.quests = quests;
        if (!quests.length) {
            const empty = document.createElement('div');
            empty.className = 'li-sub';
            empty.style.padding = '12px';
            empty.textContent = t('quest.empty');
            listEl.appendChild(empty);
            return;
        }
        for (const q of quests) {
            // 显式建节点（不用 innerHTML 模板）：本文件要能被 test/questView.test.js 的 DOM 桩驱动
            const row = document.createElement('div');
            row.className = 'list-item';
            const main = document.createElement('div');
            main.className = 'li-main';
            const textBox = document.createElement('div');
            textBox.className = 'li-text';
            const title = document.createElement('div');
            title.className = 'li-title';
            title.textContent = '🗺 ' + (q.title || q.id);
            if (q.staged) {
                const tag = document.createElement('span');
                tag.className = 'li-tag';
                tag.textContent = t('dyn.draft');
                title.appendChild(tag);
            }
            const sub = document.createElement('div');
            sub.className = 'li-sub';
            sub.textContent = q.id + ' · ' + tf('quest.counts', {
                g: q.giverCount || 0, o: q.goalCount || 0, c: q.childCount || 0,
            });
            textBox.appendChild(title);
            textBox.appendChild(sub);
            main.appendChild(textBox);
            row.appendChild(main);

            const actions = document.createElement('div');
            actions.className = 'li-actions';
            const editBtn = document.createElement('button');
            editBtn.className = 'btn ghost small act-edit';
            editBtn.textContent = t('dyn.edit');
            const delBtn = document.createElement('button');
            delBtn.className = 'btn ghost small act-del';
            delBtn.style.color = 'var(--err)';
            delBtn.textContent = t('dyn.delete');
            actions.appendChild(editBtn);
            actions.appendChild(delBtn);
            row.appendChild(actions);

            const open = () => { if (handlers.onSelect) handlers.onSelect(currentPackDir, q); };
            editBtn.addEventListener('click', (e) => {
                if (e && e.stopPropagation) e.stopPropagation();
                open();
            });
            delBtn.addEventListener('click', async (e) => {
                if (e && e.stopPropagation) e.stopPropagation();
                if (!confirm(tf('quest.deleteConfirm', { id: q.id }))) return;
                const r = await window.api.deleteQuest(currentPackDir, q.id);
                if (!r || !r.ok) { window.showError(t('quest.deleteFail') + ((r && r.error) || '')); return; }
                window.showStatus(tf('quest.deleted', { id: q.id }), true);
                await loadQuests(currentPackDir);
            });
            row.addEventListener('click', open);
            listEl.appendChild(row);
        }
    }

    function init(cb) {
        handlers = cb || {};
    }

    return { init, loadQuests };
})();
