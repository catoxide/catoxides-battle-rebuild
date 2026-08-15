// packList.js - 左栏：内容包列表
// 依赖 window.api（preload 暴露）、window.showError / window.AppState（app.js 定义）
// 暴露 window.PackList.init(onSelectPack)

window.PackList = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));
    let onSelectPack = null;
    let selectedDir = null;

    async function loadPacks() {
        const res = await window.api.listPacks();
        if (!res.ok) { window.showError(t('dyn.loadPacksFail') + res.error); return; }
        const listEl = document.getElementById('packList');
        listEl.innerHTML = '';
        const packs = res.data || [];
        for (const pack of packs) {
            const item = document.createElement('div');
            item.className = 'list-item' + (pack.dir === selectedDir ? ' active' : '');
            item.innerHTML = `
                <div class="li-title"></div>
                <div class="li-sub"></div>`;
            item.querySelector('.li-title').textContent = pack.name;
            item.querySelector('.li-sub').textContent = 'v' + pack.version + (pack.manifest['Module-Dependencies'] ? ' · dep:' + pack.manifest['Module-Dependencies'] : '');
            item.addEventListener('click', () => select(pack, item));
            listEl.appendChild(item);
        }
    }

    function select(pack, itemEl) {
        selectedDir = pack.dir;
        document.querySelectorAll('#packList .list-item').forEach((el) => el.classList.remove('active'));
        itemEl.classList.add('active');
        window.AppState.pack = pack;
        if (onSelectPack) onSelectPack(pack);
    }

    function toggleNewPackForm(show) {
        document.getElementById('newPackForm').classList.toggle('hidden', !show);
    }

    async function createPack() {
        const name = document.getElementById('npName').value.trim();
        if (!name) { window.showError(t('dyn.packNameRequired')); return; }
        const data = {
            name,
            version: document.getElementById('npVersion').value.trim() || '0.1.0',
            requires: document.getElementById('npRequires').value.trim() || 'catoxidesbattlerebuild>=0.2.0',
            deps: document.getElementById('npDeps').value.trim(),
            modRequires: document.getElementById('npModReq').value.trim(),
        };
        const res = await window.api.createPack(data);
        if (!res.ok) { window.showError(t('dyn.createPackFail') + res.error); return; }
        window.showStatus(tf('dyn.packCreated', { name }), true);
        toggleNewPackForm(false);
        ['npName', 'npVersion', 'npRequires', 'npDeps', 'npModReq'].forEach((id) => {
            document.getElementById(id).value = '';
        });
        await loadPacks();
    }

    function init(cb) {
        onSelectPack = cb;
        document.getElementById('btnNewPack').addEventListener('click', () => {
            toggleNewPackForm(document.getElementById('newPackForm').classList.contains('hidden'));
        });
        document.getElementById('npConfirm').addEventListener('click', createPack);
        document.getElementById('npCancel').addEventListener('click', () => toggleNewPackForm(false));
        loadPacks();
    }

    return { init, loadPacks };
})();
