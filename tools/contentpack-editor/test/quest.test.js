// quest.test.js - 任务定义契约单测（schema 校验/归一化/序列化 + 与 Java 侧同步）
// 权威：core/quest/QuestDefinitionParser.java（严格解析）+ QuestBuiltinComponents.java（内置类型）
//       + goal/AbstractItemGoal.java、giver/ProximityGiver.java（字段）
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const S = require('../src/core/questSchema');

const REPO = path.join(__dirname, '..', '..', '..');
const QUEST_JAVA = path.join(REPO, 'src', 'main', 'java', 'com', 'catoxide', 'catoxidesbattlerebuild', 'core', 'quest');
const PACK_QUESTS = path.join(REPO, 'contentpacks-source', 'quest-test-pack', 'quests');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

const goodQuest = () => ({
    id: 'quest_test_present',
    title: '储备口粮',
    description: '随身携带 8 个苹果',
    givers: [{ type: 'proximity_giver', center: [0, 64, 0], radius: 10 }],
    goals: [{ type: 'item_present', item: 'minecraft:apple', count: 8, label: '苹果' }],
    conditions: [],
    failConditions: [],
    children: [],
    location: [0, 64, 0],
    priority: 1.0,
});

console.log('=== 1. 内置类型与 Java 注册表一致（防漂移） ===');
// Java 侧用 TYPE 常量注册（registerGiverType(ProximityGiver.TYPE, ...)），
// 所以既核对常量声明值，也核对注册点引用了该常量 —— 只查字符串字面量会漏。
const TYPE_DECL = {
    proximity_giver: 'giver/ProximityGiver.java',
    item_present: 'goal/ItemPresentGoal.java',
    item_submit: 'goal/ItemSubmitGoal.java',
};
test('每个内置类型：常量声明值 + 注册点引用都对得上', () => {
    const src = fs.readFileSync(path.join(QUEST_JAVA, 'QuestBuiltinComponents.java'), 'utf8');
    for (const [type, file] of Object.entries(TYPE_DECL)) {
        const cls = path.basename(file, '.java');
        const decl = fs.readFileSync(path.join(QUEST_JAVA, file), 'utf8');
        assert.ok(new RegExp(`TYPE\\s*=\\s*"${type}"`).test(decl), `${cls} 的 TYPE 常量不是 '${type}'`);
        assert.ok(src.includes(`registerGiverType(${cls}.TYPE`) || src.includes(`registerGoalType(${cls}.TYPE`),
            `QuestBuiltinComponents 未注册 ${cls}.TYPE`);
    }
    // schema 里的类型集合必须与 TYPE_DECL 完全一致（多出来/漏掉都算漂移）
    const schemaTypes = [...Object.keys(S.GIVER_TYPES), ...Object.keys(S.GOAL_TYPES)].sort();
    assert.deepStrictEqual(schemaTypes, Object.keys(TYPE_DECL).sort());
});
test('schema 字段名都出现在对应 Java 类里', () => {
    const files = {
        proximity_giver: ['giver/ProximityGiver.java'],
        item_present: ['goal/AbstractItemGoal.java'],
        item_submit: ['goal/AbstractItemGoal.java', 'goal/ItemSubmitGoal.java'],
    };
    for (const [type, list] of Object.entries(files)) {
        const spec = S.GIVER_TYPES[type] || S.GOAL_TYPES[type];
        const src = list.map((f) => fs.readFileSync(path.join(QUEST_JAVA, f), 'utf8')).join('\n');
        for (const f of spec.fields) {
            assert.ok(src.includes(`"${f.key}"`), `${type} 字段 '${f.key}' 在 Java 侧找不到`);
        }
    }
});

