// editorContract.test.js - 编辑器契约（Module-Editor-Schema）单元测试
// 用真实 melee-combat-pack 契约 + 临时合成包验证：解析/加载/盘点/校验
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const os = require('os');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const ManifestLib = require('../src/core/manifest');
const Contract = require('../src/core/editorContract');

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

const MELEE = path.join(ROOT, 'contentpacks-source', 'melee-combat-pack');

console.log('=== 1. 真实包：melee-combat-pack 契约 ===');
test('MANIFEST 声明 Module-Editor-Schema', () => {
    const m = ManifestLib.readManifest(MELEE);
    assert.strictEqual(m['Module-Editor-Schema'], 'editor/animation-contract.json');
});

const report = Contract.buildReport(MELEE, ManifestLib.readManifest(MELEE));
test('buildReport 成功', () => {
    assert.ok(report.ok, JSON.stringify(report.errors || report.error));
    assert.strictEqual(report.data.sets.length, 2);
});
test('两个 set：sword + player_wrist', () => {
    const ids = report.data.sets.map((s) => s.id);
    assert.deepStrictEqual(ids, ['sword', 'player_wrist']);
});
test('sword set：8 必备动画全部存在', () => {
    const s = report.data.sets[0];
    assert.strictEqual(s.animations.length, 8);
    assert.ok(s.animations.every((a) => a.exists), s.animations.filter((a) => !a.exists).map((a) => a.file).join(','));
    assert.ok(s.model.exists, 'sword.json 应存在');
    assert.strictEqual(s.model.textureExists, true);
});
test('player_wrist set：7 文件全部存在', () => {
    const s = report.data.sets[1];
    assert.strictEqual(s.animations.length, 7);
    assert.ok(s.animations.every((a) => a.exists));
    assert.ok(s.model.exists && s.model.textureExists === true);
});
test('base_state bundle：20 个状态动画全部在文件内', () => {
    const s = report.data.sets[1];
    const base = s.animations.find((a) => a.name === 'base_state');
    assert.strictEqual(base.bundle.length, 20);
    assert.deepStrictEqual(base.missingBundled, []);
    assert.strictEqual(base.animations.length, 20);
});
test('真实内容无错误', () => {
    assert.deepStrictEqual(report.data.errors, [], report.data.errors.join('; '));
});
test('警告仅含已知发现：sword×8 引用骨骼 left、wrist 3rd_still/3rd_attack 引用 rightForeArm（Spark 容错，游戏可播）', () => {
    for (const w of report.data.warnings) {
        assert.ok(w.includes('left') || w.includes('rightForeArm'), '非预期警告: ' + w);
    }
    assert.strictEqual(report.data.warnings.length, 10, report.data.warnings.join('; '));
});
test('modelBoneNames 支持数组格式（1.19+）与对象映射格式', () => {
    const swordModel = Contract.modelBoneNames(path.join(MELEE, 'spark_models/item/catoxidesbattlerebuild/sword.json'));
    assert.deepStrictEqual(swordModel, ['hands', 'right', 'main', 'shade', 'blade', 'handle']);
    const wristModel = Contract.modelBoneNames(path.join(MELEE, 'spark_models/entity/catoxidesbattlerebuild/player_wrist.json'));
    assert.ok(wristModel.includes('rightArm') && wristModel.includes('main'), wristModel.join(','));
    // zombie_3 为对象映射格式
    const zModel = Contract.modelBoneNames(path.join(ROOT, 'contentpacks-source', 'modular-zombie-3', 'spark_models', 'entity', 'zombie3pack', 'modular_zombie_3.json'));
    assert.ok(zModel.includes('head') && zModel.includes('left_leg'), zModel.join(','));
});
test('骨骼覆盖：wrist 3rd_still/3rd_attack 缺 rightForeArm；sword 动画仅缺 left', () => {
    const wristModel = Contract.modelBoneNames(path.join(MELEE, 'spark_models/entity/catoxidesbattlerebuild/player_wrist.json'));
    for (const a of report.data.sets[1].animations) {
        const unknown = a.bones.filter((b) => !wristModel.includes(b));
        const expected = (a.name === '3rd_still' || a.name === '3rd_attack') ? ['rightForeArm'] : [];
        assert.deepStrictEqual(unknown, expected, `wrist 动画 ${a.name} 未知骨骼: ${unknown}`);
    }
    const swordModel = Contract.modelBoneNames(path.join(MELEE, 'spark_models/item/catoxidesbattlerebuild/sword.json'));
    for (const a of report.data.sets[0].animations) {
        const unknown = a.bones.filter((b) => !swordModel.includes(b));
        assert.deepStrictEqual(unknown, ['left'], `sword 动画 ${a.name} 未知骨骼: ${unknown}`);
    }
});

