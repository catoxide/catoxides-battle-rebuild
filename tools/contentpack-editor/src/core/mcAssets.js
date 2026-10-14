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

// ===== REVIEW-S3：资源路径越界防护 =====
// 数据驱动的 blockId/纹理 ID 可能来自恶意内容包（圈地 NBT 的 palette Name、
// 包内 blockstate/model JSON 的 variant.model / textures 字段）。
// 统一规则：正斜杠分段，拒绝空段 / '.' / '..' / 反斜杠 / 绝对路径。

/** rel（'assets/ns/…/x.png' 正斜杠形）是否安全（无越界段） */
function isSafeRel(rel) {
    if (typeof rel !== 'string' || !rel) return false;
    if (rel.includes('\\') || rel.startsWith('/')) return false;
    return rel.split('/').every((s) => s !== '' && s !== '.' && s !== '..');
}

/** 资源 ID 路径段（可含 '/' 子目录，如 'block/stone_bricks'）是否安全 */
function isSafeIdPath(id) {
    if (typeof id !== 'string' || !id) return false;
    if (id.includes('\\') || id.startsWith('/')) return false;
    return id.split('/').every((s) => s !== '' && s !== '.' && s !== '..');
}

/** 单一命名空间/方块名（不允许 '/' 子目录）是否安全 */
function isSafeSimpleName(name) {
    return typeof name === 'string' && name.length > 0
        && !name.includes('/') && !name.includes('\\') && name !== '.' && name !== '..';
}

