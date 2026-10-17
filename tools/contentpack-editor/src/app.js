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

    // 实体/结构/契约/状态机四个编辑器框架的显隐协调。
    // 多个框架同时有活跃内容时，按 AppState.activeFrame（最近一次打开的框架）决定显示哪个。
    window.applyEditorVisibility = function () {
        const structActive = window.StructureEditor && window.StructureEditor.hasActiveTab();
        const entityActive = window.EntityEditor && window.EntityEditor.hasActiveTab();
        const contractActive = window.ContractEditor && window.ContractEditor.hasActiveTab();
        const tapeActive = window.TapeEditor && window.TapeEditor.hasActiveTab();
        const frame = window.AppState && window.AppState.activeFrame;
        let show = null;
        if (frame === 'tape' && tapeActive) show = 'tape';
        else if (frame === 'contract' && contractActive) show = 'contract';
        else if (frame === 'structure' && structActive) show = 'structure';
        else if (frame === 'entity' && entityActive) show = 'entity';
        else if (structActive) show = 'structure';
        else if (entityActive) show = 'entity';
        else if (contractActive) show = 'contract';
        else if (tapeActive) show = 'tape';

        const empty = document.getElementById('editorEmpty');
        const ent = document.getElementById('editorContent');
        const str = document.getElementById('structureEditorContent');
        const ctr = document.getElementById('contractEditorContent');
        const tape = document.getElementById('tapeEditorContent');
        if (show) empty.classList.add('hidden'); else empty.classList.remove('hidden');
        ent.classList.toggle('hidden', show !== 'entity');
        if (str) str.classList.toggle('hidden', show !== 'structure');
        if (ctr) ctr.classList.toggle('hidden', show !== 'contract');
        if (tape) tape.classList.toggle('hidden', show !== 'tape');
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
        function updateGpuBtn(on) {
            if (!btnGpu) return;
            btnGpu.textContent = on ? '🐢 软件渲染' : '⚡ 硬件加速';
            btnGpu.title = on
                ? '当前软件渲染（绕过 GPU 驱动，3D 略慢但稳定）。点击切回硬件加速（重启生效）。'
                : '当前硬件加速。若频繁闪退/崩溃，可切软件渲染（重启生效）。';
        }
        if (btnGpu) {
            btnGpu.addEventListener('click', async () => {
                try {
                    const cur = await window.api.getSettings();
                    const on = !(cur && cur.ok && cur.data && cur.data.softwareRendering);
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
            if (!btnDebug) return;
            btnDebug.textContent = on ? '🔍 调试中' : '🔍 调试';
            btnDebug.title = on
                ? '调试模式已开启（Chromium 日志 → logs/contentpack-editor/chromium.log + DevTools 自动打开 + 崩溃转储）。点击关闭（重启生效）。'
                : '开启调试模式：抓取 Chromium 内部日志 + 自动打开 DevTools + 原生崩溃转储（重启生效）。';
        }
        if (btnDebug) {
            btnDebug.addEventListener('click', async () => {
                try {
                    const cur = await window.api.getSettings();
                    const on = !(cur && cur.ok && cur.data && cur.data.debugMode);
                    const r = await window.api.setDebugMode(on);
                    if (!r || !r.ok) { window.showError && window.showError('设置失败: ' + (r && r.error)); return; }
                    updateDebugBtn(on);
                    window.api.relaunchApp(); // 重启生效
                } catch (e) { console.error('调试模式切换失败:', e); }
            });
        }
        try {
            const st0 = await window.api.getSettings();
            if (st0 && st0.ok && st0.data) updateDebugBtn(!!st0.data.debugMode);
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
            });
            window.I18n.applyDOM();
        } else {
            langSel.style.display = 'none';
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
        window.PackList.init(async (pack) => {
            window.AppState.pack = pack;
            document.getElementById('packNameLabel').textContent = pack.name + ' v' + pack.version;
            document.getElementById('btnNewEntity').disabled = false;
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
                if (window.AppState.contentType === 'structure') {
                    window.AppState.activeFrame = 'structure';
                    window.StructureEditor.open({ pack: window.AppState.pack, structure: null, isNew: true });
                } else if (window.AppState.contentType === 'entity') {
                    window.AppState.activeFrame = 'entity';
                    window.EntityEditor.open({ pack: window.AppState.pack, entity: null, isNew: true });
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

        // 一键导入原版 / 一键导出整包原版数据
        const btnImport = document.getElementById('btnStructImport');
        const btnImportDir = document.getElementById('btnStructImportDir');
        const btnExportAll = document.getElementById('btnStructExportAll');
        if (btnImport) btnImport.addEventListener('click', () => structImportVanilla(false));
        if (btnImportDir) btnImportDir.addEventListener('click', () => structImportVanilla(true));
        if (btnExportAll) btnExportAll.addEventListener('click', () => structExportVanillaAll());

        // 动画契约按钮（右栏框架）：刷新 = 重读契约 + 重盘点；打包 = 重建该包 jar
        document.getElementById('btnContractRefresh').addEventListener('click', () => window.ContractEditor.refresh());
        document.getElementById('btnContractBuild').addEventListener('click', () => window.ContractEditor.build());

        // 内容类型路由：按中栏下拉切换列表加载器（新增类型时在此注册）
        async function loadContentList() {
            const type = document.getElementById('contentTypeSelect').value;
            window.AppState.contentType = type;
            const tools = document.getElementById('structTools');
            if (tools) tools.style.display = (type === 'structure') ? 'flex' : 'none';
            const pack = window.AppState.pack;
            if (!pack) return;
            // 契约由包声明、编辑器不新建 → 该类型下禁用"+ 新建"
            document.getElementById('btnNewEntity').disabled = (type === 'contract');
            if (type === 'structure') {
                await window.StructureList.loadStructures(pack.dir);
            } else if (type === 'contract') {
                await window.ContractList.load(pack.dir);
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
            const lines = ['=== 导出原版数据（整包） ==='];
            if (structures.length) {
                for (const s of structures) {
                    lines.push(`  ✔ ${s.id}  · ${s.files.length} 个文件`);
                    for (const f of s.files) lines.push('      ' + f);
                }
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
            window.showStatus(tf('structure.exportAllDone', { n: structures.length, f: fileCount }), true);
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
                window.showError(`设置${label}失败: ${r.error}`);
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
