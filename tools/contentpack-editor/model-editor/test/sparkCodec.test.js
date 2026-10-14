// sparkCodec.test.js - ★ round-trip 黄金测试（design §6 / §7 W1 验收）
//   真实文件 → import → export → import → 与首次 import deep-equal（语义等价）
//   覆盖：zombie_3 模型 + 8 状态动画、sword 模型 + 8 动画、player_wrist 模型 + 7 文件
//   （base_state = 20 动画 bundle）、对象映射 bones 兼容、缺省省略、per-face UV
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const os = require('os');

const Codec = require('../core/sparkCodec');

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
const Z3 = path.join(ROOT, 'contentpacks-source', 'modular-zombie-3');
const MELEE = path.join(ROOT, 'contentpacks-source', 'melee-combat-pack');

const MODEL_FILES = [
    path.join(Z3, 'spark_models', 'entity', 'zombie3pack', 'modular_zombie_3.json'),
    path.join(MELEE, 'spark_models', 'item', 'catoxidesbattlerebuild', 'sword.json'),
    path.join(MELEE, 'spark_models', 'entity', 'catoxidesbattlerebuild', 'player_wrist.json'),
];
const ANIM_DIRS = [
    path.join(Z3, 'spark_animations', 'entity', 'zombie3pack', 'modular_zombie_3'),
    path.join(MELEE, 'spark_animations', 'item', 'catoxidesbattlerebuild', 'sword'),
    path.join(MELEE, 'spark_animations', 'entity', 'catoxidesbattlerebuild', 'player_wrist'),
];

function readJsonFile(f) {
    return fs.readFileSync(f, 'utf-8');
}

console.log('=== 1. 模型 round-trip 黄金测试（3 个真实模型） ===');
for (const f of MODEL_FILES) {
    const label = path.basename(f);
    test(`${label}: import → export → import 语义等价`, () => {
        const m1 = Codec.importModel(readJsonFile(f)).data;
        const out1 = Codec.exportModel(m1);
        const m2 = Codec.importModel(out1).data;
        assert.deepStrictEqual(m2, m1);
    });
    test(`${label}: 导出幂等（export 二次序列化字节一致）`, () => {
        const m1 = Codec.importModel(readJsonFile(f)).data;
        assert.strictEqual(Codec.exportModel(Codec.importModel(Codec.exportModel(m1)).data), Codec.exportModel(m1));
    });
}
test('sword：per-face UV 完整 round-trip（6 面 × uv/uv_size/uv_rotation）', () => {
    const f = MODEL_FILES[1];
    const m1 = Codec.importModel(readJsonFile(f)).data;
    const shade = m1.bones.find((b) => b.name === 'shade');
    const cube = shade.cubes[0];
    assert.ok(!Array.isArray(cube.uv), 'shade cube 应为 per-face UV');
    for (const face of ['north', 'east', 'south', 'west', 'up', 'down']) {
        assert.ok(cube.uv[face], `缺面 ${face}`);
    }
    // 负 uv_size（镜像面）保留
    assert.deepStrictEqual(cube.uv.south.uv_size, [-13.8, 10]);
});
test('sword：cube 级静态旋转 round-trip（blade 45° / handle -80°）', () => {
    const m1 = Codec.importModel(readJsonFile(MODEL_FILES[1])).data;
    const blade = m1.bones.find((b) => b.name === 'blade');
    assert.deepStrictEqual(blade.cubes[1].rotation, [0, 0, 45]);
    const handle = m1.bones.find((b) => b.name === 'handle');
    assert.deepStrictEqual(handle.cubes[0].rotation, [0, 0, -80]);
});
test('zombie_3：mirror / inflate / locators round-trip', () => {
    const m1 = Codec.importModel(readJsonFile(MODEL_FILES[0])).data;
    const leftArm = m1.bones.find((b) => b.name === 'left_arm');
    assert.strictEqual(leftArm.mirror, true);
    assert.strictEqual(leftArm.locators[0].name, 'hitbox_left_arm');
    const headwear = m1.bones.find((b) => b.name === 'headwear');
    assert.strictEqual(headwear.cubes[0].inflate, 0.5);
});