/** 纹理 ID（minecraft:block/xxx）→ 绝对文件路径（不存在返回 null） */
function texturePath(root, texId) {
    if (!texId) return null;
    let id = texId;
    if (id.startsWith('minecraft:')) id = id.slice('minecraft:'.length);
    else if (id.includes(':')) id = id.slice(id.indexOf(':') + 1); // 自定义命名空间 → 无本地文件
    if (!isSafeIdPath(id)) return null; // REVIEW-S3：.. 段零防护 → 越界读任意 .png
    const rel = path.join('assets', 'minecraft', 'textures', id + '.png');
    const p = path.join(root, rel);
    if (!p.startsWith(root + path.sep)) return null;
    if (fs.existsSync(p)) return p;
    // 常见回退：block/ 子目录
    const name = id.split('/').pop();
    const fb = path.join(root, 'assets', 'minecraft', 'textures', 'block', name + '.png');
    if (fb.startsWith(root + path.sep) && fs.existsSync(fb)) return fb;
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

    // REVIEW-S3：方块名/命名空间必须是单一安全段（对照 blockState L305 的同款拦截，
    // 此前 blockFaces 缺失 → 'x:../../evil/blockstates/door' 可越界读 blockstate JSON）
    if (!isSafeSimpleName(id.split(':')[0]) || !isSafeSimpleName(id.split(':').pop())) {
        facesCache.set(id, MISSING);
        return {};
    }

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
    // REVIEW-S3：modelId 来自（可能恶意的）blockstate JSON 的 variant.model ——
    // modelName 允许 '/' 子目录（如 block/stone_bricks），但拒绝 '..' 段与非法 ns
    if (!isSafeIdPath(modelName) || !isSafeSimpleName(ns)) {
        facesCache.set(id, MISSING);
        return {};
    }
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

/**
 * P15 方块图标（编辑器槽位缓存栏，原版物品栏那套逻辑）：
 * ① 优先物品纹理 <root>/assets/<ns>/items/<name>.png（vanilla 物品栏图标；同 blockFaces 的 root 约定）
 * ② 回退方块面贴图（side > top > bottom）
 * @returns {{icon: string|null, from: 'item'|'face'|null}}
 */
const iconCache = new Map();
function blockIcon(assetRoot, blockId, opts = {}) {
    let id = blockId;
    const br = id ? id.indexOf('[') : -1;
    if (br > 0) id = id.slice(0, br);
    if (id && !id.includes(':')) id = 'minecraft:' + id;
    if (!id) return { icon: null, from: null };
    if (iconCache.has(id)) return iconCache.get(id);
    const colon = id.indexOf(':');
    const ns = id.slice(0, colon), name = id.slice(colon + 1);
    const res = { icon: null, from: null };
    // REVIEW-S3：blockId 可能来自恶意蓝图/圈地 NBT → ns/name 必须是单一安全段
    if (!isSafeSimpleName(ns) || !isSafeSimpleName(name)) {
        iconCache.set(id, res);
        return res;
    }
    // ① 物品纹理：vanilla 资源根 → 正在编辑的内容包（若提供）
    const itemCands = [path.join(assetRoot, 'assets', ns, 'items', name + '.png')];
    if (opts && opts.packDir) itemCands.push(path.join(opts.packDir, 'assets', ns, 'items', name + '.png'));
    for (const p of itemCands) {
        try {
            if (fs.existsSync(p)) {
                res.icon = 'data:image/png;base64,' + fs.readFileSync(p).toString('base64');
                res.from = 'item';
                break;
            }
        } catch {}
    }
    // ② 面贴图回退
    if (!res.icon) {
        const faces = blockFaces(assetRoot, id);
        const u = faces.side || faces.top || faces.bottom || null;
        if (u) { res.icon = u; res.from = 'face'; }
    }
    iconCache.set(id, res);
    return res;
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
        const compSize = buf.readUInt32LE(off + 20); // 压缩大小
        // off+24 = 未压缩大小（只读参考；切片/边界必须用压缩大小）
        const nlen = buf.readUInt16LE(off + 28);
        const elen = buf.readUInt16LE(off + 30);
        const commentLen = buf.readUInt16LE(off + 32);
        const loff = buf.readUInt32LE(off + 42);
        const name = buf.toString('utf8', off + 46, off + 46 + nlen);
        if (name === entryName) {
            if (loff + 30 > buf.length || buf.readUInt32LE(loff) !== 0x04034b50) return null;
            const lnlen = buf.readUInt16LE(loff + 26);
            const lelen = buf.readUInt16LE(loff + 28);
            const start = loff + 30 + lnlen + lelen;
            // REVIEW-M2：边界检查与切片都用【压缩大小】（此前误用未压缩大小，
            // stored 条目会切错；deflate 条目靠 inflate 忽略尾字节侥幸正确）
            if (start + compSize > buf.length) return null;
            const raw = buf.slice(start, start + compSize);
            try { return method === 8 ? zlib.inflateRawSync(raw) : Buffer.from(raw); }
            catch { return null; }
        }
        // REVIEW-M2：中央目录步进 = 46 + 文件名长 + 额外域长 + 【注释长】
        // （此前误加 off+20 的 uint32 压缩大小 → 第二条目起恒定位错，
        //   每个 jar 只有第一个条目可命中，mod jar 查找链整体失效）
        off += 46 + nlen + elen + commentLen;
    }
    return null;
}

function tryReadJson(p) {
    try { return JSON.parse(fs.readFileSync(p, 'utf-8')); } catch { return null; }
}

/**
 * 资源文件通用查找（P13/P14 链路）——按优先级：
 *   1. vanilla 数据集（resolveRoot）
 *   2. 正在编辑的内容包 opts.packDir
 *   3. run/resourcepacks/ 各包、run/saves/<world>/datapacks/ 各包、run/data（隐式数据包）
 *   4. run/mods/*.jar 内（zip 条目读取）
 * @param {string} rel 资源相对路径（'assets/ns/.../x.json'，正斜杠）
 * @returns {{abs:string, source:string}|{buf:Buffer, source:string}|null}
 *   磁盘文件 → {abs}；jar 内 → {buf}；source ∈ vanilla|pack|datapack|mod:<jar名>
 */