console.log('=== 2. 校验（镜像 QuestDefinitionParser 的严格性） ===');
test('合法任务通过且无警告', () => {
    const r = S.validateQuest(goodQuest(), { idPrefix: 'quest_test' });
    assert.ok(r.valid, r.errors.join('; '));
    assert.deepStrictEqual(r.warnings, []);
});
test('id 空/大写/带命名空间 → 失败', () => {
    const a = goodQuest(); a.id = ''; assert.ok(!S.validateQuest(a).valid);
    const b = goodQuest(); b.id = 'MyQuest'; assert.ok(!S.validateQuest(b).valid);
    const c = goodQuest(); c.id = 'mypack:q1';
    const r = S.validateQuest(c);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('B6')), '应提示 B6');
});
test('id 不以包名前缀开头 → 仅警告', () => {
    const q = goodQuest(); q.id = 'other_pack_quest';
    const r = S.validateQuest(q, { idPrefix: 'quest_test' });
    assert.ok(r.valid);
    assert.ok(r.warnings.some((w) => w.includes('撞名')));
});
test('title 缺失 → 失败', () => {
    const q = goodQuest(); q.title = '  ';
    assert.ok(!S.validateQuest(q).valid);
});
test('无 goals 也无 children → 失败（运行时发放即完成）', () => {
    const q = goodQuest(); q.goals = [];
    const r = S.validateQuest(q);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('立即完成')));
});
test('仅 children（容器任务）→ 通过', () => {
    const q = goodQuest();
    q.id = 'quest_container';
    q.goals = [];
    q.children = [Object.assign(goodQuest(), { id: 'quest_child' })];
    const r = S.validateQuest(q);
    assert.ok(r.valid, r.errors.join('; '));
});
test('goals 不是数组 → 失败', () => {
    const q = goodQuest(); q.goals = { type: 'item_present' };
    assert.ok(!S.validateQuest(q).valid);
});
test('item_present 缺 item → 失败', () => {
    const q = goodQuest(); delete q.goals[0].item;
    assert.ok(!S.validateQuest(q).valid);
});
test('count 0 / 非整数 → 失败（Java 侧 Math.max(1, ...) 会静默改成 1）', () => {
    const a = goodQuest(); a.goals[0].count = 0; assert.ok(!S.validateQuest(a).valid);
    const b = goodQuest(); b.goals[0].count = 2.5; assert.ok(!S.validateQuest(b).valid);
});
test('item 格式非法 → 失败', () => {
    const q = goodQuest(); q.goals[0].item = 'Apple';
    assert.ok(!S.validateQuest(q).valid);
});
test('proximity_giver center 非 3 元 / radius 负数 → 失败', () => {
    const a = goodQuest(); a.givers[0].center = [0, 64]; assert.ok(!S.validateQuest(a).valid);
    const b = goodQuest(); b.givers[0].radius = -1; assert.ok(!S.validateQuest(b).valid);
});
test('未知 giver/goal 类型 → 通过 + 警告（由包 Java 工厂提供）', () => {
    const q = goodQuest();
    q.goals = [{ type: 'proximity', target: [1, 2, 3], radius: 5 }];
    q.givers = [{ type: 'npc_giver', npc: 'elder' }];
    const r = S.validateQuest(q);
    assert.ok(r.valid, r.errors.join('; '));
    assert.strictEqual(r.warnings.length, 2);
});
test('repeatable 非布尔 → 失败', () => {
    const a = goodQuest(); a.repeatable = 'yes';
    assert.ok(!S.validateQuest(a).valid);
    const b = goodQuest(); b.repeatable = true;
    assert.ok(S.validateQuest(b).valid);
});
test('cooldownTicks 负数/小数/字符串 → 失败；≥0 整数 → 通过', () => {
    for (const bad of [-1, 1.5, '200']) {
        const q = goodQuest(); q.repeatable = true; q.cooldownTicks = bad;
        assert.ok(!S.validateQuest(q).valid, '应拒绝 ' + JSON.stringify(bad));
    }
    const q = goodQuest(); q.repeatable = true; q.cooldownTicks = 1200;
    assert.ok(S.validateQuest(q).valid);
});
test('cooldownTicks>0 但没有 repeatable → 失败（冷却永无意义）', () => {
    const q = goodQuest(); q.cooldownTicks = 200;
    const r = S.validateQuest(q);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('cooldownTicks')), r.errors.join('; '));
});
test('repeatable / cooldownTicks 在 Java 定义与解析器里都存在（防漂移）', () => {
    const defSrc = fs.readFileSync(path.join(QUEST_JAVA, 'QuestDefinition.java'), 'utf8');
    const parserSrc = fs.readFileSync(path.join(QUEST_JAVA, 'QuestDefinitionParser.java'), 'utf8');
    for (const key of ['repeatable', 'cooldownTicks']) {
        assert.ok(defSrc.includes(key), 'QuestDefinition 缺字段 ' + key);
        assert.ok(parserSrc.includes(`"${key}"`), 'QuestDefinitionParser 未解析 ' + key);
    }
    const policy = fs.readFileSync(path.join(QUEST_JAVA, 'QuestGivePolicy.java'), 'utf8');
    assert.ok(policy.includes('TERMINAL_HISTORY_LIMIT'), 'QuestGivePolicy 缺终态历史上限');
});
test('location 非 3 元 → 失败；priority 非数字 → 失败', () => {
    const a = goodQuest(); a.location = [0, 64]; assert.ok(!S.validateQuest(a).valid);
    const b = goodQuest(); b.priority = 'high'; assert.ok(!S.validateQuest(b).valid);
});
test('子任务缺 id → 失败且带 children[0] 前缀', () => {
    const q = goodQuest();
    q.children = [{ title: 'child', goals: [{ type: 'item_present', item: 'minecraft:apple' }] }];
    const r = S.validateQuest(q);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.startsWith('children[0]:')), r.errors.join('; '));
});

