// vanillaExport.js - blueprint.json → 原版 jigsaw 数据文件（纯机械映射）
//
// 产物（1.21.1 路径；对照 1.21.1 原版 jar 与解包源码 StructureTemplateManager 验证）：
//   pieces[i]      → data/<ns>/structure/<piece>.nbt
//                    【1.21.1 模板位于 data/ 的 structure（单数！）目录】
//                    （1.20.x 旧位置 assets/<ns>/structures/（复数）在 1.21.1 不会被加载）
//                    【1.21.1 模板格式】（对照解包源码 StructureTemplate.save/load）：
//                      size: [w,h,d]（IntList）
//                      palette: [{Name: str, properties?: str}]（properties 为 "k=v,k2=v2" 字符串）
//                      blocks: [{pos: [x,y,z], state: Int, nbt?: Compound}]
//                        - 普通方块：仅 pos + state
//                        - jigsaw：state 指向 minecraft:jigsaw[orientation=...]，
//                          nbt = jigsaw 方块实体数据 {name,target,pool,joint,final_state,
//                               placement_priority,selection_priority}
//                      entities: []
//                      DataVersion: 3465
//                    注意：1.20.x 旧格式（blocks=[state,x,y,z] + nbtData 列表）在 1.21.1 无法读取。
//   templatePools[i] → data/<ns>/worldgen/template_pool/<name>.json
//                    （elements: [{weight, element:{element_type:"minecraft:single_pool_element", location, projection, processors}}]，fallback: 空池元素）
//   structure      → data/<ns>/worldgen/structure/<structureId>.json
//   structureSet   → data/<ns>/worldgen/structure_set/<structureId>_set.json
//
// 游戏侧：主 mod 资源挂载器把内容包 jar 的 assets/+data/ 挂进资源树 →
// 原版 jigsaw 引擎直接生成，零自定义 worldgen 代码。

'use strict';
const NBT = require('./nbt');
const S = require('./structureSchema');

/** 解析 "minecraft:stone_bricks[minecraft:axis=y]" → { name, properties } */
function parseBlockId(blockStr) {
    const s = String(blockStr || '').trim();
    const m = s.match(/^([a-z0-9_.\-:]+?)(?:\[([^\]]*)\])?$/);
    if (!m) throw new Error('非法方块 ID: ' + blockStr);
    const properties = {};
    if (m[2]) {
        for (const kv of m[2].split(',')) {
            if (!kv.trim()) continue;
            const idx = kv.indexOf('=');
            if (idx === -1) throw new Error('非法方块属性: ' + kv);
            properties[kv.slice(0, idx).trim()] = kv.slice(idx + 1).trim();
        }
    }
    return { name: m[1], properties };
}

/** 展开 boxFills + fillBlocks → 位置 → 方块字符串 映射 */
function expandFills(piece) {
    const map = new Map(); // "x,y,z" → blockStr
    for (const bf of piece.boxFills || []) {
        for (let x = bf.from[0]; x <= bf.to[0]; x++)
            for (let y = bf.from[1]; y <= bf.to[1]; y++)
                for (let z = bf.from[2]; z <= bf.to[2]; z++)
                    map.set(x + ',' + y + ',' + z, bf.block);
    }
    for (const fb of piece.fillBlocks || []) {
        map.set(fb.x + ',' + fb.y + ',' + fb.z, fb.block);
    }
    return map;
}

/**
 * 构建单个片段的模板 NBT（gzip Buffer）。
 * @param {Object} piece { name, size:[w,h,d], boxFills, fillBlocks, jigsaws }
 * @param {string} namespace
 * @returns {Buffer}
 */
function buildTemplateNbt(piece, namespace) {
    const size = piece.size;
    const fills = expandFills(piece);
    const jigsaws = piece.jigsaws || [];

    // ---- palette：0 = air，然后填充方块（去重），然后 jigsaw（按 orientation 去重）
    // 1.21.1 格式：{Name: str, properties?: str}（properties 为 "k=v,..." 字符串，见 NbtUtils.writeBlockState）
    const palette = []; // [{name, props:{}}]
    const paletteIndex = new Map();
    function addPalette(name, properties) {
        const key = name + JSON.stringify(properties || {});
        if (paletteIndex.has(key)) return paletteIndex.get(key);
        paletteIndex.set(key, palette.length);
        palette.push({ name, props: properties || {} });
        return palette.length - 1;
    }
    addPalette('minecraft:air', {});

    // ---- blocks：[{pos:[x,y,z], state, nbt?}]
    const blocks = [];
    for (const [posStr, blockStr] of fills) {
        // 占位符 #*（3D 编辑器的空气占位符号）→ 导出为 air（跳过）
        if (String(blockStr).startsWith('#')) continue;
        const [x, y, z] = posStr.split(',').map(Number);
        const { name, properties } = parseBlockId(blockStr);
        const idx = addPalette(name, properties);
        blocks.push({ pos: [x, y, z], state: idx, nbt: null });
    }
    // jigsaw：palette minecraft:jigsaw[orientation=...]，实体数据放 block.nbt
    // （1.21.1 JigsawBlockEntity.saveAdditional 字段：name/target/pool/joint/final_state/
    //   placement_priority/selection_priority —— 直接位于 nbt 下，无 "jigsaw" 包装层）
    for (const j of jigsaws) {
        const idx = addPalette('minecraft:jigsaw', { orientation: j.orientation });
        blocks.push({
            pos: [j.x, j.y, j.z],
            state: idx,
            nbt: {
                name: NBT.str(j.name || ''),
                target: NBT.str(j.target || ''),
                pool: NBT.str(S.resolveId(j.pool, namespace)),
                joint: NBT.str(j.joint || 'rollable'),
                final_state: NBT.str(j.finalState || 'minecraft:air'),
                placement_priority: NBT.int(j.placementPriority != null ? j.placementPriority : 10),
                selection_priority: NBT.int(j.selectionPriority != null ? j.selectionPriority : 10),
            },
        });
    }

    // palette NBT
    const paletteTags = palette.map((e) => {
        const c = { Name: NBT.str(e.name) };
        const propStr = Object.entries(e.props).map(([k, v]) => k + '=' + v).join(',');
        if (propStr) c.properties = NBT.str(propStr);
        return { $t: NBT.T.COMPOUND, v: c };
    });

    const blocksTags = NBT.list(NBT.T.COMPOUND, blocks.map((b) => {
        const c = {
            pos: NBT.list(NBT.T.INT, [NBT.int(b.pos[0]), NBT.int(b.pos[1]), NBT.int(b.pos[2])]),
            state: NBT.int(b.state),
        };
        if (b.nbt) c.nbt = { $t: NBT.T.COMPOUND, v: b.nbt };
        return { $t: NBT.T.COMPOUND, v: c };
    }));

    const root = {
        DataVersion: NBT.int(S.DATA_VERSION_1_21_1),
        palette: NBT.list(NBT.T.COMPOUND, paletteTags),
        size: NBT.list(NBT.T.INT, [NBT.int(size[0]), NBT.int(size[1]), NBT.int(size[2])]),
        blocks: blocksTags,
        entities: NBT.list(NBT.T.COMPOUND, []),
    };
    return NBT.gzip(root);
}

