// integration.test.js - 端到端数据链集成测试（模拟 UI 调用序列，不依赖 Electron 窗口）
// 覆盖：scanPacks → listEntities → readEntity → saveEntity → saveHealth → readHealth → deleteHealth → deleteEntity
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const os = require('os');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const ManifestLib = require('../src/core/manifest');
const ModelParser = require('../src/core/modelParser');
const EntitySchema = require('../src/core/entitySchema');
const HealthConfig = require('../src/core/healthConfig');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

// 在系统临时目录创建隔离测试包（不污染项目）
const tmpPack = fs.mkdtempSync(path.join(os.tmpdir(), 'cpe-itest-'));
const srcPack = path.join(ROOT, 'contentpacks-source', 'test-mob-pack');

console.log('=== 集成测试：完整数据链 ===');

test('1. 扫描包 → 找到 test-mob-pack', () => {
    const packs = ManifestLib.scanPacks(path.join(ROOT, 'contentpacks-source'));
    assert.ok(packs.some((p) => p.name === 'test-mob-pack'));
});

test('2. 列出实体 → 读取 modular_zombie_test', () => {
    const entities = EntitySchema.listEntities(srcPack);
    assert.ok(entities.some((e) => e.id === 'modular_zombie_test'));
    const ent = EntitySchema.readEntity(srcPack, 'modular_zombie_test');
    assert.strictEqual(ent.namespace, 'testmob');
});

test('3. 在临时包中创建实体 + 保存', () => {
    fs.mkdirSync(path.join(tmpPack, 'entities'), { recursive: true });
    ManifestLib.writeManifest(tmpPack, { 'Module-Name': 'itest', 'Module-Version': '0.1.0', 'Module-Type': 'contentpack' });
    const e = EntitySchema.createDefaultEntity();
    e.id = 'itest_mob';
    e.namespace = 'itest';
    e.model.id = 'itest:itest_mob';
    const saved = EntitySchema.saveEntity(tmpPack, e);
    // saved.file 是相对包目录的路径；用绝对路径断言
    assert.ok(fs.existsSync(path.join(tmpPack, saved.file)), `实体文件应存在: ${saved.file}`);
    const read = EntitySchema.readEntity(tmpPack, 'itest_mob');
    assert.strictEqual(read.id, 'itest_mob');
    assert.strictEqual(read.attributes.maxHealth, 20.0);
});

test('4. 解析真实 spark 模型 → 生成默认部位', () => {
    const modelFile = path.join(ROOT, 'contentpacks-source', 'modular-zombie-3', 'spark_models', 'entity', 'zombie3pack', 'modular_zombie_3.json');
    const model = ModelParser.parseModelFile(modelFile);
    const boneNames = ModelParser.getBoneNames(model);
    const cfg = HealthConfig.createDefaultHealthConfig(boneNames);
    assert.ok(cfg.parts.length >= 4);
    const totalUnits = cfg.parts.reduce((s, p) => s + p.units.length, 0);
    assert.strictEqual(totalUnits, boneNames.length, '所有骨骼应分配到部位');
    const { valid, errors } = HealthConfig.validateHealthConfig(cfg);
    assert.strictEqual(valid, true, errors.join(','));
});

test('5. 保存部位配置 → 读回', () => {
    const cfg = HealthConfig.createDefaultHealthConfig(['head', 'body', 'left_arm']);
    const saved = HealthConfig.saveHealthConfig(tmpPack, 'itest', 'itest_mob', cfg);
    assert.ok(saved.file.includes('health_config'), '应写入 health_config 目录: ' + saved.file);
    const read = HealthConfig.readHealthConfig(tmpPack, 'itest', 'itest_mob');
    assert.ok(read, '应能读回');
    assert.strictEqual(read.parts.length, 4);
    const head = read.parts.find((p) => p.partName === 'head');
    assert.strictEqual(head.isFatal, true);
    assert.strictEqual(head.units[0].boneName, 'head');
});

test('6. 文件结构符合主 mod 加载约定', () => {
    // 主 mod EntityBoneHealthConfig 从 assets/<modid>/health_config/<entity>.json 读取
    const expected = path.join(tmpPack, 'assets', 'itest', 'health_config', 'itest_mob.json');
    assert.ok(fs.existsSync(expected), `应为 ${expected}`);
});

test('7. 删除部位配置 + 删除实体', () => {
    const d1 = HealthConfig.deleteHealthConfig(tmpPack, 'itest', 'itest_mob');
    assert.strictEqual(d1.deleted, true);
    const d2 = EntitySchema.deleteEntity(tmpPack, 'itest_mob');
    assert.strictEqual(d2.deleted, 'itest_mob');
    assert.ok(!fs.existsSync(path.join(tmpPack, 'entities', 'itest_mob.json')));
});

// 清理临时包
fs.rmSync(tmpPack, { recursive: true, force: true });

console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
if (failed > 0) process.exit(1);
