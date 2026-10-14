// core.test.js - core 库单元测试（node 直接运行，不依赖 Electron）
// 用真实项目文件验证：MANIFEST / spark 模型 / 实体 JSON / health_config 的读写与兼容性
const assert = require('assert');
const path = require('path');
const fs = require('fs');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const ManifestLib = require('../src/core/manifest');
const ModelParser = require('../src/core/modelParser');
const EntitySchema = require('../src/core/entitySchema');
const HealthConfig = require('../src/core/healthConfig');
const StateMachine = require('../src/core/stateMachine');

let passed = 0, failed = 0;
function test(name, fn) {
    try {
        fn();
        passed++;
        console.log(`  ✓ ${name}`);
    } catch (e) {
        failed++;
        console.log(`  ✗ ${name}\n    ${e.message}`);
    }
}

console.log('=== 1. MANIFEST 读写 ===');
const testManifestDir = path.join(ROOT, 'contentpacks-source', 'test-mob-pack');
test('scanPacks 能发现 test-mob-pack', () => {
    const packs = ManifestLib.scanPacks(path.join(ROOT, 'contentpacks-source'));
    const t = packs.find((p) => p.name === 'test-mob-pack');
    assert.ok(t, 'test-mob-pack 未找到');
    assert.strictEqual(t.version, '1.0.0');
    assert.strictEqual(t.manifest['Module-Type'], 'contentpack');
});
test('readManifest 解析 Module-Entry', () => {
    const m = ManifestLib.readManifest(testManifestDir);
    assert.strictEqual(m['Module-Entry'], 'example.contentpack.testmob.TestMobPack');
    assert.strictEqual(m['Module-Requires'], 'catoxidesbattlerebuild>=0.2.0');
});
test('parse/stringify 往返一致', () => {
    const parsed = ManifestLib.parseManifest('Manifest-Version: 1.0\r\nModule-Name: x\r\nModule-Type: contentpack\r\n');
    assert.strictEqual(parsed['Module-Name'], 'x');
    const str = ManifestLib.stringifyManifest({ 'Module-Name': 'y', 'Module-Version': '0.1.0' });
    assert.ok(str.includes('Module-Name: y'));
});

console.log('=== 2. spark 模型骨骼树解析 ===');
const modelFile = path.join(ROOT, 'contentpacks-source', 'modular-zombie-3', 'spark_models', 'entity', 'zombie3pack', 'modular_zombie_3.json');
test('解析真实 spark 模型', () => {
    const model = ModelParser.parseModelFile(modelFile);
    assert.strictEqual(model.identifier, 'geometry.modular_zombie_3');
    assert.strictEqual(model.bones.length, 7);
});
test('骨骼树按 parent 构建', () => {
    const model = ModelParser.parseModelFile(modelFile);
    const root = model.tree;
    assert.strictEqual(root.length, 1);
    assert.strictEqual(root[0].name, 'body');
    const head = model.boneMap['head'];
    assert.strictEqual(head.parent, 'body');
    const headNode = model.tree[0].children.find((c) => c.name === 'head');
    assert.ok(headNode, 'head 应是 body 的子节点');
    assert.ok(headNode.children.some((c) => c.name === 'headwear'), 'headwear 应是 head 的子节点');
});
test('骨骼名称列表', () => {
    const model = ModelParser.parseModelFile(modelFile);
    const names = ModelParser.getBoneNames(model);
    assert.ok(names.includes('head'));
    assert.ok(names.includes('left_leg'));
});

