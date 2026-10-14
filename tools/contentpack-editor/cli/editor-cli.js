#!/usr/bin/env node
// editor-cli.js - ContentPack Editor 命令行（与内嵌 AI API 同一套命令）
//
// 用法：node editor-cli.js [--root <项目根>] <group> <action> [参数...]
//
// 传输自动选择：
//   1) 应用运行中（<root>/logs/contentpack-editor/api.json 存在且端口可达）→ HTTP 调用
//      （与 UI 同一主进程、同一核心代码路径）
//   2) 应用未运行 → 直接 require src/core/editorApi 执行（结果一致）
//
// 全部输出 JSON（2 空格缩进）：{ ok: true, data } 或 { ok: false, error }；失败退出码 1。
//
// 命令表（详见 --help）：
//   packs list | pack show <name>
//   entity list|get|new|set|rm  ...
//   health get|set ...
//   contract report <pack> | model bones <pack> <rel> | anim show <pack> <rel>
//   states | build <pack>
'use strict';
const fs = require('fs');
const path = require('path');
const http = require('http');

// ==================== 参数解析 ====================

const argv = process.argv.slice(2);
const flags = {};
const rest = [];
for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--root') { flags.root = argv[++i]; continue; }
    if (a === '--port') { flags.port = parseInt(argv[++i], 10); continue; }
    if (a === '--help' || a === '-h') { flags.help = true; continue; }
    rest.push(a);
}

const HELP = `ContentPack Editor CLI（AI API 命令行客户端）

用法: node editor-cli.js [--root <项目根>] [--port <端口>] <命令> [参数...]

包:
  packs list                       列出所有包（实体/模型/动画/契约/jar 摘要）
  pack show <name>                 包详情（MANIFEST + 摘要）

实体（entities/<id>.json）:
  entity list <pack>               实体清单
  entity get <pack> <id>           读取实体
  entity new <pack> <id> [ns]      创建默认实体
  entity set <pack> <file|->       写入实体（JSON 文件或 stdin，自动校验）
  entity rm <pack> <id>            删除实体

部位健康配置（health/<ns>/<id>.json）:
  health get <pack> <ns> <id>      读取（不存在返回 null）
  health set <pack> <ns> <id> <file|->   写入（自动校验）
  health set <pack> <id> <file|->  ns 缺省时从实体文件的 namespace 自动推断

资产与契约:
  contract report <pack>           契约全量盘点（模型/动画/缺失/错误）
  model bones <pack> <rel>         模型骨骼名列表（rel = 包内相对路径）
  anim show <pack> <rel>           动画文件摘要（loop/duration/bones）

其他:
  states                           8 状态机定义 + 模板
  build <pack>                     gradlew :packs:<pack>:contentpackJar（产物 contentpacks/<name>.jar）

选项:
  --root <dir>   项目根（默认: --root > 环境变量 CBR_PROJECT_ROOT > 从 cwd 向上探测）
  --port <n>     强制使用该 HTTP 端口（跳过应用状态探测）
  -h, --help     本帮助

示例:
  node editor-cli.js packs list
  node editor-cli.js entity get test-mob-pack modular_zombie_test
  node editor-cli.js entity set test-mob-pack entities\\modular_zombie_test.json
  node editor-cli.js contract report modular-zombie-3
  node editor-cli.js build test-mob-pack
`;

// ==================== 项目根解析 ====================

