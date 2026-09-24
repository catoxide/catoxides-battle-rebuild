/**
 * mcAssets.js - 游戏资源解析器（主进程）
 *
 * 从本机 vanilla 1.21.1 资源根读取 方块→贴图 映射（blockstate → model → texture），
 * 供 3D 预览渲染真实方块贴图。
 *
 * 资源根自动探测（可用配置 mcAssetsRoot 覆盖）：
 *   1. <项目根>/../数据集/1.21.1/client            （用户数据集，首选）
 *   2. <项目根>/run/client                         （dev 客户端，若已解出 assets）
 *   3. %USERPROFILE%/.cache/minecraft-modding-mcp/decompiled/<最新>/
 *   资源根须包含 assets/minecraft/textures/block 才有效。
 *
 * 许可说明：
 *   贴图/模型/块状态 JSON 是 Mojang 游戏文件在【用户本机】的自有副本
 *   （与游戏安装同来源），仅用于本机编辑器内显示，不随编辑器分发、不重传。
 *
 * 后续演进（用户规划）：AssetSource 可替换为「实时读取正在加载的游戏
 * （原版 + 自定义包）」实现，接口不变。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

/** 探测到的资源根（懒加载一次） */
let resolvedRoot = null;
let resolvedExplicit = false;

/** 配置覆盖的资源根 */
function setRootOverride(root) {
    resolvedRoot = root && fs.existsSync(root) ? root : null;
    resolvedExplicit = true;
}

/**
 * 探测资源根
 * @param {string} projectRoot 项目根（含 run/ 的目录）
 */
function resolveRoot(projectRoot) {
    if (resolvedExplicit) return resolvedRoot;
    const home = process.env.USERPROFILE || '';
    const cands = [];
    if (projectRoot) {
        // 数据集位置不固定：从项目根向上逐级找 <X>/数据集/1.21.1/client
        let up = projectRoot;
        for (let i = 0; i < 5; i++) {
            cands.push(path.join(up, '数据集', '1.21.1', 'client'));
            up = path.dirname(up);
            if (!up || up === path.dirname(up)) break;
        }
        cands.push(path.join(projectRoot, 'run', 'client'));
    }
    // MCP 解包缓存（取最新修改的）
    const mcpBase = path.join(home, '.cache', 'minecraft-modding-mcp', 'decompiled');
    if (fs.existsSync(mcpBase)) {
        try {
            const dirs = fs.readdirSync(mcpBase, { withFileTypes: true })
                .filter((d) => d.isDirectory())
                .map((d) => {
                    const p = path.join(mcpBase, d.name);
                    let mtime = 0;
                    try { mtime = fs.statSync(p).mtimeMs; } catch {}
                    return { p, mtime };
                })
                .sort((a, b) => b.mtime - a.mtime);
            for (const d of dirs) cands.push(d.p);
        } catch {}
    }
    for (const c of cands) {
        if (c && fs.existsSync(path.join(c, 'assets', 'minecraft', 'textures', 'block'))) {
            resolvedRoot = c;
            return resolvedRoot;
        }
    }
    return null;
}

function isReady(root) {
    return !!(root && fs.existsSync(path.join(root, 'assets', 'minecraft', 'textures', 'block')));
}

/** 纹理 ID（minecraft:block/xxx）→ 绝对文件路径（不存在返回 null） */
function texturePath(root, texId) {
    if (!texId) return null;
    let id = texId;
    if (id.startsWith('minecraft:')) id = id.slice('minecraft:'.length);
    else if (id.includes(':')) id = id.slice(id.indexOf(':') + 1); // 自定义命名空间 → 无本地文件
    const rel = path.join('assets', 'minecraft', 'textures', id + '.png');
    const p = path.join(root, rel);
    if (fs.existsSync(p)) return p;
    // 常见回退：block/ 子目录
    const name = id.split('/').pop();
    const fb = path.join(root, 'assets', 'minecraft', 'textures', 'block', name + '.png');
    if (fs.existsSync(fb)) return fb;
    return null;
}

const jsonCache = new Map(); // file → parsed
function readJsonCached(p) {
    if (jsonCache.has(p)) return jsonCache.get(p);
    let v = null;
    try {
        v = JSON.parse(fs.readFileSync(p, 'utf-8'));
    } catch {}
    jsonCache.set(p, v);
    return v;
}

