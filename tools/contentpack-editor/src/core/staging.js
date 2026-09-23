// staging.js - 暂存区（草稿）模块
// 保存逻辑：编辑内容写入 app 私有暂存区（userData/staging/<packName>/），
// 显式"导出"才合并回正式包（contentpacks-source/<包>/），导出后回收暂存。
// 暂存区在 app 自己的数据目录（独立运行，不污染项目目录）。
const fs = require('fs');
const path = require('path');

let stagingRoot = null;

/**
 * 初始化暂存区根目录（app.getPath('userData')/staging）
 * @param {string} root
 */
function initStaging(root) {
    stagingRoot = path.join(root, 'staging');
    fs.mkdirSync(stagingRoot, { recursive: true });
}

function getStagingRoot() {
    return stagingRoot;
}

/** 包的暂存目录（按包名隔离） */
function packStagingDir(packName) {
    return path.join(stagingRoot, sanitize(packName));
}

function sanitize(name) {
    return String(name || 'unnamed').replace(/[\\/:*?"<>|]/g, '_');
}

/**
 * 写实体草稿：暂存 entities/<id>.json
 * @param {string} packName
 * @param {Object} entity 已通过校验的实体对象
 * @returns {{file:string, staged:boolean}}
 */
function stageEntity(packName, entity) {
    const dir = path.join(packStagingDir(packName), 'entities');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, entity.id + '.json');
    fs.writeFileSync(file, JSON.stringify(entity, null, 2), 'utf-8');
    return { file: path.relative(getStagingRoot(), file), staged: true };
}

/**
 * 写部位配置草稿：暂存 assets/<ns>/health_config/<id>.json
 * @param {string} packName
 * @param {string} namespace
 * @param {string} entityId
 * @param {Object} config
 */
function stageHealthConfig(packName, namespace, entityId, config) {
    const dir = path.join(packStagingDir(packName), 'assets', namespace, 'health_config');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, entityId + '.json');
    fs.writeFileSync(file, JSON.stringify(config, null, 2), 'utf-8');
    return { file: path.relative(getStagingRoot(), file), staged: true };
}

/**
 * 写结构蓝图草稿：暂存 structures/<structureId>/blueprint.json
 * @param {string} packName
 * @param {Object} blueprint 已通过校验的蓝图对象
 */
function stageStructure(packName, blueprint) {
    const dir = path.join(packStagingDir(packName), 'structures', sanitize(blueprint.structureId));
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, 'blueprint.json');
    fs.writeFileSync(file, JSON.stringify(blueprint, null, 2), 'utf-8');
    return { file: path.relative(getStagingRoot(), file), staged: true };
}

/**
 * 读结构蓝图草稿
 * @param {string} packName
 * @param {string} structureId
 * @returns {Object|null}
 */
function readStagedStructure(packName, structureId) {
    const file = path.join(packStagingDir(packName), 'structures', sanitize(structureId), 'blueprint.json');
    return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf-8')) : null;
}

/**
 * 读实体草稿（存在则优先于正式包）
 * @param {string} packName
 * @param {string} entityId
 * @returns {Object|null}
 */
function readStagedEntity(packName, entityId) {
    const file = path.join(packStagingDir(packName), 'entities', entityId + '.json');
    return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf-8')) : null;
}

/**
 * 读部位配置草稿
 * @param {string} packName
 * @param {string} namespace
 * @param {string} entityId
 * @returns {Object|null}
 */
function readStagedHealthConfig(packName, namespace, entityId) {
    const file = path.join(packStagingDir(packName), 'assets', namespace, 'health_config', entityId + '.json');
    return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf-8')) : null;
}

/**
 * 列出某包的全部暂存项
 * @param {string} packName
 * @returns {Array<{kind:'entity'|'health', id:string, namespace:string, file:string, mtime:string}>}
 */
function listStaged(packName) {
    const base = packStagingDir(packName);
    if (!fs.existsSync(base)) return [];
    const items = [];
    const entitiesDir = path.join(base, 'entities');
    if (fs.existsSync(entitiesDir)) {
        for (const f of fs.readdirSync(entitiesDir)) {
            if (!f.endsWith('.json')) continue;
            const p = path.join(entitiesDir, f);
            items.push({ kind: 'entity', id: f.slice(0, -5), namespace: null, file: path.relative(base, p), mtime: fs.statSync(p).mtime.toISOString() });
        }
    }
    const assetsDir = path.join(base, 'assets');
    if (fs.existsSync(assetsDir)) {
        walkHealth(assetsDir, (namespace, id, p) => {
            items.push({ kind: 'health', id, namespace, file: path.relative(base, p), mtime: fs.statSync(p).mtime.toISOString() });
        });
    }
    const structuresDir = path.join(base, 'structures');
    if (fs.existsSync(structuresDir)) {
        for (const dir of fs.readdirSync(structuresDir, { withFileTypes: true })) {
            if (!dir.isDirectory()) continue;
            const p = path.join(structuresDir, dir.name, 'blueprint.json');
            if (!fs.existsSync(p)) continue;
            items.push({ kind: 'structure', id: dir.name, namespace: null, file: path.relative(base, p), mtime: fs.statSync(p).mtime.toISOString() });
        }
    }
    return items;
}

