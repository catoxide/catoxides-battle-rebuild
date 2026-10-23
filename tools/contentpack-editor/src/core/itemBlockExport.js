// itemBlockExport.js - 物品/方块 → 原版文件导出（数据/资源范畴的「原版导出」）
//
// 与 structure 的 vanillaExport.js 同构：把编辑器源定义**机械映射**为原版 1.21.1 文件，
// 写进包目录（contentpack jar 的 assets/+data/ 由主 mod 挂载进游戏资源树）。
//
// 产物（1.21.1 路径，对照原版 jar 验证）：
//   物品  assets/<ns>/models/item/<id>.json            物品模型
//   方块  assets/<ns>/models/block/<file>.json         方块模型
//         assets/<ns>/blockstate/<id>.json             方块形态（完整状态组合 → 模型，含自定义参数）
//         assets/<ns>/models/item/<id>.json            方块物品模型（parent 指方块模型）
//   语言  assets/<ns>/lang/en_us.json / zh_cn.json     合并写（item.<ns>.<id> / block.<ns>.<id>）
//   掉落  data/<ns>/loot_table/blocks/<id>.json        挖掉掉自己（loot.selfDrop，1.21.1 形状）
//   标签  data/<ns|minecraft>/tags/block/<tag>.json    原版数据标签（如 minecraft:mineable/pickaxe）
//   创造栏 语言 key itemGroup.<ns>.pack_<packid>       （注册在 Java 侧，这里只补语言）
//
// 注意：注册本身（物品/方块/方块实体进注册表）是 Java 侧的事 ——
// 入口类调 context.registerItemsAndBlocksFromJar()（见 packScaffold.js 生成的骨架）。
// 本模块导出的文件是游戏运行这些已注册内容所需的原版数据。

'use strict';
const fs = require('fs');
const path = require('path');
const S = require('./itemBlockSchema');

/** 写 JSON 文件（2 空格缩进，UTF-8，自动建目录）；返回相对包目录路径 */
function writeJson(packDir, relPath, obj) {
    const abs = path.join(packDir, relPath);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, JSON.stringify(obj, null, 2) + '\n', 'utf-8');
    return relPath;
}

/** 合并写 lang 文件（保留已有键，只更新给定键）；返回 {file, added, updated} */
function mergeLangFile(packDir, ns, locale, entries) {
    const rel = `assets/${ns}/lang/${locale}.json`;
    const abs = path.join(packDir, rel);
    let existing = {};
    if (fs.existsSync(abs)) {
        try { existing = JSON.parse(fs.readFileSync(abs, 'utf-8')); } catch { existing = {}; }
    }
    let added = 0, updated = 0;
    for (const [k, v] of Object.entries(entries)) {
        if (v === undefined || v === null || String(v) === '') continue;
        if (existing[k] === undefined) added++; else updated++;
        existing[k] = String(v);
    }
    if (added + updated > 0) {
        // 按键排序输出（稳定 diff）
        const sorted = {};
        for (const k of Object.keys(existing).sort()) sorted[k] = existing[k];
        writeJson(packDir, rel, sorted);
    }
    return { file: rel, added, updated };
}

/** 定义 → lang 条目（item.<ns>.<id> / block.<ns>.<id>） */
function langEntries(def, kind) {
    const lang = (def.lang && typeof def.lang === 'object') ? def.lang : {};
    const key = kind === 'item' ? S.itemLangKey(def) : S.blockLangKey(def);
    const out = {};
    if (lang.en_us) out['en_us'] = { [key]: lang.en_us };
    if (lang.zh_cn) out['zh_cn'] = { [key]: lang.zh_cn };
    return out;
}

/** 掉落表：1.21.1 形状（type=pools+rolls+entries；对照 vanilla data/minecraft/loot_table/blocks/stone.json） */
function selfDropLootTable(def) {
    const id = (def.namespace || '') + ':' + def.id;
    return {
        type: 'minecraft:block',
        pools: [
            {
                rolls: 1.0,
                bonus_rolls: 0.0,
                entries: [
                    { type: 'minecraft:item', name: id },
                ],
            },
        ],
    };
}