const facesCache = new Map(); // blockId → result
const MISSING = Symbol('missing');

/**
 * 解析方块的 6 面贴图
 * @param {string} root 资源根
 * @param {string} blockId 如 minecraft:stone_bricks（可省略命名空间）
 * @returns {{top:string|null, side:string|null, bottom:string|null, all?:string|null, source?:string}}
 *          值为 dataURL；全部 null = 无贴图（调用方回退色板）
 */
function blockFaces(root, blockId) {
    let id = blockId;
    // 方块串可带状态属性（如 minecraft:oak_log[axis=y]）——贴图只取决于基础名，剥掉 [props]
    const br = id ? id.indexOf('[') : -1;
    if (br > 0) id = id.slice(0, br);
    if (id && !id.includes(':')) id = 'minecraft:' + id;
    if (!id) return {};
    if (facesCache.has(id)) return facesCache.get(id);

    const out = { top: null, side: null, bottom: null };
    const toDataUrl = (texId) => {
        const p = texturePath(root, texId);
        if (!p) return null;
        const key = 'dataUrl:' + p;
        if (!jsonCache.has(key)) {
            try { jsonCache.set(key, 'data:image/png;base64,' + fs.readFileSync(p).toString('base64')); }
            catch { jsonCache.set(key, null); }
        }
        return jsonCache.get(key);
    };

    // 1) blockstate → 首选 axis=y（竖直原木类），否则第一个 variant
    const bsFile = path.join(root, 'assets', 'minecraft', 'blockstates', id.split(':').pop() + '.json');
    let modelId = null;
    if (fs.existsSync(bsFile)) {
        const bs = readJsonCached(bsFile);
        const variants = (bs && bs.variants) || {};
        const keys = Object.keys(variants);
        let pick = null;
        if (variants['']) pick = variants[''];
        else if (variants['axis=y']) pick = variants['axis=y'];
        else if (keys.length) pick = variants[keys[0]];
        if (pick) {
            modelId = Array.isArray(pick) ? pick[0].model : pick.model;
        }
    }
    if (!modelId) {
        // 2) 无 blockstate：直接猜 model/block/<name>
        modelId = 'minecraft:block/' + id.split(':').pop();
    }

    const ns = id.split(':')[0] || 'minecraft';
    const modelName = modelId.startsWith(ns + ':') ? modelId.slice(ns.length + 1) : modelId;
    const modelFile = path.join(root, 'assets', ns, 'models', modelName + '.json');
    let tex = {};
    if (fs.existsSync(modelFile)) {
        const m = readJsonCached(modelFile);
        tex = (m && m.textures) || {};
        const parent = (m && m.parent) || '';
        if (parent.endsWith('cube_all')) {
            const a = tex.all || Object.values(tex)[0] || null;
            out.top = toDataUrl(a); out.side = toDataUrl(a); out.bottom = toDataUrl(a);
        } else if (parent.endsWith('cube_column')) {
            out.side = toDataUrl(tex.side || null);
            const t = tex.top || tex.end || tex.side || null;
            out.top = toDataUrl(t); out.bottom = toDataUrl(t);
        } else if (parent.endsWith('cube_bottom_top')) {
            out.top = toDataUrl(tex.top || null);
            out.bottom = toDataUrl(tex.bottom || null);
            out.side = toDataUrl(tex.side || tex.all || null);
        } else {
            // slab/stairs/cross/自定义：尽力取 side/top/bottom，否则首个纹理铺 6 面
            out.side = toDataUrl(tex.side || tex.all || null);
            out.top = toDataUrl(tex.top || tex.end || out.side);
            out.bottom = toDataUrl(tex.bottom || out.top);
            if (!out.side) {
                const any = Object.values(tex)[0] || null;
                out.top = out.side = out.bottom = toDataUrl(any);
            }
        }
    }

    const res = (out.top || out.side || out.bottom) ? out : MISSING;
    facesCache.set(id, res);
    return res === MISSING ? {} : res;
}