console.log('=== 2. parseContract 结构校验 ===');
function validContract() {
    return {
        contract_version: 1,
        content_type: 'spark_animation_set',
        sets: [{
            id: 'm',
            model: { path: 'spark_models/item/ns/m.json' },
            animations: { dir: 'spark_animations/item/ns/m/', required: [{ name: 'still', loop: 'true' }] },
        }],
    };
}
test('合法契约通过', () => {
    assert.ok(Contract.parseContract(validContract()).ok);
});
test('未知 contract_version 报错', () => {
    const c = validContract(); c.contract_version = 2;
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('contract_version')));
});
test('未知 content_type 报错', () => {
    const c = validContract(); c.content_type = 'nope';
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('content_type')));
});
test('sets 为空报错', () => {
    const c = validContract(); c.sets = [];
    assert.ok(!Contract.parseContract(c).ok);
});
test('重复 set.id 报错', () => {
    const c = validContract(); c.sets.push(JSON.parse(JSON.stringify(c.sets[0])));
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('重复')));
});
test('dir 不以 / 结尾报错', () => {
    const c = validContract(); c.sets[0].animations.dir = 'spark_animations/item/ns/m';
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('结尾')));
});
test('loop 非法值报错', () => {
    const c = validContract(); c.sets[0].animations.required[0].loop = 'loop';
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('loop')));
});
test('name 带路径报错', () => {
    const c = validContract(); c.sets[0].animations.required[0].name = 'a/b';
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('name')));
});
test('max_duration 非正数报错', () => {
    const c = validContract(); c.validation = { max_duration: -1 };
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('max_duration')));
});

console.log('=== 3. loadContract 边界 ===');
test('无契约的包 → no-contract（合法状态）', () => {
    const m = ManifestLib.readManifest(path.join(ROOT, 'contentpacks-source', 'test-mob-pack'));
    const r = Contract.loadContract(path.join(ROOT, 'contentpacks-source', 'test-mob-pack'), m);
    assert.ok(!r.ok && r.error === 'no-contract');
});
test('契约路径越界被拒绝', () => {
    const r = Contract.loadContract(MELEE, { 'Module-Editor-Schema': '../../build.gradle' });
    assert.ok(!r.ok && /越界/.test(r.error));
});
test('契约文件不存在报错', () => {
    const r = Contract.loadContract(MELEE, { 'Module-Editor-Schema': 'editor/nope.json' });
    assert.ok(!r.ok && /不存在/.test(r.error));
});