/**
 * 标签文件路径：tag "minecraft:mineable/pickaxe" → data/minecraft/tags/block/mineable/pickaxe.json
 * （datapack 允许在 minecraft 命名空间下追加标签条目）
 */
function tagFilePath(tag) {
    const m = String(tag).match(/^([a-z0-9_.\-]+):([a-z0-9_.\-/]+)$/);
    if (!m) return null;
    const ns = m[1];
    const parts = m[2].split('/');
    const file = parts.pop();
    return `data/${ns}/tags/block/${[...parts, file + '.json'].join('/')}`;
}

/**
 * 导出一个物品（模型 + lang）
 * @returns {{ok:boolean, files:string[], error?:string}}
 */
function exportItem(packDir, def) {
    try {
        const ns = def.namespace || '';
        const files = [];
        files.push(writeJson(packDir, `assets/${ns}/models/item/${def.id}.json`,
            S.buildItemModelJson(def, 'item')));
        for (const [locale, entries] of Object.entries(langEntries(def, 'item'))) {
            const r = mergeLangFile(packDir, ns, locale, entries);
            if (r.added + r.updated > 0) files.push(r.file);
        }
        return { ok: true, files: Array.from(new Set(files)) };
    } catch (e) {
        return { ok: false, files: [], error: String(e.message || e) };
    }
}

/**
 * 导出一个方块（方块模型 + blockstate + 方块物品模型 + lang + 掉落 + 标签）
 * @param {Object[]} [allBlocks] 同包全部方块定义（标签文件按同包合并写，避免互相覆盖）
 * @returns {{ok:boolean, files:string[], error?:string}}
 */
