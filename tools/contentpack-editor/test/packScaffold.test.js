// packScaffold.test.js - 包骨架与预检规则单测（node 直接运行，不依赖 Electron）
// 规则来源是 core 的真实行为，所以这里断言的是「必须被拦下的错误」而不是编辑器偏好。
const assert = require('assert');
const Scaffold = require('../src/core/packScaffold');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}
const codes = (findings) => findings.map((f) => f.code);
const has = (findings, code) => codes(findings).includes(code);

console.log('=== 1. MANIFEST 规范化 ===');
test('属性按规范顺序排列，Module-Type 自动补全', () => {
    const m = Scaffold.canonicalizeManifest({
        'Module-Entry': 'example.contentpack.x.XPack',
        'Module-Name': 'x-pack',
        'Module-Type': 'contentpack',
        'Module-Dependencies': 'a, b',
    });
    assert.deepStrictEqual(Object.keys(m), ['Module-Name', 'Module-Type', 'Module-Entry', 'Module-Dependencies']);
});
test('空值属性被丢弃', () => {
    const m = Scaffold.canonicalizeManifest({ 'Module-Name': 'x', 'Module-Entry': '  ', 'Module-Version': '' });
    assert.deepStrictEqual(Object.keys(m), ['Module-Name', 'Module-Type']);
});
test('未识别的自定义属性排到最后', () => {
    const m = Scaffold.canonicalizeManifest({ 'X-Custom': '1', 'Module-Name': 'x' });
    assert.deepStrictEqual(Object.keys(m), ['Module-Name', 'Module-Type', 'X-Custom']);
});

console.log('=== 2. build.gradle 骨架 ===');
test('无额外依赖时给出 5 行样板 + 共享脚本引用', () => {
    const g = Scaffold.renderBuildGradle('my-pack');
    assert.ok(g.includes("id 'java-library'"));
    assert.ok(g.includes("id 'net.neoforged.gradle.userdev'"));
    assert.ok(g.includes("apply from: '../contentpack.gradle'"));
    assert.ok(g.includes('无额外编译依赖'));
});
test('额外依赖原样写入 dependencies 块', () => {
    const g = Scaffold.renderBuildGradle('sable-pack', {
        extraDeps: ['compileOnly "dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.3"', "compileOnly rootProject.files('libs/spark-core.jar')"],
    });
    assert.ok(g.includes('dependencies {'));
    assert.ok(g.includes('sable-neoforge-1.21.1:2.0.3'));
    assert.ok(g.includes('spark-core.jar'));
});

console.log('=== 3. 入口类骨架 ===');
test('entryLocation 解析包/类/路径', () => {
    const loc = Scaffold.entryLocation('example.contentpack.my.MyPack');
    assert.strictEqual(loc.pkg, 'example.contentpack.my');
    assert.strictEqual(loc.cls, 'MyPack');
    assert.strictEqual(loc.path, 'java/example/contentpack/my/MyPack.java');
});
test('entryLocation 拒绝非法输入', () => {
    for (const bad of ['', 'MyPack', 'a..B', 'a.1Bad', 'a.b.C-D']) {
        assert.strictEqual(Scaffold.entryLocation(bad), null, `应拒绝: ${bad}`);
    }
});
test('有实体时生成数据驱动注册代码（否则 JSON 是死数据）', () => {
    const src = Scaffold.renderEntryClass({
        moduleEntry: 'example.contentpack.my.MyPack', packId: 'my_pack', displayName: 'My', version: '1.2.3',
        entityIds: ['alpha', 'beta'],
    });
    assert.ok(src.includes('package example.contentpack.my;'));
    assert.ok(src.includes('public class MyPack implements ContentPack'));
    assert.ok(src.includes('registerDataDriven(context, "alpha");'));
    assert.ok(src.includes('registerDataDriven(context, "beta");'));
    assert.ok(src.includes('var holder = context.registerDataDrivenMob(definition);'));
    assert.ok(src.includes('holder.getKey().location()'));
    assert.ok(src.includes('return "my_pack";'));
});
test('无实体时不生成数据驱动代码', () => {
    const src = Scaffold.renderEntryClass({ moduleEntry: 'example.contentpack.my.MyPack', entityIds: [] });
    // 允许 TODO 注释里提到 API，但不得有实际 import 与私有注册方法
    assert.ok(!src.includes('private void registerDataDriven'));
    assert.ok(!src.includes('import com.catoxide.catoxidesbattlerebuild.core.mob.'));
    assert.ok(src.includes('public void register(ContentPackContext context)'));
});
test('实体 id 非法时不会被写进骨架（避免生成编译不过的 Java）', () => {
    const src = Scaffold.renderEntryClass({ moduleEntry: 'a.b.C', entityIds: ['OK-upper', 'bad id'] });
    assert.ok(!src.includes('registerDataDriven(context, "OK-upper")'));
});

