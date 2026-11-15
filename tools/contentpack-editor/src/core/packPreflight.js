// packPreflight.js - 打包前预检（需要 fs，Node 侧运行）
//
// 目的：把「构建成功 ≠ 能加载」之间的坑提前暴露在编辑器里，
// 而不是等游戏启动后在日志里翻。规则来自 packScaffold.validateManifest /
// validateEntities（纯函数，单测覆盖）+ 目录/产物层面的一致性检查。
const fs = require('fs');
const path = require('path');

const ManifestLib = require('./manifest');
const Scaffold = require('./packScaffold');
const JarReader = require('./jarReader');
const ItemBlockSchema = require('./itemBlockSchema');
const QuestSchema = require('./questSchema');
const TechSchema = require('./techSchema');
const McAssets = require('./mcAssets');

/**
 * 预检一个包目录。
 *
 * @param {string} packDir contentpacks-source/<包>
 * @param {{root?:string}} [opts] root = 项目根（用于扫描 contentpacks/ 产物与运行时目录）
 * @returns {{ok:boolean, errors:Array, warnings:Array, findings:Array, manifest:Object, entities:Array,
 *            expectedJar:string, gradleTask:string}}
 */
function preflightPack(packDir, opts = {}) {
    const findings = [];
    let manifest = {};
    const entities = [];

    // --- 1. MANIFEST ---
    try {
        manifest = ManifestLib.readManifest(packDir);
    } catch (e) {
        findings.push({ level: 'error', code: 'E-NO-MANIFEST', msg: `读不到 MANIFEST.MF：${e.message}`, src: 'ContentPackManifest.fromJar' });
    }
    findings.push(...Scaffold.validateManifest(manifest));

    // --- 2. 实体 JSON ---
    const entitiesDir = path.join(packDir, 'entities');
    if (fs.existsSync(entitiesDir)) {
        for (const file of fs.readdirSync(entitiesDir).filter((f) => f.endsWith('.json'))) {
            const full = path.join(entitiesDir, file);
            try {
                const obj = JSON.parse(fs.readFileSync(full, 'utf-8'));
                entities.push(obj);
                // 文件名与 id 必须一致：加载器/骨架按 id 找 entities/<id>.json
                if (obj && obj.id && `${obj.id}.json` !== file) {
                    findings.push({
                        level: 'warn', code: 'W-ENT-FILENAME',
                        msg: `entities/${file} 的 id 是 "${obj.id}"：文件名与 id 不一致，骨架代码按 entities/${obj.id}.json 读取会找不到。`,
                        src: 'TestMobPack.java:40',
                    });
                }
            } catch (e) {
                findings.push({ level: 'error', code: 'E-ENT-JSON', msg: `entities/${file} 不是合法 JSON：${e.message}`, src: 'MobDefinitionParser.parse' });
            }
        }
    }
    findings.push(...Scaffold.validateEntities(entities));

    // --- 2b. 物品/方块 JSON（items/*.json + blocks/*.json） ---
    const items = [];
    const blocks = [];
    for (const [dirName, kind, arr] of [['items', 'item', items], ['blocks', 'block', blocks]]) {
        const dir = path.join(packDir, dirName);
        if (!fs.existsSync(dir)) continue;
        for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.json'))) {
            const full = path.join(dir, file);
            try {
                const obj = JSON.parse(fs.readFileSync(full, 'utf-8'));
                arr.push(obj);
                if (obj && obj.id && `${obj.id}.json` !== file) {
                    findings.push({
                        level: 'warn', code: 'W-IB-FILENAME',
                        msg: `${dirName}/${file} 的 id 是 "${obj.id}"：文件名与 id 不一致，批量注册器按 id 读取会找不到。`,
                        src: 'BatchContentRegistrar（按 id 扫描 jar）',
                    });
                }
                // 与基座注册器同一套校验（单一真源：itemBlockSchema ↔ BatchContentRegistrar）
                const check = kind === 'item' ? ItemBlockSchema.validateItem(obj) : ItemBlockSchema.validateBlock(obj);
                for (const err of check.errors) {
                    findings.push({ level: 'error', code: 'E-IB-VALID', msg: `${dirName}/${file}: ${err}`, src: 'BatchContentRegistrar.validate' });
                }
            } catch (e) {
                findings.push({ level: 'error', code: 'E-IB-JSON', msg: `${dirName}/${file} 不是合法 JSON：${e.message}`, src: 'ItemBlockParser.parse' });
            }
        }
    }
    // 注册成功 ≠ 能显示：缺 blockstate/模型/纹理时客户端只打日志、注册器 problems 仍为 0（紫黑块）
    findings.push(...checkItemBlockAssets(packDir, items, blocks, opts));

    // --- 2c. 任务 JSON（quests/*.json，仅一级目录） ---
    // 权威：ContentPackContext.registerQuestsFromJar（只扫 quests/ 第一层）
    //       + questSchema.validateQuest（镜子 QuestDefinitionParser 的严格性）
    const quests = [];
    const questsDir = path.join(packDir, 'quests');
    if (fs.existsSync(questsDir)) {
        const seenQuestIds = new Map();
        for (const ent of fs.readdirSync(questsDir, { withFileTypes: true })) {
            if (ent.isDirectory()) {
                // 子目录里的 JSON 永远不会被扫描（name.indexOf('/', 'quests/'.length) != -1）
                const nested = countJsonDeep(path.join(questsDir, ent.name));
                if (nested > 0) {
                    findings.push({
                        level: 'error', code: 'E-Q-NESTED',
                        msg: `quests/${ent.name}/ 下有 ${nested} 个 JSON：注册器只扫 quests/ 的【第一层】，` +
                            `子目录里的任务永远不会被加载（静默死数据）。把它们移到 quests/ 根目录。`,
                        src: 'ContentPackContext.registerQuestsFromJar（一级目录）',
                    });
                }
                continue;
            }
            if (!ent.name.endsWith('.json')) continue;
            const full = path.join(questsDir, ent.name);
            let def = null;
            try {
                def = JSON.parse(fs.readFileSync(full, 'utf-8'));
            } catch (e) {
                findings.push({
                    level: 'error', code: 'E-Q-JSON',
                    msg: `quests/${ent.name} 不是合法 JSON：${e.message}（该文件解析失败会被隔离，任务不会注册）`,
                    src: 'QuestDefinitionParser.parse',
                });
                continue;
            }
            quests.push(def);
            // 文件名不必等于 id（注册器遍历 jar、不按 id 找文件），但 id 必须包内唯一：
            // QuestSystem.registerDefinition 用 id 作键，后者覆盖前者 + 只打印一条 warn
            const id = def && def.id !== undefined && def.id !== null ? String(def.id).trim() : '';
            if (id) {
                if (seenQuestIds.has(id)) {
                    findings.push({
                        level: 'error', code: 'E-Q-ID-DUP',
                        msg: `quests/${ent.name} 的 id "${id}" 与 quests/${seenQuestIds.get(id)} 重复：` +
                            `QuestSystem 以 id 为键，后注册的会覆盖先注册的（只打印一条警告）。`,
                        src: 'QuestSystem.registerDefinition',
                    });
                } else {
                    seenQuestIds.set(id, ent.name);
                }
            }
            const check = QuestSchema.validateQuest(def);
            for (const err of check.errors) {
                findings.push({ level: 'error', code: 'E-Q-VALID', msg: `quests/${ent.name}: ${err}`, src: 'QuestDefinitionParser / questSchema' });
            }
            for (const warn of check.warnings) {
                findings.push({ level: 'warn', code: 'W-Q-VALID', msg: `quests/${ent.name}: ${warn}`, src: 'questSchema（包 Java 工厂自定字段）' });
            }
        }
    }

    // --- 2d. 科技树 JSON（techs/tree.json，单文件 = 整棵树） ---
    // 权威：TechTreeLoader.load 的严格解析 + techSchema 镜像（id/成环/成本/台子升级/布局）
    const techTreeFile = path.join(packDir, 'techs', 'tree.json');
    if (fs.existsSync(techTreeFile)) {
        let tree = null;
        try {
            tree = JSON.parse(fs.readFileSync(techTreeFile, 'utf-8'));
        } catch (e) {
            findings.push({
                level: 'error', code: 'E-TECH-JSON',
                msg: `techs/tree.json 不是合法 JSON：${e.message}（整棵树不会加载）`,
                src: 'TechTreeLoader.load',
            });
        }
        if (tree) {
            // 与编辑器 techRefContext 同口径：物品命名空间以 JSON 的 namespace 为准，缺省 = 核心命名空间
            const knownItems = new Set();
            const packNamespaces = new Set();
            for (const item of items) {
                if (!item || !item.id) continue;
                const declared = item.namespace != null && String(item.namespace).trim() !== '';
                const ns = declared ? String(item.namespace).trim() : 'catoxidesbattlerebuild';
                knownItems.add(ns + ':' + item.id);
                if (declared && ns !== 'catoxidesbattlerebuild') packNamespaces.add(ns);
            }
            const knownQuests = new Set(quests
                .filter((q) => q && q.id)
                .map((q) => 'catoxidesbattlerebuild:' + String(q.id).trim()));
            const check = TechSchema.validateTree(tree, { knownItems, knownQuests, packNamespaces });
            for (const err of check.errors) {
                findings.push({ level: 'error', code: 'E-TECH-VALID', msg: `techs/tree.json: ${err}`, src: 'TechTreeLoader / techSchema' });
            }
            for (const warn of check.warnings) {
                findings.push({ level: 'warn', code: 'W-TECH-VALID', msg: `techs/tree.json: ${warn}`, src: 'techSchema（布局重叠等提示）' });
            }
            const usage = TechSchema.collectTriggerUsage(tree);
            if (usage.length) {
                findings.push({
                    level: 'info', code: 'I-TECH-TRIGGERS',
                    msg: `科技树引用 ${usage.length} 个触发器：` +
                        usage.map((u) => `${u.name}×${u.count}`).join('、') +
                        '（运行期只有程序 post TechHooks.TriggerEvent 或 /techtree trigger 会置位）',
                    src: 'TechHooks.TriggerEvent',
                });
            }
        }
    }

    // --- 3. 有数据无入口：entities / items / blocks / quests 都不会被自动注册 ---
    const entry = (manifest['Module-Entry'] || '').trim();
    const ibCount = items.length + blocks.length;
    if (!entry && entities.length + ibCount + quests.length > 0) {
        const what = [];
        if (entities.length) what.push(`${entities.length} 个 entities/*.json`);
        if (items.length) what.push(`${items.length} 个 items/*.json`);
        if (blocks.length) what.push(`${blocks.length} 个 blocks/*.json`);
        if (quests.length) what.push(`${quests.length} 个 quests/*.json`);
        findings.push({
            level: 'error', code: 'E-DATA-NO-ENTRY',
            msg: `有 ${what.join('、')} 但 MANIFEST 没有 Module-Entry：加载器不会自动注册这些 JSON，` +
                `只有包的 register() 显式注册（registerDataDrivenMob / registerItemsAndBlocksFromJar / registerQuestsFromJar）才会生效 ⇒ 这些 JSON 是死数据（游戏里不存在该内容）。` +
                `填上 Module-Entry 并让编辑器生成入口类即可。`,
            src: 'ContentPackContext / BatchContentRegistrar',
        });
    }
    // 入口类存在但没调批量注册 → items/blocks 死数据
    if (entry && ibCount > 0) {
        const loc = Scaffold.entryLocation(entry);
        const srcFile = loc ? path.join(packDir, loc.path) : null;
        if (srcFile && fs.existsSync(srcFile)) {
            const src = fs.readFileSync(srcFile, 'utf-8');
            if (!src.includes('registerItemsAndBlocksFromJar')) {
                findings.push({
                    level: 'error', code: 'E-IB-NO-REGISTER',
                    msg: `入口类 ${loc.path} 没有调用 context.registerItemsAndBlocksFromJar()：` +
                        `本包的 ${items.length} 个物品、${blocks.length} 个方块不会注册（死数据）。` +
                        `在编辑器里「补齐骨架」或手动补一行即可。`,
                    src: 'BatchContentRegistrar（jar 扫描注册）',
                });
            }
        } else if (!srcFile || !fs.existsSync(srcFile)) {
            // E-ENTRY-NO-SOURCE 已覆盖，不重复报
        }
    }
    // 入口类存在但没调任务注册 → quests 死数据
    if (entry && quests.length > 0) {
        const loc = Scaffold.entryLocation(entry);
        const srcFile = loc ? path.join(packDir, loc.path) : null;
        if (srcFile && fs.existsSync(srcFile)) {
            const src = fs.readFileSync(srcFile, 'utf-8');
            if (!src.includes('registerQuestsFromJar')) {
                findings.push({
                    level: 'error', code: 'E-Q-NO-REGISTER',
                    msg: `入口类 ${loc.path} 没有调用 context.registerQuestsFromJar()：` +
                        `本包的 ${quests.length} 个任务不会注册（死数据，游戏里看不到）。` +
                        `在编辑器里「补齐骨架」会自动插入该调用（只插一行，不改其它代码）。`,
                    src: 'ContentPackContext.registerQuestsFromJar（jar 扫描注册）',
                });
            }
        }
    }

    // --- 4. 入口类源码是否存在 ---
    if (entry) {
        const loc = Scaffold.entryLocation(entry);
        if (loc && !fs.existsSync(path.join(packDir, loc.path))) {
            findings.push({
                level: 'error', code: 'E-ENTRY-NO-SOURCE',
                msg: `Module-Entry 指向 ${loc.path}，但该源码不存在：编译产物里没有入口类 ⇒ 运行期找不到入口。` +
                    `（若这是只作复制用的模板包，可忽略。）`,
                src: 'ContentPackLoader（入口类实例化）',
            });
        }
    }

    // --- 5. Gradle 骨架 ---
    const hasBuildGradle = fs.existsSync(path.join(packDir, 'build.gradle'));
    if (!hasBuildGradle) {
        findings.push({
            level: 'warn', code: 'W-NO-BUILD-GRADLE',
            msg: '缺少 build.gradle：settings.gradle 自动发现只认有 build.gradle 的目录，' +
                '所以 gradlew contentpackJar 不会构建它（只能走回退脚本 build-pack.ps1）。' +
                '在编辑器里「保存 MANIFEST」或重新创建即可补上。',
            src: 'settings.gradle（条件纳入 + 自动发现）',
        });
    }

    // --- 6. 产物层面：同名 Module-Name 的重复 jar ---
    const moduleName = (manifest['Module-Name'] || '').trim();
    const expectedJar = moduleName ? Scaffold.expectedJarName(moduleName) : '(未知)';
    if (moduleName && opts.root) {
        findings.push(...scanDuplicateModuleName(opts.root, moduleName, expectedJar));
    }

    const errors = findings.filter((x) => x.level === 'error');
    const warnings = findings.filter((x) => x.level === 'warn');
    return {
        ok: errors.length === 0,
        errors,
        warnings,
        findings,
        manifest,
        entities,
        items,
        blocks,
        quests,
        expectedJar,
        gradleTask: `${Scaffold.gradleProjectPath(path.basename(packDir))}:contentpackJar`,
        gradleCommand: Scaffold.gradleBuildCommand(path.basename(packDir)),
    };
}

