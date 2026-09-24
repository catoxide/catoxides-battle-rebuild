// main.js - Electron 主进程
// 负责：窗口创建 + 所有文件系统操作（读写包/实体/模型 + 调打包脚本）
// 渲染进程不接触 Node API（contextIsolation），只通过 preload 暴露的 window.api 通信
const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

// 显式设置应用名：打包后 portable exe 的默认 userData 名可能退化为 "Electron"
app.setName('ContentPack Editor');

// ==================== 核心库（纯 JS，可被 node 测试） ====================
const ManifestLib = require('./src/core/manifest');
const ModelParser = require('./src/core/modelParser');
const EntitySchema = require('./src/core/entitySchema');
const HealthConfig = require('./src/core/healthConfig');
const StateMachine = require('./src/core/stateMachine');
const Staging = require('./src/core/staging');
const StructureSchema = require('./src/core/structureSchema');
const VanillaExport = require('./src/core/vanillaExport');
const RegionImport = require('./src/core/regionImport');
const McAssets = require('./src/core/mcAssets');
const McBlocks = require('./src/core/mcBlocks');

// ==================== 项目根解析（支持打包后 exe 运行） ====================
// 优先级：① 内部配置（userData/config.json）→ ② 自动向上探测 contentpacks-source/
//        → ③ 均失败则窗口内弹对话框选择（app:setProjectRoot 保存）
// 打包为 portable 单文件 exe 后 __dirname 指向 asar 内部，不能用它定位项目根。
let ROOT = null;
let PACKS_SOURCE_DIR = null;
let PACKS_OUT_DIR = null;
let BUILD_SCRIPT = null;

function configFilePath() {
    return path.join(app.getPath('userData'), 'config.json');
}