function findAsset(projectRoot, rel, opts = {}) {
    // REVIEW-S3：rel 由各调用方（含恶意包 JSON 驱动的 modelRel/纹理 ID）拼出，
    // 统一在此拒绝 '..' / '.' / 空段 / 反斜杠 / 绝对路径（resolveRepoFile 同款规则）
    if (!isSafeRel(rel)) return null;
    const parts = rel.split('/');
    // 1) vanilla 数据集
    const root = resolveRoot(projectRoot);
    if (root) {
        const p = path.join(root, ...parts);
        if (fs.existsSync(p)) return { abs: p, source: 'vanilla' };
    }
    // 2) 正在编辑的内容包
    if (opts.packDir) {
        const p = path.join(opts.packDir, ...parts);
        if (fs.existsSync(p)) return { abs: p, source: 'pack' };
    }
    if (!projectRoot) return null;
    // 3) 资源包 / 数据包 / 隐式 data
    const dirs = [];
    const rp = path.join(projectRoot, 'run', 'resourcepacks');
    try { for (const en of fs.readdirSync(rp, { withFileTypes: true })) if (en.isDirectory()) dirs.push(path.join(rp, en.name)); } catch {}
    const saves = path.join(projectRoot, 'run', 'saves');
    try {
        for (const w of fs.readdirSync(saves, { withFileTypes: true })) {
            if (!w.isDirectory()) continue;
            const dp = path.join(saves, w.name, 'datapacks');
            try { for (const d of fs.readdirSync(dp, { withFileTypes: true })) if (d.isDirectory()) dirs.push(path.join(dp, d.name)); } catch {}
        }
    } catch {}
    dirs.push(path.join(projectRoot, 'run', 'data'));
    for (const dir of dirs) {
        const p = path.join(dir, ...parts);
        if (fs.existsSync(p)) return { abs: p, source: 'datapack' };
    }
    // 4) mod jar
    const mods = path.join(projectRoot, 'run', 'mods');
    let files;
    try { files = fs.readdirSync(mods); } catch { files = []; }
    for (const f of files) {
        if (!f.toLowerCase().endsWith('.jar')) continue;
        const buf = readZipEntry(path.join(mods, f), rel);
        if (buf) return { buf, source: 'mod:' + f };
    }
    return null;
}

function readAssetJson(found) {
    if (!found) return null;
    try {
        return JSON.parse(found.abs ? fs.readFileSync(found.abs, 'utf-8') : found.buf.toString('utf-8'));
    } catch { return null; }
}

/**
 * 通用 blockstate 定义查找（P13）
 * @returns {{data:{file,properties,variantCount}, source:'vanilla'|'pack'|'datapack'|'mod'}|null}
 */
function blockStateAny(projectRoot, blockId, opts = {}) {
    const s = stripProps(blockId);
    const i = s.indexOf(':');
    const ns = i >= 0 ? s.slice(0, i) : 'minecraft';
    const name = i >= 0 ? s.slice(i + 1) : s;
    if (!name || name.includes('/') || name.includes('\\')) return null;
    const rel = 'assets/' + ns + '/blockstates/' + name + '.json';
    const found = findAsset(projectRoot, rel, opts);
    const raw = readAssetJson(found);
    if (!raw) return null;
    const src = found.source === 'vanilla' ? 'vanilla'
        : found.source === 'pack' ? 'pack'
        : found.source === 'datapack' ? 'datapack'
        : 'mod';
    const tag = found.source.startsWith('mod:') ? '  [' + found.source.slice(4) + ']' : '';
    return { data: { file: rel + tag, ...parseBlockstateJson(raw) }, source: src };
}

/**
 * 解析 blockstate JSON 的完整 variants（P14：variant 匹配用）
 * @returns {{key:string, model:string, x:number, y:number, uvlock:boolean, weight:number}[]}（文件序）
 */
