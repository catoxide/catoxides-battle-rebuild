// packPreflight.test.js - 预检与 jar 读取测试（node 直接运行）
// 用【真实的 contentpacks/*.jar】做 jar 读取测试，用真实的包目录做预检测试。
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const os = require('os');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const PACKS_SOURCE = path.join(ROOT, 'contentpacks-source');
const Preflight = require('../src/core/packPreflight');
const JarReader = require('../src/core/jarReader');
const ManifestLib = require('../src/core/manifest');
const Scaffold = require('../src/core/packScaffold');
const ItemBlockExport = require('../src/core/itemBlockExport');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}
const codes = (findings) => findings.map((f) => f.code);
const has = (findings, code) => codes(findings).includes(code);

console.log('=== 1. jar 读取（真实产物）===');
const REAL_JAR = path.join(ROOT, 'contentpacks', 'durability-pack.jar');
test('readManifest 读出真实产物的 MANIFEST 属性', () => {
    const m = JarReader.readManifest(REAL_JAR);
    assert.ok(m, '应能读到 MANIFEST');
    assert.strictEqual(m['Module-Type'], 'contentpack');
    assert.strictEqual(m['Module-Name'], 'durability-pack');
    assert.strictEqual(m['Module-Entry'], 'example.contentpack.durability.DurabilityPack');
});
test('isContentPack 判定真实产物', () => {
    assert.strictEqual(JarReader.isContentPack(REAL_JAR), true);
});
test('listEntries 能列出条目（含 MANIFEST 与入口类）', () => {
    const names = JarReader.listEntries(REAL_JAR).map((e) => e.name);
    assert.ok(names.includes('META-INF/MANIFEST.MF'));
    assert.ok(names.includes('example/contentpack/durability/DurabilityPack.class'));
});
test('readEntry 读出 class 魔数（0xCAFEBABE）', () => {
    const buf = JarReader.readEntry(REAL_JAR, 'example/contentpack/durability/DurabilityPack.class');
    assert.strictEqual(buf.readUInt32BE(0), 0xCAFEBABE);
});
test('readEntry 找不到条目返回 null', () => {
    assert.strictEqual(JarReader.readEntry(REAL_JAR, 'no/such/entry.txt'), null);
});

console.log('=== 2. 真实包的预检 ===');
for (const name of ['durability-pack', 'test-mob-pack', 'quest-test-pack', 'sable-pack-v2', 'modular-zombie-3',
    'item-block-test-pack', // 物品/方块 + 导出资源（blockstates/models/纹理链路）
    'tech-tree-pack']) {
    test(`${name} 预检无 error`, () => {
        const r = Preflight.preflightPack(path.join(PACKS_SOURCE, name), { root: ROOT });
        assert.strictEqual(r.errors.length, 0, `意外的 error: ${JSON.stringify(r.errors, null, 2)}`);
    });
}
test('fortress-pack（纯数据包：原版 jigsaw 结构）预检无 error', () => {
    const r = Preflight.preflightPack(path.join(PACKS_SOURCE, 'fortress-pack'), { root: ROOT });
    assert.strictEqual(r.errors.length, 0, JSON.stringify(r.errors));
});

console.log('=== 3. 坏包必须被拦下（临时目录合成）===');
const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'cpe-preflight-'));
const tmpPacks = path.join(tmpRoot, 'contentpacks-source');
fs.mkdirSync(tmpPacks, { recursive: true });

function makePack(name, manifest, entities) {
    const dir = path.join(tmpPacks, name);
    fs.mkdirSync(path.join(dir, 'entities'), { recursive: true });
    ManifestLib.writeManifest(dir, manifest);
    for (const [id, obj] of Object.entries(entities || {})) {
        fs.writeFileSync(path.join(dir, 'entities', id + '.json'), JSON.stringify(obj, null, 2), 'utf-8');
    }
    return dir;
}

