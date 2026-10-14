// model.test.js - 模型数据模型 / 树构建 / 校验（design §6 测试矩阵）
const assert = require('assert');
const path = require('path');
const fs = require('fs');

const M = require('../core/model');

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

const ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const ZOMBIE = path.join(ROOT, 'contentpacks-source', 'modular-zombie-3', 'spark_models', 'entity', 'zombie3pack', 'modular_zombie_3.json');
const Codec = require('../core/sparkCodec');

console.log('=== 1. 构造器缺省值 ===');
test('createBone 缺省：rotation [0,0,0] / scale [1,1,1] / mirror false', () => {
    const b = M.createBone({ name: 'x' });
    assert.deepStrictEqual(b.rotation, [0, 0, 0]);
    assert.deepStrictEqual(b.scale, [1, 1, 1]);
    assert.strictEqual(b.mirror, false);
    assert.strictEqual(b.parent, null);
    assert.deepStrictEqual(b.pivot, [0, 0, 0]);
});
test('createCube 缺省：inflate 0 / pivot [0,0,0] / rotation [0,0,0]', () => {
    const c = M.createCube({ origin: [0, 0, 0], size: [1, 1, 1] });
    assert.strictEqual(c.inflate, 0);
    assert.deepStrictEqual(c.pivot, [0, 0, 0]);
    assert.deepStrictEqual(c.rotation, [0, 0, 0]);
});
test('createBone 拒绝无名骨骼', () => {
    assert.throws(() => M.createBone({}), /name/);
});
test('createCube 拒绝坏 origin', () => {
    assert.throws(() => M.createCube({ origin: [1, 2], size: [1, 1, 1] }), /origin/);
});

console.log('=== 2. 树构建（真实 zombie_3 模型，codec 导入为规范形） ===');
const zombieModel = Codec.importModel(fs.readFileSync(ZOMBIE, 'utf-8')).data;
test('zombie_3 骨骼数 = 7', () => {
    assert.strictEqual(M.getBoneNames(zombieModel).length, 7);
});
test('buildTree：单根 body，headwear 是 head 的子，四肢是 body 的子', () => {
    const { roots, byName } = M.buildTree(zombieModel);
    assert.strictEqual(roots.length, 1);
    assert.strictEqual(roots[0].name, 'body');
    const head = byName.get('head');
    assert.ok(head.children.some((c) => c.name === 'headwear'));
    const body = byName.get('body');
    assert.deepStrictEqual(body.children.map((c) => c.name).sort(),
        ['head', 'left_arm', 'left_leg', 'right_arm', 'right_leg']);
});
test('getChildren 正确', () => {
    assert.deepStrictEqual(M.getChildren(zombieModel, 'head'), ['headwear']);
    assert.deepStrictEqual(M.getChildren(zombieModel, 'nope'), []);
});

console.log('=== 3. 树构建错误（悬空 parent / 重名 / 环） ===');
test('悬空 parent → 报错 + 当根处理', () => {
    const g = M.createGeometry({
        identifier: 't',
        bones: [M.createBone({ name: 'a', parent: 'ghost' })],
    });
    const { roots, errors } = M.buildTree(g);
    assert.strictEqual(roots.length, 1);
    assert.ok(errors.some((e) => e.includes('ghost')));
});
test('重名骨骼 → 报错，只留第一个', () => {
    const g = M.createGeometry({
        identifier: 't',
        bones: [M.createBone({ name: 'a' }), M.createBone({ name: 'a' })],
    });
    const { byName, errors } = M.buildTree(g);
    assert.strictEqual(byName.size, 1);
    assert.ok(errors.some((e) => e.includes('重名')));
});
test('骨骼环 → 报错，环上骨不进 roots', () => {
    const g = M.createGeometry({
        identifier: 't',
        bones: [
            M.createBone({ name: 'a', parent: 'b' }),
            M.createBone({ name: 'b', parent: 'a' }),
            M.createBone({ name: 'root' }),
        ],
    });
    const { roots, errors } = M.buildTree(g);
    assert.deepStrictEqual(roots.map((r) => r.name), ['root']);
    assert.ok(errors.some((e) => e.includes('环')));
});