function readConfig() {
    try {
        const file = configFilePath();
        if (fs.existsSync(file)) return JSON.parse(fs.readFileSync(file, 'utf-8'));
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

function applyProjectRoot(root) {
    ROOT = root;
    PACKS_SOURCE_DIR = path.join(ROOT, 'contentpacks-source');
    PACKS_OUT_DIR = path.join(ROOT, 'contentpacks');
    BUILD_SCRIPT = path.join(ROOT, 'contentpacks-source', 'build-pack.ps1');
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
    // 开发时打开 devtools
    // mainWindow.webContents.openDevTools();
}

// ==================== P11 片段 3D 编辑器窗口（独立窗口，Photoshop/Blender 式布局） ====================
// 数据流：主窗口 tab 是唯一事实源 → 打开时把 blueprint 推给编辑器窗口；
// 编辑器窗口内存中编辑 → "写回"（或关窗前自动写回）→ 主窗口合并进 tab（保存草稿时落盘）。
let pieceEditorWin = null;
let pieceEditorPending = null; // 等待页面加载完成后投递的 payload（防 send 早于渲染脚本注册的竞态）

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
    pieceEditorWin.on('closed', () => { pieceEditorWin = null; pieceEditorPending = null; });
    return pieceEditorWin;
}

// 打开/聚焦编辑器窗口并推送蓝图（payload: {packDir, structureId, pieceName, blueprint}）
ipcMain.handle('pieceEditor:open', (event, payload) => {
    try {
        if (!pieceEditorWin || pieceEditorWin.isDestroyed()) {
            pieceEditorPending = payload;
            createPieceEditorWindow(); // 页面加载完成后由 did-finish-load 投递
        } else {
            pieceEditorPending = payload;
            if (!pieceEditorWin.webContents.isLoading()) deliverEditorPayload(); // 已加载 → 立即投递
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

// ==================== IPC 处理器 ====================
// 每个 handler 返回 { ok, data|error }，渲染进程统一处理

ipcMain.handle('app:getPaths', () => {
    if (!ROOT) {
        return { ok: true, data: { needProjectRoot: true, packsSourceDir: null, packsOutDir: null, root: null } };
    }
    return {
        ok: true,
        data: { packsSourceDir: PACKS_SOURCE_DIR, packsOutDir: PACKS_OUT_DIR, root: ROOT },
    };
});

// 用户选择项目根（应用内对话框 → 保存到内部配置 → 生效）
ipcMain.handle('app:setProjectRoot', async (event, rootDir) => {
    try {
        if (!rootDir || !fs.existsSync(path.join(rootDir, 'contentpacks-source'))) {
            return { ok: false, error: '所选目录不是有效的项目根（缺少 contentpacks-source）' };
        }
        applyProjectRoot(rootDir);
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
    applyProjectRoot(dir);
    saveConfig({ projectRoot: dir });
    return { ok: true, data: { packsSourceDir: PACKS_SOURCE_DIR, packsOutDir: PACKS_OUT_DIR, root: ROOT } };
});

// --- 包管理 ---
ipcMain.handle('pack:list', async () => {
    try {
        if (!ROOT) return { ok: false, error: '尚未设置项目根目录' };
        const packs = ManifestLib.scanPacks(PACKS_SOURCE_DIR);
        return { ok: true, data: packs };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('pack:create', async (event, { name, version, requires, deps, modRequires }) => {
    try {
        if (!ROOT) return { ok: false, error: '尚未设置项目根目录' };
        const dir = path.join(PACKS_SOURCE_DIR, name);
        if (fs.existsSync(dir)) {
            return { ok: false, error: `目录已存在: ${dir}` };
        }
        fs.mkdirSync(path.join(dir, 'entities'), { recursive: true });
        const manifest = {
            'Module-Name': name,
            'Module-Version': version || '0.1.0',
            'Module-Type': 'contentpack',
            'Module-Requires': requires || 'catoxidesbattlerebuild>=0.2.0',
        };
        if (deps) manifest['Module-Dependencies'] = deps;
        if (modRequires) manifest['Module-Mod-Requires'] = modRequires;
        ManifestLib.writeManifest(dir, manifest);
        const packs = ManifestLib.scanPacks(PACKS_SOURCE_DIR);
        return { ok: true, data: { packs, created: dir } };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('pack:readManifest', async (event, packDir) => {
    try {
        const manifest = ManifestLib.readManifest(packDir);
        return { ok: true, data: manifest };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('pack:saveManifest', async (event, packDir, manifest) => {
    try {
        ManifestLib.writeManifest(packDir, manifest);
        return { ok: true, data: null };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 实体 ---
// 工具函数：packDir 的包名（用于暂存区隔离）
function packNameOf(packDir) {
    return path.basename(packDir);
}

ipcMain.handle('entity:list', async (event, packDir) => {
    try {
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
        // 删除正式包 + 暂存区
        const result = EntitySchema.deleteEntity(packDir, id);
        Staging.removeStagedEntity(packNameOf(packDir), id);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 建筑结构（建筑拼图：blueprint.json → 原版 jigsaw 数据） ---
ipcMain.handle('structure:list', async (event, packDir) => {
    try {
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

ipcMain.handle('structure:delete', async (event, packDir, relFile, structureId) => {
    try {
        const id = structureId
            || (relFile && relFile !== '(staged)' ? relFile.replace(/\\/g, '/').split('/').slice(-2, -1)[0] : '');
        const result = relFile && relFile !== '(staged)'
            ? StructureSchema.deleteStructure(packDir, relFile)
            : { deleted: false };
        if (id) Staging.removeStagedStructure(packNameOf(packDir), id);
        return { ok: true, data: result };
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
        const { valid, errors } = StructureSchema.validateBlueprint(blueprint);
        if (!valid) {
            return { ok: false, error: '结构校验失败（无法导出）:\n' + errors.join('\n') };
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

// --- 部位配置 ---
ipcMain.handle('health:read', async (event, packDir, namespace, entityId) => {
    try {
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
        const result = HealthConfig.deleteHealthConfig(packDir, namespace, entityId);
        Staging.removeStagedEntity(packNameOf(packDir), entityId);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 暂存区（草稿） ---
ipcMain.handle('staging:list', async (event, packDir) => {
    try {
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
        const result = Staging.exportStaged(packNameOf(packDir), packDir);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// 丢弃某包全部暂存
ipcMain.handle('staging:discard', async (event, packDir) => {
    try {
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
        const type = modelType || 'entity';
        // 在所有包的 spark_models/<type>/<ns>/<name>.json 下找
        const sourceDir = PACKS_SOURCE_DIR;
        if (!sourceDir || !fs.existsSync(sourceDir)) return { ok: true, data: null, cached: false };
        const rel = path.join('spark_models', type, ns, name + '.json');
        for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
            if (!entry.isDirectory()) continue;
            const file = path.join(sourceDir, entry.name, rel);
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
ipcMain.handle('model:readTexture', async (event, textureId) => {
    try {
        if (!textureId) return { ok: true, data: null };
        if (textureCache.has(textureId)) return { ok: true, data: textureCache.get(textureId) };
        const sep = textureId.indexOf(':');
        const ns = sep >= 0 ? textureId.slice(0, sep) : 'catoxidesbattlerebuild';
        const rest = sep >= 0 ? textureId.slice(sep + 1) : textureId; // textures/entity/x.png
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
            if (fs.existsSync(file)) {
                const buf = fs.readFileSync(file);
                const ext = path.extname(file).toLowerCase().replace('.', '');
                const mime = ext === 'png' ? 'image/png' : ext === 'jpg' || ext === 'jpeg' ? 'image/jpeg' : 'image/png';
                const dataUrl = `data:${mime};base64,${buf.toString('base64')}`;
                textureCache.set(textureId, dataUrl);
                return { ok: true, data: dataUrl };
            }
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
ipcMain.handle('pack:build', async (event, { packDir, outJarName }) => {
    try {
        const sourceRel = path.relative(ROOT, packDir);
        const outJar = path.join('contentpacks', outJarName || (path.basename(packDir) + '.jar'));
        const result = await runBuildScript(sourceRel, outJar);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

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

// ==================== 启动 ====================
app.whenReady().then(() => {
    // 初始化暂存区（app 私有目录，独立运行）
    Staging.initStaging(app.getPath('userData'));
    // 尝试解析项目根（配置优先 → 自动探测）；失败时窗口内引导用户选择
    const root = tryResolveProjectRoot();
    if (root) applyProjectRoot(root);
    createWindow();
    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

// 关闭前：若有暂存草稿则提示（丢弃/保留）
app.on('before-quit', (event) => {
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
                // 保留草稿：退出，不清暂存
                app.exit(0);
            } else if (choice === 1) {
                // 丢弃：清空暂存后退出
                for (const p of stagedPacks) {
                    Staging.discardStaged(p.packName);
                }
                app.exit(0);
            }
            // choice === 2（取消）：留在应用
        }
    } catch (e) {
        // 提示失败不阻塞退出
    }
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
});