test('有 entities 但无 Module-Entry → error（JSON 是死数据）', () => {
    const dir = makePack('dead-data-pack', { 'Module-Name': 'dead-data-pack', 'Module-Type': 'contentpack' },
        { alpha: { id: 'alpha', size: { width: 1, height: 1 }, attributes: { maxHealth: 1 } } });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-DATA-NO-ENTRY'), codes(r.findings).join(','));
});
test('声明了 Module-Entry 但没有源码 → error', () => {
    const dir = makePack('no-source-pack', {
        'Module-Name': 'no-source-pack', 'Module-Type': 'contentpack', 'Module-Entry': 'example.contentpack.ns.NoSourcePack',
    }, {});
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-ENTRY-NO-SOURCE'), codes(r.findings).join(','));
});
test('缺 build.gradle → warn（Gradle 自动发现不到）', () => {
    const dir = makePack('no-gradle-pack', { 'Module-Name': 'no-gradle-pack', 'Module-Type': 'contentpack' }, {});
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.warnings, 'W-NO-BUILD-GRADLE'));
    assert.strictEqual(r.errors.length, 0);
});
test('entities JSON 语法错误 → error', () => {
    const dir = makePack('bad-json-pack', { 'Module-Name': 'bad-json-pack', 'Module-Type': 'contentpack' }, {});
    fs.writeFileSync(path.join(dir, 'entities', 'broken.json'), '{ not json', 'utf-8');
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-ENT-JSON'));
});
test('entities 文件名与 id 不一致 → warn', () => {
    const dir = makePack('name-mismatch-pack', { 'Module-Name': 'name-mismatch-pack', 'Module-Type': 'contentpack' },
        { wrong_name: { id: 'right_name', size: { width: 1, height: 1 } } });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.warnings, 'W-ENT-FILENAME'));
});
test('同名 Module-Name 的重复 jar → error（加载哪个取决于扫描顺序）', () => {
    const jar = path.join(REAL_JAR);
    const out = path.join(tmpRoot, 'contentpacks');
    fs.mkdirSync(out, { recursive: true });
    fs.copyFileSync(jar, path.join(out, 'durability-pack-old.jar')); // 旧文件名残留
    const r = Preflight.preflightPack(path.join(PACKS_SOURCE, 'durability-pack'), { root: tmpRoot });
    assert.ok(has(r.errors, 'E-DUP-MODULE-NAME'), codes(r.findings).join(','));
    // 删掉旧文件名残留、只留预期产物名 → 不应再报重复
    fs.unlinkSync(path.join(out, 'durability-pack-old.jar'));
    fs.copyFileSync(jar, path.join(out, 'durability-pack.jar'));
    const r2 = Preflight.preflightPack(path.join(PACKS_SOURCE, 'durability-pack'), { root: tmpRoot });
    assert.strictEqual(r2.errors.length, 0, JSON.stringify(r2.errors));
    fs.unlinkSync(path.join(out, 'durability-pack.jar'));
});

