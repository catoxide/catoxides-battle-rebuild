// packList.js - 左栏：内容包列表
// 依赖 window.api（preload 暴露）、window.showError / window.AppState（app.js 定义）
// 暴露 window.PackList.init(onSelectPack) / loadPacks()
// 0.2.1 自查修复：
//   · 所有 IPC 结果都判空 + try/catch（invoke reject 时不再变成未处理 rejection、界面毫无反应）
//   · 空列表/加载失败在列表内给出可见提示（错误条在 #editorContent 之外，见 index.html）
//   · 每行显示暂存草稿数（staging:all）
//   · 创建成功后自动选中新包
//   · 默认版本/依赖交给主进程（PACK_DEFAULTS），渲染端不再硬编码一份

window.PackList = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : (k));
    let onSelectPack = null;
    let selectedDir = null;
    let lastPacks = [];          // 最近一次列表结果（创建后按 created 选中）
    let stagedByPack = new Map(); // 包目录 basename → 暂存项数

    function renderListMessage(text, cls) {
        const listEl = document.getElementById('packList');
        if (!listEl) return;
        listEl.innerHTML = '';
        const item = document.createElement('div');
        item.className = 'list-item muted ' + (cls || '');
        item.textContent = text;
        listEl.appendChild(item);
    }

    async function loadStagedCounts() {
        stagedByPack = new Map();
        try {
            const r = await window.api.stagingAll();
            if (r && r.ok && Array.isArray(r.data)) {
                for (const p of r.data) stagedByPack.set(p.packName, (p.items || []).length);
            }
        } catch { /* 暂存信息拿不到不影响列表 */ }
    }

    async function loadPacks() {
        const listEl = document.getElementById('packList');
        let res;
        try { res = await window.api.listPacks(); }
        catch (e) { res = { ok: false, error: String((e && e.message) || e) }; }
        if (!res || !res.ok) {
            renderListMessage(t('dyn.loadPacksFail') + ((res && res.error) || ''), 'err');
            if (window.showError) window.showError(t('dyn.loadPacksFail') + ((res && res.error) || ''));
            return;
        }
        const packs = res.data || [];
        lastPacks = packs;
        await loadStagedCounts();
        listEl.innerHTML = '';
        if (!packs.length) {
            renderListMessage(t('dyn.noPacks'));
            if (selectedDir) selectedDir = null;
            return;
        }
        for (const pack of packs) {
            const item = document.createElement('div');
            item.className = 'list-item' + (pack.dir === selectedDir ? ' active' : '');
            item.innerHTML = `
                <div class="li-title"></div>
                <div class="li-sub"></div>`;
            item.querySelector('.li-title').textContent = pack.name;
            let sub = 'v' + pack.version
                + (pack.manifest['Module-Dependencies'] ? ' · dep:' + pack.manifest['Module-Dependencies'] : '')
                + (pack.hasBuildGradle ? '' : ' · ' + t('pack.notGradle'));
            const staged = stagedByPack.get(pack.dir.split(/[\\/]/).pop()) || 0;
            if (staged) sub += ' · ' + tf('dyn.packStaged', { n: staged });
            item.querySelector('.li-sub').textContent = sub;
            item.addEventListener('click', () => select(pack, item));
            listEl.appendChild(item);
        }
    }

    function select(pack, itemEl) {
        selectedDir = pack.dir;
        document.querySelectorAll('#packList .list-item').forEach((el) => el.classList.remove('active'));
        if (itemEl) itemEl.classList.add('active');
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
            // 版本/依赖留空 → 主进程 PACK_DEFAULTS 填默认值（单一来源）
            version: val('npVersion'),
            requires: val('npRequires'),
            entry: val('npEntry'),
            deps: val('npDeps'),
            depsOptional: val('npDepsOpt'),
            libRequires: val('npLibReq'),
            modRequires: val('npModReq'),
            modOptional: val('npModOpt'),
            extraDeps: val('npExtraDeps'),
        };
        const confirmBtn = document.getElementById('npConfirm');
        if (confirmBtn) confirmBtn.disabled = true;
        let res;
        try { res = await window.api.createPack(data); }
        catch (e) { res = { ok: false, error: String((e && e.message) || e) }; }
        finally { if (confirmBtn) confirmBtn.disabled = false; }
        if (!res || !res.ok) { window.showError(t('dyn.createPackFail') + ((res && res.error) || '')); return; }
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
        // 0.2.1：创建后自动选中新包（此前只刷新列表，用户得自己去点）
        const created = d.created;
        if (created) {
            const idx = lastPacks.findIndex((p) => p.dir === created);
            if (idx >= 0) {
                const els = document.querySelectorAll('#packList .list-item');
                if (els[idx]) select(lastPacks[idx], els[idx]);
            }
        }
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

    return { init, loadPacks, getSelectedDir: () => selectedDir, selectPackByDir: (dir) => {
        const idx = lastPacks.findIndex((p) => p.dir === dir);
        if (idx < 0) return null;
        const els = document.querySelectorAll('#packList .list-item');
        if (!els[idx]) return null;
        select(lastPacks[idx], els[idx]);
        return lastPacks[idx];
    } };
})();
