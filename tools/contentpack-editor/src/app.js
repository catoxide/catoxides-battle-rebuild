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
    // 0.2.1：状态条已移到 #editorContent 之外（index.html），任何模式下都可见。
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

    // 0.2.1：切换包时丢弃右侧所有编辑框架（此前契约编辑器 packDir 停留在上一个包，
    // 切换包后点「打包」会打到旧包；实体/结构标签也跨包残留）。
    window.closeAllEditorsForPackSwitch = function () {
        try { if (window.EntityEditor && window.EntityEditor.closeAll) window.EntityEditor.closeAll(); } catch { /* 忽略 */ }
        try { if (window.StructureEditor && window.StructureEditor.closeAll) window.StructureEditor.closeAll(); } catch { /* 忽略 */ }
        try { if (window.ContractEditor) window.ContractEditor.close(); } catch { /* 忽略 */ }
        try { if (window.TapeEditor) window.TapeEditor.close(); } catch { /* 忽略 */ }
        try { if (window.ItemBlockEditor) window.ItemBlockEditor.close(); } catch { /* 忽略 */ }
        try { if (window.QuestEditor) window.QuestEditor.close(); } catch { /* 忽略 */ }
        window.AppState.entity = null;
        window.AppState.healthConfig = { parts: [] };
        window.AppState.model = null;
        if (window.applyEditorVisibility) window.applyEditorVisibility();
    };

    // 实体/结构/契约/状态机四个编辑器框架的显隐协调。
    // 多个框架同时有活跃内容时，按 AppState.activeFrame（最近一次打开的框架）决定显示哪个。
    window.applyEditorVisibility = function () {
        const structActive = window.StructureEditor && window.StructureEditor.hasActiveTab();
        const entityActive = window.EntityEditor && window.EntityEditor.hasActiveTab();
        const contractActive = window.ContractEditor && window.ContractEditor.hasActiveTab();
        const tapeActive = window.TapeEditor && window.TapeEditor.hasActiveTab();
        const itemBlockActive = window.ItemBlockEditor && window.ItemBlockEditor.hasActiveTab();
        const questActive = window.QuestEditor && window.QuestEditor.hasActiveTab();
        const frame = window.AppState && window.AppState.activeFrame;
        let show = null;
        if (frame === 'tape' && tapeActive) show = 'tape';
        else if (frame === 'contract' && contractActive) show = 'contract';
        else if (frame === 'itemblock' && itemBlockActive) show = 'itemblock';
        else if (frame === 'quest' && questActive) show = 'quest';
        else if (frame === 'structure' && structActive) show = 'structure';
        else if (frame === 'entity' && entityActive) show = 'entity';
        else if (structActive) show = 'structure';
        else if (itemBlockActive) show = 'itemblock';
        else if (questActive) show = 'quest';
        else if (entityActive) show = 'entity';
        else if (contractActive) show = 'contract';
        else if (tapeActive) show = 'tape';

        const empty = document.getElementById('editorEmpty');
        const ent = document.getElementById('editorContent');
        const str = document.getElementById('structureEditorContent');
        const ctr = document.getElementById('contractEditorContent');
        const tape = document.getElementById('tapeEditorContent');
        const ib = document.getElementById('itemBlockEditorContent');
        const qs = document.getElementById('questEditorContent');
        if (show) empty.classList.add('hidden'); else empty.classList.remove('hidden');
        ent.classList.toggle('hidden', show !== 'entity');
        if (str) str.classList.toggle('hidden', show !== 'structure');
        if (ctr) ctr.classList.toggle('hidden', show !== 'contract');
        if (tape) tape.classList.toggle('hidden', show !== 'tape');
        if (ib) ib.classList.toggle('hidden', show !== 'itemblock');
        if (qs) qs.classList.toggle('hidden', show !== 'quest');
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

        // 编辑区至少留这么宽：再窄工具条和表单就不可用了。
        const MIN_EDITOR = 300;
        const DIVIDERS = 10; // 两条 5px 分隔条

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
                // 上限随视口走：固定 500 的话，窄窗口里能把编辑区拖成负宽，
                // 而 body overflow:hidden 不留滚动条，拖出去的界面不可达。
                const other = key === 'packWidth' ? entityPanel : packPanel;
                const otherW = other.classList.contains('collapsed') ? 0 : other.offsetWidth;
                const cap = Math.max(minW, Math.min(maxW, document.documentElement.clientWidth - otherW - MIN_EDITOR - DIVIDERS));
                if (w > cap) w = cap;
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

        // 侧栏宽度是 localStorage 持久化的：大屏上拖出的 500+500，换到小屏/低分辨率
        // 会把编辑区压成 0，且 body overflow:hidden 不留横向滚动条 —— 那部分界面
        // 直接不可达。视口变小时按比例压回，压到下限还不够就收左栏。只在溢出时动手。
        // 钳制结果不落盘：小屏上的一次妥协不该永久改掉用户在大屏上拖出的宽度。
        function clampPanelsToViewport() {
            const avail = document.documentElement.clientWidth - MIN_EDITOR - DIVIDERS;
            const wasCollapsed = packPanel.classList.contains('collapsed');
            let pw = wasCollapsed ? 0 : packPanel.offsetWidth;
            let ew = entityPanel.offsetWidth;
            if (pw + ew <= avail) return;
            const scale = avail / (pw + ew);
            pw = wasCollapsed ? 0 : Math.max(60, Math.round(pw * scale));
            ew = Math.max(80, Math.round(ew * scale));
            if (pw + ew > avail) {
                if (!wasCollapsed) {
                    packPanel.classList.add('collapsed');
                    document.body.classList.add('pack-collapsed');
                }
                pw = 0;
                ew = Math.max(80, Math.min(ew, avail));
            }
            if (!wasCollapsed) packPanel.style.width = pw + 'px';
            entityPanel.style.width = ew + 'px';
        }
        window.addEventListener('resize', clampPanelsToViewport);
        clampPanelsToViewport();
    }

    // ===== 启动 =====
    async function init() {
        // 布局交互（拖拽分隔条 / 左栏收起）
        initLayout();

        // 3D 模型编辑器（W2 子窗口）
        const btnModelEditor = document.getElementById('btnOpenModelEditor');
        if (btnModelEditor) {
            btnModelEditor.addEventListener('click', () => {
                window.api.openModelEditor().then((r) => {
                    if (r && !r.ok) console.error('打开模型编辑器失败:', r.error);
                });
            });
        }

        // W5.3: 渲染模式开关 + 崩溃自愈横幅
        const btnGpu = document.getElementById('btnGpuToggle');
        const tt = (k) => (window.I18n ? window.I18n.t(k) : k);
        let gpuState = false, debugState = false;
        function updateGpuBtn(on) {
            gpuState = !!on;
            if (!btnGpu) return;
            btnGpu.textContent = on ? tt('app.gpuSoft') : tt('app.gpuHard');
            btnGpu.title = on ? tt('app.gpuSoftTip') : tt('app.gpuHardTip');
        }
        if (btnGpu) {
            btnGpu.addEventListener('click', async () => {
                try {
                    const cur = await window.api.getSettings();
                    // 0.2.1：读取失败不能当成「当前是硬件加速」→ 否则点击反而切到软件渲染
                    if (!cur || !cur.ok || !cur.data) {
                        window.showError && window.showError(tt('app.settingsReadFail') + ' ' + ((cur && cur.error) || ''));
                        return;
                    }
                    const on = !cur.data.softwareRendering;
                    const r = await window.api.setSoftwareRendering(on);
                    if (!r || !r.ok) { window.showError && window.showError('设置失败: ' + (r && r.error)); return; }
                    updateGpuBtn(on);
                    window.api.relaunchApp(); // 重启生效
                } catch (e) { console.error('渲染模式切换失败:', e); }
            });
        }

        // W5.5: 调试模式开关（chromium.log + DevTools 自动打开 + 崩溃转储；重启生效）
        const btnDebug = document.getElementById('btnDebugToggle');
        function updateDebugBtn(on) {
            debugState = !!on;
            if (!btnDebug) return;
            btnDebug.textContent = on ? tt('app.debugOn') : tt('app.debugOff');
            btnDebug.title = on ? tt('app.debugOnTip') : tt('app.debugOffTip');
        }
        if (btnDebug) {
            btnDebug.addEventListener('click', async () => {
                try {
                    const cur = await window.api.getSettings();
                    if (!cur || !cur.ok || !cur.data) {
                        window.showError && window.showError(tt('app.settingsReadFail') + ' ' + ((cur && cur.error) || ''));
                        return;
                    }
                    const on = !cur.data.debugMode;
                    const r = await window.api.setDebugMode(on);
                    if (!r || !r.ok) { window.showError && window.showError('设置失败: ' + (r && r.error)); return; }
                    updateDebugBtn(on);
                    window.api.relaunchApp(); // 重启生效
                } catch (e) { console.error('调试模式切换失败:', e); }
            });
        }
        try {
            const st0 = await window.api.getSettings();
            if (st0 && st0.ok && st0.data) {
                // 0.2.1：启动时两个按钮都要按真实设置刷新（此前只刷了调试按钮，
                // 软件渲染模式下 GPU 按钮仍显示「⚡ 硬件加速」，与实际相反）
                updateDebugBtn(!!st0.data.debugMode);
                updateGpuBtn(!!st0.data.softwareRendering);
            }
        } catch { /* 忽略 */ }

        // 消费重启通知（W5.5）：reopen = 重启前模型编辑器开着 → 自动重开（W5.2 恢复最后模型）；
        // banner = 崩溃自动降级 → 显示"检测到崩溃"横幅
        try {
            const fb = await window.api.consumeFallbackNotice();
            if (fb && fb.ok && fb.data) {
                if (fb.data.banner) {
                    const banner = document.getElementById('gpuBanner');
                    const btext = document.getElementById('gpuBannerText');
                    if (banner && btext) {
                        btext.textContent = '⚠️ 检测到渲染进程崩溃，已自动切换到软件渲染模式（更稳定，3D 会稍慢）并重启。' +
                            (fb.data.reopen ? '模型编辑器已自动重开，最后的模型已恢复。' : '') +
                            '如不再崩溃，可在顶栏切回「⚡ 硬件加速」。';
                        banner.classList.remove('hidden');
                        const closeBtn = document.getElementById('btnGpuBannerClose');
                        if (closeBtn) closeBtn.onclick = () => banner.classList.add('hidden');
                    }
                }
                if (fb.data.reopen && btnModelEditor) btnModelEditor.click(); // 自动重开模型编辑器
                updateGpuBtn(!!fb.data.softwareRendering);
            }
        } catch { /* 通知消费失败不影响主流程 */ }

        // 语言初始化 + 切换（i18n.js 已在 views 之前加载）
        const langSel = document.getElementById('langSelect');
        if (window.I18n) {
            langSel.value = window.I18n.getLang();
            langSel.addEventListener('change', () => {
                window.I18n.setLang(langSel.value);
                // applyDOM 只翻译静态 [data-i18n]；列表/状态栏/面板是动态渲染的 →
                // 至少把左栏包列表与中栏内容列表按新语言重画（打开中的编辑面板需重开）。
                try { window.PackList.loadPacks(); } catch { /* 忽略 */ }
                try { loadContentList(); } catch { /* 忽略 */ }
                // 0.2.1：顶栏两个开关按钮与已打开的契约/结构/状态机编辑器也要跟着切换
                try { updateGpuBtn(gpuState); updateDebugBtn(debugState); } catch { /* 忽略 */ }
                try { if (window.ContractEditor && window.ContractEditor.rerender) window.ContractEditor.rerender(); } catch { /* 忽略 */ }
                try { if (window.StructureEditor && window.StructureEditor.rerender) window.StructureEditor.rerender(); } catch { /* 忽略 */ }
                try { if (window.TapeEditor && window.TapeEditor.rerender) window.TapeEditor.rerender(); } catch { /* 忽略 */ }
                try { if (window.QuestEditor && window.QuestEditor.rerender) window.QuestEditor.rerender(); } catch { /* 忽略 */ }
                // 0.2.2：部署面板若开着，用已有测量结果按新语言重画（不重新读盘）
                try { if (window.DeployStatus && window.DeployStatus.isOpen()) window.DeployStatus.rerender(); } catch { /* 忽略 */ }
                try { if (window.api && window.api.notifyMenuLang) window.api.notifyMenuLang(langSel.value); } catch { /* 忽略 */ }
            });
            window.I18n.applyDOM();
            try { if (window.api && window.api.notifyMenuLang) window.api.notifyMenuLang(window.I18n.getLang()); } catch { /* 忽略 */ }
        } else {
            langSel.style.display = 'none';
        }

        // 顶层应用菜单的动作下发：菜单只是已有入口的第二个触发点，
        // 全部落到既有按钮/既有函数上，不在菜单侧复制业务逻辑。
        if (window.api && window.api.onMenuAction) {
            window.api.onMenuAction((id) => {
                const clickBtn = (elId) => {
                    const el = document.getElementById(elId);
                    if (el && !el.disabled) el.click();
                };
                if (id === 'deploy') clickBtn('btnDeployStatus');
                else if (id === 'history') clickBtn('btnOpenHistory');
                else if (id === 'modelEditor') clickBtn('btnOpenModelEditor');
                else if (id === 'build') clickBtn('btnPackBuild');
                else if (id === 'collapsePack') {
                    if (document.body.classList.contains('pack-collapsed')) clickBtn('btnExpandPack');
                    else clickBtn('btnCollapsePack');
                } else if (id === 'refresh') {
                    refreshAfterPackPaths().catch(() => { /* 刷新失败走既有 showError 通路 */ });
                }
            });
        }

        // 显示路径信息 + 项目根来源（0.2.1：无论有无工程，这里都必须有输出，
        // 且**绝不 return**——反馈机事故就是这里 return 导致后面所有按钮没绑定）
        let rootSource = null;
        try {
            const pathsRes = await window.api.getPaths();
            if (pathsRes && pathsRes.ok && pathsRes.data) {
                const p = pathsRes.data;
                rootSource = p.rootSource || null;
                window.AppState.rootSource = rootSource;
                if (p.needProjectRoot) {
                    // 兜底分支：主进程连独立工作区都建不出来（极罕见）→ 引导选择，但继续初始化界面
                    await setupProjectRoot();
                    // 0.2.1：用户取消时顶栏仍要有一行可读信息（此前空白 = 和反馈机"没有源码串"同款症状）
                    const hp = document.getElementById('headerPaths');
                    if (hp && !hp.textContent) {
                        hp.textContent = '源码: （未设置）—— 点顶栏「📁 连接工程…」选择 mod 工程';
                    }
                } else {
                    document.getElementById('headerPaths').textContent = `源码: ${p.packsSourceDir}`;
                }
            } else {
                document.getElementById('headerPaths').textContent = '源码: （路径读取失败，仍可在下方操作）';
            }
        } catch (e) {
            document.getElementById('headerPaths').textContent = '源码: （路径读取异常: ' + String((e && e.message) || e) + '）';
            if (window.api && window.api.log) window.api.log('error', 'init', 'getPaths 失败: ' + String((e && e.message) || e));
        }

        // W6.1 包路径设置（包库 / 新建包目录）+ 0.2.1 连接工程 / 诊断（全部与项目根无关地绑定）
        initPackPathTools(rootSource);

        // 初始化左栏（包列表）→ 选中包 → 加载当前类型列表
        // 注意：PackList.select() 会先把 AppState.pack 赋成新包，所以这里不能拿
        // AppState.pack 当「上一个包」，必须自己记（此前比较的结果恒相等 → 从不重置）
        let editorPackDir = null;
        window.PackList.init(async (pack) => {
            if (editorPackDir && pack && editorPackDir !== pack.dir) {
                try { window.closeAllEditorsForPackSwitch(); } catch { /* 忽略 */ }
            }
            editorPackDir = pack.dir;
            window.AppState.pack = pack;
            document.getElementById('packNameLabel').textContent = pack.name + ' v' + pack.version;
            document.getElementById('btnNewEntity').disabled = false;
            // 包级「📦 打包」跟着选中态走：没选包时按下去只会静默无反应（见上面 click 里的早退）
            const pbb = document.getElementById('btnPackBuild');
            if (pbb) pbb.disabled = false;
            await loadContentList();
        });

        // 中栏内容类型切换（选项由已接入的 contentpack 编辑器扩展动态提供；
        // 目前仅 entity 内置，其余类型待 schema 驱动扩展后加入）
        document.getElementById('contentTypeSelect').addEventListener('change', loadContentList);

        // 初始化中栏（实体列表）
        window.EntityList.init({
            onSelect: async (packDir, ent) => {
                if (window.AppState.contentType !== 'entity') return;
                window.AppState.activeFrame = 'entity';
                const readRes = await window.api.readEntity(packDir, ent.id);
                if (!readRes.ok) { window.showError('读取实体失败: ' + readRes.error); return; }
                window.EntityEditor.open({ pack: window.AppState.pack, entity: readRes.data, isNew: false });
            },
            onNew: async (packDir) => {
                // 中栏"+ 新建"按钮共用：按当前内容类型路由
                const type = window.AppState.contentType;
                if (type === 'structure') {
                    window.AppState.activeFrame = 'structure';
                    window.StructureEditor.open({ pack: window.AppState.pack, structure: null, isNew: true });
                } else if (type === 'item' || type === 'block') {
                    window.AppState.activeFrame = 'itemblock';
                    window.ItemBlockEditor.open({ pack: window.AppState.pack, kind: type, def: null, isNew: true });
                } else if (type === 'entity') {
                    window.AppState.activeFrame = 'entity';
                    window.EntityEditor.open({ pack: window.AppState.pack, entity: null, isNew: true });
                } else if (type === 'quest') {
                    window.AppState.activeFrame = 'quest';
                    window.QuestEditor.open({ pack: window.AppState.pack, def: null, isNew: true });
                }
            },
        });

        // 初始化中栏（结构列表，与实体列表共用 #entityList 容器）
        window.StructureList.init({
            onSelect: async (packDir, structure) => {
                window.AppState.activeFrame = 'structure';
                window.StructureEditor.open({ pack: window.AppState.pack, structure, isNew: false });
            },
            onNew: async (packDir) => {
                window.AppState.activeFrame = 'structure';
                window.StructureEditor.open({ pack: window.AppState.pack, structure: null, isNew: true });
            },
        });

        // 初始化中栏（物品/方块列表，共用 #entityList 容器）+ 右栏编辑器
        const reloadItemBlockList = async () => {
            const pack = window.AppState.pack;
            if (pack && (window.AppState.contentType === 'item' || window.AppState.contentType === 'block')) {
                await window.ItemBlockList.loadItemsAndBlocks(pack.dir, window.AppState.contentType);
            }
        };
        window.ItemBlockList.init({
            onSelect: async (packDir, it) => {
                window.AppState.activeFrame = 'itemblock';
                const readRes = await window.api.readItemBlock(packDir, it.kind, it.id);
                if (!readRes.ok) { window.showError('读取失败: ' + readRes.error); return; }
                if (!readRes.data) { window.showError('找不到 ' + it.kind + ' ' + it.id); return; }
                window.ItemBlockEditor.open({ pack: window.AppState.pack, kind: it.kind, def: readRes.data, isNew: false });
            },
            onNew: async (packDir) => {
                window.AppState.activeFrame = 'itemblock';
                window.ItemBlockEditor.open({ pack: window.AppState.pack, kind: window.AppState.contentType === 'block' ? 'block' : 'item', def: null, isNew: true });
            },
        });
        window.ItemBlockEditor.init({ onListChanged: reloadItemBlockList });

        // 初始化中栏（任务列表，共用 #entityList 容器）+ 右栏任务编辑器
        const reloadQuestList = async () => {
            const pack = window.AppState.pack;
            if (pack && window.AppState.contentType === 'quest') {
                await window.QuestList.loadQuests(pack.dir);
            }
        };
        window.QuestList.init({
            onSelect: async (packDir, q) => {
                window.AppState.activeFrame = 'quest';
                const readRes = await window.api.readQuest(packDir, q.id);
                if (!readRes.ok) { window.showError(tf('quest.readFail', { msg: readRes.error })); return; }
                if (!readRes.data) { window.showError(tf('quest.notFound', { id: q.id })); return; }
                window.QuestEditor.open({ pack: window.AppState.pack, def: readRes.data, isNew: false });
            },
            onNew: async () => {
                window.AppState.activeFrame = 'quest';
                window.QuestEditor.open({ pack: window.AppState.pack, def: null, isNew: true });
            },
        });
        window.QuestEditor.init({ onListChanged: reloadQuestList });

        // 一键导入原版 / 一键导出整包原版数据
        const btnImport = document.getElementById('btnStructImport');
        const btnImportDir = document.getElementById('btnStructImportDir');
        const btnExportAll = document.getElementById('btnStructExportAll');
        if (btnImport) btnImport.addEventListener('click', () => structImportVanilla(false));
        if (btnImportDir) btnImportDir.addEventListener('click', () => structImportVanilla(true));
        if (btnExportAll) btnExportAll.addEventListener('click', () => structExportVanillaAll());

        // 物品/方块：导出整包原版数据（模型 + blockstate + lang + 掉落 + 标签）
        const btnIbExportAll = document.getElementById('btnItemBlockExportAll');
        if (btnIbExportAll) btnIbExportAll.addEventListener('click', () => itemBlockExportVanillaAll());

        // 任务：暂存草稿 → 正式包（staging:export；该包全部草稿一起写，与结构/实体「导出到包」同一套流程）
        const btnQuestExportAll = document.getElementById('btnQuestExportAll');
        if (btnQuestExportAll) btnQuestExportAll.addEventListener('click', () => questWriteToPack());
        const btnQuestEnsureScaffold = document.getElementById('btnQuestEnsureScaffold');
        if (btnQuestEnsureScaffold) btnQuestEnsureScaffold.addEventListener('click', () => questEnsureScaffold());

        // 动画契约按钮（右栏框架）：刷新 = 重读契约 + 重盘点；打包 = 重建该包 jar
        document.getElementById('btnContractRefresh').addEventListener('click', () => window.ContractEditor.refresh());
        document.getElementById('btnContractBuild').addEventListener('click', () => window.ContractEditor.build());

        // 0.2.2 部署四态面板 + 内容包 on/off（顶栏「🚀 部署状态」）
        if (window.DeployStatus) window.DeployStatus.init();
        // 0.2.2 第 2 步：提交回溯（只读 git）；从部署面板每行的「🕘 回溯」进入
        if (window.GitHistoryView) window.GitHistoryView.init();

        // 部署面板与回溯面板都用「包目录名」当主键（core/packStatus.js:318 dirName = 包目录名），
        // 而 AppState.pack 只有 dir，所以这里取末段目录名，不用 pack.name（两者不保证相同）。
        const dirNameOf = (d) => String(d || '').split(/[\\/]/).filter(Boolean).pop() || '';

        // 顶栏「🕘 提交回溯」：此前唯一入口藏在部署面板每行的动作里（views/deployStatus.js:195），
        // 不打开部署面板就找不到。没选中包时也让它开 —— 面板自己有 history.noTarget 提示。
        const btnHistory = document.getElementById('btnOpenHistory');
        if (btnHistory) btnHistory.addEventListener('click', () => {
            if (!window.GitHistoryView) return;
            const pack = window.AppState.pack;
            if (pack && pack.dir) window.GitHistoryView.openDir(dirNameOf(pack.dir), pack.dir);
            else window.GitHistoryView.open();
        });

        // 中栏「📦 打包」：包级入口。此前「打包」只存在于实体/结构/契约三个编辑器内部，
        // 物品/方块与 TimeTape 视图里想打个 jar 只能绕道顶栏部署面板再找那一行。
        // 走部署面板而不是直接调 buildPack：那里才有四态核对与 Gradle 构建输出流。
        const btnPackBuild = document.getElementById('btnPackBuild');
        if (btnPackBuild) btnPackBuild.addEventListener('click', () => {
            const pack = window.AppState.pack;
            if (!pack || !pack.dir || !window.DeployStatus) return;
            window.DeployStatus.open({ buildDirName: dirNameOf(pack.dir) });
        });

        // 内容类型路由：按中栏下拉切换列表加载器（新增类型时在此注册）
        async function loadContentList() {
            const type = document.getElementById('contentTypeSelect').value;
            window.AppState.contentType = type;
            const tools = document.getElementById('structTools');
            if (tools) tools.style.display = (type === 'structure') ? 'flex' : 'none';
            const ibTools = document.getElementById('itemBlockTools');
            if (ibTools) ibTools.style.display = (type === 'item' || type === 'block') ? 'flex' : 'none';
            const qTools = document.getElementById('questTools');
            if (qTools) qTools.style.display = (type === 'quest') ? 'flex' : 'none';
            // 0.2.1：切内容类型时清掉上一次的结构导入/导出报告（此前一直残留）
            if (type !== 'structure') {
                const rep = document.getElementById('structReport');
                if (rep) { rep.style.display = 'none'; rep.textContent = ''; }
            }
            if (type !== 'item' && type !== 'block') {
                const rep = document.getElementById('itemBlockReport');
                if (rep) { rep.style.display = 'none'; rep.textContent = ''; }
            }
            if (type !== 'quest') {
                const rep = document.getElementById('questReport');
                if (rep) { rep.style.display = 'none'; rep.textContent = ''; }
            }
            const pack = window.AppState.pack;
            if (!pack) return;
            // 契约由包声明、编辑器不新建 → 该类型下禁用"+ 新建"
            document.getElementById('btnNewEntity').disabled = (type === 'contract');
            if (type === 'structure') {
                await window.StructureList.loadStructures(pack.dir);
            } else if (type === 'contract') {
                await window.ContractList.load(pack.dir);
            } else if (type === 'item' || type === 'block') {
                await window.ItemBlockList.loadItemsAndBlocks(pack.dir, type);
            } else if (type === 'quest') {
                await window.QuestList.loadQuests(pack.dir);
            } else {
                await window.EntityList.loadEntities(pack.dir);
            }
        }

        // ===== 一键导入原版 / 一键导出整包原版数据（结构类型工具条） =====
        function structReport(lines) {
            const box = document.getElementById('structReport');
            if (!box) return;
            box.style.display = 'block';
            box.textContent = lines.join('\n');
        }

        async function structImportVanilla(dirMode) {
            const t = window.I18n ? window.I18n.t : (k) => k;
            const tf = window.I18n ? window.I18n.tf : (k) => k;
            const pack = window.AppState.pack;
            if (!pack) { window.showError(t('structure.needPack')); return; }
            window.showStatus(t('structure.importing'), true);
            const api = dirMode ? window.api.importStructureVanillaDir : window.api.importStructureVanilla;
            let r;
            try { r = await api(pack.dir); } catch (e) { r = { ok: false, error: String(e && e.message || e) }; }
            const d = (r && r.data) || {};
            if (d.canceled) { window.showStatus('已取消导入', false); return; }
            if (!r || !r.ok) {
                window.showError(t('structure.importDone').replace('{n}', '0').replace('{ns}', '-') + ' ' + ((r && r.error) || ''));
                structReport([
                    '=== 导入原版：失败 ===',
                    '来源: ' + (d.source || '?') + (d.kind ? ' (' + d.kind + ')' : ''),
                    (r && r.error) || '未知错误',
                    ...((d.errors || []).map((x) => '  ✗ ' + x)),
                ]);
                return;
            }
            const written = d.written || [];
            const skipped = d.skipped || [];
            const warnings = d.warnings || [];
            const lines = ['=== 导入原版：完成 ==='];
            lines.push('来源: ' + (d.source || '?') + (d.kind ? ' (' + d.kind + ')' : '') + ' → 命名空间 ' + d.namespace);
            lines.push('写入结构:');
            for (const w of written) {
                const st = w.stats || {};
                lines.push(`  ✔ ${w.id}  · 片段 ${w.pieces} / 池 ${w.pools}` +
                    (st.featureElements ? ` / feature 元素 ${st.featureElements}` : '') +
                    (st.flattenedLists ? ` / 展开列表 ${st.flattenedLists}` : '') +
                    (st.droppedEntities ? ` / 丢弃实体 ${st.droppedEntities}` : '') +
                    (w.importFrom ? `  ← ${w.importFrom}` : '') +
                    (w.warnings ? `  ⚠${w.warnings}` : ''));
            }
            if (skipped.length) {
                lines.push(`跳过 ${skipped.length} 个（蓝图无法表达的非 jigsaw 结构）:`);
                for (const s of skipped.slice(0, 40)) lines.push('  ✗ ' + s.id + '  [' + (s.type || '?') + ']');
                if (skipped.length > 40) lines.push('  … 其余 ' + (skipped.length - 40) + ' 个');
            }
            if (warnings.length) {
                lines.push(`警告 ${warnings.length} 条:`);
                for (const w of warnings.slice(0, 60)) lines.push('  ! ' + w);
                if (warnings.length > 60) lines.push('  … 其余 ' + (warnings.length - 60) + ' 条');
            }
            if ((d.errors || []).length) {
                lines.push(`错误 ${d.errors.length} 条:`);
                for (const e2 of d.errors.slice(0, 30)) lines.push('  ✗ ' + e2);
            }
            structReport(lines);
            let msg = tf('structure.importDone', { n: written.length, ns: d.namespace });
            if (skipped.length) msg += ' · ' + tf('structure.importSkip', { n: skipped.length });
            if (warnings.length) msg += ' · ' + tf('structure.importWarn', { n: warnings.length });
            window.showStatus(msg, true);
            if (window.AppState.contentType === 'structure') {
                await window.StructureList.loadStructures(pack.dir);
            }
        }

        async function structExportVanillaAll() {
            const t = window.I18n ? window.I18n.t : (k) => k;
            const tf = window.I18n ? window.I18n.tf : (k) => k;
            const pack = window.AppState.pack;
            if (!pack) { window.showError(t('structure.needPack')); return; }
            window.showStatus(t('structure.exportingAll'), true);
            let r;
            try { r = await window.api.exportStructureVanillaAll(pack.dir); } catch (e) { r = { ok: false, error: String(e && e.message || e) }; }
            const d = (r && r.data) || {};
            const structures = d.structures || [];
            const fileCount = structures.reduce((a, s) => a + s.files.length, 0);
            const stagedNotExported = d.stagedNotExported || [];
            const lines = ['=== 导出原版数据（整包） ==='];
            if (structures.length) {
                for (const s of structures) {
                    lines.push(`  ✔ ${s.id}  · ${s.files.length} 个文件`);
                    for (const f of s.files) lines.push('      ' + f);
                }
            }
            if (stagedNotExported.length) {
                // 0.2.1：整包导出不含暂存草稿 —— 必须明确列出来，否则用户以为都导出了
                lines.push(`  未导出的草稿 ${stagedNotExported.length} 个（先逐个「导出」）：${stagedNotExported.join(', ')}`);
            }
            if ((d.errors || []).length) {
                lines.push('错误:');
                for (const e2 of d.errors) lines.push('  ✗ ' + e2);
            }
            structReport(lines);
            if (!r || !r.ok) {
                window.showError(tf('structure.exportAllFail', { msg: (r && r.error) || '?' }));
                return;
            }
            window.showStatus(tf('structure.exportAllDone', { n: structures.length, f: fileCount }) +
                (stagedNotExported.length ? `（另有 ${stagedNotExported.length} 个草稿未导出）` : ''), true);
        }

        // ===== 物品/方块：导出整包原版数据 =====
        async function itemBlockExportVanillaAll() {
            const pack = window.AppState.pack;
            if (!pack) { window.showError('请先选择内容包'); return; }
            window.showStatus('导出物品/方块原版文件…', true);
            let r;
            try { r = await window.api.exportItemBlockVanillaAll(pack.dir); } catch (e) { r = { ok: false, error: String(e && e.message || e) }; }
            const d = (r && r.data) || {};
            const rep = document.getElementById('itemBlockReport');
            if (rep) {
                rep.style.display = 'block';
                const lines = ['=== 导出原版（物品/方块 整包） ==='];
                for (const id of d.items || []) lines.push('  ✔ 物品 ' + id);
                for (const id of d.blocks || []) lines.push('  ✔ 方块 ' + id);
                if (d.stagedExported) lines.push(`  （草稿并入正式包：${d.stagedExported} 个）`);
                lines.push(`  文件 ${ (d.files || []).length } 个:`);
                for (const f of (d.files || [])) lines.push('      ' + f);
                for (const p of d.problems || []) lines.push('  ✗ ' + p);
                rep.textContent = lines.join('\n');
            }
            if (!r || !r.ok) {
                window.showError('导出失败: ' + ((r && r.error) || '未知错误'));
                return;
            }
            window.showStatus(`物品 ${(d.items || []).length} / 方块 ${(d.blocks || []).length} 已导出（${(d.files || []).length} 个文件）`, true);
            await loadContentList();
        }

        // ===== 任务：暂存草稿 → 正式包（staging:export 是该包全部草稿一起写） =====
        async function questWriteToPack() {
            const t = window.I18n ? window.I18n.t : (k) => k;
            const tf = window.I18n ? window.I18n.tf : (k) => k;
            const pack = window.AppState.pack;
            if (!pack) { window.showError(t('structure.needPack')); return; }
            window.showStatus(t('quest.exporting'), true);
            let r;
            try { r = await window.api.stagingExport(pack.dir); } catch (e) { r = { ok: false, error: String((e && e.message) || e) }; }
            const d = (r && r.data) || {};
            const files = d.files || [];
            const rep = document.getElementById('questReport');
            if (rep) {
                rep.style.display = 'block';
                const lines = [t('quest.exportTitle')];
                for (const f of files) lines.push('  ✔ ' + f);
                if (!files.length) lines.push('  ' + t('quest.exportedNothing'));
                rep.textContent = lines.join('\n');
            }
            if (!r || !r.ok) {
                window.showError(t('quest.exportFail') + ((r && r.error) || ''));
                return;
            }
            window.showStatus(tf('quest.exported', { n: files.length }), true);
            await loadContentList();
        }

        // ===== 任务：补齐骨架（入口类只插一行 registerQuestsFromJar，绝不整类重生成） =====
        async function questEnsureScaffold() {
            const t = window.I18n ? window.I18n.t : (k) => k;
            const tf = window.I18n ? window.I18n.tf : (k) => k;
            const pack = window.AppState.pack;
            if (!pack) { window.showError(t('structure.needPack')); return; }
            const rep = document.getElementById('questReport');
            if (rep) { rep.style.display = 'block'; rep.textContent = t('quest.scaffoldRunning'); }
            let r;
            try { r = await window.api.ensureScaffold(pack.dir); } catch (e) { r = { ok: false, error: String((e && e.message) || e) }; }
            if (!r || !r.ok) {
                if (rep) rep.textContent = t('quest.scaffoldFail') + ((r && r.error) || '');
                window.showError(t('quest.scaffoldFail') + ((r && r.error) || ''));
                return;
            }
            const d = r.data || {};
            const created = d.created || [];
            const patched = d.patched || [];
            const skipped = d.skipped || [];
            const lines = [t('quest.scaffoldTitle')];
            for (const f of created) lines.push('  + ' + f);
            for (const f of patched) lines.push('  ~ ' + f);
            for (const s of skipped) lines.push('  ! ' + s.file + ' (' + s.reason + ')');
            if (!created.length && !patched.length && !skipped.length) lines.push('  ' + t('quest.scaffoldNothing'));
            // 顺手跑一次预检，把剩下的 E-Q-* 直接列出来（用户不用再去别处找）
            const pre = await window.api.preflightPack(pack.dir);
            if (pre && pre.ok) {
                const q = (pre.data.findings || []).filter((x) => /^[EW]-Q-/.test(x.code));
                lines.push('');
                lines.push(q.length ? tf('quest.scaffoldPreflightN', { n: q.length }) : t('quest.scaffoldPreflightClean'));
                for (const x of q) lines.push('  ' + (x.level === 'error' ? '[ERROR] ' : '[WARN ] ') + x.code + ' ' + x.msg);
            }
            if (rep) rep.textContent = lines.join('\n');
            window.showStatus(tf('quest.scaffoldDone', { c: created.length, p: patched.length }), skipped.length === 0);
        }

        // 自动加载包列表
        await window.PackList.loadPacks();
    }

    // ===== W6.1 包路径设置（包库 / 新建包目录；覆盖默认 <项目根>/contentpacks-source） =====
    // 0.2.1：新增「项目根来源」标记 + 「连接工程…」+「🩺 诊断」。
    // 这里的绑定必须**无条件执行**（不再依赖项目根是否就绪）——反馈机事故根因就是这些
    // 绑定被一个 early return 跳过了，界面看起来正常但所有按钮都没反应。
    function updateRootSourceChip(rootSource) {
        const chip = document.getElementById('rootSourceChip');
        const btn = document.getElementById('btnConnectProject');
        if (!chip || !btn) return;
        const ws = String(rootSource || '').startsWith('workspace');
        if (ws) {
            const t = window.I18n ? window.I18n.t : (k) => k;
            chip.textContent = t('app.workspaceChip');
            chip.className = 'root-chip warn';
            chip.title = '当前没有检测到 mod 工程（含 contentpacks-source 的目录），已自动创建独立工作区，可直接新建/编辑内容包。\n要编辑 mod 里的内容包，点右侧「📁 连接工程…」选择项目根。';
            chip.style.display = '';
            btn.style.display = '';
        } else {
            chip.style.display = 'none';
            btn.style.display = 'none';
        }
    }

    async function copyTextToClipboard(text) {
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                await navigator.clipboard.writeText(text);
                return true;
            }
        } catch { /* 走下面的兜底 */ }
        try {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.top = '-1000px';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            const ok = document.execCommand('copy');
            ta.remove();
            return ok;
        } catch { return false; }
    }

    function formatDiagnostics(d) {
        const L = [
            `=== ContentPack Editor 诊断信息（v${d.version}）===`,
            `打包版: ${d.packaged ? '是' : '否'}  ·  Electron ${d.electron} / Chrome ${d.chrome}`,
            `系统: ${d.os}`,
            `项目根: ${d.root || '（未设置）'}`,
            `项目根来源: ${d.rootSource || '（未知）'}${d.workspaceMode ? '  ← 独立工作区模式（未连接 mod 工程）' : ''}`,
            `包库目录: ${d.packsRoot || '（未设置）'}`,
            `新建包目录: ${d.newPackDir || '（未设置）'}`,
            `配置文件: ${d.configPath}`,
            `用户数据: ${d.userData}`,
            `当前日志: ${d.logFile || '（未初始化）'}`,
            `启动日志: ${d.bootstrapLogFile || '（未初始化）'}`,
            `exe: ${d.executable}`,
            `cwd: ${d.cwd}`,
            `portable 目录: ${d.portableDir || '（非 portable 运行）'}`,
        ];
        return L.join('\n');
    }

    async function showDiagnostics() {
        let d = null;
        try {
            const r = await window.api.getDiagnostics();
            if (r && r.ok) d = r.data;
        } catch (e) {
            d = null;
        }
        if (!d) {
            window.showError('诊断信息读取失败（主进程未响应）');
            return;
        }
        const text = formatDiagnostics(d);
        const ok = await copyTextToClipboard(text);
        if (window.api && window.api.log) window.api.log('info', 'lifecycle', '诊断信息已生成\n' + text);
        if (ok) {
            window.showStatus('诊断信息已复制到剪贴板 —— 直接粘贴给开发者即可（日志: ' + (d.logFile || d.bootstrapLogFile || '?') + '）', true);
            // 同时显示在状态栏下方，便于截图反馈
            const box = document.getElementById('statusBar');
            if (box) box.title = text;
        } else {
            window.showError('剪贴板不可用，请手动复制：' + text.replace(/\n/g, ' | '));
        }
    }

    async function updatePackPathHeader() {
        const el = document.getElementById('headerPaths');
        const btnRoot = document.getElementById('btnPickPacksRoot');
        const btnNew = document.getElementById('btnPickNewPackDir');
        const btnReset = document.getElementById('btnResetPackPaths');
        let pp = null;
        try {
            const r = await window.api.getPackPaths();
            if (r && r.ok) pp = r.data;
        } catch { /* 主进程未就绪（需项目根）时保持原显示 */ }
        if (!pp) return;
        if (window.AppState) window.AppState.rootSource = pp.rootSource || null;
        updateRootSourceChip(pp.rootSource);
        const overridden = !pp.isPacksRootDefault || !pp.isNewPackDirDefault;
        el.textContent = overridden
            ? `包库: ${pp.packsRoot} · 新建包: ${pp.newPackDir}`
            : `源码: ${pp.packsRoot}`;
        btnRoot.title = `包库目录（当前）: ${pp.packsRoot}${pp.isPacksRootDefault ? ' [默认]' : ''} —— 点击选择目录`;
        btnNew.title = `新建包目录（当前）: ${pp.newPackDir}${pp.isNewPackDirDefault ? ' [默认]' : ''} —— 点击选择目录`;
        btnReset.style.display = overridden ? '' : 'none';
    }

    async function refreshAfterPackPaths() {
        await updatePackPathHeader();
        // 0.2.1：包库/新建包目录变了 → 旧选中的包可能已不在允许根内（后续打包会报
        // 「非法的包目录」）。这里清掉选中与右侧编辑器，回到空态。
        try {
            if (window.AppState && window.AppState.pack) {
                window.AppState.pack = null;
                if (window.closeAllEditorsForPackSwitch) window.closeAllEditorsForPackSwitch();
                const lbl = document.getElementById('packNameLabel');
                if (lbl) lbl.textContent = '';
                const ne = document.getElementById('btnNewEntity');
                if (ne) ne.disabled = true;
                const pb = document.getElementById('btnPackBuild');
                if (pb) pb.disabled = true;
                const el = document.getElementById('entityList');
                if (el) el.innerHTML = '';
            }
        } catch { /* 清态失败不影响刷新 */ }
        try { await window.PackList.loadPacks(); } catch { /* 包列表刷新失败不阻断 */ }
    }

    function initPackPathTools(rootSource) {
        const btnRoot = document.getElementById('btnPickPacksRoot');
        const btnNew = document.getElementById('btnPickNewPackDir');
        const btnReset = document.getElementById('btnResetPackPaths');
        const pick = (which, label) => async () => {
            const r = await window.api.pickPackDir(which);
            if (r && r.ok) {
                window.showStatus(`${label}已更新: ${r.data[which === 'packsRoot' ? 'packsRoot' : 'newPackDir']}`, true);
                await refreshAfterPackPaths();
            } else if (r && r.error !== 'cancelled') {
                window.showError(`设置${label}失败: ${r && r.error}`);
            }
        };
        // 0.2.1：逐项绑定（缺元素只跳过自己，一个都不影响其它绑定）
        if (btnRoot) btnRoot.onclick = pick('packsRoot', '包库目录');
        if (btnNew) btnNew.onclick = pick('newPackDir', '新建包目录');
        if (btnReset) btnReset.onclick = async () => {
            const r = await window.api.setPackPaths({ packsRoot: null, newPackDir: null });
            if (r && r.ok) {
                window.showStatus('包路径已复位为默认（<项目根>/contentpacks-source）', true);
                await refreshAfterPackPaths();
            } else if (r) {
                window.showError('复位失败: ' + r.error);
            } else {
                window.showError('复位失败：主进程未响应');
            }
        };
        // 0.2.1：连接 mod 工程（独立工作区 → 真工程）
        const btnConnect = document.getElementById('btnConnectProject');
        if (btnConnect) btnConnect.onclick = async () => {
            const r = await window.api.pickProjectRoot();
            if (r && r.ok) {
                window.showStatus('已连接工程: ' + r.data.root + '，正在重载…', true);
                location.reload();
            } else if (r && r.error !== 'cancelled') {
                window.showError('连接工程失败: ' + r.error);
            } else if (!r) {
                window.showError('连接工程失败：主进程未响应');
            }
        };
        // 0.2.1：诊断信息（反馈闭环）
        const btnDiag = document.getElementById('btnDiagnostics');
        if (btnDiag) btnDiag.onclick = () => { showDiagnostics(); };
        updateRootSourceChip(rootSource);
        updatePackPathHeader();
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