console.log('=== 3. 实体定义读写 ===');
const testPackDir = path.join(ROOT, 'contentpacks-source', 'test-mob-pack');
test('读取真实实体 JSON', () => {
    const entity = EntitySchema.readEntity(testPackDir, 'modular_zombie_test');
    assert.strictEqual(entity.id, 'modular_zombie_test');
    assert.strictEqual(entity.namespace, 'testmob');
    assert.strictEqual(entity.size.width, 0.6);
    assert.strictEqual(entity.attributes.maxHealth, 20.0);
});
test('listEntities 列出实体', () => {
    const list = EntitySchema.listEntities(testPackDir);
    assert.ok(list.length >= 1);
    assert.ok(list.some((e) => e.id === 'modular_zombie_test'));
});
test('校验：缺 id 报错', () => {
    const { valid, errors } = EntitySchema.validateEntity({ size: { width: 1, height: 1 } });
    assert.strictEqual(valid, false);
    assert.ok(errors.some((e) => e.includes('id')));
});
test('校验：合法实体通过', () => {
    const e = EntitySchema.createDefaultEntity();
    e.id = 'valid_mob';
    const { valid } = EntitySchema.validateEntity(e);
    assert.strictEqual(valid, true);
});
test('默认实体包含完整字段', () => {
    const e = EntitySchema.createDefaultEntity();
    assert.ok(e.attributes.maxHealth);
    assert.ok(e.animations['0'] === 'still');
    assert.ok(Array.isArray(e.behaviors));
});

console.log('=== 4. 部位配置生成/校验 ===');
test('createDefaultHealthConfig 按骨骼名分组', () => {
    const cfg = HealthConfig.createDefaultHealthConfig(['head', 'body', 'left_arm', 'right_arm', 'left_leg', 'right_leg']);
    const parts = cfg.parts;
    const names = parts.map((p) => p.partName);
    assert.ok(names.includes('head'));
    assert.ok(names.includes('arm'));
    assert.ok(names.includes('leg'));
    const headPart = parts.find((p) => p.partName === 'head');
    assert.strictEqual(headPart.isFatal, true);
    assert.strictEqual(headPart.units.length, 1);
    assert.strictEqual(headPart.units[0].boneName, 'head');
});
test('部位配置校验：重复骨骼报错', () => {
    const cfg = {
        parts: [
            { partName: 'a', maxModuleHealth: 10, isFatal: false, transmissionToBase: 1, units: [{ boneName: 'x', transmissionCoeff: 1, armorValue: 0, antiPiercing: 0, collisionTag: 'x', specialEffects: [] }] },
            { partName: 'b', maxModuleHealth: 10, isFatal: false, transmissionToBase: 1, units: [{ boneName: 'x', transmissionCoeff: 1, armorValue: 0, antiPiercing: 0, collisionTag: 'x', specialEffects: [] }] },
        ],
    };
    const { valid, errors } = HealthConfig.validateHealthConfig(cfg);
    assert.strictEqual(valid, false);
    assert.ok(errors.some((e) => e.includes('重复')));
});
test('与主 mod EntityBoneHealthConfig 格式兼容（字段齐全）', () => {
    // 主 mod 解析器要求：parts[].partName/maxModuleHealth/isFatal/transmissionToBase/units[].boneName/transmissionCoeff/armorValue/antiPiercing/collisionTag/specialEffects/hitSound
    const cfg = HealthConfig.createDefaultHealthConfig(['head']);
    const part = cfg.parts[0];
    const unit = part.units[0];
    assert.ok('partName' in part && 'maxModuleHealth' in part && 'isFatal' in part && 'transmissionToBase' in part);
    assert.ok('boneName' in unit && 'transmissionCoeff' in unit && 'armorValue' in unit && 'antiPiercing' in unit);
    assert.ok('collisionTag' in unit && 'specialEffects' in unit && 'hitSound' in unit);
});

console.log('=== 5. 状态机模板 ===');
test('melee 模板 8 状态', () => {
    const t = StateMachine.TEMPLATES.melee;
    assert.strictEqual(t.states.length, 8);
    assert.strictEqual(t.states[0].anim, 'still');
    assert.strictEqual(t.states[3].anim, 'attack');
});
test('getDefaultAnimations 生成完整映射', () => {
    const anims = StateMachine.getDefaultAnimations('melee');
    assert.strictEqual(Object.keys(anims).length, 8);
    assert.strictEqual(anims['0'], 'still');
    const ranged = StateMachine.getDefaultAnimations('ranged');
    assert.strictEqual(ranged['3'], 'shoot');
});