console.log('=== 2. 动画 round-trip 黄金测试（真实 23 文件 / 42 动画） ===');
let totalAnims = 0;
for (const dir of ANIM_DIRS) {
    const files = fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort();
    for (const file of files) {
        const label = `${path.basename(path.dirname(dir))}/${file}`;
        test(`${label}: 全部动画 round-trip 语义等价`, () => {
            const text = readJsonFile(path.join(dir, file));
            const a1 = Codec.importAnimationFile(text).data;
            totalAnims += a1.size;
            for (const [name, anim] of a1) {
                const out = Codec.exportAnimationFile(anim);
                const a2 = Codec.importAnimationFile(out).data;
                assert.strictEqual(a2.size, 1);
                assert.deepStrictEqual(a2.get(name), anim);
            }
        });
    }
}
test('共覆盖 42 个动画（zombie 8 + sword 8 + wrist 7 文件含 20 状态 bundle = 26）', () => {
    // zombie 8 文件 ×1 + sword 8 文件 ×1 + wrist: 3rd_still,3rd_attack,base_state(20),state.fall,state.jump,state.land.idle,state.land.move = 26
    assert.strictEqual(totalAnims, 8 + 8 + 26, `实际 ${totalAnims}`);
});
test('base_state：20 状态 bundle 一次导入', () => {
    const f = path.join(ANIM_DIRS[2], 'base_state.json');
    const a1 = Codec.importAnimationFile(readJsonFile(f)).data;
    assert.strictEqual(a1.size, 20);
    assert.ok(a1.has('state.land.idle') && a1.has('state.climb.move'));
});
test('缺省 loop 归一为 once（state.jump 无 loop 字段）', () => {
    const f = path.join(ANIM_DIRS[2], 'state.jump.json');
    const a1 = Codec.importAnimationFile(readJsonFile(f)).data;
    assert.strictEqual(a1.get('state.jump').loop, 'once');
});
test('animation_length 异常审计：仅 state.land.move_back 偏离（length=1.1667 < max=2），且 codec 无损保留文件原值', () => {
    const deviants = [];
    for (const dir of ANIM_DIRS) {
        for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.json'))) {
            const raw = JSON.parse(readJsonFile(path.join(dir, file)).replace(/^\uFEFF/, ''));
            const a1 = Codec.importAnimationFile(readJsonFile(path.join(dir, file))).data;
            for (const [name, anim] of a1) {
                let max = 0;
                for (const t of anim.tracks) for (const ch of ['rotation', 'position', 'scale']) {
                    for (const k of t[ch] || []) if (k.timeSec > max) max = k.timeSec;
                }
                if (Math.abs(anim.durationSec - max) >= 1e-9) {
                    deviants.push(`${path.basename(dir)}/${file}:${name} length=${anim.durationSec} max=${max}`);
                }
                // 无损：导出的 animation_length 仍是文件原值
                const rawAnim = raw.animations[name];
                assert.strictEqual(JSON.parse(Codec.exportAnimationFile(anim)).animations[name].animation_length,
                    rawAnim.animation_length, `导出未保留文件原值 ${name}`);
            }
        }
    }
    // 历史备注：旧审计曾报 move_back "length=1.1667 max=2" —— 那是旧 codec 把
    // 通道级常量数组 head.position=[0.006,0.082,-0.831] 误解析为 t=0/1/2 标量关键帧
    // 的假象。W7 修正：通道常量 → 单一 t=0 关键帧（引擎 MAP_CODEC 语义），偏离归零。
    assert.deepStrictEqual(deviants, []);
    const f = path.join(ANIM_DIRS[2], 'base_state.json');
    const anim = Codec.importAnimationFile(readJsonFile(f)).data.get('state.land.move_back');
    const head = anim.tracks.find((t) => t.bone === 'head');
    assert.deepStrictEqual(head.position, [{ timeSec: 0, post: [0.006, 0.082, -0.831], lerpMode: 'linear' }]);
});