console.log('=== 4. 合成包：资产缺失/不匹配 ===');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'contract-test-'));
try {
    const packDir = path.join(tmp, 'fake-pack');
    const animDir = path.join(packDir, 'spark_animations/item/ns/m/');
    fs.mkdirSync(animDir, { recursive: true });
    fs.mkdirSync(path.join(packDir, 'spark_models/item/ns'), { recursive: true });
    // 模型：骨骼 a b
    fs.writeFileSync(path.join(packDir, 'spark_models/item/ns/m.json'), JSON.stringify({
        format_version: '1.12.0',
        'minecraft:geometry': [{ bones: { a: {}, b: {} } }],
    }));
    // 动画：still（loop once 与契约 true 不符）+ 超长 9s + 引用未知骨骼 zz
    fs.writeFileSync(path.join(animDir, 'still.json'), JSON.stringify({
        animations: {
            still: {
                loop: 'once',
                animation_length: 9,
                bones: { a: { rotation: { '0.0': { post: [0, 0, 0] } } }, zz: { rotation: { '0.0': { post: [0, 0, 0] } } } },
            },
        },
    }));
    // bundle 文件：缺 state.x
    fs.writeFileSync(path.join(animDir, 'base.json'), JSON.stringify({
        animations: { 'state.y': { loop: 'true', animation_length: 1, bones: { a: {} } } },
    }));
    // 契约
    const contract = {
        contract_version: 1,
        content_type: 'spark_animation_set',
        sets: [{
            id: 'm',
            model: { path: 'spark_models/item/ns/m.json', texture: 'assets/ns/t.png' },
            animations: {
                dir: 'spark_animations/item/ns/m/',
                required: [
                    { name: 'still', loop: 'true' },
                    { name: 'missing_anim' },
                    { name: 'base', bundle: ['state.x', 'state.y'] },
                ],
            },
        }],
        validation: { bones_must_exist_in_model: true, max_duration: 5.0 },
    };
    const contractFile = path.join(packDir, 'editor/anim-contract.json');
    fs.mkdirSync(path.dirname(contractFile), { recursive: true });
    fs.writeFileSync(contractFile, JSON.stringify(contract));

    const r = Contract.buildReport(packDir, { 'Module-Editor-Schema': 'editor/anim-contract.json' });
    test('合成包 buildReport 成功（问题都在 errors/warnings 里）', () => {
        assert.ok(r.ok, JSON.stringify(r.errors || r.error));
    });
    const set = r.data.sets[0];
    test('缺失动画 → error', () => {
        assert.ok(r.data.errors.some((e) => e.includes('missing_anim')));
    });
    test('loop 不符 → warning', () => {
        assert.ok(r.data.warnings.some((e) => e.includes('loop 与契约不符')));
    });
    test('超长动画 → warning（9s > 5.0s）', () => {
        assert.ok(r.data.warnings.some((e) => e.includes('超长')));
    });
    test('未知骨骼 → warning（Spark 容错，不阻断）', () => {
        assert.ok(r.data.warnings.some((e) => e.includes('zz')));
        assert.ok(!r.data.errors.some((e) => e.includes('zz')));
    });
    test('bundle 缺动画 → error', () => {
        assert.ok(r.data.errors.some((e) => e.includes('state.x')));
    });
    test('贴图缺失 → warning', () => {
        assert.ok(r.data.warnings.some((e) => e.includes('贴图缺失')));
    });
    test('set 级与汇总级错误一致', () => {
        assert.strictEqual(set.errors.length, r.data.errors.length);
    });
} finally {
    fs.rmSync(tmp, { recursive: true, force: true });
}

console.log('=== N. (REVIEW-S6) 契约越界路径按"不存在"处理 ===');
{
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cpe-con-'));
    const packDir = path.join(tmp, 'pack');
    fs.mkdirSync(packDir, { recursive: true });
    // 包外放一个"若越界读取就会被读到"的动画文件
    fs.writeFileSync(path.join(tmp, 'secret_anim.json'),
        JSON.stringify({ animations: { still: { loop: 'true', animation_length: 1, bones: { secret_bone: {} } } } }));
    const contract = {
        contract_version: 1,
        content_type: 'spark_animation_set',
        sets: [{
            id: 's1',
            label: 's1',
            model: { path: '../../secret_anim.json', texture: null },
            animations: { dir: '../../', required: [{ name: 'still', loop: 'true' }] },
        }],
        validation: { bones_must_exist_in_model: false },
    };
    test('越界 model.path / animations.dir → exists=false，不回显包外文件内容', () => {
        const sets = Contract.listContractAssets(packDir, contract);
        assert.strictEqual(sets.length, 1);
        assert.strictEqual(sets[0].model.exists, false, '越界 model.path 必须按不存在处理');
        assert.strictEqual(sets[0].animations[0].exists, false, '越界 animations.dir 必须按不存在处理');
        assert.strictEqual(sets[0].animations[0].duration, null, '不得读包外文件回显时长');
        assert.deepStrictEqual(sets[0].animations[0].bones, []);
    });
    test('包内合法路径不受影响（对照）', () => {
        fs.mkdirSync(path.join(packDir, 'spark_models', 'entity', 'ns'), { recursive: true });
        fs.mkdirSync(path.join(packDir, 'spark_animations', 'entity', 'ns', 'm1'), { recursive: true });
        fs.writeFileSync(path.join(packDir, 'spark_models', 'entity', 'ns', 'm1.json'),
            JSON.stringify({ 'minecraft:geometry': [{ bones: [{ name: 'b1' }] }] }));
        fs.writeFileSync(path.join(packDir, 'spark_animations', 'entity', 'ns', 'm1', 'still.json'),
            JSON.stringify({ animations: { still: { loop: 'true', animation_length: 2.5, bones: { b1: {} } } } }));
        const good = {
            contract_version: 1,
            content_type: 'spark_animation_set',
            sets: [{
                id: 'g1',
                label: 'g1',
                model: { path: 'spark_models/entity/ns/m1.json', texture: null },
                animations: { dir: 'spark_animations/entity/ns/m1/', required: [{ name: 'still', loop: 'true' }] },
            }],
            validation: { bones_must_exist_in_model: false },
        };
        const sets = Contract.listContractAssets(packDir, good);
        assert.strictEqual(sets[0].model.exists, true);
        assert.strictEqual(sets[0].animations[0].exists, true);
        assert.strictEqual(sets[0].animations[0].duration, 2.5);
    });
    fs.rmSync(tmp, { recursive: true, force: true });
}

