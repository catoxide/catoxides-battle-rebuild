// tech.test.js - 科技树契约单测（校验/工厂/手排布局/序列化/触发器名册 + 与 Java 侧同步）
// 权威：TechTreeLoader.java（严格解析 + 自检）、TechRequirementEngine.java（条件类型）、
//       TechTree.java / TechService.java（页签/研究项/成本/触发器）
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const S = require('../src/core/techSchema');

const REPO = path.join(__dirname, '..', '..', '..');
const TECH_JAVA = path.join(REPO, 'contentpacks-source', 'tech-tree-pack', 'java', 'example', 'contentpack', 'tech');
const REAL_TREE = path.join(REPO, 'contentpacks-source', 'tech-tree-pack', 'techs', 'tree.json');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.message}`); }
}

const goodNode = (id, page, col, row) => ({
    id,
    title: id,
    desc: '',
    page: page || 'main',
    col: col == null ? 0 : col,
    row: row == null ? 0 : row,
    icon: 'minecraft:paper',
    requirements: [],
    costs: [],
    rewards: [],
    quest: false,
    visibleWhen: [],
    resetAfterTicks: 0,
    resetWhen: [],
    onUnlock: [],
});

const goodTree = () => {
    const tree = S.newTree();
    tree.nodes = [goodNode('node_a', 'main', 0, 0)];
    return tree;
};

console.log('=== 1. 与 Java 侧类型表一致（防漂移） ===');
test('条件类型 = TechRequirementEngine 的 switch 分支', () => {
    const src = fs.readFileSync(path.join(TECH_JAVA, 'TechRequirementEngine.java'), 'utf8');
    const found = new Set();
    for (const m of src.matchAll(/case\s+"([a-z_]+)"/g)) found.add(m[1]);
    assert.deepStrictEqual([...found].sort(), Object.keys(S.REQUIREMENT_TYPES).sort());
});
test('成本类型 = TechTreeLoader.parseCost 的 switch 分支', () => {
    const src = fs.readFileSync(path.join(TECH_JAVA, 'TechTreeLoader.java'), 'utf8');
    const start = src.indexOf('private static TechCost parseCost');
    assert.ok(start >= 0, '找不到 parseCost');
    const block = src.slice(start, src.indexOf('private static', start + 10));
    const found = new Set();
    for (const m of block.matchAll(/case\s+"([a-z_]+)"/g)) found.add(m[1]);
    assert.deepStrictEqual([...found].sort(), Object.keys(S.COST_TYPES).sort());
});
test('节点字段在 TechTreeLoader 里都有对应解析', () => {
    const src = fs.readFileSync(path.join(TECH_JAVA, 'TechTreeLoader.java'), 'utf8');
    for (const key of ['page', 'col', 'row', 'icon', 'requirements', 'costs', 'rewards',
        'quest', 'visibleWhen', 'resetAfterTicks', 'resetWhen', 'onUnlock']) {
        assert.ok(src.includes(`"${key}"`), `TechTreeLoader 缺字段解析: ${key}`);
    }
});

console.log('=== 2. 工厂 / 手排落点 ===');
test('newTree 空树通过校验', () => {
    const r = S.validateTree(S.newTree());
    assert.ok(r.valid, r.errors.join('; '));
    assert.deepStrictEqual(r.warnings, []);
});
test('newNode 自动取唯一 id 与空格子', () => {
    const tree = goodTree();
    const a = S.newNode(tree, 'main');
    assert.strictEqual(a.id, 'node'); // 与 node_a 不冲突
    assert.deepStrictEqual([a.col, a.row], [1, 0]);
    tree.nodes.push(a);
    const b = S.newNode(tree, 'main');
    assert.deepStrictEqual([b.col, b.row], [2, 0]);
});
test('nextFreeCell 跳过被占格子，按 row → col 扫描', () => {
    const tree = goodTree();
    tree.nodes.push(goodNode('b', 'main', 1, 0));
    tree.nodes.push(goodNode('c', 'main', 0, 1));
    assert.deepStrictEqual(S.nextFreeCell(tree, 'main'), { col: 2, row: 0 });
    assert.deepStrictEqual(S.nextFreeCell(tree, 'other'), { col: 0, row: 0 });
});
test('newNode 显式坐标优先于自动落点', () => {
    const n = S.newNode(goodTree(), 'main', 7, 9);
    assert.deepStrictEqual([n.col, n.row], [7, 9]);
});
test('deletePage：仍被引用拒绝 / 最后一个拒绝 / 空页签可删', () => {
    const tree = goodTree();
    tree.pages.push(S.newPage('extra', '扩展'));
    assert.strictEqual(S.deletePage(tree, 'main').ok, false);
    assert.strictEqual(S.deletePage(tree, 'extra').ok, true);
    const single = S.newTree();
    single.nodes = [];
    assert.strictEqual(S.deletePage(single, 'main').ok, false);
});
test('deleteNode 移除节点', () => {
    const tree = goodTree();
    assert.strictEqual(S.deleteNode(tree, 'node_a').ok, true);
    assert.strictEqual(tree.nodes.length, 0);
    assert.strictEqual(S.deleteNode(tree, 'node_a').ok, false);
});

console.log('=== 3. 校验（镜像 TechTreeLoader 的严格性） ===');
test('重复节点/研究项 id → 失败', () => {
    const tree = goodTree();
    tree.nodes.push(goodNode('node_a', 'main', 1, 0));
    const r = S.validateTree(tree);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('重复节点 id: node_a')), r.errors.join('; '));

    const t2 = goodTree();
    t2.research = [S.newResearch(t2, 'main'), S.newResearch(t2, 'main')];
    t2.research[1].id = t2.research[0].id;
    assert.ok(S.validateTree(t2).errors.some((e) => e.includes('重复研究项 id')));
});
test('节点 id 非法 / icon 非法 → 失败', () => {
    const a = goodTree(); a.nodes[0].id = 'Node_A';
    assert.ok(!S.validateTree(a).valid);
    const b = goodTree(); b.nodes[0].icon = 'Paper';
    assert.ok(!S.validateTree(b).valid);
});
test('col/row 非整数 → 失败；负数 → 仅警告', () => {
    const a = goodTree(); a.nodes[0].col = 1.5;
    const r = S.validateTree(a);
    assert.ok(!r.valid);
    const b = goodTree(); b.nodes[0].row = -1;
    const r2 = S.validateTree(b);
    assert.ok(r2.valid);
    assert.ok(r2.warnings.some((w) => w.includes('为负数')));
});
test('条件引用不存在的节点 → 失败', () => {
    const tree = goodTree();
    tree.nodes[0].requirements = [{ type: 'tech', nodes: ['ghost'] }];
    const r = S.validateTree(tree);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('不存在的节点: ghost')));
});
test('all/any 嵌套引用也能查出缺节点 + 成环', () => {
    const tree = goodTree();
    tree.nodes.push(goodNode('node_b', 'main', 1, 0));
    tree.nodes[0].requirements = [{ type: 'all', requirements: [{ type: 'tech', nodes: ['node_b'] }] }];
    tree.nodes[1].requirements = [{ type: 'any', requirements: [{ type: 'tech', nodes: ['node_a'] }] }];
    const r = S.validateTree(tree);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('存在环')), r.errors.join('; '));
});
test('未知条件类型 → 通过 + 警告（由 TechHooks 扩展解释）', () => {
    const tree = goodTree();
    tree.nodes[0].requirements = [{ type: 'my_ext', thing: 1 }];
    const r = S.validateTree(tree);
    assert.ok(r.valid, r.errors.join('; '));
    assert.ok(r.warnings.some((w) => w.includes('my_ext')));
});
test('未知成本类型 → 失败（运行期该节点解析失败）', () => {
    const tree = goodTree();
    tree.nodes[0].costs = [{ type: 'diamond', amount: 1 }];
    const r = S.validateTree(tree);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('成本类型未知')));
});
test('研究项没有成本 → 失败；cooldownTicks 负数 → 失败', () => {
    const t1 = goodTree();
    t1.research = [S.newResearch(t1, 'main')];
    t1.research[0].costs = [];
    assert.ok(!S.validateTree(t1).valid);
    const t2 = goodTree();
    t2.research = [S.newResearch(t2, 'main')];
    t2.research[0].cooldownTicks = -1;
    assert.ok(!S.validateTree(t2).valid);
});
test('研究台升级缺层 / 越界 / 重复目标 → 失败', () => {
    const t1 = goodTree();
    t1.table = { maxTier: 3, upgrades: [{ to: 2, costs: [S.newCost('item')] }] };
    const r1 = S.validateTree(t1);
    assert.ok(!r1.valid);
    assert.ok(r1.errors.some((e) => e.includes('2 → 3')), r1.errors.join('; '));
    const t2 = goodTree();
    t2.table = { maxTier: 2, upgrades: [{ to: 3, costs: [S.newCost('item')] }] };
    assert.ok(S.validateTree(t2).errors.some((e) => e.includes('越界')));
    const t3 = goodTree();
    t3.table = { maxTier: 3, upgrades: [
        { to: 2, costs: [S.newCost('item')] },
        { to: 2, costs: [S.newCost('item')] }] };
    assert.ok(S.validateTree(t3).errors.some((e) => e.includes('重复')));
});
test('页签未声明 → 仅警告（运行期自动补）', () => {
    const tree = goodTree();
    tree.nodes[0].page = 'ghost';
    const r = S.validateTree(tree);
    assert.ok(r.valid);
    assert.ok(r.warnings.some((w) => w.includes('未声明')));
});
test('同包物品引用：不在 items/ 里 → 失败；外部命名空间不判死', () => {
    const tree = goodTree();
    tree.nodes[0].costs = [{ type: 'item', item: 'mypack:missing', amount: 1 }];
    const opts = { packNamespaces: new Set(['mypack']), knownItems: new Set(['mypack:known']) };
    const r = S.validateTree(tree, opts);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('mypack:missing')), r.errors.join('; '));

    tree.nodes[0].costs = [{ type: 'item', item: 'minecraft:iron_ingot', amount: 1 }];
    assert.ok(S.validateTree(tree, opts).valid);
});
test('同包任务引用：quests/ 或 ns:tech_<node> 之一存在才通过', () => {
    const tree = goodTree();
    tree.nodes[0].requirements = [{ type: 'quest', quests: ['mypack:tech_node_a'] }];
    const opts = { packNamespaces: new Set(['mypack']), knownQuests: new Set() };
    assert.ok(S.validateTree(tree, opts).valid, 'tech_ 前缀应映射到已有节点');

    tree.nodes[0].requirements = [{ type: 'quest', quests: ['mypack:missing_quest'] }];
    const r = S.validateTree(tree, opts);
    assert.ok(!r.valid);
    assert.ok(r.errors.some((e) => e.includes('missing_quest')), r.errors.join('; '));
});
test('页签内重叠 → 仅警告（手排允许叠格但必须提示）', () => {
    const tree = goodTree();
    tree.nodes.push(goodNode('node_b', 'main', 0, 0));
    const r = S.validateTree(tree);
    assert.ok(r.valid);
    assert.ok(r.warnings.some((w) => w.includes('重叠')), r.warnings.join('; '));
});
test('layoutCollisions 带页签与坐标', () => {
    const tree = goodTree();
    tree.nodes.push(goodNode('node_b', 'main', 0, 0));
    tree.nodes.push(goodNode('node_c', 'other', 0, 0));
    const cols = S.layoutCollisions(tree);
    assert.strictEqual(cols.length, 1);
    assert.ok(cols[0].includes('main') && cols[0].includes('node_a') && cols[0].includes('node_b'));
});

console.log('=== 4. 触发器名册 ===');
test('收集 requirements / visibleWhen / resetWhen / onUnlock / research', () => {
    const tree = goodTree();
    tree.nodes[0].requirements = [{ type: 'trigger', name: 'crafted_component' }];
    tree.nodes[0].visibleWhen = [{ type: 'all', requirements: [{ type: 'trigger', name: 'seen_forge' }] }];
    tree.nodes[0].resetWhen = [{ type: 'any', requirements: [{ type: 'trigger', name: 'logout' }] }];
    tree.nodes[0].onUnlock = ['crafted_component'];
    tree.research = [S.newResearch(tree, 'main')];
    tree.research[0].requirements = [{ type: 'trigger', name: 'crafted_component' }];

    const usage = S.collectTriggerUsage(tree);
    const byName = new Map(usage.map((u) => [u.name, u]));
    assert.strictEqual(byName.get('crafted_component').count, 3); // node.requirements + onUnlock + research
    assert.ok(byName.get('seen_forge'));
    assert.ok(byName.get('logout'));
    const fields = byName.get('crafted_component').uses.map((u) => u.field).sort();
    assert.deepStrictEqual(fields, ['onUnlock', 'requirements', 'requirements']);
    assert.deepStrictEqual(S.collectTriggerNames(tree).map((x) => x.name),
        ['crafted_component', 'logout', 'seen_forge']);
});
test('空名不进入名册', () => {
    const tree = goodTree();
    tree.nodes[0].onUnlock = ['', '  '];
    assert.deepStrictEqual(S.collectTriggerUsage(tree), []);
});

console.log('=== 5. 序列化 ===');
test('build 键序固定，编辑器字段与默认值不落盘', () => {
    const tree = goodTree();
    tree.nodes[0]._isNew = true;
    const json = S.buildTreeJson(tree);
    assert.deepStrictEqual(Object.keys(json),
        ['title', 'points', 'pages', 'research', 'table', 'nodes']);
    assert.deepStrictEqual(Object.keys(json.nodes[0]),
        ['id', 'title', 'page', 'col', 'row', 'icon', 'requirements', 'costs', 'rewards']);
    assert.ok(!JSON.stringify(json).includes('_isNew'));
    assert.strictEqual(json.nodes[0].resetAfterTicks, undefined);
    assert.strictEqual(json.nodes[0].onUnlock, undefined);
});
test('未知顶层/节点字段保留（保存不丢数据）', () => {
    const tree = goodTree();
    tree.customTop = { a: 1 };
    tree.nodes[0].customNode = 'keep';
    const json = S.buildTreeJson(tree);
    assert.deepStrictEqual(json.customTop, { a: 1 });
    assert.strictEqual(json.nodes[0].customNode, 'keep');
});
test('build 往返稳定（build → validate → build 深相等）', () => {
    const tree = goodTree();
    tree.nodes[0].requirements = [{ type: 'trigger', name: 't1' }];
    tree.nodes[0].costs = [{ type: 'tech_point', amount: 2 }];
    tree.nodes[0].rewards = [{ item: 'minecraft:apple', count: 2 }];
    tree.nodes[0].onUnlock = ['t2'];
    tree.research = [S.newResearch(tree, 'main')];
    tree.research[0].costs = [{ type: 'item', item: 'minecraft:paper', amount: 1 }];
    const once = S.buildTreeJson(tree);
    const r = S.validateTree(once);
    assert.ok(r.valid, r.errors.join('; '));
    const twice = S.buildTreeJson(once);
    assert.deepStrictEqual(twice, once);
});

console.log('=== 6. 真实包科技树 ===');
test('tech-tree-pack/techs/tree.json 通过校验', () => {
    const tree = JSON.parse(fs.readFileSync(REAL_TREE, 'utf8'));
    const r = S.validateTree(tree);
    assert.ok(r.valid, r.errors.join('\n'));
});
test('真实树的每个节点/研究项都落在已声明页签上', () => {
    const tree = JSON.parse(fs.readFileSync(REAL_TREE, 'utf8'));
    const pages = new Set((tree.pages || []).map((p) => p.id));
    for (const node of tree.nodes || []) assert.ok(pages.has(node.page), `${node.id} → ${node.page}`);
    for (const entry of tree.research || []) assert.ok(pages.has(entry.page), `${entry.id} → ${entry.page}`);
});

console.log(`\n=== tech 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