function detectRootFrom(startDir) {
    let dir = startDir;
    for (let i = 0; i < 10; i++) {
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

function resolveRoot() {
    const candidates = [flags.root, process.env.CBR_PROJECT_ROOT, detectRootFrom(process.cwd()), detectRootFrom(__dirname)]
        .filter(Boolean);
    for (const c of candidates) {
        if (fs.existsSync(path.join(c, 'contentpacks-source'))) return path.resolve(c);
    }
    return null;
}

// ==================== HTTP 探测与调用 ====================

function readStatusFile(root) {
    try {
        const f = path.join(root, 'logs', 'contentpack-editor', 'api.json');
        if (fs.existsSync(f)) return JSON.parse(fs.readFileSync(f, 'utf-8'));
    } catch { /* 忽略 */ }
    return null;
}

function httpGet(port, urlPath, timeoutMs) {
    return new Promise((resolve, reject) => {
        const req = http.get({ host: '127.0.0.1', port, path: urlPath, timeout: timeoutMs }, (res) => {
            let b = '';
            res.on('data', (d) => { b += d; });
            res.on('end', () => { try { resolve(JSON.parse(b)); } catch (e) { reject(e); } });
        });
        req.on('timeout', () => { req.destroy(new Error('timeout')); });
        req.on('error', reject);
    });
}

function httpInvoke(port, cmd, args, timeoutMs) {
    return new Promise((resolve, reject) => {
        const data = JSON.stringify({ cmd, args });
        const req = http.request({
            host: '127.0.0.1', port, path: '/invoke', method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) },
        }, (res) => {
            let b = '';
            res.on('data', (d) => { b += d; });
            res.on('end', () => { try { resolve(JSON.parse(b)); } catch (e) { reject(e); } });
        });
        req.setTimeout(timeoutMs, () => { req.destroy(new Error('HTTP 超时')); });
        req.on('error', reject);
        req.end(data);
    });
}

/** 探测应用 AI API 端口；返回 {port} 或 null */
async function probeAppPort(root) {
    if (flags.port) return { port: flags.port };
    const status = readStatusFile(root);
    if (!status || !status.port) return null;
    try {
        const r = await httpGet(status.port, '/status', 800);
        if (r && r.ok) return { port: status.port };
    } catch { /* 应用已退出或端口失效 */ }
    return null;
}

// ==================== 命令映射 ====================

/** 数据文件解析：'-' = stdin；否则先按 CWD/绝对路径找，找不到再按包内相对路径找 */
function resolveDataFile(packDir, v) {
    if (v === '-') return JSON.parse(fs.readFileSync(0, 'utf-8')); // stdin
    const direct = path.resolve(v);
    if (fs.existsSync(direct)) return JSON.parse(fs.readFileSync(direct, 'utf-8'));
    const inPack = path.join(packDir, v);
    if (fs.existsSync(inPack)) return JSON.parse(fs.readFileSync(inPack, 'utf-8'));
    throw new Error(`找不到数据文件: ${v}（CWD 与包目录 ${path.basename(packDir)}/ 均未找到）`);
}

function packNamespaceOf(packRoot) {
    return path.basename(packRoot).replace(/-/g, '_');
}

/** 推断 health.set 的 namespace（参数缺省 ns 时） */
function inferNamespace(ctx, pack, entityId) {
    const f = path.join(ctx.packsSourceDir, pack, 'entities', `${entityId}.json`);
    try {
        const ent = JSON.parse(fs.readFileSync(f, 'utf-8'));
        if (ent.namespace) return ent.namespace;
    } catch { /* 实体文件不存在或损坏 */ }
    return packNamespaceOf(path.join(ctx.packsSourceDir, pack));
}

/**
 * 把 CLI 词法解析为 {cmd, args}
 * @returns {Promise<{cmd:string, args:Object}|null>} null = 需要帮助
 */
