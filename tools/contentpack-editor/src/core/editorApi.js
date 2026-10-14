// editorApi.js - ContentPack Editor 命令调度器（AI API / CLI 共用实现）
//
// 纯 Node，无 Electron 依赖：
//   - main.js 内嵌 HTTP 服务（应用启动时自动监听 127.0.0.1）调用本模块
//   - cli/editor-cli.js 直接调用本模块（应用未运行时）
// 两者执行同一份代码路径，结果一致。UI 的 IPC 处理器与本模块共享 src/core/* 核心模块。
//
// 约定：
//   - 所有命令返回 { ok: true, data } 或 { ok: false, error }
//   - ctx = { root, packsSourceDir, packsOutDir }
//   - 写命令直接写正式包目录（contentpacks-source/…），绕过 UI 暂存区（AI/CLI 场景无需暂存）
//   - 包内相对路径一律校验不越界（safeJoin）

'use strict';
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const entitySchema = require('./entitySchema');
const healthConfig = require('./healthConfig');
const editorContract = require('./editorContract');
const manifest = require('./manifest');
const stateMachine = require('./stateMachine');
const packScaffold = require('./packScaffold');

// ==================== 工具 ====================

/** 校验包目录存在且是正式包目录；返回绝对路径 */
function resolvePackDir(ctx, packName) {
    if (!packName || typeof packName !== 'string') throw new Error('缺少包名参数');
    // REVIEW-S2：拒绝含路径分隔符的包名（pack='..' 会解析到 contentpacks-source 上级）
    if (packName.includes('\\') || packName.includes('/')) {
        throw new Error(`包名非法（不能包含路径分隔符）: ${packName}`);
    }
    const dir = path.join(ctx.packsSourceDir, packName);
    // 纵深防御：边界校验（分隔符已拒绝，此处防未来改动）
    if (!dir.startsWith(path.resolve(ctx.packsSourceDir) + path.sep)) {
        throw new Error(`包路径越界: ${packName}`);
    }
    if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
        throw new Error(`包不存在: ${packName}（${dir}）`);
    }
    return dir;
}

/** 包内相对路径 → 绝对路径（防越界） */
function safeJoin(base, rel) {
    if (!rel || typeof rel !== 'string') throw new Error('缺少路径参数');
    const abs = path.resolve(base, rel);
    if (abs !== base && !abs.startsWith(base + path.sep)) throw new Error(`路径越界: ${rel}`);
    if (!fs.existsSync(abs)) throw new Error(`文件不存在: ${rel}`);
    return abs;
}

/** 轻量统计包内模型/动画文件（读文件头 4KB 判别；跳过 entities/health 等非资产目录） */
function countModelAnimFiles(packDir) {
    let models = 0, animations = 0;
    const skipDirs = new Set(['entities', 'health', '.gradle', 'build', 'java', 'bin', 'out']);
    const walk = (dir) => {
        let entries;
        try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
        for (const e of entries) {
            const p = path.join(dir, e.name);
            if (e.isDirectory()) { if (!skipDirs.has(e.name)) walk(p); continue; }
            if (!e.name.endsWith('.json')) continue;
            let head = '';
            try {
                const fd = fs.openSync(p, 'r');
                const buf = Buffer.alloc(4096);
                const n = fs.readSync(fd, buf, 0, 4096, 0);
                fs.closeSync(fd);
                head = buf.toString('utf-8', 0, n);
            } catch { continue; }
            if (/"animations"\s*:/.test(head)) animations++;
            else if (/"bones"\s*:/.test(head)) models++;
        }
    };
    walk(packDir);
    return { models, animations };
}

/** 包摘要（实体/模型/动画/契约/产物 jar） */
function summarizePack(ctx, pack) {
    const packDir = pack.dir;
    const entities = entitySchema.listEntities(packDir).map((e) => e.id);
    const counts = countModelAnimFiles(packDir);
    const moduleName = pack.manifest['Module-Name'] || pack.name;
    const jarName = packScaffold.expectedJarName(moduleName);
    const jarPath = path.join(ctx.packsOutDir, jarName);
    let jar = { name: jarName, exists: false };
    if (fs.existsSync(jarPath)) {
        jar = { name: jarName, exists: true, modifiedAt: new Date(fs.statSync(jarPath).mtimeMs).toISOString() };
    }
    // 契约路径声明在 MANIFEST 的 Module-Editor-Schema 属性（loadContract 约定）
    const schemaRel = pack.manifest['Module-Editor-Schema'] || null;
    return {
        name: pack.name,
        dir: packDir,
        version: pack.version,
        hasBuildGradle: pack.hasBuildGradle,
        entities,
        modelCount: counts.models,
        animationCount: counts.animations,
        contractSchema: schemaRel,
        hasContract: !!schemaRel && fs.existsSync(path.join(packDir, schemaRel)),
        jar,
    };
}

// ==================== 命令实现 ====================

