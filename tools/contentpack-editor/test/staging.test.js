// staging.test.js - 暂存区模块测试（node 直接运行）
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const os = require('os');

const Staging = require('../src/core/staging');
const EntitySchema = require('../src/core/entitySchema');
const HealthConfig = require('../src/core/healthConfig');
const ManifestLib = require('../src/core/manifest');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

// 隔离环境：临时 userData + 临时正式包
const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'cpe-staging-'));
const fakeUserData = path.join(tmpRoot, 'userdata');
const packDir = path.join(tmpRoot, 'contentpacks-source', 'staging-test-pack');
fs.mkdirSync(path.join(packDir, 'entities'), { recursive: true });
ManifestLib.writeManifest(packDir, { 'Module-Name': 'staging-test-pack', 'Module-Version': '0.1.0', 'Module-Type': 'contentpack' });
const PACK_NAME = 'staging-test-pack';

console.log('=== 暂存区模块测试 ===');

Staging.initStaging(fakeUserData);

test('1. 暂存根目录在 userData/staging（app 私有，不在项目里）', () => {
    const root = Staging.getStagingRoot();
    assert.ok(root.includes('userdata'), '暂存应在 userData 下: ' + root);
    assert.ok(root.endsWith('staging'));
    assert.ok(!root.includes(packDir), '暂存不能混入正式包');
});

test('2. 写实体草稿 → 暂存优先读取', () => {
    const entity = EntitySchema.createDefaultEntity();
    entity.id = 'staged_mob';
    entity.namespace = 'stagingtest';
    const res = Staging.stageEntity(PACK_NAME, entity);
    assert.ok(res.staged);
    const staged = Staging.readStagedEntity(PACK_NAME, 'staged_mob');
    assert.ok(staged, '应能读到草稿');
    assert.strictEqual(staged.id, 'staged_mob');
    // 正式包没有被写（草稿只在暂存区）
    assert.ok(!fs.existsSync(path.join(packDir, 'entities', 'staged_mob.json')), '正式包不应有该实体');
});

test('3. 写部位配置草稿 → 暂存优先读取', () => {
    const cfg = HealthConfig.createDefaultHealthConfig(['head', 'body']);
    Staging.stageHealthConfig(PACK_NAME, 'stagingtest', 'staged_mob', cfg);
    const staged = Staging.readStagedHealthConfig(PACK_NAME, 'stagingtest', 'staged_mob');
    assert.ok(staged);
    assert.strictEqual(staged.parts.length, 4);
});

test('4. 列表包含两类暂存项', () => {
    const items = Staging.listStaged(PACK_NAME);
    assert.ok(items.some((i) => i.kind === 'entity' && i.id === 'staged_mob'));
    assert.ok(items.some((i) => i.kind === 'health' && i.id === 'staged_mob'));
});

test('5. 导出 → 正式包获得文件 → 暂存被回收', () => {
    const result = Staging.exportStaged(PACK_NAME, packDir);
    assert.strictEqual(result.exported, 2, '应导出 2 个文件（实体 + 部位）');
    assert.ok(fs.existsSync(path.join(packDir, 'entities', 'staged_mob.json')), '正式包应有实体');
    assert.ok(fs.existsSync(path.join(packDir, 'assets', 'stagingtest', 'health_config', 'staged_mob.json')), '正式包应有部位配置');
    assert.strictEqual(Staging.listStaged(PACK_NAME).length, 0, '导出后暂存应清空（回收）');
});

test('6. 再写草稿 → 丢弃 → 暂存清空且正式包不变', () => {
    Staging.stageEntity(PACK_NAME, Object.assign(EntitySchema.createDefaultEntity(), { id: 'draft_mob' }));
    assert.strictEqual(Staging.listStaged(PACK_NAME).length, 1);
    const res = Staging.discardStaged(PACK_NAME);
    assert.ok(res.discarded);
    assert.strictEqual(Staging.listStaged(PACK_NAME).length, 0);
    assert.ok(!fs.existsSync(path.join(packDir, 'entities', 'draft_mob.json')), '丢弃不应影响正式包');
});

test('7. 跨包隔离', () => {
    Staging.stageEntity('other-pack', Object.assign(EntitySchema.createDefaultEntity(), { id: 'x' }));
    assert.strictEqual(Staging.listStaged('staging-test-pack').length, 0);
    assert.strictEqual(Staging.listStaged('other-pack').length, 1);
    const all = Staging.listAllStagedPacks();
    assert.ok(all.some((p) => p.packName === 'other-pack'));
    Staging.discardStaged('other-pack');
});

test('8. removeStagedEntity 单个实体清理', () => {
    Staging.stageEntity(PACK_NAME, Object.assign(EntitySchema.createDefaultEntity(), { id: 'tmp1' }));
    Staging.stageHealthConfig(PACK_NAME, 'ns1', 'tmp1', { parts: [] });
    Staging.removeStagedEntity(PACK_NAME, 'tmp1');
    const items = Staging.listStaged(PACK_NAME);
    assert.ok(!items.some((i) => i.id === 'tmp1'), '实体及其部位草稿都应被清理');
});

test('9. (REVIEW-S1) removeStagedHealthConfig 只删 health 草稿，实体草稿必须保留', () => {
    Staging.stageEntity(PACK_NAME, Object.assign(EntitySchema.createDefaultEntity(), { id: 's1_mob' }));
    Staging.stageHealthConfig(PACK_NAME, 'ns_a', 's1_mob', { parts: [] });
    Staging.removeStagedHealthConfig(PACK_NAME, 'ns_a', 's1_mob');
    assert.strictEqual(Staging.readStagedHealthConfig(PACK_NAME, 'ns_a', 's1_mob'), null, 'health 草稿应删除');
    assert.ok(Staging.readStagedEntity(PACK_NAME, 's1_mob'), '实体草稿必须保留（S1：保存路径丢数据回归）');
    Staging.removeStagedEntity(PACK_NAME, 's1_mob');
});