console.log('=== 3. 兼容格式（旧 Bedrock 对象映射 bones / locators） ===');
test('对象映射 bones → 数组规范化（保序）', () => {
    const legacy = {
        format_version: '1.12.0',
        'minecraft:geometry': [{
            description: { identifier: 'legacy', texture_width: 64, texture_height: 64 },
            bones: {
                b1: { pivot: [1, 1, 1], cubes: [{ origin: [0, 0, 0], size: [1, 1, 1], uv: [0, 0] }] },
                b2: { parent: 'b1', pivot: [2, 2, 2], cubes: [{ origin: [0, 0, 0], size: [1, 1, 1], uv: [16, 0] }] },
            },
        }],
    };
    const m = Codec.importModel(legacy).data;
    assert.deepStrictEqual(m.bones.map((b) => b.name), ['b1', 'b2']);
    // round-trip
    assert.deepStrictEqual(Codec.importModel(Codec.exportModel(m)).data, m);
});
test('locators 数组格式 → 规范形（name 在前）', () => {
    const obj = {
        format_version: '1.12.0',
        'minecraft:geometry': [{
            description: { identifier: 't' },
            bones: [{
                name: 'a', pivot: [0, 0, 0],
                cubes: [{ origin: [0, 0, 0], size: [1, 1, 1], uv: [0, 0] }],
                locators: [{ name: 'hit', offset: [0, 1, 0], rotation: [0, 0, 0] }],
            }],
        }],
    };
    const m = Codec.importModel(obj).data;
    assert.strictEqual(m.bones[0].locators[0].name, 'hit');
    const out = JSON.parse(Codec.exportModel(m));
    // 导出 = 对象映射
    assert.deepStrictEqual(out['minecraft:geometry'][0].bones[0].locators,
        { hit: { offset: [0, 1, 0], rotation: [0, 0, 0] } });
});

console.log('=== 4. 缺省省略 / 非法输入 ===');
test('全缺省骨骼导出：无 rotation/scale/mirror/parent/locators/inflate/pivot(cube)', () => {
    const M = require('../core/model');
    const g = M.createGeometry({
        identifier: 'bare',
        bones: [M.createBone({ name: 'a', pivot: [0, 0, 0], cubes: [M.createCube({ origin: [0, 0, 0], size: [1, 1, 1], uv: [0, 0] })] })],
    });
    const text = Codec.exportModel(g);
    assert.ok(!text.includes('"mirror"'));
    assert.ok(!text.includes('"inflate"'));
    assert.ok(!text.includes('"rotation"'));
    assert.ok(!text.includes('"scale"'));
    assert.ok(!text.includes('"parent"'));
    assert.ok(!text.includes('"locators"'));
    assert.ok(text.includes('"pivot"'), 'bone pivot 恒写（现网风格；2 空格缩进下数组跨行）');
});
test('导出字段顺序固定（bone: name,parent,pivot,…; cube: origin,size,uv,…）', () => {
    const M = require('../core/model');
    const g = M.createGeometry({
        identifier: 'ord',
        bones: [
            M.createBone({ name: 'z', pivot: [0, 0, 0] }),
            M.createBone({
                name: 'a', parent: 'z', pivot: [1, 1, 1], rotation: [1, 2, 3], scale: [2, 2, 2], mirror: true,
                cubes: [M.createCube({ origin: [0, 0, 0], size: [1, 1, 1], uv: [0, 0], inflate: 0.5, pivot: [1, 1, 1], rotation: [4, 5, 6] })],
                locators: [M.createLocator({ name: 'h', offset: [0, 0, 0], rotation: [0, 0, 0] })],
            }),
        ],
    });
    const out = JSON.parse(Codec.exportModel(g));
    const bone = out['minecraft:geometry'][0].bones[1]; // bones[0] 是骨架 z
    assert.deepStrictEqual(Object.keys(bone), ['name', 'parent', 'pivot', 'rotation', 'scale', 'mirror', 'cubes', 'locators']);
    const cube = bone.cubes[0];
    assert.deepStrictEqual(Object.keys(cube), ['origin', 'size', 'uv', 'inflate', 'pivot', 'rotation']);
});
test('importModel 拒绝：缺 minecraft:geometry / 缺 identifier / 坏 uv', () => {
    assert.throws(() => Codec.importModel({ format_version: '1.12.0' }), /minecraft:geometry/);
    assert.throws(() => Codec.importModel({ 'minecraft:geometry': [{ bones: [] }] }), /identifier/);
    assert.throws(() => Codec.importModel({
        'minecraft:geometry': [{
            description: { identifier: 'x' },
            bones: [{ name: 'a', pivot: [0, 0, 0], cubes: [{ origin: [0, 0, 0], size: [1, 1, 1] }] }],
        }],
    }), /uv/);
});
test('exportModel 拒绝非法 geometry', () => {
    const M = require('../core/model');
    const g = M.createGeometry({
        identifier: 'bad',
        bones: [M.createBone({ name: 'a', cubes: [M.createCube({ origin: [0, 0, 0], size: [-1, 1, 1] })] })],
    });
    assert.throws(() => Codec.exportModel(g), /无法导出/);
});
test('importAnimationFile 拒绝：缺 animations / 坏时间', () => {
    assert.throws(() => Codec.importAnimationFile({}), /animations/);
    assert.throws(() => Codec.importAnimationFile({
        animations: { x: { bones: { a: { rotation: { abc: { post: [0, 0, 0] } } } } } },
    }), /时间非法/);
});