function exportBlock(packDir, def, allBlocks) {
    try {
        const ns = def.namespace || '';
        const files = [];
        const modelFile = (def.model && def.model.file) ? def.model.file : def.id;

        // 方块模型
        files.push(writeJson(packDir, `assets/${ns}/models/block/${modelFile}.json`,
            S.buildBlockModelJson(def)));

        // blockstate 引用的本包附加模型（variants/multipart 行里指向其它模型文件时，
        // 用同纹理 cube_all 兜底生成；完整 RL（其它命名空间）引用跳过）
        {
            const mainTex = (def.model && def.model.texture) ? def.model.texture : `block/${modelFile}`;
            const refs = [];
            if (def.blockstates && Array.isArray(def.blockstates.variants)) {
                for (const r of def.blockstates.variants) if (r && r.model) refs.push({ model: r.model, texture: r.texture });
            }
            if (def.blockstates && Array.isArray(def.blockstates.multipart)) {
                for (const r of def.blockstates.multipart) if (r && r.model) refs.push({ model: r.model, texture: r.texture });
            }
            for (const { model: raw, texture } of refs) {
                const ref = S.normalizeModelRef(raw, ns, 'block');
                if (ref.includes(':') && !ref.startsWith(ns + ':')) continue;
                const rel = ref.startsWith(ns + ':') ? ref.slice(ns.length + 1) : ref;
                if (!/^block\//.test(rel)) continue;
                const extraFile = rel.slice('block/'.length);
                if (!extraFile || extraFile === modelFile) continue;
                // 行级纹理覆盖（红石灯 lit/unlit 这类同形状不同纹理的附加模型）；空 = 跟随主纹理
                const tex = (texture && String(texture).trim() !== '')
                    ? S.normalizeModelRef(String(texture).trim(), ns, 'block')
                    : S.normalizeModelRef(mainTex, ns, 'block');
                files.push(writeJson(packDir, `assets/${ns}/models/block/${extraFile}.json`, {
                    parent: 'minecraft:block/cube_all',
                    textures: { all: tex },
                }));
            }
        }

        // blockstate（完整方块形态：状态组合 → 模型，含自定义参数）
        files.push(writeJson(packDir, `assets/${ns}/blockstate/${def.id}.json`,
            S.buildBlockstatesJson(def)));

        // 方块物品模型
        if (def.blockItem !== false) {
            files.push(writeJson(packDir, `assets/${ns}/models/item/${def.id}.json`,
                S.buildItemModelJson(def, 'block')));
        }

        // lang
        for (const [locale, entries] of Object.entries(langEntries(def, 'block'))) {
            const r = mergeLangFile(packDir, ns, locale, entries);
            if (r.added + r.updated > 0) files.push(r.file);
        }

        // 掉落（selfDrop）
        if (!def.loot || def.loot.selfDrop !== false) {
            files.push(writeJson(packDir, `data/${ns}/loot_table/blocks/${def.id}.json`,
                selfDropLootTable(def)));
        }

        // 标签（与同包其它方块合并写同一文件）
        files.push(...exportTagsForBlock(packDir, allBlocks || [def]));

        return { ok: true, files: Array.from(new Set(files)) };
    } catch (e) {
        return { ok: false, files: [], error: String(e.message || e) };
    }
}

/**
 * 标签导出：收集 blocks 里用到的每个标签的全部值，按标签写文件（同包合并，
 * 单块导出不会覆盖同包其它块的条目）。
 * @param {string[]} blocks 全部方块定义（不只是当前这块）
 */
function exportTagsForBlock(packDir, blocks) {
    const files = [];
    const byTag = new Map();
    for (const def of blocks || []) {
        for (const tag of def.tags || []) {
            const file = tagFilePath(tag);
            if (!file) continue;
            if (!byTag.has(file)) byTag.set(file, new Set());
            byTag.get(file).add((def.namespace || '') + ':' + def.id);
        }
    }
    for (const [file, values] of byTag.entries()) {
        files.push(writeJson(packDir, file, { values: Array.from(values).sort() }));
    }
    return files;
}

/**
 * 整包导出（物品/方块全部 + 语言 + 标签合并）—— 对应工具条「导出原版（全部）」。
 * @param {{items?:Object[], blocks?:Object[], packId?:string}} payload
 * @returns {{ok:boolean, report:object, error?:string}}
 */
function exportAll(packDir, payload = {}) {
    try {
        const items = payload.items || [];
        const blocks = payload.blocks || [];
        const report = { items: [], blocks: [], files: [], problems: [] };

        for (const def of items) {
            const r = exportItem(packDir, def);
            if (!r.ok) report.problems.push(`物品 ${def.id}: ${r.error}`);
            else { report.items.push(def.id); report.files.push(...r.files); }
        }
        for (const def of blocks) {
            const r = exportBlock(packDir, def, blocks);
            if (!r.ok) report.problems.push(`方块 ${def.id}: ${r.error}`);
            else { report.blocks.push(def.id); report.files.push(...r.files); }
        }

        // 创造栏语言（itemGroup.<ns>.pack_<packid>）
        if (payload.packId && (items.length || blocks.length)) {
            const ns = (blocks.length ? blocks[0].namespace : items[0].namespace) || '';
            const tabId = S.creativeTabId({ namespace: ns }, payload.packId);
            const key = S.creativeTabLangKey(tabId);
            const tabName = payload.packDisplayName || payload.packId;
            for (const locale of ['en_us', 'zh_cn']) {
                const r = mergeLangFile(packDir, ns, locale, { [key]: tabName });
                if (r.added + r.updated > 0) report.files.push(r.file);
            }
        }

        report.files = Array.from(new Set(report.files));
        return { ok: report.problems.length === 0, report };
    } catch (e) {
        return { ok: false, report: { items: [], blocks: [], files: [], problems: [String(e.message || e)] } };
    }
}

module.exports = {
    writeJson,
    mergeLangFile,
    selfDropLootTable,
    tagFilePath,
    exportItem,
    exportBlock,
    exportTagsForBlock,
    exportAll,
};