/** 生成全部原版文件（纯函数，不碰磁盘） */
function exportBlueprint(bp) {
    const errors = [];
    const v = S.validateBlueprint(bp);
    if (!v.valid) return { files: [], errors: v.errors };

    const ns = bp.namespace || 'catoxidesbattlerebuild';
    const sid = bp.structureId;
    const st = bp.structure || {};
    const ss = bp.structureSet || {};
    const files = [];

    // 1. 结构模板 NBT（1.21.1：data/<ns>/structure/，单数，见文件头注释）
    for (const piece of bp.pieces) {
        try {
            files.push({
                relPath: `data/${ns}/structure/${piece.name}.nbt`,
                content: buildTemplateNbt(piece, ns),
                kind: 'template',
            });
        } catch (e) {
            errors.push(`模板 ${piece.name}: ${e.message}`);
        }
    }

    // 2. 模板池
    for (const pool of bp.templatePools || []) {
        const elements = (pool.elements || []).map((el) => ({
            weight: el.weight,
            element: {
                element_type: 'minecraft:single_pool_element',
                location: S.resolveId(el.template, ns),
                projection: el.projection,
                processors: [],
            },
        }));
        files.push({
            relPath: `data/${ns}/worldgen/template_pool/${pool.name}.json`,
            content: JSON.stringify({
                elements,
                fallback: pool.fallback || S.EMPTY_POOL_ELEMENT,
            }, null, 2),
            kind: 'template_pool',
        });
    }

    // 3. 结构定义
    files.push({
        relPath: `data/${ns}/worldgen/structure/${sid}.json`,
        content: JSON.stringify({
            type: 'minecraft:jigsaw',
            biomes: st.biomes || ['#minecraft:is_overworld'],
            spawn_overrides: { bonus_chests: 0, spawns: [] },
            step: st.step || 'surface_structures',
            terrain_adaptation: st.terrainAdaptation || 'beard_thin',
            start_pool: S.resolveId(st.startPool, ns),
            max_distance_from_center: st.maxDistanceFromCenter != null ? st.maxDistanceFromCenter : 80,
            project_to_surface: st.projectToSurface !== false,
            max_surface_level: st.maxSurfaceLevel || 0,
        }, null, 2),
        kind: 'structure',
    });

    // 4. 结构集合
    const placement = {
        type: 'minecraft:random_spread',
        spacing: ss.spacing,
        separation: ss.separation,
        salt: ss.salt,
    };
    if (ss.frequency !== undefined) placement.frequency = ss.frequency;
    if (ss.exclusionZone && ss.exclusionZone.otherSet) {
        placement.exclusion_zone = {
            chunk_count: ss.exclusionZone.chunkCount,
            other_set: S.resolveId(ss.exclusionZone.otherSet, ns),
        };
    }
    files.push({
        relPath: `data/${ns}/worldgen/structure_set/${sid}_set.json`,
        content: JSON.stringify({
            structures: [{ structure: `${ns}:${sid}`, weight: 1 }],
            placement,
        }, null, 2),
        kind: 'structure_set',
    });

    return { files, errors };
}

/**
 * 把导出文件写入内容包源码目录（contentpacks-source/<包>/）
 * @param {string} packDir
 * @param {Object} bp
 * @returns {{ok:boolean, files?:string[], errors?:string[]}}
 */
function exportToPack(packDir, bp) {
    const fs = require('fs');
    const path = require('path');
    const { files, errors } = exportBlueprint(bp);
    if (errors.length) return { ok: false, errors };
    const written = [];
    for (const f of files) {
        const p = path.join(packDir, f.relPath);
        fs.mkdirSync(path.dirname(p), { recursive: true });
        fs.writeFileSync(p, f.content);
        written.push(f.relPath);
    }
    return { ok: true, files: written };
}

module.exports = {
    parseBlockId,
    expandFills,
    buildTemplateNbt,
    exportBlueprint,
    exportToPack,
};