console.log('=== 4. MANIFEST 预检 ===');
test('合法 MANIFEST 无 error', () => {
    const f = Scaffold.validateManifest({
        'Module-Name': 'my-pack', 'Module-Version': '1.0.0', 'Module-Requires': 'catoxidesbattlerebuild>=0.2.0',
        'Module-Type': 'contentpack', 'Module-Entry': 'example.contentpack.my.MyPack',
    });
    assert.strictEqual(f.filter((x) => x.level === 'error').length, 0);
});
test('Module-Name 空 → error', () => {
    assert.ok(has(Scaffold.validateManifest({ 'Module-Type': 'contentpack' }), 'E-NAME-EMPTY'));
});
test('Module-Name 带空格 → error', () => {
    assert.ok(has(Scaffold.validateManifest({ 'Module-Name': 'my pack' }), 'E-NAME-CHARSET'));
});
test('Module-Type 写错 → error', () => {
    assert.ok(has(Scaffold.validateManifest({ 'Module-Name': 'x', 'Module-Type': 'mod' }), 'E-TYPE-VALUE'));
});
test('入口类落在 core 的 parent-first 前缀下 → error', () => {
    const f = Scaffold.validateManifest({ 'Module-Name': 'x', 'Module-Type': 'contentpack', 'Module-Entry': 'com.catoxide.catoxidesbattlerebuild.pack.A' });
    assert.ok(has(f, 'E-ENTRY-PARENTFIRST'));
});
test('包依赖带版本区间 → error（包间禁止版本约束）', () => {
    assert.ok(has(Scaffold.validateManifest({ 'Module-Name': 'x', 'Module-Type': 'contentpack', 'Module-Dependencies': 'core>=1.0' }), 'E-DEP-VERSION-RANGE'));
});
test('包依赖引用自己 → error', () => {
    assert.ok(has(Scaffold.validateManifest({ 'Module-Name': 'x', 'Module-Type': 'contentpack', 'Module-Dependencies': 'x' }), 'E-DEP-SELF'));
});
test('同一依赖既硬又软 → warn', () => {
    const f = Scaffold.validateManifest({ 'Module-Name': 'x', 'Module-Type': 'contentpack', 'Module-Dependencies': 'a', 'Module-Dependencies-Optional': 'a' });
    assert.ok(has(f, 'W-DEP-BOTH'));
});
test('Module-Lib-Requires 非空 → 提示需要 _libs jar', () => {
    assert.ok(has(Scaffold.validateManifest({ 'Module-Name': 'x', 'Module-Type': 'contentpack', 'Module-Lib-Requires': 'spark' }), 'W-LIB-NEED-JARS'));
});

console.log('=== 5. 实体预检（镜像 MobDefinitionParser）===');
const goodMob = {
    id: 'alpha_zombie', size: { width: 0.6, height: 1.95 },
    attributes: { maxHealth: 20, attackDamage: 3 },
    model: { type: 'entity', id: 'zombie3pack:alpha_zombie' },
    sounds: { ambient: 'minecraft:entity.zombie.ambient' },
    animations: { 0: 'still', 1: 'walking' },
    behaviors: [{ type: 'attack', damage: 3 }, { type: 'ai', goals: ['hunt_player'] }],
};
test('合法实体无 error/warn', () => {
    const f = Scaffold.validateEntities([goodMob]);
    assert.deepStrictEqual(f, []);
});
test('缺 id → error', () => {
    assert.ok(has(Scaffold.validateEntities([{ size: { width: 1, height: 1 } }]), 'E-MOB-ID-EMPTY'));
});
test('id 含大写 → error（ResourceLocation 只允许小写）', () => {
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { id: 'AlphaZombie' })]), 'E-MOB-ID-CHARSET'));
});
test('id 重复 → error（entities/<id>.json 会互相覆盖）', () => {
    assert.ok(has(Scaffold.validateEntities([goodMob, Object.assign({}, goodMob)]), 'E-MOB-ID-DUP'));
});
test('数值字段为 NaN → error（JSON 会写成 null，解析器抛异常）', () => {
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { size: { width: NaN, height: 1 } })]), 'E-MOB-NUM'));
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { attributes: { maxHealth: null } })]), 'E-MOB-NUM'));
});
test('animations 键非整数 → error', () => {
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { animations: { walking: 'walking' } })]), 'E-MOB-ANIM-KEY'));
});
test('namespace 非法 → error', () => {
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { namespace: 'Bad NS' })]), 'E-MOB-NS'));
});
test('model.id / texture / sounds 非法 → error', () => {
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { model: { type: 'entity', id: 'Bad Id' } })]), 'E-MOB-MODEL-ID'));
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { texture: 'a b:c.png' })]), 'E-MOB-TEXTURE'));
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { sounds: { hurt: 'bad sound' } })]), 'E-MOB-SOUND'));
});
test('behavior 无 type → warn（被静默丢弃）', () => {
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { behaviors: [{ damage: 3 }] })]), 'W-MOB-BEH-NO-TYPE'));
});
test('behavior 类型不存在 → warn（装配时跳过）', () => {
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { behaviors: [{ type: 'teleport' }] })]), 'W-MOB-BEH-UNKNOWN'));
});
test('ai goal 未知 → warn', () => {
    assert.ok(has(Scaffold.validateEntities([Object.assign({}, goodMob, { behaviors: [{ type: 'ai', goals: ['fly'] }] })]), 'W-MOB-AI-GOAL'));
});

console.log('=== N. (REVIEW-M5) renderEntryClass Java 字符串转义 ===');
test('displayName/version 含 引号/反斜杠/换行 → 正确转义（此前生成编译失败的入口类）', () => {
    const src = Scaffold.renderEntryClass({
        moduleEntry: 'com.example.mypack.MyPack',
        packId: 'my_pack',
        displayName: 'Bad "Name" \\ line',
        version: '1.0\r\n2',
    });
    assert.ok(src.includes('return "Bad \\"Name\\" \\\\ line";'), (src.match(/getDisplayName[\s\S]{0,80}/) || ['?'])[0]);
    assert.ok(src.includes('return "1.0\\r\\n2";'), (src.match(/getVersion[\s\S]{0,60}/) || ['?'])[0]);
});
test('正常名字不受影响', () => {
    const src = Scaffold.renderEntryClass({
        moduleEntry: 'com.example.mypack.MyPack',
        packId: 'my_pack',
        displayName: 'My Pack',
        version: '1.0.0',
    });
    assert.ok(src.includes('return "My Pack";'));
    assert.ok(src.includes('return "1.0.0";'));
});

console.log(`\n=== 结果: ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