console.log('=== 6. (REVIEW) 路径校验 / MANIFEST 续行 / jar 提取 ===');
const os = require('os');
test('S2: healthConfig ns/id 含 .. 或非法字符 → 拒绝/钳制（此前可写出包外任意 .json）', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cpe-hc-'));
    try {
        // 段含路径分隔符 → 字符集直接拒绝
        assert.throws(() => HealthConfig.saveHealthConfig(tmp, '../../x', 'm', { parts: [] }), /namespace/);
        assert.throws(() => HealthConfig.saveHealthConfig(tmp, 'okns', 'a/b', { parts: [] }), /实体 id/);
        assert.throws(() => HealthConfig.readHealthConfig(tmp, '../../x', 'm'), /namespace/);
        // '..' 的点号在字符集内（MC 资源位置 path 段允许 '.'）：单级 '..' 被 join 折叠进包目录（安全）；
        // 多级 '..' 必然带 '/' 分隔 → 字符集直接拒绝；join 后边界校验是最后防线
        const p1 = HealthConfig.healthConfigFilePath(tmp, '..', 'm');
        assert.ok(p1.startsWith(path.resolve(tmp) + path.sep), '单级 .. 应折叠进包目录: ' + p1);
        assert.throws(() => HealthConfig.saveHealthConfig(tmp, '../..', 'm', { parts: [] }), /namespace/);
        assert.throws(() => HealthConfig.readHealthConfig(tmp, '../..', 'm'), /namespace/);
        const r = HealthConfig.saveHealthConfig(tmp, 'myns', 'mob1', { parts: [] });
        assert.ok(fs.existsSync(path.join(tmp, 'assets', 'myns', 'health_config', 'mob1.json')));
        assert.deepStrictEqual(r, { file: path.join('assets', 'myns', 'health_config', 'mob1.json') });
        assert.strictEqual(HealthConfig.readHealthConfig(tmp, 'myns', 'mob1').parts.length, 0);
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});
test('L8: parseManifest 支持 72 字节续行（行首空格 = 上一属性值的延续）', () => {
    const m = ManifestLib.parseManifest('Module-Name: abc\r\n ' + 'defgh\r\n ' + 'ijk\r\nModule-Version: 1.0\r\n');
    // 续行去掉行首 1 个空格后拼接：abc + defgh + ijk
    assert.strictEqual(m['Module-Name'], 'abcdefghijk');
    assert.strictEqual(m['Module-Version'], '1.0');
    const m2 = ManifestLib.parseManifest('A: x\r\n \r\nB: y\r\n');
    assert.strictEqual(m2['A'], 'x');
    assert.strictEqual(m2['B'], 'y');
});

// L7（async 校验）：非法 jar 内路径在调用 jar 命令之前即拒绝（无需 JDK）
const l7Checks = [];
const l7p1 = ModelParser.parseModelFromJar('C:/fake.jar', 'spark_models/../../evil.json')
    .then(() => l7Checks.push(['L7: 含 .. 的 jar 内路径被拒', false, '未被拒绝']),
          (e) => l7Checks.push(['L7: 含 .. 的 jar 内路径被拒', /非法/.test(String(e.message)), String(e.message)]));
const l7p2 = ModelParser.parseModelFromJar('C:/fake.jar', 'other/x.json')
    .then(() => l7Checks.push(['L7: 非 spark_models 路径被拒', false, '未被拒绝']),
          (e) => l7Checks.push(['L7: 非 spark_models 路径被拒', /非法/.test(String(e.message)), String(e.message)]));
Promise.all([l7p1, l7p2]).then(() => {
    for (const [name, good, extra] of l7Checks) {
        if (good) { passed++; console.log(`  ✓ ${name}`); }
        else { failed++; console.log(`  ✗ ${name} → ${extra}`); }
    }
    console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
    if (failed > 0) process.exit(1);
});