/** 全部方块 ID 列表（blockstates 文件名 → minecraft:<name>） */
const listCache = { root: null, list: null };
function blockList(root) {
    if (listCache.root === root && listCache.list) return listCache.list;
    const dir = path.join(root, 'assets', 'minecraft', 'blockstates');
    let list = [];
    try {
        list = fs.readdirSync(dir)
            .filter((f) => f.endsWith('.json'))
            .map((f) => 'minecraft:' + f.slice(0, -5))
            .sort();
    } catch {}
    listCache.root = root;
    listCache.list = list;
    return list;
}

/** 剥掉状态属性："minecraft:oak_log[axis=y]" → "minecraft:oak_log"（定义/贴图只取决于基础名） */
function stripProps(blockId) {
    const s = String(blockId || '');
    const i = s.indexOf('[');
    return i > 0 ? s.slice(0, i) : s;
}

/**
 * 解析 blockstate JSON（1.21.1 数据集格式）
 * variants 键格式：`"a=1,b=2"` 或 `{ prop: { value: model } }`（或单模型）
 * @returns {{properties:Object<string,string[]>, variantCount:number}}
 */
function parseBlockstateJson(raw) {
    const props = {};
    const addProp = (p, v) => {
        if (!props[p]) props[p] = [];
        if (v && !props[p].includes(v)) props[p].push(v);
    };
    const variants = (raw && typeof raw.variants === 'object' && raw.variants) || {};
    let variantCount = 0;
    for (const key of Object.keys(variants)) {
        const v = variants[key];
        if (key.includes('=')) {
            // 扁平键："facing=north,up=true"
            for (const kv of key.split(',')) {
                const eq = kv.indexOf('=');
                if (eq < 0) continue;
                addProp(kv.slice(0, eq).trim(), kv.slice(eq + 1).trim());
            }
            variantCount += Array.isArray(v) ? v.length : 1;
        } else if (v && typeof v === 'object') {
            // 嵌套：{ facing: { north: model, south: model } } 或 { facing: [models] }
            if (Array.isArray(v)) variantCount += v.length;
            else { for (const val of Object.keys(v)) addProp(key, val); variantCount += Object.keys(v).length; }
        } else {
            variantCount += 1;
        }
    }
    const out = {};
    for (const [p, vs] of Object.entries(props)) out[p] = vs.sort();
    return { properties: out, variantCount };
}

/**
 * P11 右键查方块：读 blockstate 定义（资源根 = vanilla 数据集）
 * @returns {{file:string, properties:Object<string,string[]>, variantCount:number}|null}
 */
function blockState(root, blockId) {
    const s = stripProps(blockId);
    const i = s.indexOf(':');
    const ns = i >= 0 ? s.slice(0, i) : 'minecraft';
    const name = i >= 0 ? s.slice(i + 1) : s;
    if (!name || name.includes('/') || name.includes('\\')) return null;
    const file = path.join(root, 'assets', ns, 'blockstates', name + '.json');
    let raw;
    try { raw = JSON.parse(fs.readFileSync(file, 'utf-8')); } catch { return null; }
    return {
        file: 'assets/' + ns + '/blockstates/' + name + '.json',
        ...parseBlockstateJson(raw),
    };
}

// ===== P13 通用链路：任意方块（vanilla/数据驱动/mod）的 blockstate 定义查找 =====

/**
 * 读 zip 条目内容（中央目录定位 + inflateRaw；零第三方依赖）
 * @returns {Buffer|null}
 */
function readZipEntry(jarPath, entryName) {
    let buf;
    try { buf = fs.readFileSync(jarPath); } catch { return null; }
    if (buf.length < 22) return null;
    let eocd = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65558); i--) {
        if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) return null;
    const count = buf.readUInt16LE(eocd + 10);
    let off = buf.readUInt32LE(eocd + 16);
    for (let n = 0; n < count; n++) {
        if (off + 46 > buf.length || buf.readUInt32LE(off) !== 0x02014b50) return null;
        const method = buf.readUInt16LE(off + 10);
        const clen = buf.readUInt32LE(off + 20);
        const csize = buf.readUInt32LE(off + 24);
        const nlen = buf.readUInt16LE(off + 28);
        const elen = buf.readUInt16LE(off + 30);
        const loff = buf.readUInt32LE(off + 42);
        const name = buf.toString('utf8', off + 46, off + 46 + nlen);
        if (name === entryName) {
            if (loff + 30 > buf.length || buf.readUInt32LE(loff) !== 0x04034b50) return null;
            const lnlen = buf.readUInt16LE(loff + 26);
            const lelen = buf.readUInt16LE(loff + 28);
            const start = loff + 30 + lnlen + lelen;
            if (start + csize > buf.length) return null;
            const raw = buf.slice(start, start + csize);
            try { return method === 8 ? zlib.inflateRawSync(raw) : Buffer.from(raw); }
            catch { return null; }
        }
        off += 46 + nlen + elen + clen;
    }
    return null;
}