/**
 * 物品/方块导出产物完整性（源定义 → 包内原版资源）。
 *
 * 游戏侧判定链：
 *   物品  models/item/<id>.json → 纹理
 *   方块  blockstates/<id>.json（复数）→ 模型 → 纹理
 * 任一环缺失都不会阻止注册，但运行期表现为紫黑块 / 缺失模型，
 * 且 BatchContentRegistrar.problems 仍为 0（2026-11-08 黄铜灯实证）。
 */
function checkItemBlockAssets(packDir, items, blocks, opts = {}) {
    const findings = [];
    const projectRoot = opts.root || null;
    const packNs = new Set();
    for (const def of [...items, ...blocks]) {
        if (def && def.id) packNs.add(def.namespace || 'catoxidesbattlerebuild');
    }
    if (!packNs.size) return findings;
    const vanillaReady = McAssets.isReady(McAssets.resolveRoot(projectRoot));

    const seen = new Set();
    const add = (level, code, msg, src) => {
        const key = code + '\u0000' + msg;
        if (seen.has(key)) return;
        seen.add(key);
        findings.push({ level, code, msg, src });
    };
    const abs = (rel) => path.join(packDir, ...rel.split('/'));
    const inPack = (rel) => fs.existsSync(abs(rel));
    // 包内先查：同名资源若被 vanilla/其它包提供，不能掩盖「本包没导出」
    const exists = (rel) => inPack(rel) || !!McAssets.findAsset(projectRoot, rel, { packDir });

    const splitRl = (rl) => {
        const s = String(rl || '').trim();
        const i = s.indexOf(':');
        return i >= 0 ? { ns: s.slice(0, i), path: s.slice(i + 1) } : { ns: 'minecraft', path: s };
    };
    // 缺失等级：本包命名空间 / 本机有 vanilla 底 ⇒ error；其它外部命名空间（别的 mod）⇒ warn
    const missingLevel = (ns) => (packNs.has(ns) || (ns === 'minecraft' && vanillaReady) ? 'error' : 'warn');
    const missingHint = (ns) => (ns === 'minecraft' && !vanillaReady
        ? '（本机未探测到 vanilla 资源根，无法确认原版资源是否存在）'
        : packNs.has(ns) ? '（该命名空间由本包提供）' : '（外部命名空间，需其它 mod/资源包在运行期提供）');

    const checkedTextures = new Set();
    const checkTexture = (texId, ctx) => {
        const s = String(texId || '').trim();
        if (!s || s.startsWith('#')) return;
        const { ns, path: p } = splitRl(s);
        const rel = `assets/${ns}/textures/${p}.png`;
        if (checkedTextures.has(rel)) return;
        checkedTextures.add(rel);
        if (exists(rel)) return;
        add(missingLevel(ns), 'E-IB-NO-TEXTURE',
            `${ctx} 引用的纹理 ${s} 不存在（${rel}）${missingHint(ns)}：客户端会渲染成紫黑格。`,
            '客户端 TextureAtlas（Missing textures in model）');
    };

    const checkedModels = new Set();
    const checkModel = (modelRef, ctx) => {
        const s = String(modelRef || '').trim();
        if (!s) return;
        const { ns, path: p } = splitRl(s);
        const rel = `assets/${ns}/models/${p}.json`;
        if (checkedModels.has(rel)) return;
        checkedModels.add(rel);
        if (!exists(rel)) {
            add(missingLevel(ns), 'E-IB-NO-MODEL',
                `${ctx} 引用的模型 ${s} 不存在（${rel}）${missingHint(ns)}：客户端会报缺失模型。`,
                '客户端 ModelManager（Unable to load model）');
            return;
        }
        // 沿 parent 链解析纹理（parent 可能在本包、vanilla 或其它 mod）
        const chain = McAssets._internals.loadModelChain(projectRoot, s, { packDir });
        if (!chain) return;
        const keys = new Set();
        for (const node of chain) {
            for (const k of Object.keys(node.raw.textures || {})) keys.add(k);
        }
        for (const k of keys) {
            const texId = McAssets._internals.resolveTextureId(chain, k);
            if (texId) checkTexture(texId, `${ctx} 的模型 ${s} 纹理 #${k}`);
        }
    };

    // 1.21.1 允许 variant / multipart.apply 是「对象数组」（bedrock/bamboo 实证）；
    // 唯一非法形状是裸字符串（黄铜灯实证：按对象反序列化 → missing model）
    const checkApplyObject = (value, ctx, src) => {
        const list = Array.isArray(value) ? value : [value];
        if (!list.length) {
            add('error', 'E-IB-BLOCKSTATE-SHAPE', `${ctx} 是空数组 —— 该状态没有可用模型。`, src);
            return [];
        }
        const refs = [];
        for (const v of list) {
            if (!v || typeof v !== 'object' || Array.isArray(v) || typeof v.model !== 'string' || !v.model.trim()) {
                add('error', 'E-IB-BLOCKSTATE-SHAPE',
                    `${ctx} 不是 {model: "..."} 对象 —— 1.21.1 按对象反序列化，裸字符串/非法形状会被判成` +
                    `「missing model for variant」⇒ 紫黑块。`,
                    src);
                return refs;
            }
            refs.push(v.model.trim());
        }
        return refs;
    };

    const checkBlockstateShape = (raw, ctx) => {
        const refs = [];
        const variants = raw.variants;
        const hasVariants = !!variants && typeof variants === 'object' && !Array.isArray(variants);
        if (hasVariants) {
            for (const [key, value] of Object.entries(variants)) {
                refs.push(...checkApplyObject(value, `${ctx}: variants["${key}"]`, '客户端 VariantDeserializer'));
            }
        }
        if (Array.isArray(raw.multipart)) {
            raw.multipart.forEach((entry, i) => {
                const apply = entry && entry.apply;
                refs.push(...checkApplyObject(apply, `${ctx}: multipart[${i}].apply`, '客户端 MultipartDeserializer'));
            });
        }
        if (!hasVariants && !Array.isArray(raw.multipart)) {
            add('error', 'E-IB-BLOCKSTATE-SHAPE',
                `${ctx}: 既没有 variants 也没有 multipart —— 客户端会报 Exception loading blockstate definition。`,
                '客户端 BlockStateModelLoader');
        } else if (hasVariants && Object.keys(variants).length === 0) {
            add('error', 'E-IB-BLOCKSTATE-SHAPE',
                `${ctx}: variants 是空对象 —— 任何状态都没有模型。`,
                '客户端 BlockStateModelLoader');
        }
        for (const ref of refs) checkModel(ref, `${ctx} 的 blockstate`);
    };

    const checkItemModel = (def, ctx) => {
        const ns = def.namespace || 'catoxidesbattlerebuild';
        const rel = `assets/${ns}/models/item/${def.id}.json`;
        if (!inPack(rel)) {
            add('error', 'E-IB-NO-ITEM-MODEL',
                `${ctx}: 缺少 ${rel} —— 物品会注册成功，但没有物品模型 ⇒ 紫黑块/缺失模型。请在编辑器里导出原版资源。`,
                '客户端 ModelManager（Unable to load model）');
            return;
        }
        checkModel(`${ns}:item/${def.id}`, `${ctx} 的物品模型`);
    };

    for (const def of blocks) {
        if (!def || !def.id) continue;
        const ns = def.namespace || 'catoxidesbattlerebuild';
        const ctx = `方块 ${ns}:${def.id}`;
        const rel = `assets/${ns}/blockstates/${def.id}.json`;
        const singular = `assets/${ns}/blockstate/${def.id}.json`;
        if (!inPack(rel)) {
            if (inPack(singular)) {
                add('error', 'E-IB-BLOCKSTATE-DIR',
                    `${ctx}: blockstate 写在了 ${singular}（单数）—— 1.21.1 客户端只读 blockstates/（复数，` +
                    `原版 1062 个文件全在该目录），这份定义会被忽略 ⇒ 方块无模型（紫黑块）。把目录改成 blockstates/。`,
                    '客户端 BlockStateModelLoader（只列 blockstates/）');
            } else {
                add('error', 'E-IB-NO-BLOCKSTATE',
                    `${ctx}: 缺少 ${rel} —— 方块会注册成功，但客户端没有形态定义 ⇒ 紫黑块/缺失模型。请在编辑器里导出原版资源。`,
                    '客户端 BlockStateModelLoader');
            }
        } else {
            try {
                checkBlockstateShape(JSON.parse(fs.readFileSync(abs(rel), 'utf-8')), ctx);
            } catch (e) {
                add('error', 'E-IB-BLOCKSTATE-JSON', `${ctx}: ${rel} 不是合法 JSON：${e.message}`, '客户端 BlockStateModelLoader');
            }
        }
        if (def.blockItem !== false) checkItemModel(def, `方块物品 ${ns}:${def.id}`);
    }
    for (const def of items) {
        if (!def || !def.id) continue;
        checkItemModel(def, `物品 ${(def.namespace || 'catoxidesbattlerebuild')}:${def.id}`);
    }
    return findings;
}