console.log('=== 3b. 任务 JSON（quests/）预检 ===');
/** 造一个带入口类的包；entryBody = register() 方法体（默认不含任务注册） */
function makeQuestPack(name, questFiles, opts = {}) {
    const entry = opts.entry || 'example.contentpack.qp.QuestPack';
    const dir = path.join(tmpPacks, name);
    fs.mkdirSync(path.join(dir, 'quests'), { recursive: true });
    ManifestLib.writeManifest(dir, {
        'Module-Name': name, 'Module-Type': 'contentpack',
        ...(opts.noEntry ? {} : { 'Module-Entry': entry }),
    });
    for (const [file, def] of Object.entries(questFiles)) {
        const full = path.join(dir, 'quests', file);
        fs.mkdirSync(path.dirname(full), { recursive: true });
        fs.writeFileSync(full, typeof def === 'string' ? def : JSON.stringify(def, null, 2), 'utf-8');
    }
    if (!opts.noEntry) {
        const loc = Scaffold.entryLocation(entry);
        const full = path.join(dir, loc.path);
        fs.mkdirSync(path.dirname(full), { recursive: true });
        fs.writeFileSync(full, opts.entrySource || [
            `package ${loc.pkg};`,
            'public class QuestPack implements ContentPack {',
            '    @Override',
            '    public void register(ContentPackContext context) {',
            '        // 手写逻辑（模拟真实包：工厂注册 / 命令 / 网络）',
            ...(opts.register ? ['        context.registerQuestsFromJar();'] : []),
            '    }',
            '}',
            '',
        ].join('\n'), 'utf-8');
    }
    return dir;
}
const okQuest = (id) => ({
    id, title: '任务 ' + id,
    givers: [{ type: 'proximity_giver', center: [0, 64, 0], radius: 10 }],
    goals: [{ type: 'item_present', item: 'minecraft:apple', count: 8 }],
});

test('有 quests 但入口类没调 registerQuestsFromJar → error（死数据）', () => {
    const dir = makeQuestPack('q-noregister', { 'q1.json': okQuest('q1') });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-Q-NO-REGISTER'), codes(r.findings).join(','));
});
test('入口类调了 registerQuestsFromJar → 无 error', () => {
    const dir = makeQuestPack('q-registered', { 'q1.json': okQuest('q1') }, { register: true });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.strictEqual(r.errors.length, 0, JSON.stringify(r.errors));
    assert.strictEqual(r.quests.length, 1, '应回传解析到的任务定义');
});
test('有 quests 但 MANIFEST 没有 Module-Entry → E-DATA-NO-ENTRY 点名任务', () => {
    const dir = makeQuestPack('q-noentry', { 'q1.json': okQuest('q1') }, { noEntry: true });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    const f = r.errors.find((x) => x.code === 'E-DATA-NO-ENTRY');
    assert.ok(f, codes(r.findings).join(','));
    assert.ok(f.msg.includes('quests/*.json'), f.msg);
});
test('任务 JSON 语法错误 → E-Q-JSON（该文件加载期会被隔离）', () => {
    const dir = makeQuestPack('q-badjson', { 'broken.json': '{ not json' });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-Q-JSON'), codes(r.findings).join(','));
});
test('缺 item 的任务 → E-Q-VALID（游戏里该文件会注册失败）', () => {
    const bad = okQuest('q_bad');
    delete bad.goals[0].item;
    const dir = makeQuestPack('q-invalid', { 'q_bad.json': bad });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    const f = r.errors.find((x) => x.code === 'E-Q-VALID');
    assert.ok(f && f.msg.includes('goals[0].item'), codes(r.findings).join(','));
});
test('包内任务 id 重复 → E-Q-ID-DUP（后者覆盖前者）', () => {
    const dir = makeQuestPack('q-dup', { 'a.json': okQuest('q_same'), 'b.json': okQuest('q_same') });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-Q-ID-DUP'), codes(r.findings).join(','));
});
test('quests/ 子目录里的 JSON → E-Q-NESTED（只扫第一层）', () => {
    const dir = makeQuestPack('q-nested', { 'sub/deep.json': okQuest('q_deep') });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-Q-NESTED'), codes(r.findings).join(','));
});
test('包私有 goal 类型 → 只告警不拦（字段由包的 Java 工厂决定）', () => {
    const q = okQuest('q_custom');
    q.goals.push({ type: 'kill', entity: 'minecraft:zombie', count: 2 });
    const dir = makeQuestPack('q-custom', { 'q_custom.json': q }, { register: true });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.strictEqual(r.errors.length, 0, JSON.stringify(r.errors));
    assert.ok(has(r.warnings, 'W-Q-VALID'), codes(r.findings).join(','));
});