console.log('=== 5. loadDocument（模型 + 全套动画目录） ===');
test('sword 文档：1 模型 + 8 动画 + animFiles 映射', () => {
    const doc = Codec.loadDocument(MODEL_FILES[1]).data;
    assert.strictEqual(doc.model.identifier, 'geometry.sword');
    assert.strictEqual(doc.animations.size, 8);
    assert.strictEqual(Object.keys(doc.animFiles).length, 8);
    assert.ok(doc.animFiles['3rd_attack'].endsWith('3rd_attack.json'));
});
test('zombie_3 文档：8 状态动画', () => {
    const doc = Codec.loadDocument(MODEL_FILES[0]).data;
    assert.strictEqual(doc.animations.size, 8);
    assert.ok(doc.animations.has('attack') && doc.animations.has('still'));
});
test('动画重名 → 抛错（临时目录构造冲突）', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codec-doc-'));
    try {
        fs.mkdirSync(path.join(tmp, 'animdir'), { recursive: true });
        const modelFile = path.join(tmp, 'm.json');
        fs.writeFileSync(modelFile, JSON.stringify({
            format_version: '1.12.0',
            'minecraft:geometry': [{
                description: { identifier: 'm' },
                bones: [{ name: 'a', pivot: [0, 0, 0], cubes: [{ origin: [0, 0, 0], size: [1, 1, 1], uv: [0, 0] }] }],
            }],
        }));
        const anim = JSON.stringify({ animations: { dup: { loop: 'once', animation_length: 1, bones: {} } } });
        fs.writeFileSync(path.join(tmp, 'animdir', 'a.json'), anim);
        fs.writeFileSync(path.join(tmp, 'animdir', 'b.json'), anim);
        assert.throws(() => Codec.loadDocument(modelFile, path.join(tmp, 'animdir')), /重名/);
    } finally {
        fs.rmSync(tmp, { recursive: true, force: true });
    }
});