function parseBlockstateVariants(raw) {
    const out = [];
    const variants = (raw && typeof raw.variants === 'object' && raw.variants) || {};
    const items = Array.isArray(variants) ? variants.map((v, i) => [String(i), v]) : Object.entries(variants);
    for (const [key, v] of items) {
        if (!v || typeof v !== 'object' || !v.model) continue;
        const model = Array.isArray(v.model) ? v.model[0] : String(v.model);
        out.push({
            key: String(key),
            model,
            x: Number(v.x) || 0,
            y: Number(v.y) || 0,
            uvlock: !!v.uvlock,
            weight: Number(v.weight) || 1,
        });
    }
    return out;
}

// ===== P14 blockAppearance：vanilla 标准渲染链路（主进程完整烘焙）=====
// ground truth = 反编译游戏源码（MCP 缓存 a759a935）：
//   FaceBakery（顶点/UV/元素旋转/模型旋转 pivot 0.5）· BlockFaceUV（uv 角映射 + rotation 字段）
//   · FaceInfo（6 面四角序）· BlockElement（默认 cuboid UV uvsByFace + 元素旋转参数）
//   · BlockModel（parent 链：elements 取首个非空、textures 沿链查找、#ref 递归）
//   · MultiVariant/Variant（variant 键=属性子集匹配；旋转 x/y∈0/90/180/270；uvlock）
//   · BlockModelRotation（q = rotateYXZ(-y°,-x°) → M = Ry(-y)·Rx(-x)，绕方块中心）
//   · BlockMath.getUVLockTransform（UV 锁：G2L[dir] ∘ T⁻¹ ∘ L2G[rotatedDir]，0.5 中心仿射）
// 输出：每个 element-face 的 4 顶点（pos 0..1 方块空间 / uv 0..1 已转 three.js 方向）+ 贴图 dataURL + shade。

const FACE_CORNERS = { // FaceInfo 四角（shape 数组下标；shape=[minY,maxY,minZ,maxZ,minX,maxX]）
    down:  [[4, 0, 3], [4, 0, 2], [5, 0, 2], [5, 0, 3]],
    up:    [[4, 1, 2], [4, 1, 3], [5, 1, 3], [5, 1, 2]],
    north: [[5, 1, 2], [5, 0, 2], [4, 0, 2], [4, 1, 2]],
    south: [[4, 1, 3], [4, 0, 3], [5, 0, 3], [5, 1, 3]],
    west:  [[4, 1, 2], [4, 0, 2], [4, 0, 3], [4, 1, 3]],
    east:  [[5, 1, 3], [5, 0, 3], [5, 0, 2], [5, 1, 2]],
};
const FACE_NORMAL = {
    down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1],
    south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0],
};
const SHADE = { up: 1.0, down: 0.5, north: 0.8, south: 0.8, west: 0.6, east: 0.6 }; // 游戏方块亮度

// --- 3x3 矩阵（行主序 9 元组；仅旋转） ---
function mat3Mul(a, b) {
    const r = new Array(9);
    for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) {
        r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
    }
    return r;
}
function mat3Trans(a) { return [a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]]; } // 旋转矩阵逆=转置
function mat3Vec(m, v) {
    return [
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    ];
}
function rotX(deg) {
    const t = deg * Math.PI / 180, c = Math.cos(t), s = Math.sin(t);
    return [1, 0, 0, 0, c, -s, 0, s, c];
}
function rotY(deg) {
    const t = deg * Math.PI / 180, c = Math.cos(t), s = Math.sin(t);
    return [c, 0, s, 0, 1, 0, -s, 0, c];
}
/** 模型（variant）旋转：M = Ry(-y)·Rx(-x)（BlockModelRotation rotateYXZ(-y°,-x°)） */
function modelRotMat(x, y) { return mat3Mul(rotY(-y), rotX(-x)); }
/** Rodrigues 绕任意轴右手旋转（元素旋转用） */
function rotAxisMat(axis, deg) {
    const t = deg * Math.PI / 180, c = Math.cos(t), s = Math.sin(t);
    const [x, y, z] = axis, f = 1 - c;
    return [
        c + x * x * f, x * y * f - z * s, x * z * f + y * s,
        y * x * f + z * s, c + y * y * f, y * z * f - x * s,
        z * x * f - y * s, z * y * f + x * s, c + z * z * f,
    ];
}
function closestDir(v) {
    let best = 'up', bd = -1;
    for (const [name, n] of Object.entries(FACE_NORMAL)) {
        const d = v[0] * n[0] + v[1] * n[1] + v[2] * n[2];
        if (d > bd) { bd = d; best = name; }
    }
    return best;
}