console.log('=== 4. validateGeometry ===');
test('真实 zombie_3 零错误', () => {
    const { errors, warnings } = M.validateGeometry(zombieModel);
    assert.deepStrictEqual(errors, []);
    assert.deepStrictEqual(warnings, []);
});
test('size 负分量 → 错误', () => {
    const g = M.createGeometry({
        identifier: 't',
        bones: [M.createBone({ name: 'a', cubes: [M.createCube({ origin: [0, 0, 0], size: [-1, 1, 1] })] })],
    });
    const { errors } = M.validateGeometry(g);
    assert.ok(errors.some((e) => e.includes('负分量')));
});
test('uv 越界 → 警告（不阻断）', () => {
    const g = M.createGeometry({
        identifier: 't', textureWidth: 32, textureHeight: 32,
        bones: [M.createBone({ name: 'a', cubes: [M.createCube({ origin: [0, 0, 0], size: [1, 1, 1], uv: [40, 16] })] })],
    });
    const { errors, warnings } = M.validateGeometry(g);
    assert.deepStrictEqual(errors, []);
    assert.ok(warnings.some((w) => w.includes('超出贴图范围')));
});
test('per-face uv 结构合法（sword 风格）→ 零错误', () => {
    const perFace = {
        north: { uv: [0, 0], uv_size: [2, 4] },
        east: { uv: [0, 0], uv_size: [0, 4] },
        south: { uv: [0, 0], uv_size: [-2, 4] },
        west: { uv: [0, 0], uv_size: [0, 4] },
        up: { uv: [0, 0], uv_size: [-2, 0] },
        down: { uv: [0, 0], uv_size: [-2, 0] },
    };
    const cube = M.createCube({ origin: [0, 0, 0], size: [1, 1, 0], uv: perFace });
    const g = M.createGeometry({ identifier: 't', bones: [M.createBone({ name: 'a', cubes: [cube] })] });
    const { errors } = M.validateGeometry(g);
    assert.deepStrictEqual(errors, []);
});
test('cube uv 缺失 → 错误', () => {
    const g = M.createGeometry({
        identifier: 't',
        bones: [{ name: 'a', pivot: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1], cubes: [{ origin: [0, 0, 0], size: [1, 1, 1], uv: null, inflate: 0, pivot: [0, 0, 0], rotation: [0, 0, 0] }], locators: [] }],
    });
    const { errors } = M.validateGeometry(g);
    assert.ok(errors.some((e) => e.includes('uv')));
});

