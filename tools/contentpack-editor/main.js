// main.js - Electron 主进程
// 负责：窗口创建 + 所有文件系统操作（读写包/实体/模型 + 调打包脚本）
// 渲染进程不接触 Node API（contextIsolation），只通过 preload 暴露的 window.api 通信
const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const http = require('http');
const { spawn, execSync } = require('child_process');
const os = require('os');
const Logger = require('./logger');
const ProjectRoot = require('./src/core/projectRoot');

// 显式设置应用名：打包后 portable exe 的默认 userData 名可能退化为 "Electron"
app.setName('ContentPack Editor');

// ==================== 0.2.1：启动即落日志（userData） ====================
// 反馈机事故复盘：日志原先在"项目根就绪"后才初始化（Logger.init(ROOT)），
// 但反馈机恰恰死在项目根判定这一步 → 整个失败过程一行日志都没有，没法远程定位。
// 现在启动第一秒就把日志写到 <userData>/logs/contentpack-editor/editor.log，
// 项目根确定后再切换（切换原因/前后路径都记一条），失败也有证据。
let BOOTSTRAP_LOG_FILE = null;
try {
    if (Logger.init(app.getPath('userData'))) {
        BOOTSTRAP_LOG_FILE = Logger.getLogFile();
        Logger.info('boot', `ContentPack Editor v${app.getVersion()} 启动 · 打包=${app.isPackaged ? '是' : '否'} · exe=${process.execPath} · userData=${app.getPath('userData')}`);
    }
} catch { /* 日志不可用绝不能阻断启动 */ }

// W5.3: GPU 崩溃自愈——设置里启用「软件渲染」（用户手动切换，或 renderer 10 分钟内
// 崩 2 次自动降级写入）→ 启动即强制软件渲染：--disable-gpu 让 WebGL 走 SwiftShader、
// 合成走 CPU，彻底绕开 GPU 驱动（本机 renderer 成组崩溃是长期驱动级故障，逐窗口
// 重载治不了）。appendSwitch 必须在 app ready 前调用。
{
    const _initCfg = readConfig();
    if (_initCfg.softwareRendering) app.commandLine.appendSwitch('disable-gpu');
}

// W5.5: 调试模式——Chromium 内部日志（GPU/合成/V8/utility）→ <项目根>/logs/contentpack-editor/chromium.log。
// 背景：renderer 崩溃无 Crashpad 报告、无 WER 事件（外部终止特征）→ 需要 Chromium 级日志抓崩溃前最后的活动。
{
    if (readConfig().debugMode) {
        const root = tryResolveProjectRoot();
        const logFile = root
            ? path.join(root, 'logs', 'contentpack-editor', 'chromium.log')
            : path.join(app.getPath('userData'), 'chromium.log');
        app.commandLine.appendSwitch('enable-logging');
        app.commandLine.appendSwitch('log-file', logFile);
    }
}

// ==================== 核心库（纯 JS，可被 node 测试） ====================
const ManifestLib = require('./src/core/manifest');
const ModelParser = require('./src/core/modelParser');
const EntitySchema = require('./src/core/entitySchema');
const HealthConfig = require('./src/core/healthConfig');
const StateMachine = require('./src/core/stateMachine');
const Staging = require('./src/core/staging');
const StructureSchema = require('./src/core/structureSchema');
const VanillaExport = require('./src/core/vanillaExport');
const VanillaImport = require('./src/core/vanillaImport');
const RegionImport = require('./src/core/regionImport');
const McAssets = require('./src/core/mcAssets');
const McBlocks = require('./src/core/mcBlocks');
const Scaffold = require('./src/core/packScaffold');
const Preflight = require('./src/core/packPreflight');
const JarReader = require('./src/core/jarReader');
const EditorContract = require('./src/core/editorContract');
const EditorApi = require('./src/core/editorApi');
const SparkScaffold = require('./model-editor/core/sparkScaffold');

// ==================== 项目根解析（支持打包后 exe 运行） ====================
// 优先级：① 内部配置（userData/config.json）→ ② 自动向上探测 contentpacks-source/
//        → ③ 独立工作区（0.2.1 新增：<文档>/Content Pack Editor，兜底 <userData>/…）
//        → ④ 全失败才让窗口内引导选择（app:setProjectRoot 保存）
// 打包为 portable 单文件 exe 后 __dirname 指向 asar 内部，不能用它定位项目根。
let ROOT = null;
/** 本次会话项目根来源：'config' | 'auto' | 'workspace-documents' | 'workspace-userdata' | null */
let ROOT_SOURCE = null;
let PACKS_SOURCE_DIR = null;
let NEW_PACK_DIR = null; // W6.1：新建包目录（默认 = 包库目录；config.newPackDir 可覆盖）
let MOD_RES_DIR = null; // 主 mod 资源目录（src/main/resources；仓库贴图浏览用）
let PACKS_OUT_DIR = null;
let BUILD_SCRIPT = null;

function configFilePath() {
    return path.join(app.getPath('userData'), 'config.json');
}

function readConfig() {
    try {
        const file = configFilePath();
        if (fs.existsSync(file)) {
            // 剥 UTF-8 BOM（记事本等编辑器保存会带 BOM，直接 JSON.parse 会失败 → 配置被静默清空）
            return JSON.parse(fs.readFileSync(file, 'utf-8').replace(/^\uFEFF/, ''));
        }
    } catch {}
    return {};
}

function saveConfig(partial) {
    try {
        const cfg = Object.assign(readConfig(), partial);
        fs.mkdirSync(app.getPath('userData'), { recursive: true });
        fs.writeFileSync(configFilePath(), JSON.stringify(cfg, null, 2), 'utf-8');
    } catch (e) {
        console.error('[config] save failed:', e.message);
    }
}

/** 从某目录向上探测，找含 contentpacks-source 的项目根 */
function detectProjectRootFrom(startDir) {
    let dir = startDir;
    for (let i = 0; i < 8; i++) {
        if (!dir) break;
        if (fs.existsSync(path.join(dir, 'contentpacks-source')) &&
            fs.existsSync(path.join(dir, 'contentpacks'))) {
            return dir;
        }
        const parent = path.dirname(dir);
        if (parent === dir) break;
        dir = parent;
    }
    return null;
}

/** 尝试解析项目根（配置优先，其次探测）；返回 null 表示需要用户选择 */
function tryResolveProjectRoot() {
    // ① 配置
    const cfg = readConfig();
    if (cfg.projectRoot && fs.existsSync(path.join(cfg.projectRoot, 'contentpacks-source'))) {
        return cfg.projectRoot;
    }
    // ② 自动探测：exe 实际目录（portable 版会设 PORTABLE_EXECUTABLE_DIR）/ exe 所在目录 / 当前工作目录
    //    portable 单文件 exe 运行时 cwd 会被解压 stub 重置，PORTABLE_EXECUTABLE_DIR 才是用户放置 exe 的真实位置
    const candidates = [
        process.env.PORTABLE_EXECUTABLE_DIR,
        path.dirname(process.execPath),
        process.cwd(),
        __dirname,
    ].filter(Boolean);
    for (const c of candidates) {
        const found = detectProjectRootFrom(c);
        if (found) {
            saveConfig({ projectRoot: found });
            return found;
        }
    }
    return null;
}

/**
 * 0.2.1：解析项目根并**保证有结果**（无工程 → 独立工作区）。
 * 之前的失败形态：tryResolveProjectRoot() 返回 null → 渲染端引导选目录后 return，
 * 后续所有按钮绑定被跳过 → 反馈机上「包库 / 新建包 / +新建」全死、顶栏连"源码: …"都没有。
 * @returns {{root:string|null, source:string|null, created:string[], error?:string}}
 */
function resolveRootWithFallback() {
    const r = ProjectRoot.resolveProjectRoot({
        configRoot: (readConfig().projectRoot || null),
        detectRoot: tryResolveProjectRoot,
        documentsDir: app.getPath('documents'),
        userDataDir: app.getPath('userData'),
    });
    if (r && r.root) return r;
    // 极少数情况（文档目录与 userData 都不可写）：退到临时目录，仍然保证有根
    try {
        const tmpWs = ProjectRoot.ensureWorkspace(fs, os.tmpdir());
        if (tmpWs) return { root: tmpWs.root, source: 'workspace-temp', created: tmpWs.created };
    } catch { /* 忽略 */ }
    return r || { root: null, source: null, created: [], error: '项目根解析失败' };
}

/** W6.1：配置里的包路径覆盖值是否可用（存在且是目录；否则回退默认） */
function validOverrideDir(p) {
    return !!(p && typeof p === 'string' && fs.existsSync(p) && fs.statSync(p).isDirectory());
}

function applyProjectRoot(root, source) {
    if (source) ROOT_SOURCE = source;
    ROOT = root;
    // W6.1：包库/新建包目录可被配置覆盖（默认 <项目根>/contentpacks-source）
    const cfg = readConfig();
    const defaultPacksRoot = path.join(root, 'contentpacks-source');
    PACKS_SOURCE_DIR = validOverrideDir(cfg.packsRoot) ? path.resolve(cfg.packsRoot) : defaultPacksRoot;
    NEW_PACK_DIR = validOverrideDir(cfg.newPackDir) ? path.resolve(cfg.newPackDir) : PACKS_SOURCE_DIR;
    PACKS_OUT_DIR = path.join(ROOT, 'contentpacks');
    BUILD_SCRIPT = path.join(ROOT, 'contentpacks-source', 'build-pack.ps1');
    MOD_RES_DIR = path.join(ROOT, 'src', 'main', 'resources');
    if (Logger.init(root)) {
        Logger.info('config', `项目根就绪: ${root} · 来源=${ROOT_SOURCE || '?'}（日志: ${Logger.getLogFile()}）`);
        if (BOOTSTRAP_LOG_FILE && BOOTSTRAP_LOG_FILE !== Logger.getLogFile()) {
            Logger.info('config', `日志已从启动日志切换过来（启动日志: ${BOOTSTRAP_LOG_FILE}）`);
        }
    }
    if (String(ROOT_SOURCE || '').startsWith('workspace')) {
        Logger.warn('config', '未找到 mod 工程（含 contentpacks-source 的目录）→ 已启用独立工作区；' +
            '可正常新建/编辑内容包，顶栏「连接工程…」可随时切到真工程');
    }
    startEditorApi(); // 项目根就绪后自动开启 AI API 监听
}

// ==================== W3.6 崩溃钩子与日志 IPC ====================
// 崩溃/未处理异常 → 先落盘（尽量留最后证据）→ 通知用户
process.on('uncaughtException', (e) => {
    Logger.error('crash', 'uncaughtException: ' + ((e && (e.stack || e.message)) || String(e)));
    try {
        dialog.showErrorBox(
            'ContentPack Editor — 致命错误',
            `未处理的异常：${(e && e.message) || e}\n\n已记录日志：\n${Logger.getLogFile() || '（日志未初始化，见控制台）'}`,
        );
    } catch { /* 对话框失败不二次抛出 */ }
    app.quit();
});
process.on('unhandledRejection', (e) => {
    Logger.error('crash', 'unhandledRejection: ' + ((e && (e.stack || e.message)) || String(e)));
});
const appStartMs = Date.now();
app.on('render-process-gone', (event, details) => {
    // W5.5：崩溃现场快照——运行时长 + 渲染模式 + 调试模式（配合 chromium.log / 转储定位）
    const cfg = readConfig();
    Logger.error('crash', `render-process-gone: reason=${details.reason} exitCode=${details.exitCode}（渲染进程崩溃/被杀）` +
        ` ｜ 运行 ${Math.round((Date.now() - appStartMs) / 1000)}s · 渲染=${cfg.softwareRendering ? '软件' : '硬件'} · 调试=${cfg.debugMode ? '开' : '关'}`);
});
app.on('child-process-gone', (event, details) => {
    Logger.error('crash', `child-process-gone: type=${details.type} reason=${details.reason} exitCode=${details.exitCode}`);
});
app.on('gpu-process-crashed', (event, killed) => {
    Logger.error('crash', 'gpu-process-crashed（killed=' + killed + '；3D 视口可能失效，重启应用）');
});