// --- UV 锁（BlockMath.getUVLockTransform + FaceBakery.recomputeUVs） ---
const UV_ID = [1, 0, 0, 0, 1, 0, 0, 0, 1];
const UV_L2G = {
    south: UV_ID, east: rotY(90), west: rotY(-90), north: rotY(180),
    up: rotX(-90), down: rotX(90),
};
function uvLockMat(T, faceDir) {
    const rotated = closestDir(mat3Vec(T, FACE_NORMAL[faceDir]));
    return mat3Mul(mat3Mul(mat3Trans(UV_L2G[faceDir]), mat3Trans(T)), UV_L2G[rotated]);
}
/**
 * UV 锁定重算：返回 {uv:[u0,v0,u2,v2], rotation}
 * （0.5 中心仿射变换两个对角 uv 点 + 符号保序 + 新 rotation = M·(cos r, sin r) 的角度取整）
 */
function recomputeUVs(uv, rotation, faceDir, T) {
    const M = uvLockMat(T, faceDir);
    const xf = (u, v) => {
        const p = mat3Vec(M, [u - 0.5, v - 0.5, -0.5]);
        return [p[0] + 0.5, p[1] + 0.5];
    };
    const a = xf(uv[0], uv[1]);
    const b = xf(uv[2], uv[3]);
    let u0 = a[0], u2 = b[0];
    if (Math.sign(uv[2] - uv[0]) !== Math.sign(b[0] - a[0])) { const t = u0; u0 = u2; u2 = t; }
    let v0 = a[1], v2 = b[1];
    if (Math.sign(uv[3] - uv[1]) !== Math.sign(b[1] - a[1])) { const t = v0; v0 = v2; v2 = t; }
    const r = rotation * Math.PI / 180;
    const w = mat3Vec(M, [Math.cos(r), Math.sin(r), 0]);
    const newRot = ((-Math.round(Math.atan2(w[1], w[0]) * 180 / Math.PI / 90) * 90) % 360 + 360) % 360;
    return { uv: [u0, v0, u2, v2], rotation: newRot };
}

/** 元素旋转（FaceBakery.applyElementRotation：先绕 origin 转，再按 perp 轴 rescale） */
function elemRotate(p, rot) {
    if (!rot) return p;
    const ox = rot.origin[0] / 16, oy = rot.origin[1] / 16, oz = rot.origin[2] / 16;
    const axis = rot.axis === 'x' ? [1, 0, 0] : rot.axis === 'y' ? [0, 1, 0] : [0, 0, 1];
    let d = mat3Vec(rotAxisMat(axis, rot.angle), [p[0] - ox, p[1] - oy, p[2] - oz]);
    if (rot.rescale) {
        const f = (Math.abs(rot.angle) === 22.5 ? 1 / Math.cos(22.5 * Math.PI / 180) : 1 / Math.cos(45 * Math.PI / 180)) - 1;
        const perp = rot.axis === 'x' ? [0, 1, 1] : rot.axis === 'y' ? [1, 0, 1] : [1, 1, 0];
        for (let i = 0; i < 3; i++) if (perp[i]) d[i] *= 1 + f;
    }
    return [d[0] + ox, d[1] + oy, d[2] + oz];
}