console.log('=== 3c. 物品/方块导出产物（模型 / blockstates / 纹理）===');
const okBlock = (id, ns, texture) => ({
    id, namespace: ns,
    lang: { en_us: id, zh_cn: id },
    material: { hardness: 1.5, resistance: 6, sound: 'minecraft:block.stone', lightLevel: 0, solid: true },
    properties: [],
    model: { type: 'cube_all', texture, file: id },
    blockstates: { mode: 'variants', variants: [] },
    loot: { selfDrop: true },
    tags: [],
    creative: true,
    blockItem: true,
});
const okItem = (id, ns, texture) => ({
    id, namespace: ns,
    lang: { en_us: id, zh_cn: id },
    stackSize: 64, rarity: 'common', creative: true, fireResistant: false,
    model: { type: 'generated', texture },
});

/** 带入口类（registerItemsAndBlocksFromJar）+ 真实导出产物的包 */
function makeIbPack(name, { items = [], blocks = [], exportAssets = true }) {
    const dir = path.join(tmpPacks, name);
    fs.mkdirSync(path.join(dir, 'items'), { recursive: true });
    fs.mkdirSync(path.join(dir, 'blocks'), { recursive: true });
    const entry = 'example.contentpack.ibp.IbPack';
    ManifestLib.writeManifest(dir, { 'Module-Name': name, 'Module-Type': 'contentpack', 'Module-Entry': entry });
    for (const def of items) fs.writeFileSync(path.join(dir, 'items', def.id + '.json'), JSON.stringify(def, null, 2), 'utf-8');
    for (const def of blocks) fs.writeFileSync(path.join(dir, 'blocks', def.id + '.json'), JSON.stringify(def, null, 2), 'utf-8');
    const loc = Scaffold.entryLocation(entry);
    const full = path.join(dir, loc.path);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, [
        `package ${loc.pkg};`,
        'public class IbPack implements ContentPack {',
        '    @Override',
        '    public void register(ContentPackContext context) {',
        '        context.registerItemsAndBlocksFromJar();',
        '    }',
        '}',
        '',
    ].join('\n'), 'utf-8');
    if (exportAssets) {
        for (const def of items) ItemBlockExport.exportItem(dir, def);
        for (const def of blocks) ItemBlockExport.exportBlock(dir, def, blocks);
    }
    return dir;
}

