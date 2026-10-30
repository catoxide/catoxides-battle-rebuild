// preload.js - 渲染进程桥接
// 暴露 window.api，所有 IPC 调用返回 { ok, data|error }
// 通道名与 main.js 中 ipcMain.handle 一一对应
const { contextBridge, ipcRenderer, webUtils } = require('electron');

const api = {
    // 路径信息
    getPaths: () => ipcRenderer.invoke('app:getPaths'),
    setProjectRoot: (rootDir) => ipcRenderer.invoke('app:setProjectRoot', rootDir),
    pickProjectRoot: () => ipcRenderer.invoke('app:pickProjectRoot'),
    // W6.1 包路径设置（包库 / 新建包目录；null = 复位默认）
    getPackPaths: () => ipcRenderer.invoke('settings:getPackPaths'),
    setPackPaths: (payload) => ipcRenderer.invoke('settings:setPackPaths', payload),
    pickPackDir: (which) => ipcRenderer.invoke('app:pickPackDir', which),

    // 包管理
    listPacks: () => ipcRenderer.invoke('pack:list'),
    createPack: (data) => ipcRenderer.invoke('pack:create', data),
    readManifest: (packDir) => ipcRenderer.invoke('pack:readManifest', packDir),
    saveManifest: (packDir, manifest) => ipcRenderer.invoke('pack:saveManifest', packDir, manifest),
    // 预检与骨架（Gradle 子项目 / 入口类 / 重复 Module-Name）
    preflightPack: (packDir) => ipcRenderer.invoke('pack:preflight', packDir),
    ensureScaffold: (packDir, opts) => ipcRenderer.invoke('pack:ensureScaffold', packDir, opts),

    // 编辑器契约（Module-Editor-Schema）：只读盘点 + 校验
    readContract: (packDir) => ipcRenderer.invoke('contract:read', packDir),

    // TimeTape 状态机编辑器（W2）：包内 .json 读/写（p = {pack, rel[, content]}）
    tapeReadFile: (p) => ipcRenderer.invoke('tape:readFile', p),
    tapeSaveFile: (p) => ipcRenderer.invoke('tape:saveFile', p),
    // 画布布局 sidecar（编辑器状态目录，不进包）
    tapeReadLayout: (p) => ipcRenderer.invoke('tape:readLayout', p),
    tapeSaveLayout: (p) => ipcRenderer.invoke('tape:saveLayout', p),

    // 实体
    listEntities: (packDir) => ipcRenderer.invoke('entity:list', packDir),
    readEntity: (packDir, id) => ipcRenderer.invoke('entity:read', packDir, id),
    saveEntity: (packDir, entityJson) => ipcRenderer.invoke('entity:save', packDir, entityJson),
    deleteEntity: (packDir, id) => ipcRenderer.invoke('entity:delete', packDir, id),

    // 部位配置
    readHealth: (packDir, namespace, entityId) => ipcRenderer.invoke('health:read', packDir, namespace, entityId),
    saveHealth: (packDir, namespace, entityId, config) => ipcRenderer.invoke('health:save', packDir, namespace, entityId, config),
    deleteHealth: (packDir, namespace, entityId) => ipcRenderer.invoke('health:delete', packDir, namespace, entityId),

    // 建筑结构（建筑拼图）
    listStructures: (packDir) => ipcRenderer.invoke('structure:list', packDir),
    readStructure: (packDir, relFile, structureId) => ipcRenderer.invoke('structure:read', packDir, relFile, structureId),
    saveStructure: (packDir, blueprint) => ipcRenderer.invoke('structure:save', packDir, blueprint),
    deleteStructure: (packDir, relFile, structureId) => ipcRenderer.invoke('structure:delete', packDir, relFile, structureId),
    exportStructureVanilla: (packDir, blueprint) => ipcRenderer.invoke('structure:exportVanilla', packDir, blueprint),
    // 一键导入原版（.nbt / 数据包 jar·zip）与一键导出整包原版数据
    importStructureVanilla: (packDir) => ipcRenderer.invoke('structure:importVanilla', packDir),
    importStructureVanillaDir: (packDir) => ipcRenderer.invoke('structure:importVanillaDir', packDir),
    importStructureVanillaPath: (packDir, srcPath) => ipcRenderer.invoke('structure:importVanillaPath', packDir, srcPath),
    exportStructureVanillaAll: (packDir) => ipcRenderer.invoke('structure:exportVanillaAll', packDir),
    getStructureDefaults: () => ipcRenderer.invoke('core:getStructureDefaults'),

    // 物品/方块（items/*.json + blocks/*.json → 基座批量注册 + 原版导出）
    itemBlockCatalog: () => ipcRenderer.invoke('itemblock:catalog'),
    listItemBlocks: (packDir) => ipcRenderer.invoke('itemblock:list', packDir),
    readItemBlock: (packDir, kind, id) => ipcRenderer.invoke('itemblock:read', packDir, kind, id),
    saveItemBlock: (packDir, kind, def) => ipcRenderer.invoke('itemblock:save', packDir, kind, def),
    deleteItemBlock: (packDir, kind, id) => ipcRenderer.invoke('itemblock:delete', packDir, kind, id),
    exportItemBlockVanilla: (packDir, kind, id) => ipcRenderer.invoke('itemblock:exportVanilla', packDir, kind, id),
    exportItemBlockVanillaAll: (packDir) => ipcRenderer.invoke('itemblock:exportVanillaAll', packDir),

    // 任务（quests/*.json；一个文件 = 一个任务定义）
    listQuests: (packDir) => ipcRenderer.invoke('quest:list', packDir),
    readQuest: (packDir, id) => ipcRenderer.invoke('quest:read', packDir, id),
    saveQuest: (packDir, def) => ipcRenderer.invoke('quest:save', packDir, def),
    deleteQuest: (packDir, id) => ipcRenderer.invoke('quest:delete', packDir, id),

    // 游戏资源（vanilla 方块贴图）
    mcAssets: () => ipcRenderer.invoke('core:mcAssets'),
    mcBlockFaces: (blockId) => ipcRenderer.invoke('core:mcBlockFaces', blockId),
    mcBlockIcon: (blockId) => ipcRenderer.invoke('core:mcBlockIcon', blockId), // P15 槽位图标
    importRegion: () => ipcRenderer.invoke('core:importRegion'),
    mcBlockList: (opts) => ipcRenderer.invoke('core:mcBlockList', opts),
    blockState: (blockId, opts) => ipcRenderer.invoke('core:blockState', blockId, opts),
    mcBlockAppearance: (blockId, opts) => ipcRenderer.invoke('core:blockAppearance', blockId, opts),

    // P11 片段 3D 编辑器窗口（独立窗口）
    openPieceEditor: (payload) => ipcRenderer.invoke('pieceEditor:open', payload),
    savePieceEdits: (payload) => ipcRenderer.send('pieceEditor:saveEdits', payload),
    onPieceEditorEdits: (cb) => ipcRenderer.on('pieceEditor:edits', (e, p) => cb(p)),
    onPieceEditorLoad: (cb) => ipcRenderer.on('pieceEditor:load', (e, p) => cb(p)),
    // P14 未写回修改（dirty）：关窗/切换提示
    notifyEditorDirty: (n) => ipcRenderer.send('pieceEditor:dirty', n),
    onPieceEditorFlush: (cb) => ipcRenderer.on('pieceEditor:flush', () => cb()),
    notifyEditorFlushed: () => ipcRenderer.send('pieceEditor:flushed'),

    // 暂存区（草稿）
    stagingList: (packDir) => ipcRenderer.invoke('staging:list', packDir),
    stagingAll: () => ipcRenderer.invoke('staging:all'),
    stagingExport: (packDir) => ipcRenderer.invoke('staging:export', packDir),
    stagingDiscard: (packDir) => ipcRenderer.invoke('staging:discard', packDir),
    stagingGetRoot: () => ipcRenderer.invoke('staging:getRoot'),

    // 模型解析
    parseModelFile: (filePath) => ipcRenderer.invoke('model:parseFile', filePath),
    parseModelFromJar: (jarPath, modelPathInJar) => ipcRenderer.invoke('model:parseFromJar', jarPath, modelPathInJar),
    listModelsInJar: (jarPath) => ipcRenderer.invoke('model:listInJar', jarPath),
    resolveModelFromId: (modelId, modelType) => ipcRenderer.invoke('model:resolveFromId', modelId, modelType),
    readTexture: (textureId) => ipcRenderer.invoke('model:readTexture', textureId),

    // 文件对话框
    openModelFile: () => ipcRenderer.invoke('dialog:openModelFile'),
    openJarFile: () => ipcRenderer.invoke('dialog:openJarFile'),

    // 打包
    buildPack: (data) => ipcRenderer.invoke('pack:build', data),

    // 0.2.2 部署四态核对（源码 / 产物 jar / 运行目录副本 / spark 磁盘缓存）+ 内容包 on/off
    packStatusInspect: (opts) => ipcRenderer.invoke('packStatus:inspect', opts || {}),
    packStatusSetEnabled: (p) => ipcRenderer.invoke('packStatus:setEnabled', p),
    packStatusInstall: (p) => ipcRenderer.invoke('packStatus:install', p),
    packStatusClearCache: (p) => ipcRenderer.invoke('packStatus:clearCache', p),

    // 0.2.2 第 2 步：提交回溯（只读 git：log/show/diff/status，绝不改工作区）
    gitInfo: () => ipcRenderer.invoke('git:info'),
    gitHistory: (p) => ipcRenderer.invoke('git:history', p),   // p = {packDir, jarPath[, limit]}
    gitCommit: (p) => ipcRenderer.invoke('git:commit', p),     // p = {packDir, jarPath, sha[, maxBytes]}
    gitWorkingTree: (p) => ipcRenderer.invoke('git:workingTree', p),
    // 0.2.2 第 3 步：回溯动作。plan 只读；run 会写工作区（内部先归档 + 强制检查点提交）；
    // undo 还原到那次检查点。acknowledge / expectedHead 由面板带上来，主进程再校一遍。
    gitRollbackPlan: (p) => ipcRenderer.invoke('git:rollbackPlan', p),
    gitRollback: (p) => ipcRenderer.invoke('git:rollback', p),
    gitRollbackUndo: (p) => ipcRenderer.invoke('git:rollbackUndo', p),
    // 0.2.2 第 2 步：Gradle 构建输出实时流（主进程 pack:output；返回取消订阅函数）
    onBuildOutput: (cb) => {
        const listener = (event, payload) => cb(payload);
        ipcRenderer.on('pack:output', listener);
        return () => ipcRenderer.removeListener('pack:output', listener);
    },

    // 3D 模型编辑器窗口（W2 子窗口；W3 gizmo/大纲/属性；W3.1 新建/另存为；W3.5 资源联动 / W4.2 实体状态映射 / W5 契约接入）
    // W4.2：ctx = {pack, packDir, entityId}（可选；主窗口"3D 中编辑"传入 → 模型编辑器状态映射面板）
    openModelEditor: (ctx) => ipcRenderer.invoke('modelEditor:open', ctx),
    // 0.2.1：模型编辑器未保存修改计数（主进程关窗提示用）
    modelEditorDirty: (n) => ipcRenderer.send('modelEditor:dirty', n),
    getModelEditorPendingContext: () => ipcRenderer.invoke('modelEditor:getPendingContext'),
    onModelEditorEntityContext: (cb) => ipcRenderer.on('modelEditor:entityContext', (e, ctx) => cb(ctx)),
    // W4.2：状态映射直写正式包（模型编辑器不走主窗口暂存区）
    saveEntityDirect: (packDir, entityJson) => ipcRenderer.invoke('modelEditor:saveEntityDirect', packDir, entityJson),
    // W4.3：部位配置直写正式包 assets/<ns>/health_config/<id>.json
    saveHealthDirect: (packDir, namespace, entityId, config) => ipcRenderer.invoke('modelEditor:saveHealthDirect', packDir, namespace, entityId, config),
    getModelFilePath: (file) => webUtils.getPathForFile(file),
    saveModelFile: (payload) => ipcRenderer.invoke('modelEditor:saveModel', payload),
    saveModelAs: (payload) => ipcRenderer.invoke('modelEditor:saveModelAs', payload),
    listModelPacks: () => ipcRenderer.invoke('modelEditor:listPacks'),

    // W3.5 资源联动：仓库资源选择器（模型/贴图浏览 + 读取；pack/rel 坐标，主进程重建路径）
    listModels: () => ipcRenderer.invoke('modelEditor:listModels'),
    listRepoTextures: () => ipcRenderer.invoke('modelEditor:listTextures'),
    readRepoModel: (p) => ipcRenderer.invoke('modelEditor:readModel', p),
    readRepoTexture: (p) => ipcRenderer.invoke('modelEditor:readTexture', p),
    readModelByPath: (p) => ipcRenderer.invoke('modelEditor:readModelByPath', p), // W5.2 崩溃恢复
    // 注意：主窗口已有 readContract（contract:read，契约视图）—— 键名冲突会静默覆盖（W3.6.1 事故同款），
    // 模型编辑器侧用 readPackContract/writePackContract
    readPackContract: (p) => ipcRenderer.invoke('modelEditor:readContract', p),
    writePackContract: (p) => ipcRenderer.invoke('modelEditor:writeContract', p),
    // W5.1 像素编辑：保存贴图（覆写已应用 / 对话框自选路径）
    writeRepoTexture: (p) => ipcRenderer.invoke('modelEditor:writeTexture', p),
    saveTextureDialog: (p) => ipcRenderer.invoke('modelEditor:saveTextureDialog', p),

    // W7 动画文档：p = {pack, rel}（rel = 包内模型相对路径 spark_models/**.json）
    loadAnimDoc: (p) => ipcRenderer.invoke('modelEditor:loadAnimDoc', p),
    writeAnimFile: (p) => ipcRenderer.invoke('modelEditor:writeAnimFile', p), // + {file, content}
    deleteAnimFile: (p) => ipcRenderer.invoke('modelEditor:deleteAnimFile', p), // + {file}

    // W3.6 日志与崩溃定位
    log: (level, scope, msg) => ipcRenderer.send('app:log', { level, scope, msg }),
    getLogInfo: () => ipcRenderer.invoke('app:getLogInfo'),
    openLogDir: () => ipcRenderer.invoke('app:openLogDir'),
    // 0.2.1 诊断信息（反馈闭环：版本/项目根来源/路径/日志位置一键复制）
    getDiagnostics: () => ipcRenderer.invoke('app:diagnostics'),

    // W5.3 渲染模式（软件渲染自愈）
    getSettings: () => ipcRenderer.invoke('settings:get'),
    setSoftwareRendering: (on) => ipcRenderer.invoke('settings:setSoftwareRendering', on),
    consumeFallbackNotice: () => ipcRenderer.invoke('settings:consumeFallbackNotice'),
    relaunchApp: () => ipcRenderer.invoke('app:relaunch'),
    // W5.5 调试模式（chromium.log + DevTools 自动打开 + 崩溃转储）
    setDebugMode: (on) => ipcRenderer.invoke('settings:setDebugMode', on),

    // 核心模板/默认值
    getStateMachineTemplates: () => ipcRenderer.invoke('core:getStateMachineTemplates'),
    getEntityDefaults: () => ipcRenderer.invoke('core:getEntityDefaults'),

    // 顶层应用菜单（main.js Menu.buildFromTemplate）：动作下发 + 语言上报
    onMenuAction: (cb) => ipcRenderer.on('menu:action', (_e, id) => cb(id)),
    notifyMenuLang: (lang) => ipcRenderer.send('menu:setLang', lang),
};

contextBridge.exposeInMainWorld('api', api);