/** 默认 cuboid UV（BlockElement.uvsByFace；face 无 "uv" 字段时用） */
function defaultUv(face, from, to) {
    const [x1, y1, z1] = from, [x2, y2, z2] = to;
    switch (face) {
        case 'down': return [x1, 16 - z2, x2, 16 - z1];
        case 'up': return [x1, z1, x2, z2];
        case 'south': return [x1, 16 - y2, x2, 16 - y1];
        case 'west': return [z1, 16 - y2, z2, 16 - y1];
        case 'east': return [16 - z2, 16 - y2, 16 - z1, 16 - y1];
        default: return [16 - x2, 16 - y2, 16 - x1, 16 - y1]; // north
    }
}

/** 模型 ID → 资源相对路径（'ns:path' → assets/ns/models/path.json） */
function modelRel(modelId) {
    const i = String(modelId).indexOf(':');
    const ns = i >= 0 ? modelId.slice(0, i) : 'minecraft';
    const p = i >= 0 ? modelId.slice(i + 1) : modelId;
    return 'assets/' + ns + '/models/' + p + '.json';
}

/** 模型 parent 链（child→root；防环 8 层）；@returns Array<{raw,parent}>（[0]=child） */
function loadModelChain(projectRoot, modelId, opts, depth = 0) {
    if (depth > 8 || !modelId) return null;
    const found = findAsset(projectRoot, modelRel(modelId), opts);
    const raw = readAssetJson(found);
    if (!raw || typeof raw !== 'object') return null;
    const chain = [{ raw }];
    if (raw.parent) {
        const parentChain = loadModelChain(projectRoot, raw.parent, opts, depth + 1);
        if (parentChain) chain.push(...parentChain);
    }
    return chain;
}

/** 沿链解析纹理引用（#ref 递归；child 优先）→ 纹理 ID 或 null */
function resolveTextureId(chain, ref) {
    let name = ref;
    const seen = new Set();
    for (;;) {
        if (seen.has(name)) return null;
        seen.add(name);
        if (name.startsWith('#')) name = name.slice(1);
        let resolved = null;
        for (const node of chain) {
            const t = node.raw.textures && node.raw.textures[name];
            if (t != null) { resolved = String(t); break; }
        }
        if (resolved == null) return null;
        if (!resolved.startsWith('#')) return resolved;
        name = resolved;
    }
}

/** 读纹理 → dataURL（缓存；不存在返回 null） */
const texDataCache = new Map();
function textureDataUrl(projectRoot, texId, opts) {
    const key = texId;
    if (texDataCache.has(key)) return texDataCache.get(key);
    if (texId && texId.includes(':')) {
        const i = texId.indexOf(':');
        const rel = 'assets/' + texId.slice(0, i) + '/textures/' + texId.slice(i + 1) + '.png';
        const found = findAsset(projectRoot, rel, opts);
        if (found) {
            const buf = found.abs ? fs.readFileSync(found.abs) : found.buf;
            const url = 'data:image/png;base64,' + buf.toString('base64');
            if (texDataCache.size > 4096) texDataCache.clear();
            texDataCache.set(key, url);
            return url;
        }
    }
    texDataCache.set(key, null);
    return null;
}

// 裸方块串（无 [props]）按游戏 StateDefinition 默认值补齐（对照反编译源码：
// SlabBlock type=bottom/waterlogged=false；DoorBlock half=lower；stairs facing=north 等）
const PROP_DEFAULTS = {
    facing: 'north',
    half: 'bottom', // stairs/trapdoor；门的 half=lower 由第三级通配兜底
    shape: 'straight',
    waterlogged: 'false',
    open: 'false',
    hinge: 'left',
    type: 'bottom',
    axis: 'x',
    east: 'false', west: 'false', north: 'false', south: 'false',
    powered: 'false', lit: 'false',
};

/**
 * 解析 blockstate variants → 匹配当前实例。三级（属性子集匹配，文件序首个命中）：
 *   1. 严格：键中所有属性在实例中存在且相等
 *   2. 默认补齐：缺失的属性必须等于 PROP_DEFAULTS（裸方块串 → 游戏默认朝向）
 *   3. 通配：缺失属性任意值（兜底门等默认值不在表内的情况）
 *   均未命中 → 空键 variant；再未命中 → null
 */