test('导出产物齐全 → 无 error（blockstates → 模型 → 纹理闭合）', () => {
    const dir = makeIbPack('ib-assets-ok', { blocks: [okBlock('stone_chunk', 'ibp', 'minecraft:block/stone')] });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.strictEqual(r.errors.length, 0, JSON.stringify(r.errors, null, 2));
});
test('方块缺 blockstates → E-IB-NO-BLOCKSTATE', () => {
    const dir = makeIbPack('ib-no-blockstate', { blocks: [okBlock('stone_chunk', 'ibp', 'minecraft:block/stone')] });
    fs.unlinkSync(path.join(dir, 'assets', 'ibp', 'blockstates', 'stone_chunk.json'));
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-IB-NO-BLOCKSTATE'), codes(r.findings).join(','));
});
test('blockstate 写进单数目录 → E-IB-BLOCKSTATE-DIR（点名紫黑块）', () => {
    const dir = makeIbPack('ib-singular', { blocks: [okBlock('stone_chunk', 'ibp', 'minecraft:block/stone')] });
    fs.renameSync(path.join(dir, 'assets', 'ibp', 'blockstates'), path.join(dir, 'assets', 'ibp', 'blockstate'));
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    const f = r.errors.find((x) => x.code === 'E-IB-BLOCKSTATE-DIR');
    assert.ok(f, codes(r.findings).join(','));
    assert.ok(f.msg.includes('blockstates/'), f.msg);
});
test('variant 值是裸字符串 → E-IB-BLOCKSTATE-SHAPE', () => {
    const dir = makeIbPack('ib-bare-string', { blocks: [okBlock('stone_chunk', 'ibp', 'minecraft:block/stone')] });
    fs.writeFileSync(path.join(dir, 'assets', 'ibp', 'blockstates', 'stone_chunk.json'),
        JSON.stringify({ variants: { '': 'ibp:block/stone_chunk' } }), 'utf-8');
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-IB-BLOCKSTATE-SHAPE'), codes(r.findings).join(','));
});
test('包内纹理缺失 → E-IB-NO-TEXTURE（紫黑格）', () => {
    const dir = makeIbPack('ib-missing-texture', { blocks: [okBlock('stone_chunk', 'ibp', 'ibp:block/not_exported')] });
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-IB-NO-TEXTURE'), codes(r.findings).join(','));
});
test('物品缺 models/item → E-IB-NO-ITEM-MODEL', () => {
    const dir = makeIbPack('ib-no-item-model', { items: [okItem('plate', 'ibp', 'minecraft:item/iron_ingot')] });
    fs.unlinkSync(path.join(dir, 'assets', 'ibp', 'models', 'item', 'plate.json'));
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-IB-NO-ITEM-MODEL'), codes(r.findings).join(','));
});
test('multipart.apply 是裸字符串 → E-IB-BLOCKSTATE-SHAPE', () => {
    const dir = makeIbPack('ib-multipart-string', { blocks: [okBlock('stone_chunk', 'ibp', 'minecraft:block/stone')] });
    fs.writeFileSync(path.join(dir, 'assets', 'ibp', 'blockstates', 'stone_chunk.json'),
        JSON.stringify({ multipart: [{ apply: 'ibp:block/stone_chunk' }] }), 'utf-8');
    const r = Preflight.preflightPack(dir, { root: tmpRoot });
    assert.ok(has(r.errors, 'E-IB-BLOCKSTATE-SHAPE'), codes(r.findings).join(','));
});

console.log('=== 4. 骨架文件生成 ===');
test('scaffoldFiles 产出 MANIFEST + build.gradle + 入口类 + 实体注册', () => {
    const files = Preflight.scaffoldFiles({
        'Module-Name': 'new-pack', 'Module-Version': '0.1.0', 'Module-Type': 'contentpack',
        'Module-Requires': 'catoxidesbattlerebuild>=0.2.0', 'Module-Entry': 'example.contentpack.newpkg.NewPack',
    }, { packId: 'new_pack', displayName: 'New Pack', entityIds: ['alpha'] });
    assert.deepStrictEqual(Object.keys(files).sort(),
        ['MANIFEST.MF', 'build.gradle', 'java/example/contentpack/newpkg/NewPack.java']);
    assert.ok(files['MANIFEST.MF'].startsWith('Manifest-Version: 1.0\r\n'));
    assert.ok(files['MANIFEST.MF'].includes('Module-Type: contentpack'));
    assert.ok(files['build.gradle'].includes("apply from: '../contentpack.gradle'"));
    assert.ok(files['java/example/contentpack/newpkg/NewPack.java'].includes('registerDataDriven(context, "alpha");'));
});
test('无 Module-Entry 时不产出入口类（纯数据包）', () => {
    const files = Preflight.scaffoldFiles({ 'Module-Name': 'data-pack', 'Module-Type': 'contentpack' }, {});
    assert.deepStrictEqual(Object.keys(files).sort(), ['MANIFEST.MF', 'build.gradle']);
});
test('生成的 build.gradle 语法可被 Gradle 解析（括号配对 + 无 BOM）', () => {
    const g = Scaffold.renderBuildGradle('x-pack', { extraDeps: ['compileOnly "a:b:1"'] });
    assert.ok(!g.startsWith('\uFEFF'));
    const opens = (g.match(/{/g) || []).length, closes = (g.match(/}/g) || []).length;
    assert.strictEqual(opens, closes);
});

fs.rmSync(tmpRoot, { recursive: true, force: true });

console.log(`\n=== 结果: ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