function tryReadJson(p) {
    try { return JSON.parse(fs.readFileSync(p, 'utf-8')); } catch { return null; }
}

/**
 * 通用 blockstate 定义查找（P13）——按优先级：
 *   1. vanilla 数据集（resolveRoot）
 *   2. 正在编辑的内容包 opts.packDir
 *   3. run/resourcepacks/ 各包、run/saves/<world>/datapacks/ 各包、run/data（隐式数据包）
 *   4. run/mods/*.jar 内 assets/<ns>/blockstates/<name>.json（zip 条目读取）
 * @returns {{data:{file,properties,variantCount}, source:'vanilla'|'pack'|'datapack'|'mod'}|null}
 */
function blockStateAny(projectRoot, blockId, opts = {}) {
    const s = stripProps(blockId);
    const i = s.indexOf(':');
    const ns = i >= 0 ? s.slice(0, i) : 'minecraft';
    const name = i >= 0 ? s.slice(i + 1) : s;
    if (!name || name.includes('/') || name.includes('\\')) return null;
    const rel = 'assets/' + ns + '/blockstates/' + name + '.json';

    // 1) vanilla 数据集
    const root = resolveRoot(projectRoot);
    if (root) {
        const d = blockState(root, s);
        if (d) return { data: d, source: 'vanilla' };
    }
    // 2) 正在编辑的内容包
    if (opts.packDir) {
        const raw = tryReadJson(path.join(opts.packDir, rel));
        if (raw) return { data: { file: rel, ...parseBlockstateJson(raw) }, source: 'pack' };
    }
    // 3) 资源包 / 数据包 / 隐式 data
    if (projectRoot) {
        const dirs = [];
        const rp = path.join(projectRoot, 'run', 'resourcepacks');
        try { for (const en of fs.readdirSync(rp, { withFileTypes: true })) if (en.isDirectory()) dirs.push([path.join(rp, en.name), 'datapack']); } catch {}
        const saves = path.join(projectRoot, 'run', 'saves');
        try {
            for (const w of fs.readdirSync(saves, { withFileTypes: true })) {
                if (!w.isDirectory()) continue;
                const dp = path.join(saves, w.name, 'datapacks');
                try { for (const d of fs.readdirSync(dp, { withFileTypes: true })) if (d.isDirectory()) dirs.push([path.join(dp, d.name), 'datapack']); } catch {}
            }
        } catch {}
        dirs.push([path.join(projectRoot, 'run', 'data'), 'datapack']);
        for (const [dir, src] of dirs) {
            const raw = tryReadJson(path.join(dir, rel));
            if (raw) return { data: { file: rel, ...parseBlockstateJson(raw) }, source: src };
        }
    }
    // 4) mod jar
    if (projectRoot) {
        const mods = path.join(projectRoot, 'run', 'mods');
        let files;
        try { files = fs.readdirSync(mods); } catch { files = []; }
        for (const f of files) {
            if (!f.toLowerCase().endsWith('.jar')) continue;
            const rawBuf = readZipEntry(path.join(mods, f), rel);
            if (!rawBuf) continue;
            let raw;
            try { raw = JSON.parse(rawBuf.toString('utf-8')); } catch { raw = null; }
            if (raw) return { data: { file: rel + '  [' + f + ']', ...parseBlockstateJson(raw) }, source: 'mod' };
        }
    }
    return null;
}

module.exports = {
    resolveRoot,
    setRootOverride,
    isReady,
    blockFaces,
    blockList,
    blockState,
    parseBlockstateJson,
    readZipEntry,
    blockStateAny,
    stripProps,
    texturePath,
};