function walkHealth(dir, cb) {
    for (const ns of fs.readdirSync(dir, { withFileTypes: true })) {
        if (!ns.isDirectory()) continue;
        const hcDir = path.join(dir, ns.name, 'health_config');
        if (!fs.existsSync(hcDir)) continue;
        for (const f of fs.readdirSync(hcDir)) {
            if (!f.endsWith('.json')) continue;
            cb(ns.name, f.slice(0, -5), path.join(hcDir, f));
        }
    }
}

/**
 * 某包是否有暂存
 * @param {string} packName
 */
function hasStaged(packName) {
    return listStaged(packName).length > 0;
}

/** 全部暂存包（跨包汇总，用于关闭时提示） */
function listAllStagedPacks() {
    if (!fs.existsSync(stagingRoot)) return [];
    const packs = [];
    for (const entry of fs.readdirSync(stagingRoot, { withFileTypes: true })) {
        if (!entry.isDirectory()) continue;
        const items = listStaged(entry.name);
        if (items.length) packs.push({ packName: entry.name, items });
    }
    return packs;
}

/**
 * 导出某包全部暂存 → 正式包，然后回收（删除该包暂存目录）
 * @param {string} packName
 * @param {string} packSourceDir 正式包源码目录（contentpacks-source/<包>）
 * @returns {{exported:number, files:string[]}}
 */
function exportStaged(packName, packSourceDir) {
    const items = listStaged(packName);
    if (!items.length) return { exported: 0, files: [] };
    const base = packStagingDir(packName);
    const exported = [];
    for (const item of items) {
        if (item.kind === 'entity') {
            const src = path.join(base, 'entities', item.id + '.json');
            const dest = path.join(packSourceDir, 'entities', item.id + '.json');
            fs.mkdirSync(path.dirname(dest), { recursive: true });
            fs.copyFileSync(src, dest);
            exported.push(path.relative(packSourceDir, dest));
        } else if (item.kind === 'health') {
            const src = path.join(base, 'assets', item.namespace, 'health_config', item.id + '.json');
            const dest = path.join(packSourceDir, 'assets', item.namespace, 'health_config', item.id + '.json');
            fs.mkdirSync(path.dirname(dest), { recursive: true });
            fs.copyFileSync(src, dest);
            exported.push(path.relative(packSourceDir, dest));
        } else if (item.kind === 'structure') {
            const src = path.join(base, 'structures', item.id, 'blueprint.json');
            const dest = path.join(packSourceDir, 'structures', item.id, 'blueprint.json');
            fs.mkdirSync(path.dirname(dest), { recursive: true });
            fs.copyFileSync(src, dest);
            exported.push(path.relative(packSourceDir, dest));
        }
    }
    // 回收：删除该包暂存目录
    fs.rmSync(base, { recursive: true, force: true });
    return { exported: items.length, files: exported };
}

/**
 * 丢弃某包全部暂存
 * @param {string} packName
 */
function discardStaged(packName) {
    const base = packStagingDir(packName);
    if (fs.existsSync(base)) {
        fs.rmSync(base, { recursive: true, force: true });
        return { discarded: true };
    }
    return { discarded: false };
}

/**
 * 删除单个实体的暂存（实体删除时联动）
 * @param {string} packName
 * @param {string} entityId
 */
function removeStagedEntity(packName, entityId) {
    const base = packStagingDir(packName);
    const f1 = path.join(base, 'entities', entityId + '.json');
    if (fs.existsSync(f1)) fs.unlinkSync(f1);
    // 健康配置：需遍历 namespaces
    const assetsDir = path.join(base, 'assets');
    if (fs.existsSync(assetsDir)) {
        for (const ns of fs.readdirSync(assetsDir, { withFileTypes: true })) {
            if (!ns.isDirectory()) continue;
            const f2 = path.join(assetsDir, ns.name, 'health_config', entityId + '.json');
            if (fs.existsSync(f2)) fs.unlinkSync(f2);
        }
    }
    return true;
}

/**
 * 删除单个结构的暂存（结构删除时联动）
 * @param {string} packName
 * @param {string} structureId
 */
function removeStagedStructure(packName, structureId) {
    const f = path.join(packStagingDir(packName), 'structures', sanitize(structureId), 'blueprint.json');
    if (fs.existsSync(f)) fs.unlinkSync(f);
    return true;
}

module.exports = {
    initStaging,
    getStagingRoot,
    stageEntity,
    stageHealthConfig,
    readStagedEntity,
    readStagedHealthConfig,
    stageStructure,
    readStagedStructure,
    listStaged,
    hasStaged,
    listAllStagedPacks,
    exportStaged,
    discardStaged,
    removeStagedEntity,
    removeStagedStructure,
};
