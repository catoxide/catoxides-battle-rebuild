/**
 * mcBlocks.js - P10 方块列表实时读取（主进程）
 *
 * 目标："查方块时读取游戏准备加载的内容"，而非只依赖静态数据集：
 *   1. vanilla  —— 数据集 assets/minecraft/blockstates/（版本锁定 1.21.1 的静态底）
 *   2. data     —— run/data 下递归找名为 blockstate 的目录（1.21.1 方块定义格式）
 *   3. packs    —— run/saves/<世界>/datapacks/<包>/data 下递归找 blockstate 目录
 *   4. mods     —— run/mods/ 下各 jar 内 data/<ns>/blockstate/ 条目（zip 中央目录解析，零依赖）
 *
 * 合并去重后返回排序 ID 列表 + 来源统计。缓存 30s，支持 force 刷新。
 * 说明：mod 中以 Java 代码注册（非 data 文件）的方块对文件扫描不可见 ——
 *       此类方块仍可通过搜索框手动输入 ID（jigsaw/structure_void 为 vanilla data 方块，恒可见）。
 *
 * 纯 Node（require），无第三方依赖。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const McAssets = require('./mcAssets');

const CACHE_TTL = 30 * 1000;
const cache = { at: 0, data: null };

/** 递归收集目录下所有 data/<ns>/blockstate/*.json 的方块 ID（找名为 blockstate 的目录） */
function collectFromDir(root, ids, depth = 0) {
    if (depth > 8) return;
    let entries;
    try { entries = fs.readdirSync(root, { withFileTypes: true }); } catch { return; }
    for (const en of entries) {
        if (!en.isDirectory()) continue;
        const p = path.join(root, en.name);
        if (en.name === 'blockstate') {
            const ns = path.basename(path.dirname(p));
            let files;
            try { files = fs.readdirSync(p); } catch { continue; }
            for (const f of files) {
                if (!f.endsWith('.json')) continue;
                ids.add(ns + ':' + f.slice(0, -5));
            }
        } else if (!en.name.startsWith('.') && en.name !== 'node_modules') {
            collectFromDir(p, ids, depth + 1);
        }
    }
}

/**
 * 从 jar（zip）中央目录读取 data/<ns>/blockstate/*.json 的文件名 → 方块 ID。
 * 只解析中央目录条目名，不解压内容（零 zlib 依赖、快速）。
 */
function collectFromJar(jarPath, ids) {
    let buf;
    try { buf = fs.readFileSync(jarPath); } catch { return 0; }
    if (buf.length < 22) return 0;
    // EOCD 签名 0x06054b50，从尾部向前找（允许最多 64KB 的 zip 注释）
    let eocd = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65558); i--) {
        if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) return 0;
    const count = buf.readUInt16LE(eocd + 10);
    let off = buf.readUInt32LE(eocd + 16);
    let found = 0;
    for (let n = 0; n < count; n++) {
        if (off + 46 > buf.length || buf.readUInt32LE(off) !== 0x02014b50) break;
        const nlen = buf.readUInt16LE(off + 28);
        const elen = buf.readUInt16LE(off + 30);
        const clen = buf.readUInt16LE(off + 32);
        if (off + 46 + nlen > buf.length) break;
        const name = buf.toString('utf8', off + 46, off + 46 + nlen);
        if (name.startsWith('data/') && name.includes('/blockstate/') && name.endsWith('.json')) {
            const parts = name.split('/');
            if (parts.length >= 4) {
                ids.add(parts[1] + ':' + parts[parts.length - 1].slice(0, -5));
                found++;
            }
        }
        off += 46 + nlen + elen + clen;
    }
    return found;
}

/**
 * 扫描游戏将加载的方块列表
 * @param {string} projectRoot 项目根（含 run/ 游戏目录）
 * @param {{force?:boolean}} opts
 * @returns {{ids:string[], meta:{vanilla:number, data:number, mods:number, at:number}}}
 */
function scanBlocks(projectRoot, opts = {}) {
    if (!opts.force && cache.data && Date.now() - cache.at < CACHE_TTL) return cache.data;

    // 1) vanilla（数据集静态底）
    const vanilla = new Set();
    try {
        const root = McAssets.resolveRoot(projectRoot);
        if (root) for (const id of McAssets.blockList(root)) vanilla.add(id);
    } catch {}

    // 2) 游戏数据目录 + 各世界的数据包
    const data = new Set();
    if (projectRoot) {
        collectFromDir(path.join(projectRoot, 'run', 'data'), data);
        const savesDir = path.join(projectRoot, 'run', 'saves');
        let worlds;
        try { worlds = fs.readdirSync(savesDir, { withFileTypes: true }); } catch { worlds = []; }
        for (const w of worlds) {
            if (!w.isDirectory()) continue;
            collectFromDir(path.join(savesDir, w.name, 'datapacks'), data);
        }
    }

    // 3) mods（jar 内 data 文件）
    let modFound = 0;
    if (projectRoot) {
        const modsDir = path.join(projectRoot, 'run', 'mods');
        let files;
        try { files = fs.readdirSync(modsDir); } catch { files = []; }
        for (const f of files) {
            if (!f.toLowerCase().endsWith('.jar')) continue;
            modFound += collectFromJar(path.join(modsDir, f), data);
        }
    }

    const ids = [...new Set([...vanilla, ...data])].sort();
    const out = {
        ids,
        meta: { vanilla: vanilla.size, data: data.size, mods: modFound, at: Date.now() },
    };
    cache.data = out;
    cache.at = Date.now();
    return out;
}

/** 测试用：清缓存 */
function _resetCache() { cache.data = null; cache.at = 0; }

module.exports = { scanBlocks, collectFromDir, collectFromJar, _resetCache };
