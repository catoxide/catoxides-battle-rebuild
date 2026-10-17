// projectRoot.js - 项目根解析策略（纯逻辑 + fs 注入，node 可测）
//
// 0.2.1 修复背景（反馈机事故）：
//   反馈同学只拿到一个 portable exe，机器上**没有** catoxides-battle-rebuild 工程。
//   main.js 里 tryResolveProjectRoot() 返回 null → getPaths 回 needProjectRoot:true
//   → 渲染端 init() 走 setupProjectRoot() 后 **直接 return**：
//     · 顶栏「源码: …」永远不出现（那行在 return 之后才写）
//     · 包库 / 新建包 / 左侧「+ 新建」的绑定全被跳过 → 三个按钮全死
//     · 包列表空白，界面其余部分看起来"正常但点不动"
//   结论：不是按钮代码坏了，是"没有工程"把整段初始化短路了。
//
// 现在的策略（顺序固定）：
//   ① 配置里的 projectRoot（用户上次选的真工程）
//   ② 向上探测（exe 目录 / cwd / __dirname 附近有 contentpacks-source + contentpacks）
//   ③ 自动建**独立工作区**：<文档>/Content Pack Editor（不可写则 <userData>/Content Pack Editor）
//      —— 保证编辑器在任何机器上都能启动并可用；工作区不写进配置，
//         这样每次启动仍会重新找真工程（找到就用真工程），"独立工作区"标记也才稳定。
//   ④ 全失败才返回 null（渲染端只提示、不再死锁）
'use strict';
const path = require('path');
const fs = require('fs');

/** 工作区目录名（含空格，用户可直接在文件管理器里找到） */
const WORKSPACE_NAME = 'Content Pack Editor';
/** 工作区必须有的两个子目录（与真项目根同名，编辑器其余逻辑无需特判） */
const REQUIRED_SUBDIRS = ['contentpacks-source', 'contentpacks'];

function isDir(fsImpl, p) {
    try { return !!(p && fsImpl.existsSync(p) && fsImpl.statSync(p).isDirectory()); }
    catch { return false; }
}

/** 有效项目根 = 目录 且 含 contentpacks-source（与 main.js 原判定一致） */
function isProjectRoot(fsImpl, dir) {
    try { return isDir(fsImpl, dir) && fsImpl.existsSync(path.join(dir, 'contentpacks-source')); }
    catch { return false; }
}

/** 在 baseDir 下建（或复用）工作区；返回 { root, created } 或 null（不可写） */
function ensureWorkspace(fsImpl, baseDir) {
    if (!baseDir) return null;
    const root = path.join(baseDir, WORKSPACE_NAME);
    try {
        const created = [];
        for (const sub of REQUIRED_SUBDIRS) {
            const d = path.join(root, sub);
            if (!fsImpl.existsSync(d)) {
                fsImpl.mkdirSync(d, { recursive: true });
                created.push(d);
            }
        }
        return { root, created };
    } catch {
        return null; // 只读/无权限/被占用 → 让调用方换下一个候选目录
    }
}

/**
 * 决定本次会话的项目根。
 * @param {object} opts
 * @param {string|null} [opts.configRoot]    配置里上次保存的项目根
 * @param {()=>string|null} [opts.detectRoot] 自动探测回调（返回候选目录或 null）
 * @param {string|null} [opts.documentsDir]  文档目录（工作区首选位置）
 * @param {string|null} [opts.userDataDir]   应用数据目录（工作区兜底位置）
 * @param {object} [opts.fsImpl]             fs 注入（测试用）
 * @returns {{root:string|null, source:string|null, created:string[], error?:string}}
 *          source: 'config' | 'auto' | 'workspace-documents' | 'workspace-userdata' | null
 */
function resolveProjectRoot(opts = {}) {
    const fsImpl = opts.fsImpl || fs;
    const { configRoot = null, detectRoot = null, documentsDir = null, userDataDir = null } = opts;

    // ① 配置优先
    if (configRoot && isProjectRoot(fsImpl, configRoot)) {
        return { root: configRoot, source: 'config', created: [] };
    }

    // ② 自动探测
    if (typeof detectRoot === 'function') {
        let detected = null;
        try { detected = detectRoot(); } catch { detected = null; }
        if (detected && isProjectRoot(fsImpl, detected)) {
            return { root: detected, source: 'auto', created: [] };
        }
    }

    // ③ 独立工作区（文档目录 → 应用数据目录）
    const errors = [];
    for (const [source, base] of [['workspace-documents', documentsDir], ['workspace-userdata', userDataDir]]) {
        if (!base) continue;
        const ws = ensureWorkspace(fsImpl, base);
        if (ws) return { root: ws.root, source, created: ws.created };
        errors.push(`${source}（${base}）不可写`);
    }

    // ④ 彻底失败
    return {
        root: null,
        source: null,
        created: [],
        error: '无法创建独立工作区：' + (errors.join('；') || '没有可用的候选目录'),
    };
}

module.exports = {
    WORKSPACE_NAME,
    REQUIRED_SUBDIRS,
    isProjectRoot,
    ensureWorkspace,
    resolveProjectRoot,
};