console.log('=== 7. 编辑操作（W3：gizmo/大纲/属性面板的数据侧） ===');
function freshZombie() {
    return Codec.importModel(fs.readFileSync(ZOMBIE, 'utf-8')).data;
}
test('addBone：根骨 + 子骨 + 重名拒绝 + 缺省值', () => {
    const g = freshZombie();
    assert.strictEqual(M.addBone(g, { name: 'tail' }).ok, true);
    assert.strictEqual(M.addBone(g, { name: 'tail_tip', parent: 'tail', pivot: [0, 32, 0] }).ok, true);
    assert.strictEqual(M.addBone(g, { name: 'tail' }).ok, false);
    assert.ok(M.addBone(g, { name: 'x', parent: 'nope' }).error.includes('父骨骼'));
    const tail = g.bones.find((b) => b.name === 'tail');
    assert.deepStrictEqual(tail.rotation, [0, 0, 0]);
    assert.deepStrictEqual(tail.scale, [1, 1, 1]);
});
test('addBone 缺省名 = 不冲突的 "bone" 序列', () => {
    const g = M.createGeometry({ identifier: 't', bones: [M.createBone({ name: 'bone' }), M.createBone({ name: 'bone_2' })] });
    const r = M.addBone(g, {});
    assert.strictEqual(r.name, 'bone_3');
});
test('uniqueBoneName：base 空闲直接用', () => {
    const g = M.createGeometry({ identifier: 't', bones: [] });
    assert.strictEqual(M.uniqueBoneName(g, 'left_fist'), 'left_fist');
});
test('removeBone：子骨重挂祖父；删根 → 子骨升根', () => {
    const g = freshZombie();
    M.addBone(g, { name: 'head_camera', parent: 'head', pivot: [0, 32, 0] });
    assert.strictEqual(M.removeBone(g, 'head').ok, true);
    const cam = g.bones.find((b) => b.name === 'head_camera');
    assert.strictEqual(cam.parent, 'body'); // head 的父
    assert.strictEqual(M.removeBone(g, 'nope').ok, false);
    const g2 = M.createGeometry({ identifier: 't', bones: [
        M.createBone({ name: 'a' }), M.createBone({ name: 'b', parent: 'a' }) ] });
    M.removeBone(g2, 'a');
    assert.strictEqual(g2.bones.find((b) => b.name === 'b').parent, null);
});
test('duplicateBone：子树深拷贝 + 新名 + 互不影响', () => {
    const g = freshZombie();
    const r = M.duplicateBone(g, 'head');
    assert.strictEqual(r.ok, true);
    assert.strictEqual(r.name, 'head_copy');
    const copy = g.bones.find((b) => b.name === 'head_copy');
    const copyChild = g.bones.find((b) => b.name === 'head_copy_headwear');
    assert.ok(copy && copyChild);
    assert.strictEqual(copy.parent, 'body');        // 根的父保持
    assert.strictEqual(copyChild.parent, 'head_copy'); // 子树内 parent 已映射
    assert.deepStrictEqual(copy.pivot, [0, 24, 0]);
    // 独立性：改副本不影响原件
    copy.pivot[0] = 99;
    assert.strictEqual(g.bones.find((b) => b.name === 'head').pivot[0], 0);
    copy.cubes[0].origin[0] = 99;
    assert.strictEqual(g.bones.find((b) => b.name === 'head').cubes[0].origin[0], -4);
    assert.strictEqual(M.duplicateBone(g, 'nope').ok, false);
});
test('renameBone：parent 引用同步 + 重名/不存在拒绝', () => {
    const g = freshZombie();
    assert.strictEqual(M.renameBone(g, 'head', 'skull').ok, true);
    const hw = g.bones.find((b) => b.name === 'headwear');
    assert.strictEqual(hw.parent, 'skull');
    assert.strictEqual(M.renameBone(g, 'skull', 'body').ok, false); // 目标已存在
    assert.strictEqual(M.renameBone(g, 'nope', 'x').ok, false);
});
test('addCube / removeCube：索引与拒绝', () => {
    const g = freshZombie();
    const r = M.addCube(g, 'body', { origin: [0, 0, 0], size: [2, 2, 2], uv: [0, 0] });
    assert.strictEqual(r.ok, true);
    assert.strictEqual(r.index, 1); // body 原有 1 个 cube
    const body = g.bones.find((b) => b.name === 'body');
    assert.deepStrictEqual(body.cubes[1].pivot, [0, 0, 0]); // createCube 归一缺省
    assert.strictEqual(M.addCube(g, 'nope', { origin: [0, 0, 0], size: [1, 1, 1], uv: [0, 0] }).ok, false);
    assert.strictEqual(M.removeCube(g, 'body', 1).ok, true);
    assert.strictEqual(body.cubes.length, 1);
    assert.strictEqual(M.removeCube(g, 'body', 5).ok, false);
});
test('编辑后 validateGeometry 仍合法', () => {
    const g = freshZombie();
    M.addBone(g, { name: 'tail', parent: 'body', pivot: [0, 12, 0] });
    M.addCube(g, 'tail', { origin: [-1, 8, -1], size: [2, 4, 2], uv: [0, 0] });
    M.removeBone(g, 'headwear');
    M.renameBone(g, 'tail', 'tail2');
    const { errors } = M.validateGeometry(g);
    assert.deepStrictEqual(errors, []);
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