async function parseCommand(root) {
    const [group, action, ...r] = rest;
    if (!group || flags.help) return null;
    const ctx = { root, packsSourceDir: path.join(root, 'contentpacks-source') };
    switch (group) {
        case 'packs':
            if (action === 'list') return { cmd: 'packs.list', args: {} };
            break;
        case 'pack':
            if (action === 'show' && r[0]) return { cmd: 'pack.show', args: { pack: r[0] } };
            break;
        case 'entity': {
            const packDir = path.join(ctx.packsSourceDir, r[0] || '');
            switch (action) {
                case 'list': if (r[0]) return { cmd: 'entity.list', args: { pack: r[0] } }; break;
                case 'get': if (r[0] && r[1]) return { cmd: 'entity.get', args: { pack: r[0], id: r[1] } }; break;
                case 'new': if (r[0] && r[1]) return { cmd: 'entity.new', args: { pack: r[0], id: r[1], namespace: r[2] || '' } }; break;
                case 'set': if (r[0] && r[1]) return { cmd: 'entity.set', args: { pack: r[0], entity: resolveDataFile(packDir, r[1]) } }; break;
                case 'rm': if (r[0] && r[1]) return { cmd: 'entity.rm', args: { pack: r[0], id: r[1] } }; break;
            }
            break;
        }
        case 'health': {
            const packDir = path.join(ctx.packsSourceDir, r[0] || '');
            if (action === 'get' && r[0] && r[1] && r[2]) {
                return { cmd: 'health.get', args: { pack: r[0], ns: r[1], id: r[2] } };
            }
            if (action === 'set') {
                // 4 参数 = (pack, ns, id, file)；3 参数 = (pack, id, file)，ns 自动推断
                if (r.length === 4 && r[3]) {
                    return { cmd: 'health.set', args: { pack: r[0], ns: r[1], id: r[2], config: resolveDataFile(packDir, r[3]) } };
                }
                if (r.length === 3 && r[2]) {
                    const ns = inferNamespace(ctx, r[0], r[1]);
                    return { cmd: 'health.set', args: { pack: r[0], ns, id: r[1], config: resolveDataFile(packDir, r[2]) } };
                }
            }
            break;
        }
        case 'contract':
            if (action === 'report' && r[0]) return { cmd: 'contract.report', args: { pack: r[0] } };
            break;
        case 'model':
            if (action === 'bones' && r[0] && r[1]) return { cmd: 'model.bones', args: { pack: r[0], rel: r[1] } };
            break;
        case 'anim':
            if (action === 'show' && r[0] && r[1]) return { cmd: 'anim.show', args: { pack: r[0], rel: r[1] } };
            break;
        case 'states':
            return { cmd: 'states', args: {} };
        case 'build':
            if (r[0]) return { cmd: 'build', args: { pack: r[0] } };
            break;
    }
    return null;
}

// ==================== 主流程 ====================

(async () => {
    const root = resolveRoot();
    const parsed = await parseCommand(root);
    if (!parsed) {
        console.log(HELP);
        process.exit(flags.help && rest.length === 0 ? 0 : 1);
    }
    if (!root) {
        console.log(JSON.stringify({ ok: false, error: '无法定位项目根（含 contentpacks-source 的目录）。用 --root 指定。' }, null, 2));
        process.exit(2);
    }

    let result;
    const app = await probeAppPort(root);
    if (app) {
        // 应用运行中 → HTTP（build 命令给足 10 分钟）
        const timeout = parsed.cmd === 'build' ? 10 * 60 * 1000 : 60 * 1000;
        try {
            result = await httpInvoke(app.port, parsed.cmd, parsed.args, timeout);
        } catch (e) {
            result = { ok: false, error: `HTTP 调用失败（端口 ${app.port}）: ${e.message}` };
        }
    } else {
        // 应用未运行 → 直接执行核心模块
        const editorApi = require('../src/core/editorApi');
        const ctx = {
            root,
            packsSourceDir: path.join(root, 'contentpacks-source'),
            packsOutDir: path.join(root, 'contentpacks'),
        };
        result = await editorApi.invoke(parsed.cmd, parsed.args, ctx);
    }

    console.log(JSON.stringify(result, null, 2));
    process.exit(result.ok ? 0 : 1);
})().catch((e) => {
    console.log(JSON.stringify({ ok: false, error: e.message }, null, 2));
    process.exit(1);
});
