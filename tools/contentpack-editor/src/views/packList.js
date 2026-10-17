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
            item.querySelector('.li-sub').textContent = 'v' + pack.version
                + (pack.manifest['Module-Dependencies'] ? ' · dep:' + pack.manifest['Module-Dependencies'] : '')
                + (pack.hasBuildGradle ? '' : ' · ' + t('pack.notGradle'));
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
        const val = (id) => document.getElementById(id).value.trim();
        const data = {
            name,
            version: val('npVersion') || '0.1.0',
            requires: val('npRequires') || 'catoxidesbattlerebuild>=0.2.0',
            entry: val('npEntry'),
            deps: val('npDeps'),
            depsOptional: val('npDepsOpt'),
            libRequires: val('npLibReq'),
            modRequires: val('npModReq'),
            modOptional: val('npModOpt'),
            extraDeps: val('npExtraDeps'),
        };
        const res = await window.api.createPack(data);
        if (!res.ok) { window.showError(t('dyn.createPackFail') + res.error); return; }
        const d = res.data || {};
        const ws = String((window.AppState && window.AppState.rootSource) || '').startsWith('workspace');
        window.showStatus(tf('dyn.packCreatedFiles', {
            name,
            files: (d.written || []).join(' / '),
            cmd: d.gradleCommand || '',
        }) + (ws ? '（独立工作区：无 gradlew，构建需先点顶栏「📁 连接工程…」选择 mod 工程）' : '')
            + (d.outsideLibrary ? '（建在「新建包目录」—— 与包库不同目录，列表已合并显示）' : ''), true);
        toggleNewPackForm(false);
        ['npName', 'npVersion', 'npRequires', 'npEntry', 'npDeps', 'npDepsOpt',
            'npLibReq', 'npModReq', 'npModOpt', 'npExtraDeps'].forEach((id) => {
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
        // 首次加载由 app.js 显式 await（只加载一次，避免 init 与启动流程各拉一遍列表）
    }

    return { init, loadPacks };
})();