console.log('=== 6. W7：pre / timeline / 通道常量 / 未知 lerp_mode 保全 ===');
test('pre（反向切线）round-trip：导入保全 + 导出写出 + 再导入一致', () => {
    const src = JSON.stringify({
        animations: {
            x: {
                loop: 'once', animation_length: 1,
                bones: { a: { rotation: { '0.00': { post: [0, 0, 0] }, '0.50': { pre: [0, 30, 0], post: [0, 60, 0], lerp_mode: 'catmullrom' } } } },
            },
        },
    });
    const { data } = Codec.importAnimationFile(src);
    const k = data.get('x').tracks[0].rotation[1];
    assert.deepStrictEqual(k.pre, [0, 30, 0]);
    const out = Codec.exportAnimationFile(data.get('x'));
    assert.ok(out.includes('"pre"'), '导出应写出 pre');
    const reimport = Codec.importAnimationFile(out).data.get('x');
    assert.deepStrictEqual(reimport.tracks[0].rotation[1].pre, [0, 30, 0]);
});
test('pre == post → 导出仍写 pre（现网文件显式 pre==post，恒写保 deep-equal 往返）', () => {
    const { data } = Codec.importAnimationFile(JSON.stringify({
        animations: { x: { loop: 'once', animation_length: 1, bones: { a: { rotation: { '0.00': { pre: [1, 1, 1], post: [1, 1, 1] } } } } } },
    }));
    const out = Codec.exportAnimationFile(data.get('x'));
    assert.ok(out.includes('"pre"'));
    const re = Codec.importAnimationFile(out).data.get('x');
    assert.deepStrictEqual(re.tracks[0].rotation[0], data.get('x').tracks[0].rotation[0]);
});
test('真实 wrist 文件：pre 不丢（改帧再保存不改变游戏内曲线）', () => {
    const file = path.join(MELEE, 'spark_animations', 'entity', 'catoxidesbattlerebuild', 'player_wrist', 'state.land.move.json');
    if (!fs.existsSync(file)) { passed--; console.log('  - 跳过（文件缺失）'); return; }
    const text = fs.readFileSync(file, 'utf-8');
    const { data } = Codec.importAnimationFile(text);
    let withPre = 0;
    for (const anim of data.values()) {
        for (const tr of anim.tracks) for (const ch of ['rotation', 'position', 'scale']) {
            for (const k of tr[ch] || []) if (k.pre != null) withPre++;
        }
    }
    assert.ok(withPre > 0, 'wrist 文件应含 pre 关键帧');
    for (const [name, anim] of data) {
        const out = Codec.exportAnimationFile(anim);
        const re = Codec.importAnimationFile(out).data.get(name);
        const countPre = (a) => { let n = 0; for (const tr of a.tracks) for (const ch of ['rotation', 'position', 'scale']) for (const k of tr[ch] || []) if (k.pre != null) n++; return n; };
        assert.strictEqual(countPre(re), countPre(anim), `${name}: pre 数量 round-trip 不一致`);
    }
});
test('timeline（过点脚本）round-trip：导入 + 导出（list 形）+ 再导入一致', () => {
    const src = JSON.stringify({
        animations: { x: { loop: 'once', animation_length: 1, bones: {}, timeline: { '0.25': 'sound.play("s")', '0.5': ['a()', 'b()'] } } },
    });
    const { data } = Codec.importAnimationFile(src);
    assert.deepStrictEqual(data.get('x').timeline, [
        { timeSec: 0.25, scripts: ['sound.play("s")'] },
        { timeSec: 0.5, scripts: ['a()', 'b()'] },
    ]);
    const out = Codec.exportAnimationFile(data.get('x'));
    assert.ok(out.includes('"timeline"'));
    const re = Codec.importAnimationFile(out).data.get('x');
    assert.deepStrictEqual(re.timeline, data.get('x').timeline);
});
test('通道常量（裸值 → t=0 关键帧）', () => {
    const { data } = Codec.importAnimationFile(JSON.stringify({
        animations: { x: { loop: 'once', bones: { a: { position: 0.006 }, b: { rotation: [1, 2, 3] } } } },
    }));
    assert.deepStrictEqual(data.get('x').tracks.find((t) => t.bone === 'a').position,
        [{ timeSec: 0, post: [0.006, 0.006, 0.006], lerpMode: 'linear' }]);
    assert.deepStrictEqual(data.get('x').tracks.find((t) => t.bone === 'b').rotation[0].post, [1, 2, 3]);
});
test('未知 lerp_mode：导入透传（警告非错误）；导出拦截', () => {
    const { data } = Codec.importAnimationFile(JSON.stringify({
        animations: { x: { loop: 'once', animation_length: 1, bones: { a: { rotation: { '0.00': { post: [0, 0, 0], lerp_mode: 'stepped' } } } } } },
    }));
    assert.strictEqual(data.get('x').tracks[0].rotation[0].lerpMode, 'stepped');
    const A = require('../core/animation');
    const { warnings } = A.validateAnimation(data.get('x'));
    assert.ok(warnings.some((w) => w.includes('stepped')));
    assert.throws(() => Codec.exportAnimationFile(data.get('x')), /lerp_mode/);
});

console.log(`\n=== 结果: ${passed} 通过, ${failed} 失败 ===`);
if (failed > 0) process.exit(1);