console.log('=== 3. 归一化 / 序列化 ===');
test('归一化补默认值（count=1 / radius=10 / priority=0）', () => {
    const n = S.normalizeQuest({
        id: ' q1 ',
        title: ' t ',
        givers: [{ type: 'proximity_giver', center: [0, 64, 0] }],
        goals: [{ type: 'item_present', item: 'minecraft:apple' }],
    });
    assert.strictEqual(n.id, 'q1');
    assert.strictEqual(n.title, 't');
    assert.strictEqual(n.givers[0].radius, 10);
    assert.strictEqual(n.goals[0].count, 1);
    assert.strictEqual(n.priority, 0);
    assert.strictEqual(n.location, null);
    assert.strictEqual(n.repeatable, false);
    assert.strictEqual(n.cooldownTicks, 0);
});
test('可选空串 label 不落盘；未知组件原样保留', () => {
    const q = goodQuest();
    q.goals[0].label = '';
    q.goals.push({ type: 'kill', entity: 'minecraft:zombie', count: 2 });
    const json = S.buildQuestJson(q);
    assert.strictEqual(json.goals[0].label, undefined);
    assert.deepStrictEqual(json.goals[1], { type: 'kill', entity: 'minecraft:zombie', count: 2 });
});
test('build 不泄漏编辑器内部字段（_isNew）', () => {
    const json = S.buildQuestJson(S.newQuest());
    assert.ok(!JSON.stringify(json).includes('_isNew'));
});
test('build 往返稳定（build → normalize → build 深相等）', () => {
    const q = goodQuest();
    q.goals.push({ type: 'kill', entity: 'minecraft:zombie', count: 2 });
    const once = S.buildQuestJson(q);
    const twice = S.buildQuestJson(S.normalizeQuest(once));
    assert.deepStrictEqual(twice, once);
});
test('build 键序 = Java 解析顺序', () => {
    const keys = Object.keys(S.buildQuestJson(goodQuest()));
    assert.deepStrictEqual(keys, ['id', 'title', 'description', 'givers', 'goals',
        'conditions', 'failConditions', 'children', 'location', 'priority',
        'repeatable', 'cooldownTicks']);
});
test('newQuest 骨架通过校验（填好 id/title/item 后）', () => {
    const q = S.newQuest();
    q.id = 'quest_new';
    q.title = '新任务';
    q.goals[0].item = 'minecraft:apple';
    const r = S.validateQuest(q);
    assert.ok(r.valid, r.errors.join('; '));
});
test('filenameFor / idFromFilename', () => {
    assert.strictEqual(S.filenameFor({ id: 'q1' }), 'q1.json');
    assert.strictEqual(S.idFromFilename('q1.json'), 'q1');
});

console.log('=== 4. 真实包任务文件 ===');
test('quest-test-pack/quests/*.json 全部通过校验', () => {
    const files = fs.readdirSync(PACK_QUESTS).filter((f) => f.endsWith('.json'));
    assert.ok(files.length >= 3, '应至少有 3 个任务文件，实际 ' + files.length);
    for (const f of files) {
        const def = JSON.parse(fs.readFileSync(path.join(PACK_QUESTS, f), 'utf8'));
        const r = S.validateQuest(def, { idPrefix: 'test' });
        assert.ok(r.valid, `${f}: ${r.errors.join('; ')}`);
    }
});
test('item_present / item_submit 示例字段齐全', () => {
    const present = JSON.parse(fs.readFileSync(path.join(PACK_QUESTS, 'item_present.json'), 'utf8'));
    const submit = JSON.parse(fs.readFileSync(path.join(PACK_QUESTS, 'item_submit.json'), 'utf8'));
    assert.strictEqual(present.goals[0].type, 'item_present');
    assert.strictEqual(submit.goals[0].type, 'item_submit');
    assert.ok(present.goals[0].item && present.goals[0].count >= 1);
    assert.ok(submit.goals[0].item && submit.goals[0].count >= 1);
    // 示例必须演示可重复发放，且冷却非 0（否则玩家站在区域内完成瞬间就会重发）
    assert.strictEqual(present.repeatable, true, 'item_present 示例应演示 repeatable');
    assert.ok(present.cooldownTicks >= 20, '可重复示例的 cooldownTicks 应 ≥ 1 秒');
});
test('包内自注册目标类型（proximity）→ 通过但带警告', () => {
    const def = JSON.parse(fs.readFileSync(path.join(PACK_QUESTS, 'quests.json'), 'utf8'));
    const r = S.validateQuest(def);
    assert.ok(r.valid, r.errors.join('; '));
    assert.ok(r.warnings.some((w) => w.includes('proximity')));
});

console.log(`\n=== quest 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