function matchVariant(variants, inst) {
    const parse = (k) => {
        const vp = {};
        for (const kv of String(k).split(',')) {
            const i = kv.indexOf('=');
            if (i > 0) vp[kv.slice(0, i).trim()] = kv.slice(i + 1).trim();
        }
        return vp;
    };
    // mode 1 严格（缺失属性=不匹配）· mode 2 默认补齐（缺失须=PROP_DEFAULTS）· mode 3 通配（缺失任意）
    const passes = (vp, mode) => {
        for (const [k, val] of Object.entries(vp)) {
            if (inst[k] !== undefined) {
                if (inst[k] !== val) return false;
            } else if (mode === 1) {
                return false;
            } else if (mode === 2 && PROP_DEFAULTS[k] !== val) {
                return false;
            }
        }
        return true;
    };
    for (const mode of [1, 2, 3]) {
        for (const v of variants) {
            if (passes(parse(v.key), mode)) return v;
        }
    }
    const empty = variants.find((v) => !v.key);
    return empty || null;
}

/**
 * P14 方块外观（vanilla 标准链路完整烘焙）
 * @param {string} blockId 方块串（可带 [props] 实例）
 * @returns {{ok:true, data:{model,variantKey,faces:[{pos,uv,tex,shade}],textures:{id:dataURL}}}|{ok:false}}
 */
