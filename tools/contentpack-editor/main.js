// main.js - Electron 主进程
// 负责：窗口创建 + 所有文件系统操作（读写包/实体/模型 + 调打包脚本）
// 渲染进程不接触 Node API（contextIsolation），只通过 preload 暴露的 window.api 通信
const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

// ==================== 核心库（纯 JS，可被 node 测试） ====================
const ManifestLib = require('./src/core/manifest');
const ModelParser = require('./src/core/modelParser');
const EntitySchema = require('./src/core/entitySchema');
const HealthConfig = require('./src/core/healthConfig');
const StateMachine = require('./src/core/stateMachine');

// 项目根（editor 目录的上级的上级）
const ROOT = path.resolve(__dirname, '..', '..');
const PACKS_SOURCE_DIR = path.join(ROOT, 'contentpacks-source');
const PACKS_OUT_DIR = path.join(ROOT, 'contentpacks');
const BUILD_SCRIPT = path.join(ROOT, 'contentpacks-source', 'build-pack.ps1');

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

// ==================== IPC 处理器 ====================
// 每个 handler 返回 { ok, data|error }，渲染进程统一处理

ipcMain.handle('app:getPaths', () => ({
    ok: true,
    data: {
        packsSourceDir: PACKS_SOURCE_DIR,
        packsOutDir: PACKS_OUT_DIR,
        root: ROOT,
    },
}));

// --- 包管理 ---
ipcMain.handle('pack:list', async () => {
    try {
        const packs = ManifestLib.scanPacks(PACKS_SOURCE_DIR);
        return { ok: true, data: packs };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('pack:create', async (event, { name, version, requires, deps, modRequires }) => {
    try {
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
ipcMain.handle('entity:list', async (event, packDir) => {
    try {
        const entities = EntitySchema.listEntities(packDir);
        return { ok: true, data: entities };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('entity:read', async (event, packDir, id) => {
    try {
        const entity = EntitySchema.readEntity(packDir, id);
        return { ok: true, data: entity };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('entity:save', async (event, packDir, entityJson) => {
    try {
        const result = EntitySchema.saveEntity(packDir, entityJson);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('entity:delete', async (event, packDir, id) => {
    try {
        const result = EntitySchema.deleteEntity(packDir, id);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

// --- 部位配置 ---
ipcMain.handle('health:read', async (event, packDir, namespace, entityId) => {
    try {
        const config = HealthConfig.readHealthConfig(packDir, namespace, entityId);
        return { ok: true, data: config };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('health:save', async (event, packDir, namespace, entityId, config) => {
    try {
        const result = HealthConfig.saveHealthConfig(packDir, namespace, entityId, config);
        return { ok: true, data: result };
    } catch (e) {
        return { ok: false, error: String(e.message || e) };
    }
});

ipcMain.handle('health:delete', async (event, packDir, namespace, entityId) => {
    try {
        const result = HealthConfig.deleteHealthConfig(packDir, namespace, entityId);
        return { ok: true, data: result };
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

ipcMain.handle('core:getEntityDefaults', () => ({
    ok: true,
    data: EntitySchema.createDefaultEntity(),
}));

// ==================== 启动 ====================
app.whenReady().then(() => {
    createWindow();
    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
});
