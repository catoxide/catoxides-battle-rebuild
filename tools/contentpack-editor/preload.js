// preload.js - 渲染进程桥接
// 暴露 window.api，所有 IPC 调用返回 { ok, data|error }
// 通道名与 main.js 中 ipcMain.handle 一一对应
const { contextBridge, ipcRenderer } = require('electron');

const api = {
    // 路径信息
    getPaths: () => ipcRenderer.invoke('app:getPaths'),
    setProjectRoot: (rootDir) => ipcRenderer.invoke('app:setProjectRoot', rootDir),
    pickProjectRoot: () => ipcRenderer.invoke('app:pickProjectRoot'),

    // 包管理
    listPacks: () => ipcRenderer.invoke('pack:list'),
    createPack: (data) => ipcRenderer.invoke('pack:create', data),
    readManifest: (packDir) => ipcRenderer.invoke('pack:readManifest', packDir),
    saveManifest: (packDir, manifest) => ipcRenderer.invoke('pack:saveManifest', packDir, manifest),

    // 实体
    listEntities: (packDir) => ipcRenderer.invoke('entity:list', packDir),
    readEntity: (packDir, id) => ipcRenderer.invoke('entity:read', packDir, id),
    saveEntity: (packDir, entityJson) => ipcRenderer.invoke('entity:save', packDir, entityJson),
    deleteEntity: (packDir, id) => ipcRenderer.invoke('entity:delete', packDir, id),

    // 部位配置
    readHealth: (packDir, namespace, entityId) => ipcRenderer.invoke('health:read', packDir, namespace, entityId),
    saveHealth: (packDir, namespace, entityId, config) => ipcRenderer.invoke('health:save', packDir, namespace, entityId, config),
    deleteHealth: (packDir, namespace, entityId) => ipcRenderer.invoke('health:delete', packDir, namespace, entityId),

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

    // 核心模板/默认值
    getStateMachineTemplates: () => ipcRenderer.invoke('core:getStateMachineTemplates'),
    getEntityDefaults: () => ipcRenderer.invoke('core:getEntityDefaults'),
};

contextBridge.exposeInMainWorld('api', api);