function blockAppearance(projectRoot, blockId, opts = {}) {
    const id = String(blockId || '');
    if (id === 'minecraft:air' || !id || id.startsWith('#')) return { ok: false, error: 'air/placeholder' };
    if (blockAppearanceCache.has(id)) return blockAppearanceCache.get(id);

    let data = null;
    try {
        const bsRel = (() => {
            const s = stripProps(id);
            const i = s.indexOf(':');
            const ns = i >= 0 ? s.slice(0, i) : 'minecraft';
            return 'assets/' + ns + '/blockstates/' + (i >= 0 ? s.slice(i + 1) : s) + '.json';
        })();
        const rawBs = readAssetJson(findAsset(projectRoot, bsRel, opts));
        if (rawBs) {
            const inst = parsePropsObj(id);
            const variants = parseBlockstateVariants(rawBs);
            const v = matchVariant(variants, inst);
            if (v) {
                const chain = loadModelChain(projectRoot, v.model, opts);
                if (chain) {
                    // elements：链上首个非空（BlockModel.getElements）
                    let elements = null;
                    for (const node of chain) if (node.raw.elements && node.raw.elements.length) { elements = node.raw.elements; break; }
                    if (elements) {
                        const T = modelRotMat(v.x, v.y);
                        const Tinv = mat3Trans(T);
                        const faces = [];
                        const textures = {};
                        for (const el of elements) {
                            const from = el.from, to = el.to;
                            const shape = [from[1] / 16, to[1] / 16, from[2] / 16, to[2] / 16, from[0] / 16, to[0] / 16];
                            const elRot = el.rotation
                                ? { origin: el.rotation.origin || [8, 8, 8], axis: (el.rotation.axis || 'y').toLowerCase(), angle: Number(el.rotation.angle) || 0, rescale: !!el.rotation.rescale }
                                : null;
                            const elemN = elRot ? rotAxisMat(elRot.axis === 'x' ? [1, 0, 0] : elRot.axis === 'y' ? [0, 1, 0] : [0, 0, 1], elRot.angle) : null;
                            for (const [faceName, fd] of Object.entries(el.faces || {})) {
                                if (!FACE_CORNERS[faceName] || !fd || typeof fd !== 'object') continue;
                                let uv = Array.isArray(fd.uv) && fd.uv.length === 4 ? fd.uv.slice() : defaultUv(faceName, from, to);
                                let frot = Number(fd.rotation) || 0;
                                if (v.uvlock) {
                                    const r = recomputeUVs(uv, frot, faceName, T);
                                    uv = r.uv; frot = r.rotation;
                                }
                                const pos = [], uvv = [];
                                for (let i = 0; i < 4; i++) {
                                    const c = FACE_CORNERS[faceName][i];
                                    let p = [shape[c[0]], shape[c[1]], shape[c[2]]];
                                    p = elemRotate(p, elRot);
                                    const d = mat3Vec(T, [p[0] - 0.5, p[1] - 0.5, p[2] - 0.5]);
                                    p = [d[0] + 0.5, d[1] + 0.5, d[2] + 0.5];
                                    // BlockFaceUV.getU/getV（反编译精确对照）：
                                    //   idx = (i + rot/90) % 4
                                    //   U = uv[idx∈{2,3} ? 2 : 0]   V = uv[idx∈{1,2} ? 3 : 1]
                                    // 即角点 0=(u1,v1) 1=(u1,v2) 2=(u2,v2) 3=(u2,v1)
                                    const idx = (i + frot / 90) % 4;
                                    const u = uv[idx === 2 || idx === 3 ? 2 : 0];
                                    const vv = uv[idx === 1 || idx === 2 ? 3 : 1];
                                    pos.push(p[0], p[1], p[2]);
                                    uvv.push(u / 16, 1 - vv / 16);
                                }
                                const texId = resolveTextureId(chain, String(fd.texture || ''));
                                let texUrl = null;
                                if (texId) {
                                    texUrl = textureDataUrl(projectRoot, texId, opts);
                                    if (texUrl && textures[texId] === undefined) textures[texId] = texUrl;
                                }
                                // shade：旋转后的面朝向（T·elemN·normal）
                                let n = FACE_NORMAL[faceName];
                                if (elemN) n = mat3Vec(elemN, n);
                                n = mat3Vec(T, n);
                                const shade = el.shade === false ? 1.0 : SHADE[closestDir(n)];
                                faces.push({ pos, uv: uvv, tex: texId, texUrl, shade });
                            }
                        }
                        if (faces.length) {
                            data = { model: v.model, variantKey: v.key, faces, textures };
                        }
                    }
                }
            }
        }
    } catch (e) {
        data = null;
    }

    const out = data
        ? { ok: true, data }
        : { ok: false, error: 'no blockstate/model' };
    if (blockAppearanceCache.size > 2048) blockAppearanceCache.clear();
    blockAppearanceCache.set(id, out);
    return out;
}

const blockAppearanceCache = new Map();
/** 测试用：清外观缓存 */
function _resetAppearanceCache() {
    blockAppearanceCache.clear();
    texDataCache.clear();
}

/** 方块串 → 实例属性对象（键剥 minecraft: 前缀） */
function parsePropsObj(blockId) {
    const out = {};
    const m = String(blockId || '').match(/\[([^\]]*)\]$/);
    if (!m) return out;
    for (const kv of m[1].split(',')) {
        const i = kv.indexOf('=');
        if (i > 0) out[kv.slice(0, i).trim().replace(/^minecraft:/, '')] = kv.slice(i + 1).trim();
    }
    return out;
}

module.exports = {
    resolveRoot,
    setRootOverride,
    isReady,
    blockFaces,
    blockIcon, // P15 槽位缓存栏图标（物品纹理优先，面贴图回退）
    blockList,
    blockState,
    parseBlockstateJson,
    parseBlockstateVariants,
    readZipEntry,
    blockStateAny,
    blockAppearance,
    stripProps,
    texturePath,
    // P14 测试用
    _internals: {
        findAsset,
        matchVariant,
        modelRotMat,
        mat3Vec,
        recomputeUVs,
        defaultUv,
        elemRotate,
        FACE_CORNERS,
        loadModelChain,
        modelRel,
        resolveTextureId,
        _resetAppearanceCache,
    },
};
