#!/usr/bin/env node
/**
 * 由 tools/tech-blueprints/catalog.json 生成 tech-tree-pack 的蓝图/钻头物品与研发网。
 *
 * 数据来源：KubeJS 枪械组件表（GUN_PARTS + TACZ_GUN_COLORS）导出的 catalog.json。
 * catalog.json 是构建输入，不进包 jar；包内只保留生成物 techs/tree.json。
 * 关系规则：
 *   - 同口径枪机：半自动 → 自动；基础 → 精密 → 进阶
 *   - 枪管：短 → 长；通用 → 高性能；高口径由低口径高性能派生
 *   - 枪身：按系列血缘（AR / AK / 手枪 / 冲锋枪 / 霰弹枪 / 狙击 / 机枪 / 发射器）
 * 布局与成本按 tier（1..5）分档；研发网 = 口径枢纽 → 部件前置 → 部件。
 *
 * 用法：node tools/tech-blueprints/generate.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "../..");
const PACK = path.join(ROOT, "contentpacks-source/tech-tree-pack");
const NS = "tech_tree_pack";

const catalog = JSON.parse(fs.readFileSync(path.join(HERE, "catalog.json"), "utf8"));

const parts = [...catalog.bolts, ...catalog.barrels, ...catalog.frames].map((p) => ({
    ...p,
    kind: p.id.startsWith("barrel_") ? "barrel" : p.id.startsWith("frame_") ? "frame" : "bolt",
}));
const partById = new Map(parts.map((p) => [p.id, p]));
const gunName = new Map(catalog.guns.map((g) => [g.id, g.name]));

// 目录里个别部件的口径字段来自 TACZ 颜色表，与部件命名冲突时以命名为准。
const CALIBER_OVERRIDE = {
    sa_338: ["338"],                 // MK18 SA-ASR 在颜色表里被归到 5.56 组，但枪机/枪管都是 .338
    auto_762x39_precise: ["762x39"], // 未被任何枪引用
    auto_308_adv: ["308"],           // 未被任何枪引用
};

const BOLT_PARENT = {
    pistol_bolt_precise: "pistol_bolt",
    br_556x45: "auto_556x45",
    auto_556x45_precise: "auto_556x45",
    auto_556x45_adv: "auto_556x45_precise",
    auto_68x51_adv: "auto_556x45_adv",
    auto_762x39: "sa_762x39",
    auto_762x39_precise: "auto_762x39",
    auto_762x39_adv: "auto_762x39_precise",
    auto_9mm_precise: "auto_9mm",
    auto_9mm_adv: "auto_9mm_precise",
    auto_45_precise: "auto_45",
    auto_545x39_precise: "auto_545x39",
    auto_545x39_adv: "auto_545x39_precise",
    auto_308: "sa_308",
    auto_308_precise: "auto_308",
    auto_308_adv: "auto_308_precise",
    auto_58x42_adv: "auto_58x42_precise",
    auto_57x28_adv: "auto_57x28_precise",
    sa_12g: "pin_12g",
    auto_12g: "sa_12g",
    bolt_338: "bolt_30_06",
    sa_338: "bolt_338",
    bolt_50bmg: "bolt_338",
    sa_50bmg: "bolt_50bmg",
    pin_40mm: "pin_12g",
    rpg7_trigger: "pin_40mm",
    revolver_308: "sa_308",
};

const BARREL_PARENT = {
    barrel_9mm_long: "barrel_9mm_short",
    barrel_45_long: "barrel_45_short",
    barrel_12g_hp: "barrel_12g",
    barrel_556mm_hp: "barrel_556mm",
    barrel_762mm_hp: "barrel_762mm",
    barrel_545mm_hp: "barrel_545mm",
    barrel_58mm_hp: "barrel_58mm",
    barrel_57mm_long: "barrel_57mm_short",
    barrel_50_long: "barrel_50_short",
    barrel_357mag: "barrel_50_short",
    barrel_338_precise: "barrel_762mm_hp",
    barrel_68mm_hp: "barrel_556mm_hp",
    barrel_rpg7: "barrel_40mm",
};

const FRAME_PARENT = {
    frame_deagle: "frame_pistol",
    frame_deagle_golden: "frame_deagle",
    frame_uzi: "frame_pistol",
    frame_mp5: "frame_uzi",
    frame_mpx: "frame_mp5",
    frame_ump45: "frame_uzi",
    frame_vector: "frame_ump45",
    frame_ar57: "frame_p90",
    frame_m870: "frame_db",
    frame_m1014: "frame_m870",
    frame_spas12: "frame_m1014",
    frame_aa12: "frame_m1014",
    frame_m4a1: "frame_m16",
    frame_m4_sopmod2: "frame_m4a1",
    frame_hk416: "frame_m4a1",
    frame_mk18: "frame_m4a1",
    frame_spr15: "frame_m16",
    frame_m7: "frame_m4a1",
    frame_aug_a3: "frame_aug",
    frame_qbz191: "frame_qbz95",
    frame_ak103: "frame_akm",
    frame_rpk: "frame_akm",
    frame_type81: "frame_akm",
    frame_mk47: "frame_akm",
    frame_ak74m: "frame_ak74",
    frame_aks74u: "frame_ak74",
    frame_ak105: "frame_ak74m",
    frame_ak12: "frame_ak74m",
    frame_am17: "frame_ak105",
    frame_sa58: "frame_fnfal",
    frame_scar_h: "frame_scar_l",
    frame_sr25: "frame_m16",
    frame_m110: "frame_sr25",
    frame_g28: "frame_g3",
    frame_mk14: "frame_g3",
    frame_awp: "frame_m700",
    frame_m107: "frame_m95",
    frame_m60: "frame_m249",
    frame_m134: "frame_m60",
    frame_fnevolys: "frame_m249",
    frame_mgl: "frame_m320",
    frame_rpg7: "frame_mgl",
    frame_marlin1895: "frame_springfield1873",
};

const PARENT = { ...BOLT_PARENT, ...BARREL_PARENT, ...FRAME_PARENT };

const CALIBER_LABEL = {
    "556x45": "5.56×45", "762x39": "7.62×39", "9mm": "9mm", "45acp": ".45 ACP",
    "545x39": "5.45×39", "308": ".308", "338": ".338", "50bmg": ".50 BMG",
    "50ae": ".50 AE", "357mag": ".357 马格南", "12g": "12 号霰弹", "57x28": "5.7×28",
    "58x42": "5.8×42", "68x51fury": "6.8×51", "30_06": ".30-06", "45_70": ".45-70",
    "40mm": "40mm 榴弹", "rpg_rocket": "RPG 火箭",
};
const CALIBER_LAYOUT = [
    ["556x45", "762x39", "9mm", "45acp", "545x39", "308"],
    ["338", "50bmg", "50ae", "357mag", "12g", "57x28"],
    ["58x42", "68x51fury", "30_06", "45_70", "40mm", "rpg_rocket"],
];

const BOLT_LAYOUT = [
    ["auto_556x45", "br_556x45", "auto_556x45_precise", "auto_556x45_adv", "auto_68x51_adv", "auto_9mm", "auto_9mm_precise", "auto_9mm_adv"],
    ["sa_762x39", "auto_762x39", "auto_762x39_precise", "auto_762x39_adv", "auto_45", "auto_45_precise", "auto_58x42_precise", "auto_58x42_adv"],
    ["auto_545x39", "auto_545x39_precise", "auto_545x39_adv", "auto_57x28_precise", "auto_57x28_adv", "pin_12g", "sa_12g", "auto_12g"],
    ["sa_308", "auto_308", "auto_308_precise", "auto_308_adv", "revolver_308", "pistol_bolt", "pistol_bolt_precise"],
    ["pin_40mm", "rpg7_trigger", "bolt_30_06", "bolt_45_70", "bolt_338", "sa_338", "bolt_50bmg", "sa_50bmg"],
];

const BARREL_LAYOUT = [
    ["barrel_9mm_short", "barrel_9mm_long", "barrel_45_short", "barrel_45_long", "barrel_12g", "barrel_12g_hp"],
    ["barrel_556mm", "barrel_556mm_hp", "barrel_762mm", "barrel_762mm_hp", "barrel_545mm", "barrel_545mm_hp"],
    ["barrel_58mm", "barrel_58mm_hp", "barrel_57mm_short", "barrel_57mm_long", "barrel_50_short", "barrel_50_long"],
    ["barrel_338_precise", "barrel_30_06", "barrel_357mag", "barrel_40mm", "barrel_68mm_hp", "barrel_rpg7"],
];

const FRAMES_A_LAYOUT = [
    ["frame_pistol", "frame_deagle", "frame_deagle_golden"],
    ["frame_uzi", "frame_mp5", "frame_mpx", "frame_ump45", "frame_vector", "frame_p90", "frame_ar57"],
    ["frame_db", "frame_m870", "frame_m1014", "frame_spas12", "frame_aa12"],
    ["frame_m16", "frame_m4a1", "frame_m4_sopmod2", "frame_hk416", "frame_mk18", "frame_spr15", "frame_m7"],
    ["frame_scar_l", "frame_aug", "frame_aug_a3", "frame_g36", "frame_qbz95", "frame_qbz191"],
];

const FRAMES_B_LAYOUT = [
    ["frame_akm", "frame_ak103", "frame_rpk", "frame_type81", "frame_mk47", "frame_ak74", "frame_aks74u"],
    ["frame_ak74m", "frame_ak105", "frame_ak12", "frame_am17", "frame_sks"],
    ["frame_g3", "frame_mk14", "frame_fnfal", "frame_sa58", "frame_scar_h"],
    ["frame_sr25", "frame_m110", "frame_g28"],
    ["frame_m700", "frame_awp", "frame_m95", "frame_m107", "frame_springfield1873", "frame_marlin1895"],
    ["frame_m249", "frame_fnevolys", "frame_m60", "frame_m134", "frame_m320", "frame_mgl", "frame_rpg7"],
];

const PAGES = [
    { id: "foundation", title: "基础工业", icon: "create:andesite_alloy" },
    { id: "bolts", title: "枪机", icon: `${NS}:auto_556x45_blueprint` },
    { id: "barrels", title: "枪管", icon: `${NS}:barrel_556mm_drill` },
    { id: "frames_a", title: "枪身 I", icon: `${NS}:frame_m16_blueprint` },
    { id: "frames_b", title: "枪身 II", icon: `${NS}:frame_akm_blueprint` },
];

const TIER_COLOR = ["§a", "§b", "§d", "§6", "§c"];
const TIER_RARITY = ["common", "uncommon", "rare", "epic", "epic"];
const COST_MATERIAL = {
    bolt: "minecraft:iron_ingot",
    barrel: "create:copper_sheet",
    frame: "create:andesite_alloy",
};

const blueprintId = (partId) => `${partId}_blueprint`;
const drillId = (barrelId) => `${barrelId}_drill`;
const itemId = (local) => `${NS}:${local}`;

function calibersOf(part) {
    const override = CALIBER_OVERRIDE[part.id];
    const list = override || part.calibers || [];
    if (!list.length) {
        throw new Error(`无法确定部件 ${part.id} 的口径`);
    }
    for (const ammo of list) {
        if (!CALIBER_LABEL[ammo]) {
            throw new Error(`未知口径 ${ammo}（部件 ${part.id}）`);
        }
    }
    return list;
}

function ownerText(part) {
    const names = (part.guns || []).map((g) => gunName.get(g)).filter(Boolean);
    if (!names.length) {
        return "该规格暂无对应枪械，作为技术储备保留。";
    }
    return `被 ${names.length} 把枪使用：${names.slice(0, 6).join("、")}${names.length > 6 ? " 等" : ""}。`;
}

function costsFor(part) {
    const tier = part.tier;
    if (tier <= 2) {
        return [
            { type: "xp_levels", amount: tier },
            { type: "item", item: COST_MATERIAL[part.kind], amount: tier === 1 ? 4 : 6 },
        ];
    }
    if (tier <= 4) {
        return [
            { type: "tech_point", amount: tier - 2 },
            { type: "item", item: "create:brass_ingot", amount: 8 },
        ];
    }
    return [
        { type: "tech_point", amount: 3 },
        { type: "item", item: "minecraft:diamond", amount: 4 },
    ];
}

function tableRequirement(tier) {
    return { type: "table_tier", min: tier <= 2 ? 1 : tier === 3 ? 2 : 3 };
}

function caliberRequirement(ammos) {
    if (ammos.length === 1) {
        return { type: "tech", nodes: [`caliber_${ammos[0]}`] };
    }
    return {
        type: "any",
        requirements: ammos.map((ammo) => ({ type: "tech", nodes: [`caliber_${ammo}`] })),
    };
}

function partNode(part, page, col, row) {
    const ammos = calibersOf(part);
    const requirements = [caliberRequirement(ammos)];
    const parent = PARENT[part.id];
    if (parent) {
        if (!partById.has(parent)) throw new Error(`部件 ${part.id} 的前置 ${parent} 不存在`);
        requirements.push({ type: "tech", nodes: [parent] });
    }
    requirements.push(tableRequirement(part.tier));

    const rewards = [{ item: itemId(blueprintId(part.id)), count: 1 }];
    if (part.kind === "barrel") {
        rewards.push({ item: itemId(drillId(part.id)), count: 1 });
    }
    const icon = part.kind === "barrel" ? itemId(drillId(part.id)) : itemId(blueprintId(part.id));

    return {
        id: part.id,
        title: part.name,
        desc: `T${part.tier} · ${CALIBER_LABEL[ammos[0]]} · ${ownerText(part)}`,
        page,
        col,
        row,
        icon,
        requirements,
        costs: costsFor(part),
        rewards,
        quest: true,
    };
}

function layoutNodes(ids, page, layout) {
    const wanted = new Set(ids);
    const nodes = [];
    layout.forEach((rowIds, row) => {
        rowIds.forEach((id, col) => {
            if (!wanted.delete(id)) throw new Error(`布局重复或多余: ${id} (${page})`);
            const part = partById.get(id);
            if (!part) throw new Error(`布局引用了不存在的部件: ${id}`);
            nodes.push(partNode(part, page, col, row));
        });
    });
    if (wanted.size) throw new Error(`布局遗漏: ${[...wanted].join(",")} (${page})`);
    return nodes;
}

function hubIcon(ammo) {
    const candidates = parts
        .filter((p) => p.kind === "bolt" && calibersOf(p).includes(ammo))
        .sort((a, b) => a.tier - b.tier || a.id.localeCompare(b.id));
    if (candidates.length) return itemId(blueprintId(candidates[0].id));
    const any = parts.find((p) => calibersOf(p).includes(ammo));
    return any ? itemId(blueprintId(any.id)) : "create:andesite_alloy";
}

// ==================== 节点 ====================

const nodes = [];
nodes.push({
    id: "create_industry",
    title: "机械化工业",
    desc: "先建立机械动力产线：安山合金、动力辊压与序列组装。没有工业基础，枪械蓝图只是一张纸。",
    page: "foundation",
    col: 0,
    row: 1,
    icon: "create:andesite_alloy",
    requirements: [{ type: "item", item: "create:andesite_alloy", count: 1, consume: false }],
    costs: [{ type: "xp_levels", amount: 1 }],
    rewards: [],
    quest: true,
});
CALIBER_LAYOUT.forEach((rowIds, row) => {
    rowIds.forEach((ammo, i) => {
        nodes.push({
            id: `caliber_${ammo}`,
            title: `${CALIBER_LABEL[ammo]} 口径`,
            desc: `掌握 ${CALIBER_LABEL[ammo]} 的弹道与膛压数据。该口径的枪机 / 枪管 / 枪身都以此为前置。`,
            page: "foundation",
            col: i + 2,
            row,
            icon: hubIcon(ammo),
            requirements: [
                { type: "tech", nodes: ["create_industry"] },
                { type: "table_tier", min: 1 },
            ],
            costs: [
                { type: "xp_levels", amount: 1 },
                { type: "item", item: "minecraft:paper", amount: 2 },
            ],
            rewards: [],
            quest: true,
        });
    });
});
nodes.push(...layoutNodes(catalog.bolts.map((p) => p.id), "bolts", BOLT_LAYOUT));
nodes.push(...layoutNodes(catalog.barrels.map((p) => p.id), "barrels", BARREL_LAYOUT));
nodes.push(...layoutNodes(
    catalog.frames.filter((f) => FRAMES_A_LAYOUT.flat().includes(f.id)).map((f) => f.id),
    "frames_a", FRAMES_A_LAYOUT));
nodes.push(...layoutNodes(
    catalog.frames.filter((f) => FRAMES_B_LAYOUT.flat().includes(f.id)).map((f) => f.id),
    "frames_b", FRAMES_B_LAYOUT));

// 完整性：每个部件恰好一个节点，且所有前置/口径枢纽都存在
const nodeIds = new Set(nodes.map((n) => n.id));
if (nodeIds.size !== nodes.length) throw new Error("节点 id 重复");
for (const part of parts) {
    if (!nodeIds.has(part.id)) throw new Error(`部件缺少节点: ${part.id}`);
}
for (const node of nodes) {
    for (const req of node.requirements) {
        if (req.type === "tech") {
            for (const ref of req.nodes) {
                if (!nodeIds.has(ref)) throw new Error(`节点 ${node.id} 引用了不存在的节点 ${ref}`);
            }
        }
    }
}

// ==================== 研究项 ====================

const research = [
    {
        id: "study_foundation",
        title: "整理笔记",
        desc: "把零散观察整理成可复用的口径与工艺记录。",
        page: "foundation",
        icon: "minecraft:paper",
        requirements: [{ type: "tech", nodes: ["create_industry"] }],
        visibleWhen: [{ type: "tech", nodes: ["create_industry"] }],
        costs: [{ type: "item", item: "minecraft:paper", amount: 8 }],
        points: 4,
        cooldownTicks: 600,
    },
    {
        id: "study_bolts",
        title: "枪机复检",
        desc: "拆解并记录闭锁与击发结构，为自动枪机积累公差数据。",
        page: "bolts",
        icon: "minecraft:iron_ingot",
        requirements: [{ type: "tech", nodes: ["auto_9mm_precise"] }],
        visibleWhen: [{ type: "tech", nodes: ["auto_9mm_precise"] }],
        costs: [{ type: "item", item: "create:copper_sheet", amount: 8 }],
        points: 6,
        cooldownTicks: 1200,
    },
    {
        id: "study_barrels",
        title: "膛线测量",
        desc: "测量各族枪管的缠距与壁厚，建立高性能枪管的设计余量。",
        page: "barrels",
        icon: "create:copper_sheet",
        requirements: [{ type: "tech", nodes: ["auto_556x45_adv"] }],
        visibleWhen: [{ type: "tech", nodes: ["auto_556x45_adv"] }],
        costs: [{ type: "item", item: "minecraft:diamond", amount: 8 }],
        points: 24,
        cooldownTicks: 3000,
    },
    {
        id: "study_frames_a",
        title: "机匣测绘",
        desc: "测绘 M16 / 手枪 / 冲锋枪族机匣，建立可复用的枪身图纸。",
        page: "frames_a",
        icon: "create:andesite_alloy",
        requirements: [{ type: "tech", nodes: ["auto_556x45_precise"] }],
        visibleWhen: [{ type: "tech", nodes: ["auto_556x45_precise"] }],
        costs: [{ type: "item", item: "create:brass_ingot", amount: 8 }],
        points: 10,
        cooldownTicks: 1800,
    },
    {
        id: "study_frames_b",
        title: "精密机匣测绘",
        desc: "测绘 AK 与狙击 / 机枪族机匣，标定大口径枪身的受力结构。",
        page: "frames_b",
        icon: "create:brass_ingot",
        requirements: [{ type: "tech", nodes: ["auto_308_precise"] }],
        visibleWhen: [{ type: "tech", nodes: ["auto_308_precise"] }],
        costs: [{ type: "item", item: "minecraft:diamond", amount: 4 }],
        points: 16,
        cooldownTicks: 2400,
    },
];

// ==================== 输出 ====================

const tree = {
    title: "枪械工程 · 蓝图研究",
    points: { questReward: 1 },
    pages: PAGES,
    research,
    table: {
        maxTier: 3,
        upgrades: [
            {
                to: 2,
                costs: [
                    { type: "tech_point", amount: 2 },
                    { type: "item", item: "create:andesite_alloy", amount: 16 },
                ],
            },
            {
                to: 3,
                costs: [
                    { type: "tech_point", amount: 4 },
                    { type: "item", item: "create:precision_mechanism", amount: 2 },
                ],
            },
        ],
    },
    nodes,
};

const writeJson = (file, value) => {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(value, null, 2) + "\n", "utf8");
};

const itemsDir = path.join(PACK, "items");
const modelsDir = path.join(PACK, "assets", NS, "models/item");
for (const dir of [itemsDir, modelsDir]) {
    if (fs.existsSync(dir)) {
        for (const entry of fs.readdirSync(dir)) {
            if (/^(?:(?:blueprint|drill)_.*|.*_(?:blueprint|drill))\.json$/.test(entry)) {
                fs.unlinkSync(path.join(dir, entry));
            }
        }
    }
}

const zh = {};
const en = {};
const items = [];
const models = [];

function defineItem(local, tier, name, suffixZh, suffixEn, texture) {
    const id = itemId(local);
    zh[`item.${NS}.${local}`] = `${TIER_COLOR[tier - 1]}${name} ${suffixZh}`;
    en[`item.${NS}.${local}`] = `${suffixEn}: ${name}`;
    items.push({
        id: local,
        namespace: NS,
        lang: { en_us: en[`item.${NS}.${local}`], zh_cn: zh[`item.${NS}.${local}`] },
        stackSize: 1,
        rarity: TIER_RARITY[tier - 1],
        creative: true,
        fireResistant: false,
    });
    models.push({ local, texture: `${NS}:item/${texture}` });
}

for (const part of parts) {
    defineItem(blueprintId(part.id), part.tier, part.name, "蓝图", "Blueprint", `blueprint_t${part.tier}`);
    if (part.kind === "barrel") {
        defineItem(drillId(part.id), part.tier, part.name, "钻头", "Drill", `drill_t${part.tier}`);
    }
}

writeJson(path.join(PACK, "techs/tree.json"), tree);

for (const item of items) {
    writeJson(path.join(itemsDir, `${item.id}.json`), item);
}
for (const model of models) {
    writeJson(path.join(modelsDir, `${model.local}.json`), {
        parent: "minecraft:item/generated",
        textures: { layer0: model.texture },
    });
}

for (const [file, additions] of [
    [path.join(PACK, "assets", NS, "lang/zh_cn.json"), zh],
    [path.join(PACK, "assets", NS, "lang/en_us.json"), en],
]) {
    const existing = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : {};
    for (const key of Object.keys(existing)) {
        if (key.startsWith(`item.${NS}.`)
                && (/^(?:blueprint|drill)_/.test(key.slice(`item.${NS}.`.length))
                    || /_(?:blueprint|drill)$/.test(key))) {
            delete existing[key];
        }
    }
    for (const [key, value] of Object.entries(additions)) {
        existing[key] = value;
    }
    writeJson(file, existing);
}

console.log(`nodes=${nodes.length} parts=${parts.length} items=${items.length}`);
console.log(`pages=${PAGES.map((p) => `${p.id}:${nodes.filter((n) => n.page === p.id).length}`).join(" ")}`);
console.log(`hubs=${nodes.filter((n) => n.id.startsWith("caliber_")).length}`);
