// app.js - 渲染进程主逻辑（加载顺序在 views 之后）
// 定义 window.AppState / window.showError / window.showStatus
// 初始化：显示路径 → 包列表 → 联动实体列表 → 实体编辑器

(function () {
    // ===== 全局状态 =====
    window.AppState = {
        pack: null,          // 当前选中包 { dir, name, version, manifest }
        entity: null,        // 当前编辑中的实体对象
        entities: [],        // 当前包的实体列表
        model: null,         // 当前已解析的模型
        healthConfig: { parts: [] },  // 当前实体的部位配置
    };

    // ===== 全局 UI 辅助 =====
    window.showError = function (msg) {
        const bar = document.getElementById('statusBar');
        if (bar) {
            bar.className = 'status err';
            bar.textContent = msg;
        }
        console.error('[Editor]', msg);
    };

    window.showStatus = function (msg, ok) {
        const bar = document.getElementById('statusBar');
        if (bar) {
            bar.className = 'status ' + (ok ? 'ok' : 'err');
            bar.textContent = msg;
        }
    };

    // ===== 布局交互：可拖拽分隔条 + 左栏收起/展开 =====
    const LAYOUT_KEY = 'contentpack-editor.layout';

    function loadLayout() {
        try { return JSON.parse(localStorage.getItem(LAYOUT_KEY)) || {}; } catch { return {}; }
    }
    function saveLayout(partial) {
        const cur = loadLayout();
        localStorage.setItem(LAYOUT_KEY, JSON.stringify(Object.assign(cur, partial)));
    }

    function initLayout() {
        const layout = loadLayout();
        const packPanel = document.getElementById('panelPack');
        const entityPanel = document.getElementById('panelEntity');
        const expandBtn = document.getElementById('btnExpandPack');
        const collapseBtn = document.getElementById('btnCollapsePack');

        // 恢复保存的宽度
        if (layout.packWidth) packPanel.style.width = layout.packWidth + 'px';
        if (layout.entityWidth) entityPanel.style.width = layout.entityWidth + 'px';
        // 恢复收起状态
        if (layout.packCollapsed) {
            packPanel.classList.add('collapsed');
            document.body.classList.add('pack-collapsed');
        }

        // 左栏收起/展开
        collapseBtn.addEventListener('click', () => {
            packPanel.classList.add('collapsed');
            document.body.classList.add('pack-collapsed');
            saveLayout({ packCollapsed: true });
        });
        expandBtn.addEventListener('click', () => {
            packPanel.classList.remove('collapsed');
            document.body.classList.remove('pack-collapsed');
            saveLayout({ packCollapsed: false });
        });

        // 分隔条拖拽：通用逻辑
        function makeDivider(dividerId, targetPanel, key, minW, maxW) {
            const divider = document.getElementById(dividerId);
            const panel = document.getElementById(targetPanel);
            let startX = 0, startW = 0;

            divider.addEventListener('mousedown', (e) => {
                e.preventDefault();
                startX = e.clientX;
                startW = panel.offsetWidth;
                document.body.classList.add('dragging');
                document.addEventListener('mousemove', onMove);
                document.addEventListener('mouseup', onUp);
            });

            function onMove(e) {
                const dx = e.clientX - startX;
                let w = startW + dx;
                if (w < minW) w = minW;
                if (w > maxW) w = maxW;
                panel.style.width = w + 'px';
                panel.classList.remove('collapsed');
                if (key === 'packWidth') {
                    document.body.classList.remove('pack-collapsed');
                    saveLayout({ packCollapsed: false, packWidth: w });
                } else {
                    saveLayout({ entityWidth: w });
                }
            }
            function onUp() {
                document.body.classList.remove('dragging');
                document.removeEventListener('mousemove', onMove);
                document.removeEventListener('mouseup', onUp);
            }
        }

        // divider1 拖左栏宽度（60~500px），divider2 拖中栏宽度（80~500px）
        makeDivider('divider1', 'panelPack', 'packWidth', 60, 500);
        makeDivider('divider2', 'panelEntity', 'entityWidth', 80, 500);
    }

    // ===== 启动 =====
    async function init() {
        // 布局交互（拖拽分隔条 / 左栏收起）
        initLayout();

        // 语言初始化 + 切换（i18n.js 已在 views 之前加载）
        const langSel = document.getElementById('langSelect');
        if (window.I18n) {
            langSel.value = window.I18n.getLang();
            langSel.addEventListener('change', () => {
                window.I18n.setLang(langSel.value);
            });
            window.I18n.applyDOM();
        } else {
            langSel.style.display = 'none';
        }

        // 显示路径信息
        const pathsRes = await window.api.getPaths();
        if (pathsRes.ok) {
            const p = pathsRes.data;
            if (p.needProjectRoot) {
                await setupProjectRoot();
                return;
            }
            document.getElementById('headerPaths').textContent = `源码: ${p.packsSourceDir}`;
        }

        // 初始化左栏（包列表）→ 选中包 → 加载实体列表
        window.PackList.init(async (pack) => {
            window.AppState.pack = pack;
            document.getElementById('packNameLabel').textContent = pack.name + ' v' + pack.version;
            document.getElementById('btnNewEntity').disabled = false;
            await window.EntityList.loadEntities(pack.dir);
        });

        // 初始化中栏（实体列表）
        window.EntityList.init({
            onSelect: async (packDir, ent) => {
                const readRes = await window.api.readEntity(packDir, ent.id);
                if (!readRes.ok) { window.showError('读取实体失败: ' + readRes.error); return; }
                window.EntityEditor.open({ pack: window.AppState.pack, entity: readRes.data, isNew: false });
            },
            onNew: async (packDir) => {
                window.EntityEditor.open({ pack: window.AppState.pack, entity: null, isNew: true });
            },
        });

        // 自动加载包列表
        await window.PackList.loadPacks();
    }

    // 项目根引导：应用内提示 → 选目录 → 生效后重载界面
    async function setupProjectRoot() {
        const bar = document.getElementById('statusBar');
        if (bar) {
            bar.className = 'status err';
            bar.textContent = '未找到项目目录。请选择项目根（含 contentpacks-source 的目录）。';
        }
        const res = await window.api.pickProjectRoot();
        if (!res.ok) {
            if (res.error === 'cancelled') {
                window.showError('未选择项目目录，编辑器不可用。点击此处重试 →');
                document.querySelector('#statusBar').style.cursor = 'pointer';
                document.querySelector('#statusBar').addEventListener('click', () => location.reload());
            } else {
                window.showError('设置项目根失败: ' + res.error);
            }
            return;
        }
        window.showStatus('项目目录已设置，加载中…', true);
        location.reload();
    }

    document.addEventListener('DOMContentLoaded', init);
})();