test('10. (REVIEW-S1) namespace 不一致时按 id 全 ns 兜底清理', () => {
    Staging.stageEntity(PACK_NAME, Object.assign(EntitySchema.createDefaultEntity(), { id: 's1b_mob' }));
    Staging.stageHealthConfig(PACK_NAME, 'ns_real', 's1b_mob', { parts: [] });
    Staging.removeStagedHealthConfig(PACK_NAME, 'ns_wrong', 's1b_mob');
    assert.strictEqual(Staging.readStagedHealthConfig(PACK_NAME, 'ns_real', 's1b_mob'), null, '应被兜底清理');
    assert.ok(Staging.readStagedEntity(PACK_NAME, 's1b_mob'), '实体草稿必须保留');
    Staging.removeStagedEntity(PACK_NAME, 's1b_mob');
});

test('11. (REVIEW-L6) structureId ".." 不再逃逸 structures/ 目录（草稿保持可见）', () => {
    Staging.stageStructure('s6-pack', { structureId: '..', namespace: '' });
    const items = Staging.listStaged('s6-pack');
    assert.ok(items.some((i) => i.kind === 'structure'), '.. 草稿应可见（此前写入包暂存根、列表不可见）');
    Staging.discardStaged('s6-pack');
});

test('12. (0.2.1) stageEntity 剥掉 _staged 等内部字段（否则导出后变成正式包脏数据）', () => {
    const e = Object.assign(EntitySchema.createDefaultEntity(), { id: 'meta_mob', _staged: true, _x: 1 });
    Staging.stageEntity(PACK_NAME, e);
    const read = Staging.readStagedEntity(PACK_NAME, 'meta_mob');
    assert.strictEqual(read._staged, undefined, '_staged 不应落盘');
    assert.strictEqual(read._x, undefined);
    assert.strictEqual(read.id, 'meta_mob');
    Staging.removeStagedEntity(PACK_NAME, 'meta_mob');
});

test('13. (0.2.1) id 含路径分隔符/.. 时草稿仍落在暂存区内（不越界）', () => {
    Staging.stageEntity(PACK_NAME, Object.assign(EntitySchema.createDefaultEntity(), { id: '..\\..\\evil' }));
    Staging.stageHealthConfig(PACK_NAME, '..\\..', '..\\..\\evil', { parts: [] });
    const root = path.resolve(Staging.getStagingRoot());
    const all = [];
    (function walk(d) {
        for (const ent of fs.readdirSync(d, { withFileTypes: true })) {
            const q = path.join(d, ent.name);
            if (ent.isDirectory()) walk(q); else all.push(path.resolve(q));
        }
    })(root);
    for (const f of all) assert.ok(f.startsWith(root + path.sep), '草稿越界: ' + f);
    Staging.removeStagedEntity(PACK_NAME, '..\\..\\evil');
    Staging.removeStagedHealthConfig(PACK_NAME, '..\\..', '..\\..\\evil');
});

test('14. 任务草稿：按内容 id 读取，保留既有文件名（quests.json）', () => {
    const def = {
        id: 'legacy_quest', title: '旧格式',
        givers: [], goals: [{ type: 'item_present', item: 'minecraft:apple' }], children: [],
    };
    Staging.stageQuest(PACK_NAME, def, 'quests/quests.json');
    const staged = Staging.readStagedQuest(PACK_NAME, 'legacy_quest');
    assert.ok(staged, '应能按 id 读到草稿（文件名不叫 legacy_quest.json）');
    assert.strictEqual(staged.id, 'legacy_quest');
    const item = Staging.listStaged(PACK_NAME).find((i) => i.kind === 'quest');
    assert.strictEqual(item.file, path.join('quests', 'quests.json'));
});

test('15. 任务草稿：fileRel 路径逃逸被拒绝（回落到 quests/<id>.json）', () => {
    Staging.stageQuest(PACK_NAME, { id: 'evil_quest', title: 'x' }, '../../evil.json');
    const root = path.resolve(Staging.getStagingRoot());
    const item = Staging.listStaged(PACK_NAME).find((i) => i.kind === 'quest' && i.id === 'evil_quest');
    assert.ok(item, '草稿应可见');
    assert.strictEqual(item.file, path.join('quests', 'evil_quest.json'));
    assert.ok(path.resolve(root, item.file).startsWith(root + path.sep), '草稿越界: ' + item.file);
    Staging.removeStagedQuest(PACK_NAME, 'evil_quest');
});

test('16. 任务草稿导出 → 正式包（保留文件名）→ 暂存回收', () => {
    const r = Staging.exportStaged(PACK_NAME, packDir);
    assert.ok(r.files.some((f) => f === path.join('quests', 'quests.json')), '应写回原文件名: ' + r.files.join(', '));
    const written = JSON.parse(fs.readFileSync(path.join(packDir, 'quests', 'quests.json'), 'utf-8'));
    assert.strictEqual(written.id, 'legacy_quest');
    assert.strictEqual(Staging.listStaged(PACK_NAME).length, 0);
    fs.rmSync(path.join(packDir, 'quests'), { recursive: true, force: true });
});

// 清理
fs.rmSync(tmpRoot, { recursive: true, force: true });

console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
if (failed > 0) process.exit(1);