/** 递归统计目录下 .json 文件数（用于「子目录里的 JSON 不会被扫描」提示） */
function countJsonDeep(dir) {
    let n = 0;
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
        if (ent.isDirectory()) n += countJsonDeep(path.join(dir, ent.name));
        else if (ent.name.endsWith('.json')) n++;
    }
    return n;
}

/**
 * 扫描 contentpacks/ 与 run/client/contentpacks/ 里【同 Module-Name 的其它 jar】。
 *
 * 为什么必须查：ContentPackLoader 遇到重复 Module-Name 只打印
 *   Duplicate ContentPack Module-Name 'x': ignoring '<后扫描到的>' (already provided by '<先扫描到的>')
 * 然后丢弃一个——【哪个被加载取决于扫描顺序】，不可复现。
 * 典型成因：产物按 Module-Name 命名后旧文件名残留（如 x-pack.jar → x.jar），
 * 而 copyContentPacksToClient 是 Copy 不是 Sync，运行时目录里的陈旧文件也不会被清掉。
 */
function scanDuplicateModuleName(root, moduleName, expectedJar) {
    const findings = [];
    const dirs = [
        path.join(root, 'contentpacks'),
        path.join(root, 'run', 'client', 'contentpacks'),
        path.join(root, 'run', 'server', 'contentpacks'),
    ];
    for (const dir of dirs) {
        if (!fs.existsSync(dir)) continue;
        for (const file of fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith('.jar'))) {
            const full = path.join(dir, file);
            let theirs = null;
            try {
                theirs = JarReader.readManifest(full);
            } catch {
                continue; // 不是有效 jar（可能正在被写入），跳过
            }
            if (!theirs || theirs['Module-Name'] !== moduleName) continue;
            const rel = path.relative(root, full);
            if (file === expectedJar) {
                continue; // 就是本包的预期产物，正常
            }
            findings.push({
                level: 'error', code: 'E-DUP-MODULE-NAME',
                msg: `${rel} 与本次产物同名（Module-Name "${moduleName}"）：加载器会丢弃其中一个，` +
                    `且【丢弃哪个取决于扫描顺序】⇒ 不可复现。请删除该文件（预期产物名是 contentpacks/${expectedJar}）。`,
                src: 'ContentPackLoader（Duplicate Module-Name）',
            });
        }
    }
    return findings;
}

/** 生成包骨架文件清单（路径 → 内容），供 pack:create / 补齐脚本使用 */
function scaffoldFiles(manifest, opts = {}) {
    const moduleName = (manifest['Module-Name'] || '').trim();
    const files = {};
    files['MANIFEST.MF'] = ManifestLib.stringifyManifest(Scaffold.canonicalizeManifest(manifest));
    files['build.gradle'] = Scaffold.renderBuildGradle(moduleName, { extraDeps: opts.extraDeps || [] });
    const entry = (manifest['Module-Entry'] || '').trim();
    if (entry) {
        const src = Scaffold.renderEntryClass({
            moduleEntry: entry,
            packId: opts.packId || moduleName.replace(/-/g, '_'),
            displayName: opts.displayName || moduleName,
            version: manifest['Module-Version'] || '1.0.0',
            entityIds: opts.entityIds || [],
            questIds: opts.questIds || [],
        });
        if (src) {
            const loc = Scaffold.entryLocation(entry);
            files[loc.path] = src;
        }
    }
    return files;
}

const PackPreflightAPI = { preflightPack, scanDuplicateModuleName, scaffoldFiles };

if (typeof module === 'object' && module.exports) module.exports = PackPreflightAPI;