// 渲染端日志（fire-and-forget：日志永不阻塞/失败 UI）
ipcMain.on('app:log', (event, p) => {
    try {
        if (!p || typeof p.msg !== 'string') return;
        const level = p.level === 'error' ? 'error' : (p.level === 'warn' ? 'warn' : 'info');
        const scope = 'renderer:' + (p.scope || 'main-window');
        Logger.log(level, scope, p.msg);
    } catch { /* 日志路径自身容错 */ }
});
ipcMain.handle('app:getLogInfo', () => {
    return { ok: true, data: { dir: Logger.getLogDir(), file: Logger.getLogFile(), bootstrapFile: BOOTSTRAP_LOG_FILE } };
});
ipcMain.handle('app:openLogDir', async () => {
    // 0.2.1：项目根解析失败时日志仍在启动位置（userData）——打开它而不是报"未初始化"
    const dir = Logger.getLogDir() || path.join(app.getPath('userData'), 'logs', 'contentpack-editor');
    if (!fs.existsSync(dir)) {
        try { fs.mkdirSync(dir, { recursive: true }); } catch { /* 建不出来由 openPath 报错 */ }
    }
    try {
        const err = await shell.openPath(dir);
        if (err) return { ok: false, error: err };
        return { ok: true };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// ==================== 0.2.1：诊断信息（反馈闭环用） ====================
// 反馈同学点顶栏「🩺 诊断」→ 复制这段文本发过来，即可判断是工程根/工作区、日志在哪、
// 是否打包版，省去反复猜测（本次事故就是"没有日志也没有路径信息"才绕了远路）。
ipcMain.handle('app:diagnostics', () => {
    try {
        return {
            ok: true,
            data: {
                name: 'ContentPack Editor',
                version: app.getVersion(),
                packaged: app.isPackaged,
                electron: process.versions.electron,
                chrome: process.versions.chrome,
                node: process.versions.node,
                os: `${os.type()} ${os.release()} ${process.arch}`,
                root: ROOT,
                rootSource: ROOT_SOURCE,
                workspaceMode: String(ROOT_SOURCE || '').startsWith('workspace'),
                packsRoot: PACKS_SOURCE_DIR,
                newPackDir: NEW_PACK_DIR,
                packsOutDir: PACKS_OUT_DIR,
                configPath: configFilePath(),
                userData: app.getPath('userData'),
                logFile: Logger.getLogFile(),
                bootstrapLogFile: BOOTSTRAP_LOG_FILE,
                executable: process.execPath,
                cwd: process.cwd(),
                portableDir: process.env.PORTABLE_EXECUTABLE_DIR || null,
            },
        };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// ==================== W5.3: 渲染模式设置（软件渲染自愈） ====================
ipcMain.handle('settings:get', () => {
    const cfg = readConfig();
    return { ok: true, data: { softwareRendering: !!cfg.softwareRendering, softwareRenderingAuto: !!cfg.softwareRenderingAuto, debugMode: !!cfg.debugMode } };
});
ipcMain.handle('settings:setSoftwareRendering', (event, on) => {
    try {
        saveConfig({ softwareRendering: !!on, softwareRenderingAuto: false, reopenModelEditorAfterRelaunch: false });
        Logger.info('config', '渲染模式设为: ' + (on ? '软件渲染（--disable-gpu，重启后生效）' : '硬件加速（重启后生效）'));
        return { ok: true };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});
// 「崩溃后自动降级」通知：主窗口启动时消费一次（弹横幅 + 自动重开模型编辑器）。
// W5.5：reopen（重启前模型编辑器开着 → 自动重开，手动切换重启也适用）与
//       banner（仅崩溃自动降级时显示"检测到崩溃"文案）解耦。
ipcMain.handle('settings:consumeFallbackNotice', () => {
    const cfg = readConfig();
    const notice = {
        reopen: !!cfg.reopenModelEditorAfterRelaunch,
        banner: !!(cfg.gpuFallbackNotice || cfg.softwareRenderingAuto),
        softwareRendering: !!cfg.softwareRendering,
    };
    saveConfig({ reopenModelEditorAfterRelaunch: false, gpuFallbackNotice: false });
    return { ok: true, data: notice };
});

// W5.5: 调试模式（chromium 日志 + DevTools 自动打开 + 原生崩溃转储；日志开关需重启生效）
ipcMain.handle('settings:setDebugMode', (event, on) => {
    try {
        saveConfig({ debugMode: !!on });
        const dumpKey = 'HKCU\\Software\\Microsoft\\Windows\\Windows Error Reporting\\LocalDumps\\' + path.basename(process.execPath);
        if (!on) {
            try { execSync(`reg delete "${dumpKey}" /f`, { stdio: 'ignore' }); } catch { /* 键不存在 = 已清 */ }
        } else {
            const root = tryResolveProjectRoot();
            if (root) {
                const dumpDir = path.join(root, 'logs', 'contentpack-editor', 'dumps');
                try { fs.mkdirSync(dumpDir, { recursive: true }); } catch { /* 忽略 */ }
                execSync(`reg add "${dumpKey}" /v DumpType /t REG_DWORD /d 2 /f`, { stdio: 'ignore' });
                execSync(`reg add "${dumpKey}" /v DumpFolder /t REG_SZ /d "${dumpDir}" /f`, { stdio: 'ignore' });
            }
        }
        Logger.info('config', '调试模式设为: ' + (on ? '开（chromium.log + DevTools 自动打开 + 崩溃转储，重启生效）' : '关'));
        return { ok: true };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});
// 重启应用（W5.3），踩坑记录：
// ① app.exit(0) 会跳过退出流程导致 app.relaunch() 不生效 → 必须 app.quit()。
// ② portable 单文件版不能用 app.relaunch()：新实例会在旧实例释放单实例锁之前
//    启动，requestSingleInstanceLock() 失败 → 走 !gotLock 分支静默退出（实测：
//    relaunch 后无任何新实例、无日志）。解决：spawn 用户目录里的 portable stub
//    （PORTABLE_EXECUTABLE_DIR）——新 stub 要 ~25s 解压才会启动 Electron，
//    那时旧实例早已退出、锁已释放，零竞态。
// ③ 重启前绕过暂存草稿弹窗（草稿在磁盘暂存区，重启不丢，等价于"保留草稿"分支）。
let forceQuitNoPrompt = false;
function relaunchAppProcess() {
    try {
        forceQuitNoPrompt = true;
        let spawned = false;
        const stubDir = process.env.PORTABLE_EXECUTABLE_DIR;
        if (stubDir) {
            const stub = path.join(stubDir, app.getName() + ' ' + app.getVersion() + '.exe');
            if (fs.existsSync(stub)) {
                const child = spawn(stub, process.argv.slice(1), { detached: true, stdio: 'ignore' });
                child.unref();
                spawned = true;
                Logger.info('config', '重启：已启动新实例 ' + stub);
            }
        }
        if (!spawned) app.relaunch(); // 非 portable（win-unpacked / 开发）
        // W5.5：重启前模型编辑器开着 → 重启后自动重开（W5.2 自动恢复最后模型）——
        // 手动切换渲染模式/调试模式时不再"丢窗口"
        try {
            if (modelEditorWin && !modelEditorWin.isDestroyed()) saveConfig({ reopenModelEditorAfterRelaunch: true });
        } catch { /* 忽略 */ }
        setTimeout(() => app.quit(), 500); // 留时间让子进程 spawn 完成
        return { ok: true };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
}
ipcMain.handle('app:relaunch', () => relaunchAppProcess());

// W4.8: renderer 崩溃 → 自动重载（此前 renderer 崩溃只留冻屏白窗口，被误认为"功能消失/界面空了"；
// 60s 滑窗内 3 次崩溃则停止自动重载，防重载死循环——GPU 类故障需重启应用）
const rendererCrashTimes = new WeakMap();

// W5.3: 应用级崩溃计数——10 分钟内 2 次 crashed/oom → 自动降级软件渲染并重启整个应用
// （纯逻辑在 src/core/crashGuard.js，node 可测；降级配置持久化，重启后仍生效，
// 用户可在主窗口顶栏手动切回硬件加速）
const CrashGuard = require('./src/core/crashGuard');
const crashTracker = CrashGuard.makeCrashTracker({ windowMs: 10 * 60 * 1000, threshold: 2 });
function maybeAutoFallbackToSoftware(details) {
    if (!crashTracker.record(details && details.reason)) return;
    if (readConfig().softwareRendering) { crashTracker.reset(); return; } // 已是软件渲染：不再降级
    const reopenEditor = !!(modelEditorWin && !modelEditorWin.isDestroyed());
    try {
        saveConfig({ softwareRendering: true, softwareRenderingAuto: true, gpuFallbackNotice: true, reopenModelEditorAfterRelaunch: reopenEditor });
    } catch { /* 配置写失败不阻塞重启 */ }
    Logger.error('crash', '10 分钟内 renderer 崩溃 2 次（reason=' + (details && details.reason) +
        '）→ 自动启用软件渲染（--disable-gpu）并重启应用' +
        (reopenEditor ? '（重启后自动重开模型编辑器，最后模型自动恢复）' : ''));
    setTimeout(() => {
        const r = relaunchAppProcess();
        if (!r.ok) Logger.error('crash', '自动重启失败: ' + r.error);
    }, 300);
}

function guardRendererReload(win, label) {
    win.webContents.on('render-process-gone', (e, details) => {
        maybeAutoFallbackToSoftware(details);
        if (win.isDestroyed()) return;
        const now = Date.now();
        const arr = (rendererCrashTimes.get(win) || []).filter((t) => now - t < 60000);
        arr.push(now);
        rendererCrashTimes.set(win, arr);
        if (arr.length < 3) {
            Logger.error('crash', `${label} renderer 崩溃（reason=${details.reason} exitCode=${details.exitCode}）→ 自动重载`);
            try { win.webContents.reload(); } catch (err) { Logger.error('crash', `${label} 重载失败: ${err.message}`); }
        } else {
            Logger.error('crash', `${label} renderer 60s 内已崩溃 ${arr.length} 次（reason=${details.reason}）→ 停止自动重载，请重启应用`);
        }
    });
}

// W5.5: 调试模式——所有窗口加载完成自动开 DevTools（崩溃重载后 did-finish-load 重发 → 自动重开）
function maybeOpenDevTools(win) {
    try {
        if (readConfig().debugMode) {
            win.webContents.on('did-finish-load', () => {
                try { if (!win.isDestroyed()) win.webContents.openDevTools({ mode: 'right' }); } catch { /* 忽略 */ }
            });
        }
    } catch { /* 忽略 */ }
}

let mainWindow = null;

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1400,
        height: 900,
        title: 'ContentPack Editor',
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: false,
        },
    });
    mainWindow.loadFile(path.join(__dirname, 'src', 'index.html'));
    guardRendererReload(mainWindow, '主窗口');
    maybeOpenDevTools(mainWindow); // W5.5
    // 开发时打开 devtools
    // mainWindow.webContents.openDevTools();
}

// ==================== P11 片段 3D 编辑器窗口（独立窗口，Photoshop/Blender 式布局） ====================
// 数据流：主窗口 tab 是唯一事实源 → 打开时把 blueprint 推给编辑器窗口；
// 编辑器窗口内存中编辑 → "写回"（或关窗前自动写回）→ 主窗口合并进 tab（保存草稿时落盘）。
let pieceEditorWin = null;
let pieceEditorPending = null; // 等待页面加载完成后投递的 payload（防 send 早于渲染脚本注册的竞态）
// P14 未写回修改跟踪（关窗提示）：渲染端每次编辑变化推送计数
let editorDirtyCount = 0;
let editorForceClose = false;
let editorFlushForClose = false; // 仅「写回并关闭」流程的 flush 才在 flushed 后强关窗口

function deliverEditorPayload() {
    if (pieceEditorWin && !pieceEditorWin.isDestroyed() && pieceEditorPending) {
        pieceEditorWin.webContents.send('pieceEditor:load', pieceEditorPending);
        pieceEditorPending = null;
    }
}

function createPieceEditorWindow() {
    pieceEditorWin = new BrowserWindow({
        width: 1180,
        height: 800,
        title: '片段 3D 编辑器',
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: false,
        },
    });
    pieceEditorWin.loadFile(path.join(__dirname, 'src', 'piece-editor.html'));
    // 持久监听：页面（重新）加载完成即投递 pending payload（投递后置 null，重复事件无害）
    pieceEditorWin.webContents.on('did-finish-load', deliverEditorPayload);
    // P14 关窗提示：有未写回修改 → 先询问（取消 / 写回并关闭 / 不保存关闭）
    pieceEditorWin.on('close', (e) => {
        if (editorDirtyCount > 0 && !editorForceClose) {
            e.preventDefault();
            dialog.showMessageBox(pieceEditorWin, {
                type: 'warning',
                title: '未写回的修改',
                message: '有 ' + editorDirtyCount + ' 个片段有未写回的修改。',
                detail: '「写回并关闭」会把修改同步到主窗口（保存草稿时落盘）；\n「不保存关闭」将丢失这些修改。',
                buttons: ['取消', '写回并关闭', '不保存关闭'],
                defaultId: 0,
                cancelId: 0,
            }).then((r) => {
                if (!pieceEditorWin || pieceEditorWin.isDestroyed()) return;
                if (r.response === 1) {
                    editorFlushForClose = true;
                    pieceEditorWin.webContents.send('pieceEditor:flush'); // 渲染端写回后回 pieceEditor:flushed
                    // 兜底：渲染端写回抛错时不会回 flushed → 1.5s 后强制关闭，避免窗口关不掉
                    setTimeout(() => {
                        if (!pieceEditorWin || pieceEditorWin.isDestroyed() || !editorFlushForClose) return;
                        editorFlushForClose = false;
                        editorForceClose = true;
                        pieceEditorWin.close();
                    }, 1500);
                } else if (r.response === 2) {
                    editorForceClose = true;
                    pieceEditorWin.close();
                }
            });
        }
    });
    pieceEditorWin.on('closed', () => {
        pieceEditorWin = null;
        pieceEditorPending = null;
        editorDirtyCount = 0;
        editorForceClose = false;
        editorFlushForClose = false;
    });
    maybeOpenDevTools(pieceEditorWin); // W5.5
    return pieceEditorWin;
}

// P14：渲染端推送未写回修改计数（编辑/写回/撤销后）
ipcMain.on('pieceEditor:dirty', (event, n) => {
    editorDirtyCount = Math.max(0, n | 0);
});
// P14：flush 完成回报 —— 仅「写回并关闭」流程放行关闭；
// 重开前的预 flush（见 pieceEditor:open）不关窗（由 webContents.once 投递 payload）
ipcMain.on('pieceEditor:flushed', () => {
    // pieceEditor:open 的「先 flush 再投递」分支：写回完成后立即投递排队的新目标（1500ms 兜底仍在）
    if (pieceEditorPending) deliverEditorPayload();
    if (!editorFlushForClose) return;
    editorFlushForClose = false;
    if (pieceEditorWin && !pieceEditorWin.isDestroyed()) {
        editorForceClose = true;
        pieceEditorWin.close();
    }
});

// 打开/聚焦编辑器窗口并推送蓝图（payload: {packDir, structureId, pieceName, blueprint}）
// P14：已加载且窗口有未写回修改 → 先 flush（同步写回主窗口）再投递 payload，
// 否则 payload 是写回前的旧蓝图 → 刚写回的修改从视图消失（主窗口内存其实已合并）。
ipcMain.handle('pieceEditor:open', (event, payload) => {
    try {
        if (!pieceEditorWin || pieceEditorWin.isDestroyed()) {
            pieceEditorPending = payload;
            createPieceEditorWindow(); // 页面加载完成后由 did-finish-load 投递
        } else {
            const win = pieceEditorWin;
            if (editorDirtyCount > 0 && !win.webContents.isLoading()) {
                // 关键：新目标 payload 必须先在 pending 里排队——flush 完成（或 1500ms 兜底）后
                // 投递的就是它。此前这里没赋值 → 标题换了、画面还是旧片段，新 payload 永久丢失。
                pieceEditorPending = payload;
                win.webContents.send('pieceEditor:flush');
                setTimeout(deliverEditorPayload, 1500); // 渲染端异常兜底：超时强制投递
            } else {
                pieceEditorPending = payload;
                if (!win.webContents.isLoading()) deliverEditorPayload(); // 已加载 → 立即投递
            }
        }
        if (!pieceEditorWin || pieceEditorWin.isDestroyed()) return { ok: false, error: '窗口创建失败' };
        pieceEditorWin.setTitle('片段 3D 编辑器 — ' + (payload.structureId || '?') + (payload.pieceName ? ' / ' + payload.pieceName : ''));
        pieceEditorWin.show();
        pieceEditorWin.focus();
        return { ok: true };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 编辑器窗口 → 主窗口：编辑写回（转发给所有非源窗口）
ipcMain.on('pieceEditor:saveEdits', (event, payload) => {
    const srcId = event.sender.id;
    for (const w of BrowserWindow.getAllWindows()) {
        if (w.isDestroyed() || w.webContents.id === srcId) continue;
        w.webContents.send('pieceEditor:edits', payload);
    }
});

// ---------- 3D 模型编辑器（W2 子窗口；W3.5 资源联动 / W4.2 实体状态映射 / W5 契约接入） ----------
let modelEditorWin = null;
let modelEditorEntityCtx = null; // W4.2 实体上下文 {pack, packDir, entityId}（主窗口"3D 中编辑"设置）
let modelEditorDirtyCount = 0;   // 0.2.1：未保存修改数（渲染端每秒上报；关窗提示用）
let modelEditorForceClose = false;
function createModelEditorWindow() {
    modelEditorWin = new BrowserWindow({
        width: 1280,
        height: 860,
        title: '3D 模型编辑器',
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: false,
        },
    });
    modelEditorWin.loadFile(path.join(__dirname, 'model-editor', 'index.html'));
    guardRendererReload(modelEditorWin, '模型编辑器窗口');
    maybeOpenDevTools(modelEditorWin); // W5.5
    // 0.2.1：有未保存修改 → 关窗前确认（此前 doc.dirty 只写不读，关窗静默丢弃几何/动画编辑）
    modelEditorWin.on('close', (e) => {
        if (modelEditorDirtyCount > 0 && !modelEditorForceClose) {
            e.preventDefault();
            dialog.showMessageBox(modelEditorWin, {
                type: 'warning',
                title: '未保存的模型修改',
                message: `有 ${modelEditorDirtyCount} 处未保存的修改。`,
                detail: '关闭窗口会丢失这些修改（模型几何 / 动画 / 契约）。\n请先点「💾 保存」。',
                buttons: ['取消', '不保存关闭'],
                defaultId: 0,
                cancelId: 0,
            }).then((r) => {
                if (r.response === 1 && modelEditorWin && !modelEditorWin.isDestroyed()) {
                    modelEditorForceClose = true;
                    modelEditorWin.close();
                }
            });
        }
    });
    modelEditorWin.on('closed', () => {
        modelEditorWin = null;
        modelEditorDirtyCount = 0;
        modelEditorForceClose = false;
        modelEditorEntityCtx = null; // 关窗后清上下文：否则工具栏无 ctx 重开会套用上一次的实体
    });
}

// 0.2.1：模型编辑器未保存修改上报（1s 一次；fire-and-forget）
ipcMain.on('modelEditor:dirty', (event, n) => {
    modelEditorDirtyCount = Math.max(0, Number(n) | 0);
});

ipcMain.handle('modelEditor:open', (event, ctx) => {
    try {
        const created = !(modelEditorWin && !modelEditorWin.isDestroyed());
        if (created) {
            createModelEditorWindow();
            Logger.info('model-editor', '打开模型编辑器窗口');
        } else {
            modelEditorWin.show();
            modelEditorWin.focus();
        }
        // W4.2：携带实体上下文 → 推送给模型编辑器。
        // 新窗口尚未加载完（renderer 未订阅）→ 不推；其 app.js 加载时会拉 getPendingContext。
        if (ctx && ctx.packDir && ctx.entityId) {
            modelEditorEntityCtx = ctx;
            if (!created) modelEditorWin.webContents.send('modelEditor:entityContext', ctx);
        }
        return { ok: true };
    } catch (e) {
        Logger.error('model-editor', '打开模型编辑器失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// W4.2：模型编辑器加载时拉取待处理的实体上下文（可能为 null）
ipcMain.handle('modelEditor:getPendingContext', () => ({ ok: true, data: modelEditorEntityCtx }));

// W4.2：状态映射直写正式包（模型编辑器 = 直写哲学，与 saveModel/writeContract 一致；
// 主窗口的 entity:save 走暂存区，两者互不影响）
// REVIEW-M3：只做字段级合并（animations 是状态映射面板唯一可编辑字段）——
// 此前把渲染端整个实体对象（来自"暂存优先"的 readEntity）直写正式包，
// 会把主窗口未导出的草稿整体静默提升为正式文件；且主窗口内存/暂存区仍是旧值，
// 随后"保存→导出"会用旧草稿覆盖刚直写的正式文件 → 状态映射改动回退。
ipcMain.handle('modelEditor:saveEntityDirect', (event, packDir, entityJson) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const { valid, errors } = EntitySchema.validateEntity(entityJson);
        if (!valid) {
            return { ok: false, error: '实体校验失败:\n' + errors.join('\n') };
        }
        const packName = packNameOf(packDir);
        // 基线 = 正式包实体（缺省时回退暂存草稿）→ 只覆盖 animations → 写回
        let base = null;
        try { base = EntitySchema.readEntity(packDir, entityJson.id); } catch { base = null; }
        let fromStaged = false;
        if (!base) {
            base = Staging.readStagedEntity(packName, entityJson.id);
            fromStaged = !!base;
        }
        if (!base) {
            return { ok: false, error: `实体不存在（正式包与暂存区均无）: ${entityJson.id}` };
        }
        if (entityJson.animations) base.animations = entityJson.animations;
        else delete base.animations;
        const result = EntitySchema.saveEntity(packDir, base);
        // 暂存草稿存在则同步【只同步动画】——基线是正式包实体，整份回写会把主窗口
        // 尚未导出的草稿字段（attributes/behaviors/size…）覆盖掉（实测丢数据路径）。
        const stagedEntity = Staging.readStagedEntity(packName, entityJson.id);
        if (stagedEntity) {
            if (base.animations) stagedEntity.animations = base.animations;
            else delete stagedEntity.animations;
            Staging.stageEntity(packName, stagedEntity);
        }
        Logger.info('model-editor', `状态映射直写: ${path.basename(String(packDir))}/${entityJson.id} → ${result.file}${fromStaged ? '（基线=暂存草稿，已一并落盘）' : ''}`);
        return { ok: true, data: result };
    } catch (e) {
        Logger.error('model-editor', 'saveEntityDirect 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// W4.3：部位配置直写正式包 assets/<ns>/health_config/<id>.json（模型编辑器 = 直写哲学，同 saveEntityDirect）
ipcMain.handle('modelEditor:saveHealthDirect', (event, packDir, namespace, entityId, config) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        if (!namespace) return { ok: false, error: 'namespace 必填' };
        const { valid, errors } = HealthConfig.validateHealthConfig(config);
        if (!valid) {
            return { ok: false, error: '部位配置校验失败:\n' + errors.join('\n') };
        }
        const result = HealthConfig.saveHealthConfig(packDir, namespace, entityId, config);
        // 暂存草稿存在则同步（3D 直写比主窗口草稿新）：否则主窗口随后「导出」会用旧草稿覆盖回去
        if (Staging.readStagedHealthConfig(packNameOf(packDir), namespace, entityId)) {
            Staging.stageHealthConfig(packNameOf(packDir), namespace, entityId, config);
        }
        Logger.info('model-editor', `部位配置直写: ${path.basename(String(packDir))}/${namespace}/${entityId} → ${result.file}`);
        return { ok: true, data: result };
    } catch (e) {
        Logger.error('model-editor', 'saveHealthDirect 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// 模型编辑器（W3.1）：列出所有内容包（一键保存面板用）
ipcMain.handle('modelEditor:listPacks', () => {
    try {
        if (!PACKS_SOURCE_DIR || !fs.existsSync(PACKS_SOURCE_DIR)) return { ok: true, data: [] };
        const data = fs.readdirSync(PACKS_SOURCE_DIR, { withFileTypes: true })
            .filter((e) => e.isDirectory())
            .map((e) => ({
                name: e.name,
                dir: path.join(PACKS_SOURCE_DIR, e.name),
                hasModels: fs.existsSync(path.join(PACKS_SOURCE_DIR, e.name, 'spark_models')),
            }))
            .sort((a, b) => a.name.localeCompare(b.name));
        return { ok: true, data };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// ---------- 模型编辑器（W3.5 资源联动）：仓库资源选择器 ----------
// 渲染端只传 (source, pack, rel) 坐标；绝对路径一律由主进程重建并校验（同现有路径安全模式）。

/** rel（slash 形）→ 绝对路径；防 .. / 绝对路径 / 前缀逃逸 / 扩展名不符 */
function resolveRepoFile(baseDir, rel, { mustStartWith, exts }) {
    if (typeof rel !== 'string' || !rel) return null;
    const norm = rel.replace(/\\/g, '/');
    if (norm.startsWith('/')) return null;
    const segs = norm.split('/');
    if (segs.some((s) => s === '..' || s === '.' || s === '')) return null;
    if (mustStartWith && !norm.startsWith(mustStartWith)) return null;
    const abs = path.join(baseDir, ...segs);
    if (!abs.startsWith(baseDir + path.sep)) return null;
    if (exts && !exts.some((e) => abs.toLowerCase().endsWith(e))) return null;
    return abs;
}

/** 递归列目录（带上限，防病态规模）；返回相对 baseDir 的 slash 形路径 */
function listFilesRecursive(baseDir, ext, limit = 5000) {
    const out = [];
    if (!baseDir || !fs.existsSync(baseDir)) return out;
    const stack = [baseDir];
    while (stack.length && out.length < limit) {
        const dir = stack.pop();
        let entries;
        try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { continue; }
        for (const e of entries) {
            if (e.isDirectory()) {
                stack.push(path.join(dir, e.name));
            } else if (e.isFile() && e.name.toLowerCase().endsWith(ext) && out.length < limit) {
                out.push(path.relative(baseDir, path.join(dir, e.name)).split(path.sep).join('/'));
            }
        }
    }
    return out;
}

/** PNG 尺寸（IHDR；非 PNG 返回 [null, null]） */
function pngSize(buf) {
    if (!buf || buf.length < 24
        || !(buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47)) return [null, null];
    return [buf.readUInt32BE(16), buf.readUInt32BE(20)];
}

function packDirs() {
    if (!PACKS_SOURCE_DIR || !fs.existsSync(PACKS_SOURCE_DIR)) return [];
    return fs.readdirSync(PACKS_SOURCE_DIR, { withFileTypes: true })
        .filter((e) => e.isDirectory())
        .map((e) => e.name)
        .sort((a, b) => a.localeCompare(b));
}

// 仓库模型列表：所有包 spark_models/**/*.json
ipcMain.handle('modelEditor:listModels', () => {
    try {
        if (!PACKS_SOURCE_DIR) return { ok: false, error: '未设置项目根目录' };
        const data = [];
        for (const pack of packDirs()) {
            const base = path.join(PACKS_SOURCE_DIR, pack, 'spark_models');
            for (const rel of listFilesRecursive(base, '.json')) {
                // rel = <type>/<ns>/<name>.json（约定 3 层；更深时 ns 取第 2 层、name 取末段）
                const segs = rel.split('/');
                const type = segs[0] || '';
                const ns = segs.length >= 3 ? segs[1] : (segs.length === 2 ? '' : '');
                const name = segs[segs.length - 1].replace(/\.json$/i, '');
                data.push({ pack, rel: 'spark_models/' + rel, type, ns, name });
            }
        }
        data.sort((a, b) => (a.pack + a.rel).localeCompare(b.pack + b.rel));
        return { ok: true, data };
    } catch (e) {
        Logger.warn('model-editor', 'listModels 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// 仓库贴图列表：包 assets/**/*.png + 主 mod resources assets/**/*.png
ipcMain.handle('modelEditor:listTextures', () => {
    try {
        if (!PACKS_SOURCE_DIR) return { ok: false, error: '未设置项目根目录' };
        const data = [];
        for (const pack of packDirs()) {
            const base = path.join(PACKS_SOURCE_DIR, pack, 'assets');
            for (const rel of listFilesRecursive(base, '.png')) {
                data.push({ source: 'pack', pack, rel: 'assets/' + rel, name: rel.split('/').pop() });
            }
        }
        if (MOD_RES_DIR) {
            const base = path.join(MOD_RES_DIR, 'assets');
            for (const rel of listFilesRecursive(base, '.png')) {
                data.push({ source: 'mod', pack: '(主 mod)', rel: 'assets/' + rel, name: rel.split('/').pop() });
            }
        }
        data.sort((a, b) => ((a.source === 'pack' ? a.pack : 'zz') + a.rel).localeCompare((b.source === 'pack' ? b.pack : 'zz') + b.rel));
        return { ok: true, data };
    } catch (e) {
        Logger.warn('model-editor', 'listTextures 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// 读仓库模型 JSON（组合预览用；pack+rel 坐标，主进程重建绝对路径）
ipcMain.handle('modelEditor:readModel', async (event, p) => {
    try {
        if (!p || typeof p.pack !== 'string' || typeof p.rel !== 'string') return { ok: false, error: '参数无效' };
        const packDir = path.resolve(PACKS_SOURCE_DIR, p.pack);
        if (!packDir.startsWith(PACKS_SOURCE_DIR + path.sep)
            || !fs.existsSync(packDir) || !fs.statSync(packDir).isDirectory()) {
            return { ok: false, error: `包不存在: ${p.pack}` };
        }
        const abs = resolveRepoFile(packDir, p.rel, { mustStartWith: 'spark_models/', exts: ['.json'] });
        if (!abs) return { ok: false, error: '非法路径（须为包内 spark_models/**.json）' };
        if (!fs.existsSync(abs)) return { ok: false, error: '文件不存在: ' + p.rel };
        return { ok: true, data: { content: fs.readFileSync(abs, 'utf8'), path: abs, pack: p.pack, rel: p.rel } };
    } catch (e) {
        Logger.warn('model-editor', 'readModel 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// 读仓库贴图（PNG → dataURL + 尺寸；source=pack|mod）
ipcMain.handle('modelEditor:readTexture', async (event, p) => {
    try {
        if (!p || typeof p.source !== 'string' || typeof p.rel !== 'string') return { ok: false, error: '参数无效' };
        let baseDir, baseLabel;
        if (p.source === 'mod') {
            if (!MOD_RES_DIR) return { ok: false, error: '主 mod 资源目录不可用' };
            baseDir = MOD_RES_DIR; baseLabel = '(主 mod)';
        } else if (p.source === 'pack') {
            if (!p.pack) return { ok: false, error: '缺少包名' };
            const packDir = path.resolve(PACKS_SOURCE_DIR, p.pack);
            if (!packDir.startsWith(PACKS_SOURCE_DIR + path.sep)
                || !fs.existsSync(packDir) || !fs.statSync(packDir).isDirectory()) {
                return { ok: false, error: `包不存在: ${p.pack}` };
            }
            baseDir = packDir; baseLabel = p.pack;
        } else {
            return { ok: false, error: 'source 须为 pack|mod' };
        }
        const abs = resolveRepoFile(baseDir, p.rel, { mustStartWith: 'assets/', exts: ['.png'] });
        if (!abs) return { ok: false, error: '非法路径（须为 assets/**.png）' };
        if (!fs.existsSync(abs)) return { ok: false, error: '文件不存在: ' + p.rel };
        const buf = fs.readFileSync(abs);
        const [w, h] = pngSize(buf);
        return {
            ok: true,
            data: {
                dataUrl: 'data:image/png;base64,' + buf.toString('base64'),
                width: w, height: h,
                path: abs, pack: baseLabel, rel: p.rel,
            },
        };
    } catch (e) {
        Logger.warn('model-editor', 'readTexture 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// W5.2：按路径读模型文件（崩溃后自动恢复用；信任级 = 文件对话框，用户本机文件）
ipcMain.handle('modelEditor:readModelByPath', async (event, p) => {
    try {
        if (!p || typeof p.path !== 'string') return { ok: false, error: '参数无效' };
        const abs = path.resolve(p.path);
        if (!/\.(json|bbmodel)$/i.test(abs)) return { ok: false, error: '仅允许读取 .json/.bbmodel' };
        if (!fs.existsSync(abs)) return { ok: false, error: '文件不存在: ' + abs };
        return { ok: true, data: { content: fs.readFileSync(abs, 'utf8'), path: abs } };
    } catch (e) {
        Logger.warn('model-editor', 'readModelByPath 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// W5.1：保存编辑后贴图（覆写已应用贴图文件；pack+rel 坐标，主进程重建绝对路径）
ipcMain.handle('modelEditor:writeTexture', async (event, p) => {
    try {
        if (!p || typeof p.pack !== 'string' || typeof p.rel !== 'string' || typeof p.pngBase64 !== 'string') {
            return { ok: false, error: '参数无效' };
        }
        if (!PACKS_SOURCE_DIR) return { ok: false, error: '未设置项目根目录' };
        const packDir = path.resolve(PACKS_SOURCE_DIR, p.pack);
        if (!packDir.startsWith(PACKS_SOURCE_DIR + path.sep)
            || !fs.existsSync(packDir) || !fs.statSync(packDir).isDirectory()) {
            return { ok: false, error: `包不存在: ${p.pack}` };
        }
        const abs = resolveRepoFile(packDir, p.rel, { mustStartWith: 'assets/', exts: ['.png'] });
        if (!abs) return { ok: false, error: '非法路径（须为包内 assets/**.png）' };
        fs.mkdirSync(path.dirname(abs), { recursive: true });
        fs.writeFileSync(abs, Buffer.from(p.pngBase64, 'base64'));
        return { ok: true, data: { path: abs } };
    } catch (e) {
        Logger.warn('model-editor', 'writeTexture 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// W5.1：贴图保存对话框（无已应用贴图时用户自选路径；存入 contentpacks-source/<包>/assets/ 内 → 返回 pack/rel 供契约联动）
ipcMain.handle('modelEditor:saveTextureDialog', async (event, p) => {
    try {
        if (!p || typeof p.pngBase64 !== 'string') return { ok: false, error: '参数无效' };
        const defName = (typeof p.defaultName === 'string' && p.defaultName) || 'texture.png';
        const win = BrowserWindow.fromWebContents(event.sender);
        const { canceled, filePath } = await dialog.showSaveDialog(win, {
            title: '保存贴图',
            defaultPath: path.join(PACKS_SOURCE_DIR || app.getPath('documents'), defName),
            filters: [{ name: 'PNG 贴图', extensions: ['png'] }],
        });
        if (canceled || !filePath) return { ok: true, data: null };
        const abs = path.resolve(filePath);
        if (!abs.toLowerCase().endsWith('.png')) return { ok: false, error: '仅允许保存 .png' };
        fs.mkdirSync(path.dirname(abs), { recursive: true });
        fs.writeFileSync(abs, Buffer.from(p.pngBase64, 'base64'));
        let pack = null, rel = null;
        if (PACKS_SOURCE_DIR) {
            const base = path.resolve(PACKS_SOURCE_DIR);
            if (abs.startsWith(base + path.sep)) {
                const parts = abs.slice(base.length + 1).split(path.sep);
                if (parts.length >= 3 && parts[1] === 'assets') {
                    pack = parts[0];
                    rel = 'assets/' + parts.slice(1).join('/');
                }
            }
        }
        return { ok: true, data: { path: abs, pack, rel } };
    } catch (e) {
        Logger.warn('model-editor', 'saveTextureDialog 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// 读包契约（W3.5-②：贴图应用持久化）
ipcMain.handle('modelEditor:readContract', async (event, p) => {
    try {
        if (!p || typeof p.pack !== 'string') return { ok: false, error: '参数无效' };
        const packDir = path.resolve(PACKS_SOURCE_DIR, p.pack);
        if (!packDir.startsWith(PACKS_SOURCE_DIR + path.sep)
            || !fs.existsSync(packDir) || !fs.statSync(packDir).isDirectory()) {
            return { ok: false, error: `包不存在: ${p.pack}` };
        }
        const abs = path.join(packDir, 'editor', 'animation-contract.json');
        if (!fs.existsSync(abs)) return { ok: false, error: '该包无契约 editor/animation-contract.json' };
        return { ok: true, data: { content: fs.readFileSync(abs, 'utf8'), path: abs } };
    } catch (e) {
        Logger.warn('model-editor', 'readContract 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// 写包契约（W3.5-②；路径固定 <pack>/editor/animation-contract.json；内容须为含 sets[] 的 JSON）
ipcMain.handle('modelEditor:writeContract', async (event, p) => {
    try {
        if (!p || typeof p.pack !== 'string' || typeof p.content !== 'string') return { ok: false, error: '参数无效' };
        let obj;
        try { obj = JSON.parse(p.content); } catch (e) { return { ok: false, error: '契约 JSON 无效: ' + e.message }; }
        if (!obj || !Array.isArray(obj.sets)) return { ok: false, error: '契约须含 sets[]' };
        const packDir = path.resolve(PACKS_SOURCE_DIR, p.pack);
        if (!packDir.startsWith(PACKS_SOURCE_DIR + path.sep)
            || !fs.existsSync(packDir) || !fs.statSync(packDir).isDirectory()) {
            return { ok: false, error: `包不存在: ${p.pack}` };
        }
        const abs = path.join(packDir, 'editor', 'animation-contract.json');
        fs.mkdirSync(path.dirname(abs), { recursive: true });
        fs.writeFileSync(abs, p.content, 'utf-8');
        Logger.info('model-editor', `写契约: ${abs}（${obj.sets.length} sets）`);
        return { ok: true, data: { path: abs, sets: obj.sets.length } };
    } catch (e) {
        Logger.error('model-editor', 'writeContract 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// 模型编辑器「另存为」（W3.1）：一键选包落盘（p.packName）或保存对话框 + 路径安全 + 目录脚手架
// （动画目录 + 缺省契约；路径约定 <pack>/spark_models/<type>/<ns>/<name>.json，见 core/sparkScaffold.js）
ipcMain.handle('modelEditor:saveModelAs', async (event, p) => {
    try {
        if (!p || typeof p.content !== 'string' || typeof p.identifier !== 'string') {
            return { ok: false, error: '参数无效' };
        }
        if (!PACKS_SOURCE_DIR) return { ok: false, error: '未设置项目根目录' };
        const ident = SparkScaffold.parseIdentifier(p.identifier);
        if (!ident.ok) return { ok: false, error: ident.error };
        const ns = ident.ns || 'new';
        // REVIEW-L10：模型类型（entity/item）由渲染端传入——此前硬编码 entity 目录，
        // item 模型会被写进 spark_models/entity/，与 resolveFromId(type='item') 的
        // 查找路径（spark_models/item/…）不一致 → 物品模型游戏内丢失
        const type = (p.type === 'item' || p.type === 'entity') ? p.type : 'entity';
        let abs;
        if (typeof p.packName === 'string' && p.packName.trim()) {
            // 一键落盘：目标包内按约定路径
            const packName = p.packName.trim();
            const packDir = path.resolve(PACKS_SOURCE_DIR, packName);
            if (!packDir.startsWith(PACKS_SOURCE_DIR + path.sep)
                || !fs.existsSync(packDir) || !fs.statSync(packDir).isDirectory()) {
                return { ok: false, error: `包目录不存在: ${packName}` };
            }
            abs = path.join(packDir, 'spark_models', type, ns, ident.name + '.json');
        } else {
            // 手动路径：保存对话框
            let defaultDir = PACKS_SOURCE_DIR;
            if (fs.existsSync(PACKS_SOURCE_DIR)) {
                const packsWithModels = fs.readdirSync(PACKS_SOURCE_DIR, { withFileTypes: true })
                    .filter((e) => e.isDirectory())
                    .map((e) => e.name)
                    .sort()
                    .filter((n) => fs.existsSync(path.join(PACKS_SOURCE_DIR, n, 'spark_models')));
                if (packsWithModels.length) defaultDir = path.join(PACKS_SOURCE_DIR, packsWithModels[0], 'spark_models', type, ns);
            }
            const win = BrowserWindow.fromWebContents(event.sender);
            const { canceled, filePath } = await dialog.showSaveDialog(win, {
                title: `保存模型 ${ident.ns ? ident.ns + ':' : ''}${ident.name}（须位于 <包>/spark_models/${type}/${ns}/）`,
                defaultPath: path.join(defaultDir, ident.name + '.json'),
                filters: [{ name: 'Spark 模型', extensions: ['json'] }],
            });
            if (canceled || !filePath) return { ok: true, data: { canceled: true } };
            abs = path.resolve(filePath);
        }
        // 路径安全（与 saveModel 同规则）
        if (abs !== PACKS_SOURCE_DIR && !abs.startsWith(PACKS_SOURCE_DIR + path.sep)) {
            return { ok: false, error: '路径超出包源目录: ' + abs };
        }
        if (!abs.toLowerCase().endsWith('.json')) return { ok: false, error: '仅允许保存 .json' };
        // 脚手架计划（纯计算）+ 落盘
        const parsed = SparkScaffold.parseModelPath(abs);
        if (!parsed.ok) {
            return { ok: false, error: `路径不符合约定: ${parsed.error}（应为 <包>/spark_models/entity|item/<ns>/<name>.json）` };
        }
        const packDir = parsed.packRel.split('/').join(path.sep);
        const plan = SparkScaffold.scaffoldPlan(abs, {
            contractExists: fs.existsSync(path.join(packDir, 'editor', 'animation-contract.json')),
            label: p.label || ident.name,
            texture: typeof p.texture === 'string' && p.texture ? p.texture : undefined, // W3.5-②：应用过的贴图写入缺省契约
        });
        if (!plan.ok) {
            return { ok: false, error: `脚手架失败: ${plan.error}` };
        }
        const d = plan.data;
        const created = [];
        fs.mkdirSync(path.dirname(abs), { recursive: true });
        fs.writeFileSync(abs, p.content, 'utf-8');
        created.push(abs);
        fs.mkdirSync(d.animDir, { recursive: true });
        created.push(d.animDir);
        if (d.contract.create) {
            fs.mkdirSync(path.dirname(d.contract.path), { recursive: true });
            fs.writeFileSync(d.contract.path, d.contract.content, 'utf-8');
            created.push(d.contract.path);
        }
        Logger.info('model-editor', `另存为: ${abs}（创建 ${created.length} 项${d.contract.create ? '，含缺省契约' : ''}）`);
        return { ok: true, data: { path: abs, created, animDir: d.animDir, textureHint: d.texturePath, contractCreated: d.contract.create } };
    } catch (e) {
        Logger.error('model-editor', `另存为失败: ${String(e.message || e)}`);
        return { ok: false, error: String(e.message || e) };
    }
});

// 模型编辑器落盘（W3）：写回 spark 模型 JSON。路径安全 = 必须落在包源目录内 + .json
ipcMain.handle('modelEditor:saveModel', (event, p) => {
    try {
        if (!p || typeof p.path !== 'string' || typeof p.content !== 'string') {
            return { ok: false, error: '参数无效' };
        }
        if (!PACKS_SOURCE_DIR) return { ok: false, error: '未设置项目根目录' };
        const abs = path.resolve(p.path);
        // 0.2.1：.bbmodel 源（外部 Blockbench 工程文件，不在包内）此前一律报「路径超出包源目录」，
        // 保存永远失败。这里只允许【覆写已存在的 .bbmodel】（文件由打开对话框产生，不新增任意写入口）。
        if (abs.toLowerCase().endsWith('.bbmodel')) {
            if (!fs.existsSync(abs) || !fs.statSync(abs).isFile()) {
                return { ok: false, error: 'bbmodel 文件不存在（只允许覆写已打开过的工程）: ' + abs };
            }
            fs.writeFileSync(abs, p.content, 'utf-8');
            Logger.info('model-editor', `保存 bbmodel: ${abs}（${p.content.length} 字节）`);
            return { ok: true, data: { path: abs } };
        }
        if (abs !== PACKS_SOURCE_DIR && !abs.startsWith(PACKS_SOURCE_DIR + path.sep)) {
            return { ok: false, error: '路径超出包源目录: ' + abs };
        }
        if (!abs.toLowerCase().endsWith('.json')) return { ok: false, error: '仅允许保存 .json' };
        fs.writeFileSync(abs, p.content, 'utf-8');
        Logger.info('model-editor', `保存模型: ${abs}（${p.content.length} 字节）`);
        return { ok: true, data: { path: abs } };
    } catch (e) {
        Logger.error('model-editor', `保存模型失败: ${String(e.message || e)}`);
        return { ok: false, error: String(e.message || e) };
    }
});

// ---------- TimeTape 状态机编辑器（W2）：包内 .json 读/写（p = {pack, rel[, content]}） ----------
function tapeResolvePackFile(p) {
    if (!p || typeof p.pack !== 'string' || typeof p.rel !== 'string') return { error: '参数无效' };
    if (!PACKS_SOURCE_DIR) return { error: '未设置项目根目录' };
    // p.pack 兼容：绝对路径（AppState.pack.dir）或包目录名（相对 PACKS_SOURCE_DIR）
    const packDir = path.isAbsolute(p.pack) ? path.resolve(p.pack) : path.resolve(PACKS_SOURCE_DIR, p.pack);
    // 0.2.1：允许根 = 包库 + 新建包目录（与 safePackDir 一致）。此前只认包库，
    // 建在「新建包目录」的包在契约里点「状态机编辑」必然报「包不存在」。
    const roots = [PACKS_SOURCE_DIR, NEW_PACK_DIR].filter((r, i, a) => r && a.indexOf(r) === i).map((r) => path.resolve(r));
    const inRoot = roots.some((r) => packDir === r || packDir.startsWith(r + path.sep));
    const insideButRoot = roots.some((r) => packDir === r);
    if (!inRoot || insideButRoot
        || !fs.existsSync(packDir) || !fs.statSync(packDir).isDirectory()) {
        return { error: `包不存在: ${p.pack}` };
    }
    const abs = path.resolve(packDir, p.rel);
    if (abs !== packDir && !abs.startsWith(packDir + path.sep)) return { error: '路径超出包目录: ' + abs };
    if (!abs.toLowerCase().endsWith('.json')) return { error: '仅允许 .json 文件' };
    return { packDir, abs };
}

ipcMain.handle('tape:readFile', (event, p) => {
    try {
        const r = tapeResolvePackFile(p);
        if (r.error) return { ok: false, error: r.error };
        if (!fs.existsSync(r.abs)) return { ok: false, error: '文件不存在: ' + p.rel };
        return { ok: true, data: { content: fs.readFileSync(r.abs, 'utf8'), path: r.abs } };
    } catch (e) {
        Logger.error('tape-editor', 'readFile 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('tape:saveFile', (event, p) => {
    try {
        if (typeof p.content !== 'string') return { ok: false, error: '参数无效' };
        const r = tapeResolvePackFile(p);
        if (r.error) return { ok: false, error: r.error };
        try { JSON.parse(p.content); } catch (e) { return { ok: false, error: 'JSON 无效: ' + e.message }; }
        fs.mkdirSync(path.dirname(r.abs), { recursive: true });
        fs.writeFileSync(r.abs, p.content, 'utf8');
        Logger.info('tape-editor', `保存: ${r.abs}（${p.content.length} 字节）`);
        return { ok: true, data: { path: r.abs } };
    } catch (e) {
        Logger.error('tape-editor', 'saveFile 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// 画布布局 sidecar：存编辑器状态目录（<root>/.contentpack-editor/tape-layouts/），不进包、不打进 jar
function tapeLayoutPath(pack) {
    const safe = String(pack).replace(/[^A-Za-z0-9_.-]/g, '_');
    return path.join(ROOT, '.contentpack-editor', 'tape-layouts', safe + '.json');
}

ipcMain.handle('tape:readLayout', (event, p) => {
    try {
        if (!p || typeof p.pack !== 'string' || !ROOT) return { ok: false, error: '参数无效' };
        const abs = tapeLayoutPath(p.pack);
        if (!fs.existsSync(abs)) return { ok: false, error: 'no layout' };
        return { ok: true, data: { content: fs.readFileSync(abs, 'utf8') } };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('tape:saveLayout', (event, p) => {
    try {
        if (!p || typeof p.pack !== 'string' || typeof p.content !== 'string' || !ROOT) {
            return { ok: false, error: '参数无效' };
        }
        const abs = tapeLayoutPath(p.pack);
        fs.mkdirSync(path.dirname(abs), { recursive: true });
        fs.writeFileSync(abs, p.content, 'utf8');
        return { ok: true };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// ---------- W7：动画文档（一模型的全套动画 = 一目录 spark_animations/<type>/<ns>/<model>/） ----------
// 渲染端传 (pack, rel) 坐标（rel = 包内模型相对路径 spark_models/**.json）；
// 动画目录由主进程按 spark 惯例推导并校验（与 model-editor/core/sparkCodec.defaultAnimDir 同规则）。

/** spark_models/<type>/<ns>/<model>.json → spark_animations/<type>/<ns>/<model>（包内相对目录） */
function animDirForModelRel(rel) {
    const norm = (typeof rel === 'string' ? rel : '').replace(/\\/g, '/');
    const m = norm.match(/^spark_models\/([^/]+)\/(.+)\/([^/]+)\.json$/);
    if (!m) return null;
    return `spark_animations/${m[1]}/${m[2]}/${m[3]}`;
}

/** 校验 (pack, rel) → {packDir, modelAbs}；失败返回 null */
function resolvePackModel(p) {
    if (!p || typeof p.pack !== 'string' || typeof p.rel !== 'string') return null;
    if (!PACKS_SOURCE_DIR) return null;
    const packDir = path.resolve(PACKS_SOURCE_DIR, p.pack);
    if (!packDir.startsWith(PACKS_SOURCE_DIR + path.sep)
        || !fs.existsSync(packDir) || !fs.statSync(packDir).isDirectory()) return null;
    const modelAbs = resolveRepoFile(packDir, p.rel, { mustStartWith: 'spark_models/', exts: ['.json'] });
    if (!modelAbs) return null;
    const animRel = animDirForModelRel(p.rel);
    if (!animRel) return null;
    return { packDir, modelAbs, animDir: path.join(packDir, ...animRel.split('/')), animRel };
}

// W7：载入模型的动画文档（目录内全部 *.json 原文；解析在渲染端）
ipcMain.handle('modelEditor:loadAnimDoc', async (event, p) => {
    try {
        const r = resolvePackModel(p);
        if (!r) return { ok: false, error: '参数无效（须为包内 spark_models/<type>/<ns>/<model>.json）' };
        if (!fs.existsSync(r.animDir) || !fs.statSync(r.animDir).isDirectory()) {
            return { ok: true, data: { animRel: r.animRel, files: [] } };
        }
        const files = fs.readdirSync(r.animDir).filter((f) => f.toLowerCase().endsWith('.json')).sort();
        const out = files.map((f) => ({ file: f, content: fs.readFileSync(path.join(r.animDir, f), 'utf-8') }));
        return { ok: true, data: { animRel: r.animRel, files: out } };
    } catch (e) {
        Logger.warn('model-editor', 'loadAnimDoc 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// W7：写单个动画文件（内容须为含 animations 映射的 JSON 文本）
ipcMain.handle('modelEditor:writeAnimFile', async (event, p) => {
    try {
        if (!p || typeof p.file !== 'string' || typeof p.content !== 'string') return { ok: false, error: '参数无效' };
        if (!/^[A-Za-z0-9._-]+\.json$/.test(p.file)) return { ok: false, error: '动画文件名非法（仅允许字母/数字/._- + .json）' };
        let obj;
        try { obj = JSON.parse(p.content); } catch (e) { return { ok: false, error: '动画 JSON 无效: ' + e.message }; }
        if (!obj || typeof obj.animations !== 'object' || Array.isArray(obj.animations)) {
            return { ok: false, error: '动画文件须含 animations 映射' };
        }
        const r = resolvePackModel(p);
        if (!r) return { ok: false, error: '参数无效（须为包内 spark_models/<type>/<ns>/<model>.json）' };
        fs.mkdirSync(r.animDir, { recursive: true });
        const abs = path.join(r.animDir, p.file);
        fs.writeFileSync(abs, p.content, 'utf-8');
        Logger.info('model-editor', `写动画: ${abs}（${p.content.length} 字节）`);
        return { ok: true, data: { path: abs, rel: r.animRel + '/' + p.file } };
    } catch (e) {
        Logger.error('model-editor', 'writeAnimFile 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// W7：删单个动画文件（仅限该模型动画目录内）
ipcMain.handle('modelEditor:deleteAnimFile', async (event, p) => {
    try {
        if (!p || typeof p.file !== 'string') return { ok: false, error: '参数无效' };
        if (!/^[A-Za-z0-9._-]+\.json$/.test(p.file)) return { ok: false, error: '动画文件名非法' };
        const r = resolvePackModel(p);
        if (!r) return { ok: false, error: '参数无效（须为包内 spark_models/<type>/<ns>/<model>.json）' };
        const abs = path.join(r.animDir, p.file);
        if (!fs.existsSync(abs)) return { ok: false, error: '文件不存在: ' + p.file };
        fs.rmSync(abs);
        Logger.info('model-editor', `删动画: ${abs}`);
        return { ok: true, data: { path: abs } };
    } catch (e) {
        Logger.error('model-editor', 'deleteAnimFile 失败: ' + String(e.message || e));
        return { ok: false, error: String(e.message || e) };
    }
});

// ==================== IPC 处理器 ====================
// 每个 handler 返回 { ok, data|error }，渲染进程统一处理

ipcMain.handle('app:getPaths', () => {
    if (!ROOT) {
        return { ok: true, data: { needProjectRoot: true, packsSourceDir: null, packsOutDir: null, root: null, rootSource: null } };
    }
    return {
        ok: true,
        data: {
            packsSourceDir: PACKS_SOURCE_DIR, packsOutDir: PACKS_OUT_DIR, root: ROOT,
            rootSource: ROOT_SOURCE, packPaths: getPackPathsState(),
        },
    };
});

// 用户选择项目根（应用内对话框 → 保存到内部配置 → 生效）
ipcMain.handle('app:setProjectRoot', async (event, rootDir) => {
    try {
        if (!rootDir || !fs.existsSync(path.join(rootDir, 'contentpacks-source'))) {
            return { ok: false, error: '所选目录不是有效的项目根（缺少 contentpacks-source）' };
        }
        applyProjectRoot(rootDir, 'config');
        saveConfig({ projectRoot: rootDir });
        return { ok: true, data: { packsSourceDir: PACKS_SOURCE_DIR, packsOutDir: PACKS_OUT_DIR, root: ROOT } };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 弹目录选择框（渲染进程触发，选项目根）
ipcMain.handle('app:pickProjectRoot', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        title: '选择项目根目录（含 contentpacks-source 的目录）',
        properties: ['openDirectory'],
    });
    if (result.canceled || result.filePaths.length === 0) return { ok: false, error: 'cancelled' };
    const dir = result.filePaths[0];
    if (!fs.existsSync(path.join(dir, 'contentpacks-source'))) {
        return { ok: false, error: '所选目录不是有效的项目根（缺少 contentpacks-source）' };
    }
    applyProjectRoot(dir, 'config');
    saveConfig({ projectRoot: dir });
    return { ok: true, data: { packsSourceDir: PACKS_SOURCE_DIR, packsOutDir: PACKS_OUT_DIR, root: ROOT } };
});

// ==================== W6.1：包路径设置（包库 packsRoot / 新建包目录 newPackDir） ====================
// 默认值：<项目根>/contentpacks-source（包库）与同值（新建包目录）。
// 覆盖后：包列表合并扫描两个目录、pack:create 落到 newPackDir、safePackDir 边界同时接受两个根。
function getPackPathsState() {
    const defaultPacksRoot = ROOT ? path.join(ROOT, 'contentpacks-source') : null;
    return {
        root: ROOT,
        rootSource: ROOT_SOURCE,
        packsRoot: PACKS_SOURCE_DIR,
        newPackDir: NEW_PACK_DIR,
        defaultPacksRoot,
        // 新建包目录的默认值 = 跟随包库（不是固定 <项目根>/contentpacks-source）
        defaultNewPackDir: PACKS_SOURCE_DIR,
        isPacksRootDefault: !!defaultPacksRoot && PACKS_SOURCE_DIR === defaultPacksRoot,
        isNewPackDirDefault: NEW_PACK_DIR === PACKS_SOURCE_DIR,
    };
}

/** W6.1：应用包路径覆盖（payload.{packsRoot,newPackDir}；null = 复位该项默认，undefined = 不动） */
function handleSetPackPaths(payload = {}) {
    if (!ROOT) return { ok: false, error: '尚未设置项目根目录' };
    const patch = {};
    for (const [key, label] of [['packsRoot', '包库目录'], ['newPackDir', '新建包目录']]) {
        const v = payload[key];
        if (v === undefined) continue;
        if (v === null || v === '') { patch[key] = null; continue; }
        if (!validOverrideDir(v)) return { ok: false, error: `${label}不存在或不是目录: ${v}` };
        patch[key] = path.resolve(v);
    }
    try {
        // saveConfig 用 Object.assign 合并，删不掉键 → 复位走直接读写
        const cfg = readConfig();
        for (const k of Object.keys(patch)) {
            if (patch[k] === null) delete cfg[k]; else cfg[k] = patch[k];
        }
        fs.mkdirSync(app.getPath('userData'), { recursive: true });
        fs.writeFileSync(configFilePath(), JSON.stringify(cfg, null, 2), 'utf-8');
        applyProjectRoot(ROOT); // 重算 PACKS_SOURCE_DIR / NEW_PACK_DIR
        Logger.info('config', `包路径设置: 包库=${PACKS_SOURCE_DIR} 新建包=${NEW_PACK_DIR}`);
        return { ok: true, data: getPackPathsState() };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
}

ipcMain.handle('settings:getPackPaths', () => {
    if (!ROOT) return { ok: false, error: '尚未设置项目根目录' };
    return { ok: true, data: getPackPathsState() };
});

ipcMain.handle('settings:setPackPaths', (event, payload) => handleSetPackPaths(payload));

// 弹目录选择框（渲染进程触发；which = 'packsRoot' | 'newPackDir'）
ipcMain.handle('app:pickPackDir', async (event, which) => {
    if (which !== 'packsRoot' && which !== 'newPackDir') return { ok: false, error: 'invalid which' };
    const label = which === 'newPackDir'
        ? '选择新建包目录（新建内容包将创建在此）'
        : '选择包库目录（包含内容包的目录）';
    const result = await dialog.showOpenDialog(mainWindow, { title: label, properties: ['openDirectory'] });
    if (result.canceled || result.filePaths.length === 0) return { ok: false, error: 'cancelled' };
    return handleSetPackPaths({ [which]: result.filePaths[0] });
});

// --- 包管理 ---
/** 扫描包库 + 新建包目录（按目录去重合并）——pack:list / pack:create 共用 */
function scanAllPacks() {
    const packs = ManifestLib.scanPacks(PACKS_SOURCE_DIR);
    if (NEW_PACK_DIR && NEW_PACK_DIR !== PACKS_SOURCE_DIR) {
        let extra = [];
        try { extra = ManifestLib.scanPacks(NEW_PACK_DIR); } catch { extra = []; }
        const seen = new Set(packs.map((p) => p.dir));
        for (const p of extra) if (!seen.has(p.dir)) packs.push(p);
    }
    return packs;
}

ipcMain.handle('pack:list', async () => {
    try {
        if (!ROOT) return { ok: false, error: '尚未设置项目根目录' };
        return { ok: true, data: scanAllPacks() };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 新包默认值 ---
const PACK_DEFAULTS = {
    version: '0.1.0',
    requires: 'catoxidesbattlerebuild>=0.2.0',
};

/** 额外编译依赖：分号/换行分隔的【整条】依赖语句（compileOnly） */
function splitExtraDeps(raw) {
    if (!raw) return [];
    return String(raw).split(/[;\n]/).map((s) => s.trim()).filter(Boolean);
}

/** 额外依赖必须是 compileOnly —— 包只能看到父环境 + 自己 jar + _libs 的类 */
function checkExtraDeps(extraDeps) {
    for (const d of extraDeps) {
        if (/\b(implementation|api|runtimeOnly|compile)\b/.test(d)) {
            return `额外编译依赖必须写成 compileOnly(...)：内容包的类加载闭包只有「父环境前缀 + 自己的 jar + _libs」，` +
                `用 implementation/api 打进包内也不会被加载 ⇒ 运行期 NoClassDefFoundError。\n有问题的条目：${d}`;
        }
    }
    return null;
}

// 创建内容包：MANIFEST（全属性）+ build.gradle（Gradle 子项目）+ 入口类骨架
ipcMain.handle('pack:create', async (event, payload = {}) => {
    try {
        if (!ROOT) return { ok: false, error: '尚未设置项目根目录' };
        const manifest = Scaffold.canonicalizeManifest({
            'Module-Name': String(payload.name || '').trim(),
            'Module-Version': String(payload.version || '').trim() || PACK_DEFAULTS.version,
            'Module-Requires': String(payload.requires || '').trim() || PACK_DEFAULTS.requires,
            'Module-Type': 'contentpack',
            'Module-Entry': String(payload.entry || '').trim(),
            'Module-Dependencies': String(payload.deps || '').trim(),
            'Module-Dependencies-Optional': String(payload.depsOptional || '').trim(),
            'Module-Lib-Requires': String(payload.libRequires || '').trim(),
            'Module-Mod-Requires': String(payload.modRequires || '').trim(),
            'Module-Mod-Optional': String(payload.modOptional || '').trim(),
        });

        // 坏名/坏入口不许落盘
        const errors = Scaffold.validateManifest(manifest).filter((f) => f.level === 'error');
        if (errors.length) return { ok: false, error: errors.map((e) => e.msg).join('\n') };

        const extraDeps = splitExtraDeps(payload.extraDeps);
        const depErr = checkExtraDeps(extraDeps);
        if (depErr) return { ok: false, error: depErr };

        // W6.1：新建包目录（默认 = 包库目录）
        // 0.2.1：两个根（包库 / 新建包目录）都要查重 —— 同名 Module-Name 会共用
        // 产物名（contentpacks/<name>.jar）与暂存键，后建的那个会串味。
        const targetRoot = NEW_PACK_DIR || PACKS_SOURCE_DIR;
        const dir = path.join(targetRoot, manifest['Module-Name']);
        const dupRoots = [PACKS_SOURCE_DIR, NEW_PACK_DIR].filter((r, i, a) => r && a.indexOf(r) === i);
        for (const r of dupRoots) {
            const candidate = path.join(r, manifest['Module-Name']);
            if (fs.existsSync(candidate)) {
                return {
                    ok: false,
                    error: `包已存在（Module-Name 唯一）：${candidate}\n` +
                        (r === PACKS_SOURCE_DIR ? '在「包库目录」里已有同名包。' : '在「新建包目录」里已有同名包。') +
                        '请换一个包名，或先删除/重命名已有包。',
                };
            }
        }

        const files = Preflight.scaffoldFiles(manifest, {
            packId: String(payload.packId || '').trim() || manifest['Module-Name'].replace(/-/g, '_'),
            displayName: String(payload.displayName || '').trim() || manifest['Module-Name'],
            extraDeps,
        });
        fs.mkdirSync(path.join(dir, 'entities'), { recursive: true });
        const written = [];
        for (const [rel, content] of Object.entries(files)) {
            const full = path.join(dir, rel);
            fs.mkdirSync(path.dirname(full), { recursive: true });
            fs.writeFileSync(full, content, 'utf-8');
            written.push(rel);
        }
        const packs = scanAllPacks();
        return {
            ok: true,
            data: {
                packs, created: dir, written,
                // W6.1：包建在包库之外（独立新建包目录）→ UI 提示
                outsideLibrary: NEW_PACK_DIR !== PACKS_SOURCE_DIR,
                gradleCommand: Scaffold.gradleBuildCommand(manifest['Module-Name']),
            },
        };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('pack:readManifest', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const manifest = ManifestLib.readManifest(packDir);
        return { ok: true, data: manifest };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('pack:saveManifest', async (event, packDir, manifest) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const canonical = Scaffold.canonicalizeManifest(manifest || {});
        ManifestLib.writeManifest(packDir, canonical);
        // 老包首次保存时补齐 Gradle 骨架（自动发现只认有 build.gradle 的目录）
        const scaffolded = [];
        const gradleFile = path.join(packDir, 'build.gradle');
        if (!fs.existsSync(gradleFile)) {
            fs.writeFileSync(gradleFile, Scaffold.renderBuildGradle(canonical['Module-Name'], {}), 'utf-8');
            scaffolded.push('build.gradle');
        }
        return {
            ok: true,
            data: { scaffolded, gradleCommand: Scaffold.gradleBuildCommand(path.basename(packDir)) },
        };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 打包前预检：把「构建成功 ≠ 能加载」的坑提前暴露
ipcMain.handle('pack:preflight', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        return { ok: true, data: Preflight.preflightPack(packDir, { root: ROOT }) };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 编辑器契约（Module-Editor-Schema）：读包源目录的契约 + 资产盘点 + 校验（只读）
// 返回 {ok:true, data:{schemaPath, contract, sets, errors, warnings}}
//      {ok:false, error:'no-contract'}（该包未声明契约，合法状态）
//      {ok:false, error|errors:…}（声明了但读不到/不合法）
ipcMain.handle('contract:read', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const manifest = ManifestLib.readManifest(packDir);
        return EditorContract.buildReport(packDir, manifest);
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 补齐骨架：build.gradle + 入口类（绝不覆盖已存在的源码）
ipcMain.handle('pack:ensureScaffold', async (event, packDir, opts = {}) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const manifest = ManifestLib.readManifest(packDir);
        const created = [];

        const gradleFile = path.join(packDir, 'build.gradle');
        if (!fs.existsSync(gradleFile)) {
            fs.writeFileSync(gradleFile, Scaffold.renderBuildGradle(manifest['Module-Name'],
                { extraDeps: splitExtraDeps(opts.extraDeps) }), 'utf-8');
            created.push('build.gradle');
        }

        const entry = (manifest['Module-Entry'] || '').trim();
        if (entry) {
            const loc = Scaffold.entryLocation(entry);
            if (loc && !fs.existsSync(path.join(packDir, loc.path))) {
                const entityIds = listEntityIds(packDir);
                const src = Scaffold.renderEntryClass({
                    moduleEntry: entry,
                    packId: manifest['Module-Name'].replace(/-/g, '_'),
                    displayName: manifest['Module-Name'],
                    version: manifest['Module-Version'] || '1.0.0',
                    entityIds,
                });
                if (src) {
                    const full = path.join(packDir, loc.path);
                    fs.mkdirSync(path.dirname(full), { recursive: true });
                    fs.writeFileSync(full, src, 'utf-8');
                    created.push(loc.path);
                }
            }
        }
        return {
            ok: true,
            data: { created, gradleCommand: Scaffold.gradleBuildCommand(path.basename(packDir)) },
        };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

/** 包内 entities/*.json 的 id 列表 */
function listEntityIds(packDir) {
    const dir = path.join(packDir, 'entities');
    if (!fs.existsSync(dir)) return [];
    const ids = [];
    for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.json'))) {
        try {
            const obj = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf-8'));
            if (obj && obj.id) ids.push(String(obj.id));
        } catch { /* 坏 JSON 由预检报告 */ }
    }
    return ids;
}

// --- 实体 ---
// 工具函数：packDir 的包名（用于暂存区隔离）
function packNameOf(packDir) {
    return path.basename(packDir);
}

/**
 * REVIEW-L5：packDir 边界校验（此前 pack: / entity: / structure: / health: / staging:
 * 各 handler 一族只查存在性，不校验位于 contentpacks-source 内——
 * 与 modelEditor: 族的严格标准不一致）。
 * 合法 → 返回解析后的绝对路径；越界/不存在/根未就绪 → null。
 */
function safePackDir(packDir) {
    if (!packDir || typeof packDir !== 'string') return null;
    if (!PACKS_SOURCE_DIR) return null; // 项目根未就绪时这些 API 本就无意义
    const abs = path.resolve(packDir);
    // W6.1：新建包目录可独立于包库 → 两个根都合法（边界校验不变，只是根可配）
    const roots = [PACKS_SOURCE_DIR];
    if (NEW_PACK_DIR && NEW_PACK_DIR !== PACKS_SOURCE_DIR) roots.push(NEW_PACK_DIR);
    if (!roots.some((r) => abs.startsWith(r + path.sep))) return null;
    if (!fs.existsSync(abs) || !fs.statSync(abs).isDirectory()) return null;
    return abs;
}

ipcMain.handle('entity:list', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // 合并：正式包实体 + 暂存区实体（暂存优先，避免重复）
        const entities = EntitySchema.listEntities(packDir);
        const staged = Staging.listStaged(packNameOf(packDir)).filter((i) => i.kind === 'entity');
        const stagedIds = new Set(staged.map((i) => i.id));
        for (const s of staged) {
            if (!entities.some((e) => e.id === s.id)) {
                const stagedEntity = Staging.readStagedEntity(packNameOf(packDir), s.id);
                if (stagedEntity) {
                    entities.push({ id: s.id, namespace: stagedEntity.namespace || '', displayName: stagedEntity.displayName || s.id, file: '(staged)', staged: true });
                }
            } else {
                const e = entities.find((x) => x.id === s.id);
                if (e) e.staged = true;
            }
        }
        return { ok: true, data: entities };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('entity:read', async (event, packDir, id) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // 暂存优先：有草稿读草稿，否则读正式包
        const staged = Staging.readStagedEntity(packNameOf(packDir), id);
        if (staged) {
            return { ok: true, data: Object.assign({}, staged, { _staged: true }) };
        }
        const entity = EntitySchema.readEntity(packDir, id);
        return { ok: true, data: entity };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('entity:save', async (event, packDir, entityJson) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // 校验（复用 EntitySchema 的校验逻辑）
        const { valid, errors } = EntitySchema.validateEntity(entityJson);
        if (!valid) {
            return { ok: false, error: '实体校验失败:\n' + errors.join('\n') };
        }
        // 写入暂存区（不直接写正式包）
        const result = Staging.stageEntity(packNameOf(packDir), entityJson);
        return { ok: true, data: Object.assign({}, result, { staged: true }) };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('entity:delete', async (event, packDir, id) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // 删除正式包 + 暂存区
        const result = EntitySchema.deleteEntity(packDir, id);
        Staging.removeStagedEntity(packNameOf(packDir), id);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 建筑结构（建筑拼图：blueprint.json → 原版 jigsaw 数据） ---

/**
 * 同包多结构的「同名片段/池」检查（0.2.1）：
 * 原版导出路径是扁平命名 `data/<ns>/structure/<piece>.nbt`、
 * `data/<ns>/worldgen/template_pool/<pool>.json`（路径不含 structureId），
 * 两个结构都叫 piece_1 / pool_1 时后导出者会静默覆盖前者的模板与池，
 * 两个结构随之串味。导出前先查冲突并拒绝写盘，让人先改名。
 * @returns {{pieceConflicts:string[], poolConflicts:string[]}}
 */
function findExportNameConflicts(packDir, bp, skipStructureId) {
    const res = { pieceConflicts: [], poolConflicts: [] };
    const minePieces = new Set((bp.pieces || []).map((p) => p.name).filter(Boolean));
    const minePools = new Set((bp.templatePools || []).map((p) => p.name).filter(Boolean));
    if (!minePieces.size && !minePools.size) return res;
    let others = [];
    try { others = StructureSchema.listStructures(packDir); } catch { others = []; }
    for (const s of others) {
        if (s.structureId === bp.structureId || s.structureId === skipStructureId) continue;
        let obp = null;
        try { obp = StructureSchema.readStructure(packDir, s.file); } catch { continue; }
        for (const p of obp.pieces || []) {
            if (p.name && minePieces.has(p.name)) res.pieceConflicts.push(`${p.name}（也被结构 ${s.structureId} 使用）`);
        }
        for (const pl of obp.templatePools || []) {
            if (pl.name && minePools.has(pl.name)) res.poolConflicts.push(`${pl.name}（也被结构 ${s.structureId} 使用）`);
        }
    }
    res.pieceConflicts = Array.from(new Set(res.pieceConflicts));
    res.poolConflicts = Array.from(new Set(res.poolConflicts));
    return res;
}

function exportConflictError(conf) {
    const parts = [];
    if (conf.pieceConflicts.length) parts.push('片段名冲突: ' + conf.pieceConflicts.join('、'));
    if (conf.poolConflicts.length) parts.push('模板池名冲突: ' + conf.poolConflicts.join('、'));
    return '导出会覆盖其它结构的原版数据文件（模板/池的文件路径不含结构名）：\n' + parts.join('\n') +
        '\n请把这些片段/池改名（加 <结构名>_ 前缀）后再导出——否则游戏里两个结构会互相串味。';
}

ipcMain.handle('structure:list', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // 合并：正式包蓝图 + 暂存区蓝图（暂存优先，避免重复）
        const structures = StructureSchema.listStructures(packDir);
        const staged = Staging.listStaged(packNameOf(packDir)).filter((i) => i.kind === 'structure');
        const stagedIds = new Set(staged.map((i) => i.id));
        for (const s of staged) {
            if (!structures.some((x) => x.structureId === s.id)) {
                const stagedBp = Staging.readStagedStructure(packNameOf(packDir), s.id);
                if (stagedBp) {
                    structures.push({
                        file: '(staged)',
                        structureId: s.id,
                        namespace: stagedBp.namespace || '',
                        pieces: (stagedBp.pieces || []).length,
                        pools: (stagedBp.templatePools || []).length,
                        staged: true,
                    });
                }
            } else {
                const x = structures.find((y) => y.structureId === s.id);
                if (x) x.staged = true;
            }
        }
        return { ok: true, data: structures };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('structure:read', async (event, packDir, relFile, structureId) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // REVIEW-L5：relFile 须为 structures/… 安全相对路径（防 .. 越界读）
        if (relFile && relFile !== '(staged)' && !StructureSchema.resolveStructureFile(packDir, relFile)) {
            return { ok: false, error: '非法的蓝图路径: ' + relFile };
        }
        // 暂存优先：按 structureId 匹配草稿
        const id = structureId
            || (relFile && relFile !== '(staged)' ? relFile.replace(/\\/g, '/').split('/').slice(-2, -1)[0] : '');
        if (id) {
            const staged = Staging.readStagedStructure(packNameOf(packDir), id);
            if (staged) return { ok: true, data: Object.assign({}, staged, { _staged: true }) };
        }
        if (!relFile || relFile === '(staged)') {
            return { ok: false, error: '未找到蓝图（正式包与暂存区均无）' };
        }
        const bp = StructureSchema.readStructure(packDir, relFile);
        return { ok: true, data: bp };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('structure:save', async (event, packDir, blueprint) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const { valid, errors } = StructureSchema.validateBlueprint(blueprint);
        if (!valid) {
            return { ok: false, error: '结构校验失败:\n' + errors.join('\n') };
        }
        const result = Staging.stageStructure(packNameOf(packDir), blueprint);
        return { ok: true, data: Object.assign({}, result, { staged: true }) };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

/**
 * 删除结构后清理它导出的原版数据文件（0.2.1）：
 * 此前只删 blueprint / 草稿，data/<ns>/… 里的结构 JSON、structure_set、模板、池全部残留
 * → 游戏里被删掉的结构还会继续生成。这里按蓝图记录的文件名删除，但**共用的模板/池不动**
 * （别的蓝图也引用同名文件时保留，避免删 A 破坏 B）。
 * @returns {string[]} 删除的相对路径
 */
function cleanupExportedStructure(packDir, bp) {
    const fsx = fs;
    const removed = [];
    if (!bp || !bp.structureId) return removed;
    const ns = bp.namespace || path.basename(packDir).toLowerCase().replace(/[^a-z0-9_.-]/g, '_');
    const sid = bp.structureId;
    const othersPiece = new Set();
    const othersPool = new Set();
    let others = [];
    try { others = StructureSchema.listStructures(packDir); } catch { others = []; }
    for (const s of others) {
        if (s.structureId === sid) continue;
        let obp = null;
        try { obp = StructureSchema.readStructure(packDir, s.file); } catch { continue; }
        for (const p of obp.pieces || []) if (p.name) othersPiece.add(p.name);
        for (const pl of obp.templatePools || []) if (pl.name) othersPool.add(pl.name);
    }
    const targets = [
        `data/${ns}/worldgen/structure/${sid}.json`,
        `data/${ns}/worldgen/structure_set/${sid}_set.json`,
    ];
    for (const p of bp.pieces || []) {
        if (p.name && !othersPiece.has(p.name)) targets.push(`data/${ns}/structure/${p.name}.nbt`);
    }
    for (const pl of bp.templatePools || []) {
        if (pl.name && !othersPool.has(pl.name)) targets.push(`data/${ns}/worldgen/template_pool/${pl.name}.json`);
    }
    for (const rel of targets) {
        const abs = path.join(packDir, rel);
        try {
            if (fsx.existsSync(abs) && fsx.statSync(abs).isFile()) {
                fsx.unlinkSync(abs);
                removed.push(rel);
            }
        } catch (e) {
            Logger.warn('structure', `清理旧导出文件失败: ${rel} — ${String(e.message || e)}`);
        }
    }
    return removed;
}

ipcMain.handle('structure:delete', async (event, packDir, relFile, structureId) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // REVIEW-L5：relFile 须为 structures/… 安全相对路径（防 .. 越界删）
        if (relFile && relFile !== '(staged)' && !StructureSchema.resolveStructureFile(packDir, relFile)) {
            return { ok: false, error: '非法的蓝图路径: ' + relFile };
        }
        const id = structureId
            || (relFile && relFile !== '(staged)' ? relFile.replace(/\\/g, '/').split('/').slice(-2, -1)[0] : '');
        // 0.2.1：删除前先读蓝图，用于清理它导出的 data/ 文件
        let bp = null;
        if (relFile && relFile !== '(staged)') {
            try { bp = StructureSchema.readStructure(packDir, relFile); } catch { bp = null; }
        } else if (id) {
            try { bp = Staging.readStagedStructure(packNameOf(packDir), id); } catch { bp = null; }
        }
        const result = relFile && relFile !== '(staged)'
            ? StructureSchema.deleteStructure(packDir, relFile)
            : { deleted: false };
        const cleaned = bp ? cleanupExportedStructure(packDir, bp) : [];
        if (id) Staging.removeStagedStructure(packNameOf(packDir), id);
        return { ok: true, data: Object.assign({}, result, { cleanedFiles: cleaned }) };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

/**
 * 把蓝图映射导出为原版 jigsaw 数据文件（写入包源码目录，随 build-pack.ps1 进 jar）。
 * 同时把当前蓝图写为正式 blueprint.json（仅本结构，不触碰其他暂存草稿）。
 */
ipcMain.handle('structure:exportVanilla', async (event, packDir, blueprint) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const { valid, errors } = StructureSchema.validateBlueprint(blueprint);
        if (!valid) {
            return { ok: false, error: '结构校验失败（无法导出）:\n' + errors.join('\n') };
        }
        // 0.2.1：同名片段/池会覆盖其它结构的原版文件 → 先拒绝，避免静默串味
        const conf = findExportNameConflicts(packDir, blueprint, blueprint.structureId);
        if (conf.pieceConflicts.length || conf.poolConflicts.length) {
            return { ok: false, error: exportConflictError(conf) };
        }
        // 1. 当前蓝图落盘为正式 blueprint.json（仅此结构）
        StructureSchema.writeStructure(packDir, blueprint);
        Staging.removeStagedStructure(packNameOf(packDir), blueprint.structureId);
        // 2. 映射导出原版数据文件
        const result = VanillaExport.exportToPack(packDir, blueprint);
        if (!result.ok) return { ok: false, error: result.errors.join('\n') };
        return { ok: true, data: { files: result.files } };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 一键导入原版（.nbt 模板 / 原版数据包 jar·zip / 数据包文件夹） ---
//
// 导入产物直接写成正式的 structures/<id>/blueprint.json（不是草稿），
// 之后就能在结构编辑器里改，再点「导出原版数据」写回 data/<ns>/... 的 4 类文件。
// 命名空间：默认取包目录名（如 structure-example-pack → structure_example_pack），
// 避免直接覆盖 data/minecraft（那会顶掉原版结构）。
function importVanillaInto(packDir, srcPath) {
    const rawNs = path.basename(packDir).toLowerCase().replace(/[^a-z0-9_.-]/g, '_');
    const namespace = /^[a-z0-9_.-]+$/.test(rawNs) && rawNs !== '.' && rawNs !== '..' ? rawNs : 'imported';
    const r = VanillaImport.importVanilla(srcPath, { namespace });
    const data = {
        source: srcPath,
        kind: r.kind,
        namespace,
        written: [],
        skipped: r.skipped || [],
        warnings: r.warnings || [],
        errors: r.errors || [],
    };
    if (!r.ok) {
        return { ok: false, error: data.errors.join('\n') || '导入失败', data };
    }
    const existing = new Set(StructureSchema.listStructures(packDir).map((s) => s.structureId));
    for (const b of r.blueprints) {
        const bp = b.blueprint;
        // structureId 冲突（重复导入同一个结构）→ 加 _2/_3 后缀，绝不覆盖已有蓝图
        let sid = bp.structureId;
        if (existing.has(sid)) {
            let k = 2;
            while (existing.has(sid + '_' + k)) k++;
            sid = sid + '_' + k;
        }
        bp.structureId = sid;
        existing.add(sid);
        const v = StructureSchema.validateBlueprint(bp);
        if (!v.valid) {
            data.errors.push(`${sid}: 蓝图校验失败: ${v.errors.slice(0, 3).join('; ')}`);
            continue;
        }
        try {
            StructureSchema.writeStructure(packDir, bp);
            data.written.push({
                id: sid,
                pieces: bp.pieces.length,
                pools: bp.templatePools.length,
                warnings: b.warnings.length,
                stats: b.stats || null,
                importFrom: (bp.importInfo && bp.importInfo.from) || '',
            });
        } catch (e) {
            data.errors.push(`${sid}: 写入失败: ${e.message}`);
        }
    }
    return { ok: data.written.length > 0, data, error: data.written.length ? undefined : (data.errors.join('\n') || '没有可写入的结构') };
}

ipcMain.handle('structure:importVanilla', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const result = await dialog.showOpenDialog(mainWindow, {
            title: '导入原版结构（.nbt 模板 / 数据包 .jar·.zip）',
            filters: [
                { name: '结构模板 / 数据包', extensions: ['nbt', 'jar', 'zip'] },
                { name: '全部文件', extensions: ['*'] },
            ],
            properties: ['openFile'],
        });
        if (result.canceled || !result.filePaths.length) return { ok: true, data: { canceled: true } };
        return importVanillaInto(packDir, result.filePaths[0]);
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('structure:importVanillaDir', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const result = await dialog.showOpenDialog(mainWindow, {
            title: '导入原版数据包文件夹（含 data/<命名空间>/worldgen）',
            properties: ['openDirectory'],
        });
        if (result.canceled || !result.filePaths.length) return { ok: true, data: { canceled: true } };
        return importVanillaInto(packDir, result.filePaths[0]);
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

/** 测试/自动化入口：直接给来源路径（不弹对话框） */
ipcMain.handle('structure:importVanillaPath', async (event, packDir, srcPath) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        if (!srcPath || typeof srcPath !== 'string' || !fs.existsSync(srcPath)) {
            return { ok: false, error: '来源不存在: ' + srcPath };
        }
        return importVanillaInto(packDir, srcPath);
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

/** 一键导出整包：所有蓝图 → 原版 4 类数据文件 */
ipcMain.handle('structure:exportVanillaAll', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // 0.2.1：整包导出前先查跨结构同名冲突（扁平文件路径会互相覆盖）
        const all = StructureSchema.listStructures(packDir);
        const conflicts = [];
        const used = new Map(); // name → structureId
        for (const s of all) {
            let bp = null;
            try { bp = StructureSchema.readStructure(packDir, s.file); } catch { continue; }
            for (const p of bp.pieces || []) {
                if (!p.name) continue;
                const key = 'piece:' + p.name;
                if (used.has(key) && used.get(key) !== s.structureId) {
                    conflicts.push(`片段 ${p.name}：${used.get(key)} 与 ${s.structureId} 同名`);
                } else used.set(key, s.structureId);
            }
            for (const pl of bp.templatePools || []) {
                if (!pl.name) continue;
                const key = 'pool:' + pl.name;
                if (used.has(key) && used.get(key) !== s.structureId) {
                    conflicts.push(`模板池 ${pl.name}：${used.get(key)} 与 ${s.structureId} 同名`);
                } else used.set(key, s.structureId);
            }
        }
        if (conflicts.length) {
            return {
                ok: false,
                error: '整包导出已中止：多个结构共用同名片段/池（原版文件路径不含结构名，会互相覆盖）\n' +
                    conflicts.slice(0, 20).map((c) => '· ' + c).join('\n') +
                    '\n请改名为 <结构名>_xxx 后再导出。',
            };
        }
        // 暂存草稿提醒（0.2.1）：整包导出只导出正式蓝图，草稿需要先「导出」
        const stagedItems = Staging.listStaged(packNameOf(packDir)).filter((i) => i.kind === 'structure');
        const r = VanillaExport.exportPack(packDir);
        return {
            ok: r.ok,
            data: Object.assign({}, r, { stagedNotExported: stagedItems.map((i) => i.id) }),
            error: r.ok ? undefined : (r.errors || []).join('\n'),
        };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 部位配置 ---
ipcMain.handle('health:read', async (event, packDir, namespace, entityId) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // 暂存优先
        const staged = Staging.readStagedHealthConfig(packNameOf(packDir), namespace, entityId);
        if (staged) {
            return { ok: true, data: Object.assign({}, staged, { _staged: true }) };
        }
        const config = HealthConfig.readHealthConfig(packDir, namespace, entityId);
        return { ok: true, data: config };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('health:save', async (event, packDir, namespace, entityId, config) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        // 校验
        const { valid, errors } = HealthConfig.validateHealthConfig(config);
        if (!valid) {
            return { ok: false, error: '部位配置校验失败:\n' + errors.join('\n') };
        }
        // 写入暂存区
        const result = Staging.stageHealthConfig(packNameOf(packDir), namespace, entityId, config);
        return { ok: true, data: Object.assign({}, result, { staged: true }) };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('health:delete', async (event, packDir, namespace, entityId) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const result = HealthConfig.deleteHealthConfig(packDir, namespace, entityId);
        // REVIEW-S1：只删本 (namespace, entityId) 的暂存 health 草稿。
        // 此前借用 removeStagedEntity 会连带删除 entities/<id>.json 实体草稿——
        // "无部位配置的实体保存草稿"（最常见保存路径）刚写入的实体草稿被误删，用户无感知。
        Staging.removeStagedHealthConfig(packNameOf(packDir), namespace, entityId);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 暂存区（草稿） ---
ipcMain.handle('staging:list', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const items = Staging.listStaged(packNameOf(packDir));
        return { ok: true, data: { packName: packNameOf(packDir), items, hasStaged: items.length > 0 } };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('staging:all', async () => {
    try {
        const packs = Staging.listAllStagedPacks();
        return { ok: true, data: packs };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 导出某包全部暂存 → 正式包 → 回收
ipcMain.handle('staging:export', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const result = Staging.exportStaged(packNameOf(packDir), packDir);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 丢弃某包全部暂存
ipcMain.handle('staging:discard', async (event, packDir) => {
    try {
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const result = Staging.discardStaged(packNameOf(packDir));
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('staging:getRoot', async () => {
    try {
        return { ok: true, data: Staging.getStagingRoot() };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 模型解析 ---
ipcMain.handle('model:parseFile', async (event, filePath) => {
    try {
        const tree = ModelParser.parseModelFile(filePath);
        return { ok: true, data: tree };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 从已打包 jar 中提取并解析 spark 模型（用于"导入"已有包的模型）
ipcMain.handle('model:parseFromJar', async (event, jarPath, modelPathInJar) => {
    try {
        const tree = await ModelParser.parseModelFromJar(jarPath, modelPathInJar);
        return { ok: true, data: tree };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 列出 jar 内的 spark 模型文件（导入时选择模型）
ipcMain.handle('model:listInJar', async (event, jarPath) => {
    try {
        const models = await ModelParser.listModelsInJar(jarPath);
        return { ok: true, data: models };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 模型自动预加载（根据实体的 model.id 在整个 contentpacks-source 查找） ---
// model.id 形如 "zombie3pack:modular_zombie_3"（type=entity → spark_models/entity/<ns>/<name>.json）
// 带内存缓存：同一模型 id 只解析一次
const modelCache = new Map();

ipcMain.handle('model:resolveFromId', async (event, modelId, modelType) => {
    try {
        if (!modelId) return { ok: true, data: null, cached: false };
        if (modelCache.has(modelId)) {
            return { ok: true, data: modelCache.get(modelId), cached: true };
        }
        // 拆 ns:name
        const sep = modelId.indexOf(':');
        const ns = sep >= 0 ? modelId.slice(0, sep) : 'catoxidesbattlerebuild';
        const name = sep >= 0 ? modelId.slice(sep + 1) : modelId;
        // REVIEW-S5：modelType（= entity.model.type，渲染端无白名单）此前直接拼入路径，
        // type='../../../..' 即逃逸包目录读任意 .json 并解析返回渲染端。
        const type = modelType || 'entity';
        if (type !== 'entity' && type !== 'item') return { ok: true, data: null, cached: false };
        // ns/name 字符集（model.id 正则允许 name 含 '/'，此处收紧为单文件名 + 拒绝 . / ..）
        const ID_SEG_RE = /^[a-z0-9_.-]+$/;
        if (!ID_SEG_RE.test(ns) || ns === '.' || ns === '..'
            || !ID_SEG_RE.test(name) || name === '.' || name === '..') {
            return { ok: true, data: null, cached: false };
        }
        // 在所有包的 spark_models/<type>/<ns>/<name>.json 下找
        const sourceDir = PACKS_SOURCE_DIR;
        if (!sourceDir || !fs.existsSync(sourceDir)) return { ok: true, data: null, cached: false };
        const rel = path.join('spark_models', type, ns, name + '.json');
        for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
            if (!entry.isDirectory()) continue;
            const file = path.join(sourceDir, entry.name, rel);
            // 纵深防御：落点必须仍在包目录内
            if (!file.startsWith(path.join(sourceDir, entry.name) + path.sep)) continue;
            if (fs.existsSync(file)) {
                const model = ModelParser.parseModelFile(file);
                modelCache.set(modelId, model);
                return { ok: true, data: model, cached: false };
            }
        }
        return { ok: true, data: null, cached: false };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 读取贴图文件 → data URL（物品/武器缩略图用）
// texture 形如 "zombie3pack:textures/entity/x.png" → 先在 contentpacks-source/*/assets/<ns>/ 找
// 找不到再回退到主 mod 资源 src/main/resources/assets/<ns>/
const textureCache = new Map();
// REVIEW-S4：texture 来自实体 JSON（恶意内容包可控）。此前 "ns:rest" 直接拼路径、
// 无扩展名过滤、无大小上限 → 实体列表打开即触发任意本地文件读取进渲染端
// （指向大文件还可 OOM/冻结主进程）。统一：ns 字符集 + rest 段校验 + .png/.jpg 白名单 + 大小上限。
const TEXTURE_MAX_BYTES = 16 * 1024 * 1024;
ipcMain.handle('model:readTexture', async (event, textureId) => {
    try {
        if (!textureId || typeof textureId !== 'string') return { ok: true, data: null };
        if (textureCache.has(textureId)) return { ok: true, data: textureCache.get(textureId) };
        const sep = textureId.indexOf(':');
        const ns = sep >= 0 ? textureId.slice(0, sep) : 'catoxidesbattlerebuild';
        const rest = sep >= 0 ? textureId.slice(sep + 1) : textureId; // textures/entity/x.png
        const nsOk = /^[a-z0-9_.-]+$/.test(ns) && ns !== '.' && ns !== '..' && !ns.includes(':');
        const restOk = typeof rest === 'string' && rest.length > 0
            && !rest.includes('\\') && !rest.startsWith('/')
            && rest.split('/').every((s) => s !== '' && s !== '.' && s !== '..');
        if (!nsOk || !restOk) return { ok: true, data: null };
        const ext = path.extname(rest).toLowerCase();
        if (ext !== '.png' && ext !== '.jpg' && ext !== '.jpeg') return { ok: true, data: null };
        const rel = path.join('assets', ns, rest);
        const candidates = [];
        // ① 各 contentpack 源目录
        const sourceDir = PACKS_SOURCE_DIR;
        if (sourceDir && fs.existsSync(sourceDir)) {
            for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
                if (!entry.isDirectory()) continue;
                candidates.push(path.join(sourceDir, entry.name, rel));
            }
        }
        // ② 主 mod 资源（贴图可能直接内置在主 mod 里）
        const mainAssets = path.join(ROOT, 'src', 'main', 'resources', 'assets', ns, rest);
        candidates.push(mainAssets);
        // ③ 已打包的 jar（contentpacks/*.jar 里的资源）——先不展开，靠前两种兜底
        for (const file of candidates) {
            if (!fs.existsSync(file)) continue;
            try {
                if (fs.statSync(file).size > TEXTURE_MAX_BYTES) continue; // 防大文件进 base64
            } catch { continue; }
            const buf = fs.readFileSync(file);
            const ext2 = path.extname(file).toLowerCase().replace('.', '');
            const mime = ext2 === 'png' ? 'image/png' : 'image/jpeg';
            const dataUrl = `data:${mime};base64,${buf.toString('base64')}`;
            textureCache.set(textureId, dataUrl);
            return { ok: true, data: dataUrl };
        }
        return { ok: true, data: null };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 文件对话框 ---
ipcMain.handle('dialog:openModelFile', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        title: '选择 spark 模型文件',
        filters: [{ name: 'Spark Model', extensions: ['json'] }],
        properties: ['openFile'],
    });
    if (result.canceled || result.filePaths.length === 0) return { ok: false, error: 'cancelled' };
    return { ok: true, data: result.filePaths[0] };
});

ipcMain.handle('dialog:openJarFile', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        title: '选择 contentpack jar',
        filters: [{ name: 'ContentPack', extensions: ['jar'] }],
        properties: ['openFile'],
    });
    if (result.canceled || result.filePaths.length === 0) return { ok: false, error: 'cancelled' };
    return { ok: true, data: result.filePaths[0] };
});

// --- 圈地读取：结构模板 .nbt → 蓝图片段 ---
// 支持 1.21.1 现行格式（blocks[].nbt 携带 jigsaw 数据，小端）与 1.20.x 旧格式；
// 字节序由 RegionImport/NBT.decodeAuto 自动判别（游戏=小端，部分工具/数据集=大端）。
ipcMain.handle('core:importRegion', async () => {
    try {
        const result = await dialog.showOpenDialog(mainWindow, {
            title: '圈地读取：选择结构模板 .nbt',
            filters: [{ name: 'Minecraft 结构模板', extensions: ['nbt'] }],
            properties: ['openFile'],
        });
        if (result.canceled || result.filePaths.length === 0) return { ok: true, canceled: true };
        const file = result.filePaths[0];
        const region = RegionImport.parseRegionNbt(fs.readFileSync(file));
        const base = path.basename(file, path.extname(file));
        return {
            ok: true,
            file: path.basename(file),
            piece: RegionImport.regionToPiece(region, base),
            info: {
                size: region.size,
                blocks: region.blocks.filter((b) => b.block !== 'minecraft:air').length,
                jigsaws: region.jigsaws.length,
                format: region.format,
                dataVersion: region.dataVersion,
                entities: region.entities,
            },
        };
    } catch (e) {
        return { ok: false, error: e.message };
    }
});

// --- 打包 ---
// 路径：① 缺失 build.gradle 先自动补齐 ② 预检（有 error 不打包）
//      ③ gradlew :packs:<目录名>:contentpackJar（首选）
//      ④ 失败则回退 contentpacks-source/build-pack.ps1
//      ⑤ 产物校验（MANIFEST 属性 / 实体条目 / 重复 Module-Name）
ipcMain.handle('pack:build', async (event, { packDir, outJarName, extraDeps } = {}) => {
    try {
        if (!ROOT) return { ok: false, error: '尚未设置项目根目录' };
        packDir = safePackDir(packDir);
        if (!packDir) return { ok: false, error: '非法的包目录（须位于包库或新建包目录内）' };
        const dirName = path.basename(packDir);
        // REVIEW-S7：dirName 拼进 Gradle 任务串（:packs:<dirName>:contentpackJar），
        // 字符集收紧到模块名规则（含 shell 元字符的恶意目录名在 zip/仓库拷贝场景可达）
        if (!/^[A-Za-z0-9._-]+$/.test(dirName)) {
            return { ok: false, error: `包目录名非法（仅允许字母/数字/._-）: ${dirName}` };
        }
        // REVIEW-S7 同族：outJarName 会拼进 contentpacks/… 并作为 build-pack.ps1 参数，收紧字符集
        if (outJarName !== undefined && outJarName !== null &&
            !(typeof outJarName === 'string' && /^[A-Za-z0-9._-]+\.jar$/.test(outJarName))) {
            return { ok: false, error: `outJarName 非法（仅允许字母/数字/._- 且以 .jar 结尾）: ${outJarName}` };
        }

        // 0.2.1：独立工作区 / 非 mod 工程根没有构建入口——直接给可操作提示，
        // 而不是让 powershell 抛「build-pack.ps1 不存在」这种看不懂的失败。
        const gradlewFile = path.join(ROOT, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
        const hasGradlew = fs.existsSync(gradlewFile);
        const hasBuildScript = !!(BUILD_SCRIPT && fs.existsSync(BUILD_SCRIPT));
        if (!hasGradlew && !hasBuildScript) {
            return {
                ok: false,
                error: `当前项目根没有可用的构建入口（既无 gradlew 也无 build-pack.ps1）：${ROOT}\n` +
                    (String(ROOT_SOURCE || '').startsWith('workspace')
                        ? '当前处于「独立工作区」模式（未连接 mod 工程）—— 打包需要 mod 工程的 Gradle 环境。' +
                          '请点顶栏「📁 连接工程…」选择工程根后再打包。'
                        : '请检查顶栏显示的项目根是否正确。'),
                data: { preflight: null, autoCreated: [], mode: null, verify: [] },
            };
        }

        // ① 自动补齐 Gradle 骨架（生成器的职责：让包天生就是 Gradle 包）
        const autoCreated = [];
        const gradleFile = path.join(packDir, 'build.gradle');
        if (!fs.existsSync(gradleFile)) {
            const manifest = ManifestLib.readManifest(packDir);
            fs.writeFileSync(gradleFile, Scaffold.renderBuildGradle(manifest['Module-Name'],
                { extraDeps: splitExtraDeps(extraDeps) }), 'utf-8');
            autoCreated.push('build.gradle');
        }

        // ② 预检
        const preflight = Preflight.preflightPack(packDir, { root: ROOT });
        if (!preflight.ok) {
            return {
                ok: false,
                error: `预检未通过（${preflight.errors.length} 个错误），已中止打包：\n` +
                    preflight.errors.map((e) => `· ${e.msg}  〔${e.src}〕`).join('\n'),
                data: { preflight, autoCreated, mode: null, verify: [] },
            };
        }

        // ③ Gradle 优先
        let mode = 'gradle';
        let stdout = '';
        let stderr = '';
        const task = `${Scaffold.gradleProjectPath(dirName)}:contentpackJar`;
        const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
        if (hasGradlew) {
            try {
                const r = await runCommand(gradlew, [task, '--console=plain'], { cwd: ROOT });
                stdout = r.stdout;
                stderr = r.stderr;
            } catch (e) {
                // 0.2.1：没有 build-pack.ps1 时不能再「回退」——否则用户看到的是
                // PowerShell 的「-File 不存在」，完全不知所云。直接把 Gradle 的错误返回。
                if (!hasBuildScript) {
                    return {
                        ok: false,
                        error: `Gradle 构建失败，且当前项目根没有 build-pack.ps1 可回退：\n${e.message}`,
                        data: { preflight, autoCreated, mode: 'gradle', stdout, stderr: e.message, jarPath: null, expectedJar: null, verify: [], gradleTask: task },
                    };
                }
                mode = 'ps1-fallback';
                stderr = `Gradle 构建失败，回退到 build-pack.ps1：\n${e.message}\n`;
                const outJar = path.join('contentpacks', outJarName || (preflight.manifest['Module-Name'] + '.jar'));
                const r = await runBuildScript(path.relative(ROOT, packDir), outJar);
                stdout = r.stdout;
                stderr += r.stderr;
            }
        } else {
            mode = 'ps1-fallback';
            stderr = '未找到 gradlew，使用 build-pack.ps1。\n';
            const outJar = path.join('contentpacks', outJarName || (preflight.manifest['Module-Name'] + '.jar'));
            const r = await runBuildScript(path.relative(ROOT, packDir), outJar);
            stdout = r.stdout;
            stderr += r.stderr;
        }

        // ④ 产物校验：构建成功 ≠ 能加载
        const moduleName = preflight.manifest['Module-Name'];
        const expectedJar = Scaffold.expectedJarName(moduleName);
        const jarPath = path.join(PACKS_OUT_DIR, expectedJar);
        const verify = [];
        if (!fs.existsSync(jarPath)) {
            verify.push({ level: 'error', code: 'E-JAR-MISSING', msg: `没有找到预期产物 ${path.relative(ROOT, jarPath)}`, src: 'contentpack.gradle: archiveFileName' });
        } else {
            const m = JarReader.readManifest(jarPath);
            if (!m) {
                verify.push({ level: 'error', code: 'E-JAR-NO-MANIFEST', msg: '产物 jar 里没有可解析的 MANIFEST.MF', src: 'ContentPackManifest.fromJar' });
            } else {
                if (m['Module-Type'] !== 'contentpack') {
                    verify.push({ level: 'error', code: 'E-JAR-TYPE', msg: `产物 Module-Type="${m['Module-Type'] || ''}"，必须是 contentpack，否则运行期会被静默跳过`, src: 'ContentPackManifest.parseAttributes' });
                }
                if (m['Module-Name'] !== moduleName) {
                    verify.push({ level: 'error', code: 'E-JAR-NAME', msg: `产物 Module-Name="${m['Module-Name'] || ''}" 与 MANIFEST 的 "${moduleName}" 不一致`, src: 'contentpack.gradle（MANIFEST 派生）' });
                }
            }
            const names = new Set(JarReader.listEntries(jarPath).map((e) => e.name));
            for (const ent of preflight.entities) {
                if (ent && ent.id && !names.has(`entities/${ent.id}.json`)) {
                    verify.push({ level: 'error', code: 'E-JAR-ENT-MISSING', msg: `产物里缺少 entities/${ent.id}.json（骨架会按这个路径读取，读不到就注册不了）`, src: 'TestMobPack.java:40' });
                }
            }
            verify.push({ level: 'ok', code: 'OK-JAR', msg: `产物 ${expectedJar}：${names.size} 个条目，MANIFEST 属性正确`, src: '' });
        }
        // 打包后再查一次重复 Module-Name（旧文件名残留是真实事故来源）
        verify.push(...Preflight.scanDuplicateModuleName(ROOT, moduleName, expectedJar));

        const verifyErrors = verify.filter((v) => v.level === 'error');
        const data = { preflight, autoCreated, mode, stdout, stderr, jarPath, expectedJar, verify, gradleTask: task };
        if (verifyErrors.length) {
            return { ok: false, error: `产物校验未通过：\n` + verifyErrors.map((v) => `· ${v.msg}`).join('\n'), data };
        }
        return { ok: true, data };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

/** 通用命令执行：捕获 stdout/stderr，非 0 退出即 reject */
function runCommand(cmd, args, opts = {}) {
    return new Promise((resolve, reject) => {
        // REVIEW-S7：去掉 Windows 的 shell:true —— 经 cmd.exe 解析时参数中的 & | ^ < >
        // 均可注入（已实证：包目录名 'x & calc' 点打包即执行任意命令）。
        // 直接 spawn 无需 shell：Windows 的 CreateProcess 对 .bat/.cmd 自带 cmd 包装，
        // 参数由 Node 以独立 argv 传入并整体加引号，元字符保持字面量。
        const child = spawn(cmd, args, Object.assign({ windowsHide: true }, opts));
        let stdout = '';
        let stderr = '';
        child.stdout.on('data', (d) => { stdout += d.toString(); });
        child.stderr.on('data', (d) => { stderr += d.toString(); });
        child.on('close', (code) => {
            if (code === 0) resolve({ code, stdout, stderr });
            else reject(new Error(`${cmd} 退出码 ${code}\n${stdout}\n${stderr}`));
        });
        child.on('error', (err) => reject(err));
    });
}

function runBuildScript(sourceDir, outJar) {
    return new Promise((resolve, reject) => {
        const args = [
            '-ExecutionPolicy', 'Bypass',
            '-File', BUILD_SCRIPT,
            '-SourceDir', sourceDir,
            '-OutJar', outJar,
        ];
        const child = spawn('powershell', args, { cwd: ROOT, windowsHide: true });
        let stdout = '';
        let stderr = '';
        child.stdout.on('data', (d) => { stdout += d.toString(); });
        child.stderr.on('data', (d) => { stderr += d.toString(); });
        child.on('close', (code) => {
            if (code === 0) {
                resolve({ code, stdout, stderr });
            } else {
                reject(new Error(`打包失败 (exit ${code})\n${stdout}\n${stderr}`));
            }
        });
        child.on('error', (err) => reject(err));
    });
}

// 暴露核心库给渲染进程（元数据/模板，无文件操作的部分）
ipcMain.handle('core:getStateMachineTemplates', () => ({
    ok: true,
    data: StateMachine.TEMPLATES,
}));

ipcMain.handle('core:getStructureDefaults', () => ({
    ok: true,
    data: {
        blueprint: StructureSchema.defaultBlueprint(),
        piece: StructureSchema.defaultPiece(),
        jigsaw: StructureSchema.defaultJigsaw(),
        poolElement: StructureSchema.defaultPoolElement(),
        constants: {
            steps: StructureSchema.STEPS,
            terrainAdaptations: StructureSchema.TERRAIN_ADAPTATIONS,
            projections: StructureSchema.PROJECTIONS,
            joints: StructureSchema.JOINTS,
            orientations: StructureSchema.ORIENTATIONS,
        },
    },
}));

ipcMain.handle('core:getEntityDefaults', () => ({
    ok: true,
    data: EntitySchema.createDefaultEntity(),
}));

// ===== 游戏资源（vanilla 方块贴图，本机自有副本） =====
ipcMain.handle('core:mcAssets', () => {
    const root = McAssets.resolveRoot(ROOT);
    if (!root) return { ok: true, data: { ready: false, root: null, blockCount: 0 } };
    const list = McAssets.blockList(root);
    return { ok: true, data: { ready: true, root, blockCount: list.length } };
});

ipcMain.handle('core:mcBlockFaces', (event, blockId) => {
    const root = McAssets.resolveRoot(ROOT);
    if (!root) return { ok: true, data: null };
    const faces = McAssets.blockFaces(root, blockId);
    return { ok: true, data: Object.keys(faces).length ? faces : null };
});

// P15 槽位缓存栏图标：原版物品栏逻辑（物品纹理优先，方块面贴图回退）
ipcMain.handle('core:mcBlockIcon', (event, blockId) => {
    const root = McAssets.resolveRoot(ROOT);
    if (!root) return { ok: true, data: null };
    const r = McAssets.blockIcon(root, blockId);
    return { ok: true, data: r.icon ? r : null };
});

// P11 右键查方块：读 blockstate 定义（属性/朝向/变体数）
// P13 通用链路：vanilla 数据集 → 内容包 → 资源包/数据包 → mod jar
ipcMain.handle('core:blockState', (event, blockId, opts) => {
    try {
        const r = McAssets.blockStateAny(ROOT, blockId, opts || {});
        return { ok: true, data: r && r.data, source: r && r.source, file: r && r.data && r.data.file };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// P14 方块外观：vanilla 标准链路完整烘焙（blockstate variant → 模型 parent 链 →
// 元素面顶点/UV/旋转/uvlock + 贴图 dataURL）；渲染端按 blockId[props] 缓存
ipcMain.handle('core:blockAppearance', (event, blockId, opts) => {
    try {
        const r = McAssets.blockAppearance(ROOT, blockId, opts || {});
        return r && r.ok ? { ok: true, data: r.data } : { ok: false, error: r && r.error };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// P10：实时读取游戏将加载的方块（vanilla 数据集 + run/data + run/mods），30s 缓存，force 刷新
ipcMain.handle('core:mcBlockList', (event, opts) => {
    try {
        const r = McBlocks.scanBlocks(ROOT, { force: !!(opts && opts.force) });
        return { ok: true, data: r.ids, meta: r.meta };
    } catch (e) {
        // 兜底：仅 vanilla 静态列表
        try {
            const root = McAssets.resolveRoot(ROOT);
            return { ok: true, data: root ? McAssets.blockList(root) : [], meta: null };
        } catch { return { ok: false, error: String(e.message || e) }; }
    }
});

// ==================== W5 AI API（内嵌 HTTP 端点，仅 127.0.0.1） ====================
// 应用启动、项目根就绪后自动监听，供 AI/CLI 调用（与 UI 共享 src/core/* 同一套核心逻辑）。
// 空闲开销 ≈ 一个监听 socket（无轮询、无定时器）；每个请求执行与 UI 相同的文件操作。
// 端点：GET /status（健康检查）；POST /invoke {"cmd":"entity.get","args":{...}} → {ok,data|error}
// 端口发现：写 <项目根>/logs/contentpack-editor/api.json（{port,pid,root,startedAt}）
const API_HOST = '127.0.0.1';
const API_PORT = 48900;
let apiServer = null;
let apiPort = null;

function apiStatusFile() {
    const dir = Logger.getLogDir();
    return dir ? path.join(dir, 'api.json') : null;
}

function startEditorApi() {
    if (apiServer || !PACKS_SOURCE_DIR) return;
    const server = http.createServer((req, res) => { handleApiRequest(req, res); });
    const tryListen = (port) => new Promise((resolve, reject) => {
        const once = (err) => { server.removeListener('error', once); err ? reject(err) : resolve(); };
        server.once('error', once);
        server.listen(port, API_HOST, once);
    });
    (async () => {
        for (let p = API_PORT; p < API_PORT + 10; p++) {
            try { await tryListen(p); apiPort = p; break; } catch { /* 端口占用，试下一个 */ }
        }
        if (!apiPort) {
            Logger.error('api', 'AI API 启动失败：48900-48909 端口全部被占用');
            try { server.close(); } catch { /* 忽略 */ }
            return;
        }
        apiServer = server;
        const sf = apiStatusFile();
        if (sf) {
            try {
                fs.writeFileSync(sf, JSON.stringify({ port: apiPort, pid: process.pid, root: ROOT, startedAt: new Date().toISOString() }, null, 2), 'utf-8');
            } catch (e) { Logger.warn('api', `状态文件写入失败: ${e.message}`); }
        }
        Logger.info('api', `AI API 监听 http://${API_HOST}:${apiPort}（仅本机；状态文件: ${sf || '未写入'}）`);
    })();
}

function stopEditorApi() {
    const sf = apiStatusFile();
    if (sf) { try { if (fs.existsSync(sf)) fs.unlinkSync(sf); } catch { /* 忽略 */ } }
    if (apiServer) { try { apiServer.close(); } catch { /* 忽略 */ } apiServer = null; apiPort = null; }
}

function handleApiRequest(req, res) {
    const send = (code, obj) => {
        const body = JSON.stringify(obj, null, 2);
        res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
        res.end(body);
    };
    if (req.method === 'GET' && (req.url === '/' || req.url === '/status')) {
        return send(200, {
            ok: true,
            data: { name: 'contentpack-editor', version: app.getVersion(), port: apiPort, pid: process.pid, root: ROOT, uptimeSec: Math.round(process.uptime()) },
        });
    }
    if (req.method !== 'POST' || req.url !== '/invoke') {
        return send(404, { ok: false, error: 'Not found（可用: GET /status, POST /invoke）' });
    }
    // REVIEW-0.2.0 遗留（AI API 无鉴权）的浏览器侧补强：本机浏览器里的任意网页都能对
    // 127.0.0.1 发「简单请求」POST（text/plain，不触发 CORS 预检）→ 等于任意网页可调本 API。
    // 两道闸：① 带 Origin 头 = 来自浏览器（CLI/AI 客户端不带）→ 拒绝；② 必须是 JSON content-type。
    // 真正的强鉴权（token）见 REVIEW-0.2.0 L11 建议，未做以免破坏既有 CLI 用法。
    const origin = req.headers.origin;
    if (origin) return send(403, { ok: false, error: '拒绝浏览器跨源调用（Origin: ' + origin + '）' });
    const ctype = String(req.headers['content-type'] || '');
    if (!/^application\/json\b/i.test(ctype)) {
        return send(415, { ok: false, error: 'Content-Type 必须是 application/json' });
    }
    let body = '';
    let aborted = false;
    req.on('data', (d) => {
        body += d;
        if (body.length > 10 * 1024 * 1024) { aborted = true; req.destroy(); } // 10MB 上限
    });
    req.on('end', async () => {
        if (aborted) return;
        let payload;
        try { payload = JSON.parse(body || '{}'); } catch { return send(400, { ok: false, error: '请求体不是合法 JSON' }); }
        const ctx = { root: ROOT, packsSourceDir: PACKS_SOURCE_DIR, packsOutDir: PACKS_OUT_DIR };
        try {
            const result = await EditorApi.invoke(String(payload.cmd || ''), payload.args || {}, ctx);
            Logger.info('api', `${result.ok ? 'OK' : 'ERR'} ${payload.cmd} ${result.ok ? '' : '→ ' + result.error}`);
            send(result.ok ? 200 : 400, result);
        } catch (e) {
            Logger.error('api', `invoke ${payload.cmd} 异常: ${e.message}`);
            send(500, { ok: false, error: e.message });
        }
    });
}

// ==================== 启动 ====================
// W4.8: 单实例锁——此前重复启动应用会新建实例，旧窗口（含 renderer 崩溃后的冻屏窗口）
// 叠加在屏幕上，"点错窗口"造成"找不到 UV 按钮 / 大纲空白"等一连串假故障。
// 现在：重复启动 = 聚焦已有主窗口。
const gotInstanceLock = app.requestSingleInstanceLock();
if (!gotInstanceLock) {
    // 已有实例在运行 → 立即退出（首个实例收到 second-instance 事件并聚焦窗口）
    app.quit();
} else {
    app.on('second-instance', () => {
        if (mainWindow && !mainWindow.isDestroyed()) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
        }
    });
}

app.whenReady().then(() => {
    if (!gotInstanceLock) return; // 第二实例自行退出（second-instance 会聚焦已有窗口）
    // 初始化暂存区（app 私有目录，独立运行）
    Staging.initStaging(app.getPath('userData'));
    // 0.2.1：项目根解析（配置 → 探测 → 独立工作区），**保证有结果**，
    // 不再出现"无工程 → 渲染端引导后 return → 按钮全死"的反馈机事故。
    const decided = resolveRootWithFallback();
    if (decided && decided.root) {
        applyProjectRoot(decided.root, decided.source);
        Logger.info('config', `项目根来源: ${decided.source}${decided.created && decided.created.length ? '（新建目录 ' + decided.created.length + ' 个）' : ''}`);
        if (String(decided.source || '').startsWith('workspace')) {
            Logger.warn('config', `独立工作区模式：${decided.root}（未连接 mod 工程；顶栏「连接工程…」可切换）`);
        }
    } else {
        Logger.error('config', '项目根解析与工作区创建均失败: ' + ((decided && decided.error) || '未知原因') +
            '（界面会提示选择项目目录，其余功能保持可点击）');
    }
    // W5.3: 启动诊断——记录当前渲染模式与 GPU 型号（日后排查驱动级崩溃用）
    if (readConfig().softwareRendering) {
        Logger.info('config', '当前为软件渲染模式（--disable-gpu，绕过 GPU 驱动）');
    } else {
        try {
            app.getGPUInfo('complete').then((info) => {
                // gpuModel 在部分 Windows 上为空 → 回退 glRenderer（ANGLE 渲染器串）
                const g = info && ((info.gpuModel && info.gpuModel.value) || (info.glRenderer && info.glRenderer.value));
                const mem = info && info.gpuMemory && info.gpuMemory.value;
                Logger.info('config', 'GPU: ' + (g || '未知') + (mem ? '（显存 ' + Math.round(mem / 1048576) + 'MB）' : ''));
            }).catch(() => { /* GPU 信息获取失败不影响启动 */ });
        } catch { /* 同上 */ }
    }
    createWindow();
    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

// 关闭前：若有暂存草稿则提示（丢弃/保留）
// REVIEW-M4：stopEditorApi 原本在弹窗【之前】无条件执行——选"取消"留在应用时
// apiServer 已关、api.json 已删，而 startEditorApi 只在启动/项目根就绪时调用
// → 本次会话 AI API/CLI 永久失联（状态栏/日志无提示）。
// 现在：只有真正退出的分支才停 API；"取消"留在应用则 API 继续服务。
app.on('before-quit', (event) => {
    // W5.3: 渲染模式重启——草稿在磁盘暂存区不丢，跳过弹窗直接放行
    if (forceQuitNoPrompt) { stopEditorApi(); return; }
    let canceled = false;
    try {
        const stagedPacks = Staging.listAllStagedPacks();
        if (stagedPacks.length > 0 && mainWindow) {
            const total = stagedPacks.reduce((s, p) => s + p.items.length, 0);
            event.preventDefault();
            const choice = dialog.showMessageBoxSync(mainWindow, {
                type: 'warning',
                title: '未导出的草稿',
                message: `有 ${total} 个暂存修改未导出（${stagedPacks.map((p) => p.packName).join(', ')}）`,
                detail: '「保留草稿」下次打开还能继续编辑；「丢弃」将删除全部暂存。\n导出请在各包的"导出"按钮操作。',
                buttons: ['保留草稿', '丢弃并退出', '取消'],
                defaultId: 0,
                cancelId: 2,
            });
            if (choice === 0) {
                // 保留草稿：退出，不清暂存（app.exit 不再触发 before-quit，须先停 API）
                stopEditorApi();
                app.exit(0);
                return;
            } else if (choice === 1) {
                // 丢弃：清空暂存后退出
                for (const p of stagedPacks) {
                    Staging.discardStaged(p.packName);
                }
                stopEditorApi();
                app.exit(0);
                return;
            }
            canceled = true; // choice === 2（取消）：留在应用
        }
    } catch (e) {
        // 提示失败不阻塞退出
    }
    if (!canceled) stopEditorApi();
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
});
