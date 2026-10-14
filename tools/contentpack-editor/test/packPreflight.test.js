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
for (const name of ['durability-pack', 'test-mob-pack', 'quest-test-pack', 'sable-base-pack', 'modular-zombie-3']) {
    test(`${name} 预检无 error`, () => {
        const r = Preflight.preflightPack(path.join(PACKS_SOURCE, name), { root: ROOT });
        assert.strictEqual(r.errors.length, 0, `意外的 error: ${JSON.stringify(r.errors, null, 2)}`);
    });
}
test('structure-example-pack（纯数据包）预检无 error', () => {
    const r = Preflight.preflightPack(path.join(PACKS_SOURCE, 'structure-example-pack'), { root: ROOT });
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