console.log('=== 5. (P4) TimeTape 状态机契约：真实包 modular-zombie-4 ===');
const Z4 = path.join(ROOT, 'contentpacks-source', 'modular-zombie-4');
const z4report = Contract.buildReport(Z4, ManifestLib.readManifest(Z4));
test('zombie-4 buildReport 成功', () => {
    assert.ok(z4report.ok, JSON.stringify(z4report.errors || z4report.error));
});
test('zombie-4 无错误（状态机引用完整 + 跨包资产齐全）', () => {
    assert.deepStrictEqual(z4report.data.errors, [], z4report.data.errors.join('; '));
});
test('zombie-4 状态机：body 轨 8 态（含 aggressive 站定警戒），initial=still', () => {
    const sm = z4report.data.stateMachine;
    assert.ok(sm, 'stateMachine 摘要缺失');
    assert.ok(sm.tracks.body, 'body 轨缺失');
    const names = Object.keys(sm.tracks.body.states);
    assert.strictEqual(names.length, 8, names.join(','));
    assert.ok(names.includes('aggressive'), 'aggressive 态缺失: ' + names.join(','));
    assert.strictEqual(sm.tracks.body.initial, 'still');
});
test('zombie-4 状态机：attack 纸带 0.15s→settle；hit_reaction 双动画 alt', () => {
    const sm = z4report.data.stateMachine;
    const attack = sm.tracks.body.states.attack;
    assert.deepStrictEqual(attack.segs, [{ until: 0.3, next: 'settle' }]);
    const hit = sm.tracks.body.states.hit_reaction;
    assert.ok(hit.anims.includes('hit_front') && hit.anims.includes('hit_back'), hit.anims.join(','));
});
test('zombie-4 跨包引用：模型/动画经 ref_pack=modular-zombie-3 解析存在', () => {
    const s = z4report.data.sets[0];
    assert.strictEqual(s.model.exists, true, s.model.path);
    assert.ok(s.animations.every((a) => a.exists), s.animations.filter((a) => !a.exists).map((a) => a.file).join(','));
});
test('zombie-4 无警告（骨骼覆盖经 ref_pack 模型校验，无误报）', () => {
    assert.deepStrictEqual(z4report.data.warnings, [], z4report.data.warnings.join('; '));
});

