// sparkScaffold.test.js - W3.1 新建模型脚手架（路径约定 / 契约渲染 / 人形模板）
'use strict';
const assert = require('assert');
const path = require('path');
const S = require('../core/sparkScaffold');
const M = require('../core/model');
const Codec = require('../core/sparkCodec');

const Z3 = path.resolve(__dirname, '..', '..', '..', '..', 'contentpacks-source', 'modular-zombie-3');
const Z3_MODEL = path.join(Z3, 'spark_models', 'entity', 'zombie3pack', 'modular_zombie_3.json');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

console.log('=== sparkScaffold（W3.1 新建模型脚手架） ===');

test('parseIdentifier：ns:name / 纯名 / 非法 ns / 非法名', () => {
    assert.deepStrictEqual(S.parseIdentifier('zombie3pack:modular_zombie_3'), { ok: true, ns: 'zombie3pack', name: 'modular_zombie_3' });
    assert.deepStrictEqual(S.parseIdentifier('new_model'), { ok: true, ns: null, name: 'new_model' });
    assert.strictEqual(S.parseIdentifier('Bad-Ns:x').ok, false);
    assert.strictEqual(S.parseIdentifier('ns:bad/name').ok, false);
    assert.strictEqual(S.parseIdentifier('').ok, false);
});

test('parseModelPath：真实 zombie-3 路径', () => {
    const r = S.parseModelPath(Z3_MODEL);
    assert.strictEqual(r.ok, true, r.error);
    assert.strictEqual(r.type, 'entity');
    assert.strictEqual(r.ns, 'zombie3pack');
    assert.strictEqual(r.name, 'modular_zombie_3');
    assert.strictEqual(r.packName, 'modular-zombie-3');
    assert.strictEqual(r.packRel, Z3.split(path.sep).join('/'), 'packRel = spark_models 前的完整前缀（/ 规范形）');
});

test('parseModelPath：反斜杠路径（Windows）', () => {
    const win = Z3_MODEL.split(path.sep).join('\\');
    const r = S.parseModelPath(win);
    assert.strictEqual(r.ok, true, r.error);
    assert.strictEqual(r.ns, 'zombie3pack');
});

test('parseModelPath：违例路径全部报错', () => {
    assert.strictEqual(S.parseModelPath(path.join(Z3, 'foo.json')).ok, false, '缺 spark_models');
    assert.strictEqual(S.parseModelPath(path.join(Z3, 'spark_models', 'block', 'ns', 'a.json')).ok, false, 'type 非 entity/item');
    assert.strictEqual(S.parseModelPath(path.join(Z3, 'spark_models', 'entity', 'ns.txt')).ok, false, '段数不足');
    assert.strictEqual(S.parseModelPath(path.join(Z3, 'spark_models', 'entity', 'ns', 'Bad Name.txt')).ok, false, '非 .json');
    assert.strictEqual(S.parseModelPath('').ok, false, '空路径');
});

test('scaffoldPlan：动画目录 / 贴图建议 / 契约路径', () => {
    const r = S.scaffoldPlan(Z3_MODEL);
    assert.strictEqual(r.ok, true, r.error);
    const d = r.data;
    assert.strictEqual(d.animDir, path.join(Z3, 'spark_animations', 'entity', 'zombie3pack', 'modular_zombie_3') + path.sep);
    assert.strictEqual(d.texturePath, path.join(Z3, 'assets', 'zombie3pack', 'textures', 'entity', 'modular_zombie_3.png'));
    assert.strictEqual(d.contract.path, path.join(Z3, 'editor', 'animation-contract.json'));
    // 契约已存在 → 不生成内容
    const exists = S.scaffoldPlan(Z3_MODEL, { contractExists: true });
    assert.strictEqual(exists.data.contract.create, false);
    assert.strictEqual(exists.data.contract.content, null);
});

test('renderContract：最小契约结构（与 zombie-3 实装同形）', () => {
    const text = S.renderContract({ ns: 'zombie3pack', name: 'modular_zombie_3', type: 'entity', label: 'Modular Zombie 3' });
    const obj = JSON.parse(text);
    assert.strictEqual(obj.contract_version, 1);
    assert.strictEqual(obj.content_type, 'spark_animation_set');
    assert.strictEqual(obj.sets.length, 1);
    const set = obj.sets[0];
    assert.strictEqual(set.model.path, 'spark_models/entity/zombie3pack/modular_zombie_3.json');
    assert.strictEqual(set.model.texture, 'assets/zombie3pack/textures/entity/modular_zombie_3.png');
    assert.strictEqual(set.animations.dir, 'spark_animations/entity/zombie3pack/modular_zombie_3/');
    assert.deepStrictEqual(
        set.animations.required.map((r) => r.name),
        ['still', 'walking', 'running', 'attack', 'alert', 'hit_front', 'hit_back', 'aggressive'],
    );
    assert.strictEqual(text.endsWith('\n'), true);
});