const commands = {
    // ---------- 包 ----------
    'packs.list': (_args, ctx) => {
        return manifest.scanPacks(ctx.packsSourceDir).map((p) => summarizePack(ctx, p)).sort((a, b) => a.name.localeCompare(b.name));
    },

    'pack.show': (args, ctx) => {
        const packs = manifest.scanPacks(ctx.packsSourceDir);
        const pack = packs.find((p) => p.name === args.pack || path.basename(p.dir) === args.pack);
        if (!pack) throw new Error(`包不存在: ${args.pack}`);
        const summary = summarizePack(ctx, pack);
        return { ...summary, manifest: pack.manifest };
    },

    // ---------- 实体 ----------
    'entity.list': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        return entitySchema.listEntities(packDir);
    },

    'entity.get': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        return entitySchema.readEntity(packDir, args.id);
    },

    'entity.new': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        const existing = entitySchema.listEntities(packDir).map((e) => e.id);
        if (existing.includes(args.id)) throw new Error(`实体已存在: ${args.id}`);
        const entity = Object.assign(entitySchema.createDefaultEntity(), { id: args.id, namespace: args.namespace || '' });
        return entitySchema.saveEntity(packDir, entity);
    },

    'entity.set': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        if (!args.entity || typeof args.entity !== 'object') throw new Error('缺少 entity 对象参数');
        return entitySchema.saveEntity(packDir, args.entity);
    },

    'entity.rm': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        return entitySchema.deleteEntity(packDir, args.id);
    },

    // ---------- 部位健康配置 ----------
    'health.get': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        return healthConfig.readHealthConfig(packDir, args.ns, args.id); // 不存在返回 null
    },

    'health.set': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        if (!args.config || typeof args.config !== 'object') throw new Error('缺少 config 对象参数');
        return healthConfig.saveHealthConfig(packDir, args.ns, args.id, args.config);
    },

    // ---------- 契约与资产 ----------
    'contract.report': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        const m = manifest.readManifest(packDir);
        const rep = editorContract.buildReport(packDir, m);
        if (!rep.ok) {
            if (rep.error === 'no-contract') {
                throw new Error('该包未声明编辑器契约（MANIFEST.MF 缺 Module-Editor-Schema 属性）');
            }
            throw new Error(`契约无效: ${(rep.errors || [rep.error]).join('; ')}`);
        }
        return { pack: args.pack, hasErrors: rep.data.errors.length > 0, ...rep.data };
    },

    'model.bones': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        const abs = safeJoin(packDir, args.rel);
        return { file: path.relative(packDir, abs).split(path.sep).join('/'), bones: editorContract.modelBoneNames(abs) };
    },

    'anim.show': (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        const abs = safeJoin(packDir, args.rel);
        // readAnimFile 返回动画摘要数组（bundle 文件可含多个）；解析失败 = null
        return { file: path.relative(packDir, abs).split(path.sep).join('/'), animations: editorContract.readAnimFile(abs) };
    },

    // ---------- 状态机模板 ----------
    'states': () => {
        return {
            states: [
                { state: 0, name: 'still', label: '静止' },
                { state: 1, name: 'walking', label: '行走' },
                { state: 2, name: 'running', label: '奔跑' },
                { state: 3, name: 'attack', label: '攻击' },
                { state: 4, name: 'alert', label: '警觉' },
                { state: 5, name: 'hit_front', label: '受击-正面' },
                { state: 6, name: 'hit_back', label: '受击-背面' },
                { state: 7, name: 'aggressive', label: '蓄力(可选)' },
            ],
            templates: stateMachine.TEMPLATES,
        };
    },

    // ---------- 构建 ----------
    'build': async (args, ctx) => {
        const packDir = resolvePackDir(ctx, args.pack);
        const gradlew = process.platform === 'win32'
            ? path.join(ctx.root, 'gradlew.bat')
            : path.join(ctx.root, 'gradlew');
        if (!fs.existsSync(gradlew)) throw new Error(`gradlew 不存在（项目根: ${ctx.root}）`);
        const m = manifest.readManifest(packDir);
        const moduleName = m['Module-Name'] || args.pack;
        const result = await new Promise((resolve) => {
            const child = spawn(gradlew, [':packs:' + args.pack + ':contentpackJar', '--console=plain'], {
                cwd: ctx.root,
                windowsHide: true,
            });
            let out = '';
            child.stdout.on('data', (d) => { out += d; });
            child.stderr.on('data', (d) => { out += d; });
            const timer = setTimeout(() => { try { child.kill(); } catch { /* 忽略 */ } }, 10 * 60 * 1000);
            child.on('error', (e) => { clearTimeout(timer); resolve({ exitCode: -1, error: e.message, output: out.slice(-4000) }); });
            child.on('close', (code) => { clearTimeout(timer); resolve({ exitCode: code, output: out.slice(-4000) }); });
        });
        const jarPath = path.join(ctx.packsOutDir, packScaffold.expectedJarName(moduleName));
        const jar = { path: jarPath, exists: fs.existsSync(jarPath) };
        if (jar.exists) jar.modifiedAt = new Date(fs.statSync(jarPath).mtimeMs).toISOString();
        return { exitCode: result.exitCode, error: result.error || null, jar, output: result.output };
    },
};

// ==================== 调度入口 ====================

/**
 * 执行一条命令
 * @param {string} cmd 点分命令名（如 "entity.get"）
 * @param {Object} args 命令参数
 * @param {{root:string, packsSourceDir:string, packsOutDir:string}} ctx 运行上下文
 * @returns {Promise<{ok:boolean, data?:any, error?:string}>}
 */
async function invoke(cmd, args, ctx) {
    const handler = commands[cmd];
    if (!handler) {
        return { ok: false, error: `未知命令: ${cmd}（可用: ${Object.keys(commands).join(', ')}）` };
    }
    try {
        const data = await handler(args || {}, ctx);
        return { ok: true, data };
    } catch (e) {
        return { ok: false, error: e.message };
    }
}

module.exports = {
    invoke,
    /** 全部命令名（帮助/文档用） */
    commandNames: () => Object.keys(commands),
};