console.log('=== 6. (P4) TimeTape 状态机：合成坏数据 ===');
{
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cpe-tape-'));
    const packDir = path.join(tmp, 'pack-a');
    const sibling = path.join(tmp, 'pack-b');
    fs.mkdirSync(path.join(packDir, 'editor'), { recursive: true });
    fs.mkdirSync(path.join(packDir, 'spark_animations/item/ns/m/'), { recursive: true });
    fs.writeFileSync(path.join(packDir, 'spark_animations/item/ns/m/still.json'),
        JSON.stringify({ animations: { still: { loop: 'true', animation_length: 1, bones: {} } } }));
    fs.mkdirSync(path.join(sibling, 'spark_models/item/ns'), { recursive: true });
    fs.writeFileSync(path.join(sibling, 'spark_models/item/ns/m.json'),
        JSON.stringify({ 'minecraft:geometry': [{ bones: [{ name: 'b1' }] }] }));

    // 坏状态机：exit→不存在状态 / 引用未声明动画 / 未知动作 / 未知条件词汇（warning）
    const badMachine = {
        format_version: '1.0.0',
        tracks: {
            body: {
                initial: 'still',
                states: {
                    still: {
                        anim: { group: 1, name: 'still', loop: true },
                        exit: [{ when: 'is_floating', to: 'ghost' }],
                    },
                    attack: {
                        segments: [{
                            until: 0.5,
                            anim: { group: 1, name: 'nope' },
                            milestones: [{ at: 0.2, do: 'explode' }],
                        }],
                    },
                },
            },
        },
    };
    const machineFile = path.join(packDir, 'data/ns/state_machines/m.json');
    fs.mkdirSync(path.dirname(machineFile), { recursive: true });
    fs.writeFileSync(machineFile, JSON.stringify(badMachine));
    const contract = {
        contract_version: 1,
        content_type: 'spark_animation_set',
        sets: [{
            id: 'm',
            model: { ref_pack: 'pack-b', path: 'spark_models/item/ns/m.json' },
            animations: {
                dir: 'spark_animations/item/ns/m/',
                required: [{ name: 'still', loop: 'true' }],
            },
        }],
        wiring: { state_machine: { type: 'timetape', path: 'data/ns/state_machines/m.json' } },
    };
    const contractFile = path.join(packDir, 'editor/animation-contract.json');
    fs.writeFileSync(contractFile, JSON.stringify(contract));

    const r = Contract.buildReport(packDir, { 'Module-Editor-Schema': 'editor/animation-contract.json' });
    test('合成坏包 buildReport 成功（问题在 errors/warnings）', () => {
        assert.ok(r.ok, JSON.stringify(r.errors || r.error));
    });
    test('ref_pack 兄弟包模型解析存在', () => {
        assert.strictEqual(r.data.sets[0].model.exists, true);
    });
    test('exit.to 不存在状态 → error', () => {
        assert.ok(r.data.errors.some((e) => e.includes('ghost')), r.data.errors.join('; '));
    });
    test('引用未声明动画 → error', () => {
        assert.ok(r.data.errors.some((e) => e.includes('nope')));
    });
    test('未知里程碑动作 → error', () => {
        assert.ok(r.data.errors.some((e) => e.includes('explode')));
    });
    test('未知条件词汇 → warning（不阻断）', () => {
        assert.ok(r.data.warnings.some((e) => e.includes('is_floating')));
        assert.ok(!r.data.errors.some((e) => e.includes('is_floating')));
    });
    test('状态机摘要可展示（轨/态/纸带/转移）', () => {
        const sm = r.data.stateMachine;
        assert.ok(sm && sm.tracks.body.states.attack.segs.length === 1);
    });
    fs.rmSync(tmp, { recursive: true, force: true });
}

console.log('=== 7. (P4) parseContract：state_machine 类型校验 ===');
test('timetape 缺 path → 报错', () => {
    const c = validContract();
    c.wiring = { state_machine: { type: 'timetape' } };
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('path')));
});
test('未知 state_machine 类型 → 报错', () => {
    const c = validContract();
    c.wiring = { state_machine: { type: 'xstate' } };
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('state_machine.type')));
});
test('java_state_machine（zombie-3 旧形态）仍合法', () => {
    const c = validContract();
    c.wiring = { state_machine: { type: 'java_state_machine', states: [] } };
    assert.ok(Contract.parseContract(c).ok);
});
test('ref_pack 非字符串 → 报错', () => {
    const c = validContract();
    c.sets[0].model.ref_pack = 42;
    const r = Contract.parseContract(c);
    assert.ok(!r.ok && r.errors.some((e) => e.includes('ref_pack')));
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