test('buildHumanoidTemplate：7 骨 + hitbox，通过 validateGeometry，round-trip 无损', () => {
    const bones = S.buildHumanoidTemplate();
    assert.strictEqual(bones.length, 7);
    assert.deepStrictEqual(bones.map((b) => b.name),
        ['body', 'left_arm', 'right_arm', 'left_leg', 'right_leg', 'head', 'headwear']);
    assert.ok(bones.every((b) => (b.cubes || []).length === 0), '模板无 cube');
    const geo = M.createGeometry({ identifier: 't:tpl', textureWidth: 64, textureHeight: 64, bones });
    const { errors } = M.validateGeometry(geo);
    assert.deepStrictEqual(errors, []);
    // round-trip：import(export) deep-equal
    const rt = Codec.importModel(Codec.exportModel(geo)).data;
    assert.deepStrictEqual(rt, geo);
    // hitbox locator 齐全
    const body = geo.bones.find((b) => b.name === 'body');
    assert.deepStrictEqual(body.locators[0].offset, [0, 18, 0]);
});

test('STANDARD_STATES：8 状态（loop 语义与 zombie-3 一致）', () => {
    assert.strictEqual(S.STANDARD_STATES.length, 8);
    const byName = Object.fromEntries(S.STANDARD_STATES.map((s) => [s.name, s.loop]));
    assert.strictEqual(byName.attack, 'hold_on_last_frame');
    assert.strictEqual(byName.still, 'true');
});

test('setContractTexture：已有 set（对象形 model）→ 写 model.texture', () => {
    const c = { contract_version: 1, sets: [{ id: 'z', label: 'z', model: { path: 'spark_models/entity/zombie3pack/modular_zombie_3.json', texture: 'old.png' }, animations: { dir: 'd', required: [] } }] };
    const r = S.setContractTexture(c, 'spark_models/entity/zombie3pack/modular_zombie_3.json', 'assets/x/new.png');
    assert.strictEqual(r.ok, true);
    assert.strictEqual(r.data.changed, true);
    assert.strictEqual(r.data.created, false);
    assert.strictEqual(c.sets[0].model.texture, 'assets/x/new.png');
});

test('setContractTexture：字符串形 model → 升级为 {path,texture}', () => {
    const c = { sets: [{ id: 's', model: 'spark_models/item/catoxidesbattlerebuild/sword.json', animations: {} }] };
    const r = S.setContractTexture(c, 'spark_models/item/catoxidesbattlerebuild/sword.json', 't/sword.png');
    assert.strictEqual(r.ok, true);
    assert.deepStrictEqual(c.sets[0].model, { path: 'spark_models/item/catoxidesbattlerebuild/sword.json', texture: 't/sword.png' });
});

test('setContractTexture：未找到 → 新增 set（标准 8 状态 + 路径推导）', () => {
    const c = { sets: [] };
    const r = S.setContractTexture(c, 'spark_models/entity/zombie3pack/new_zombie.json', 'assets/zombie3pack/textures/entity/new_zombie.png');
    assert.strictEqual(r.ok, true);
    assert.strictEqual(r.data.created, true);
    const s = c.sets[0];
    assert.strictEqual(s.id, 'new_zombie');
    assert.deepStrictEqual(s.model, { path: 'spark_models/entity/zombie3pack/new_zombie.json', texture: 'assets/zombie3pack/textures/entity/new_zombie.png' });
    assert.strictEqual(s.animations.dir, 'spark_animations/entity/zombie3pack/new_zombie/');
    assert.strictEqual(s.animations.required.length, 8);
});

test('setContractTexture：反斜杠归一 + 非法路径/契约拒绝', () => {
    const c = { sets: [{ id: 'a', model: { path: 'spark_models\\entity\\ns\\m.json', texture: 'x' }, animations: {} }] };
    assert.strictEqual(S.setContractTexture(c, 'spark_models/entity/ns/m.json', 'y').ok, true);
    assert.strictEqual(c.sets[0].model.texture, 'y');
    assert.strictEqual(S.setContractTexture({ sets: [] }, 'no_spark_models/x.json', 'y').ok, false);
    assert.strictEqual(S.setContractTexture(null, 'a', 'b').ok, false);
    assert.strictEqual(S.setContractTexture({ sets: [] }, 'a', null).ok, false);
});

test('renderContract：texture 参数覆盖约定路径', () => {
    const j = JSON.parse(S.renderContract({ ns: 'ns', name: 'm', type: 'entity', label: 'M', texture: 'assets/custom/tex.png' }));
    assert.strictEqual(j.sets[0].model.texture, 'assets/custom/tex.png');
    const j2 = JSON.parse(S.renderContract({ ns: 'ns', name: 'm', type: 'entity' }));
    assert.strictEqual(j2.sets[0].model.texture, 'assets/ns/textures/entity/m.png');
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
