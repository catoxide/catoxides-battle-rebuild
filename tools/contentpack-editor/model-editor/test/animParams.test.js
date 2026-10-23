// animParams.test.js - 动画运行时参数（params）声明模型 / 编解码 / 校验 / 预览求值
// 对齐 Spark-Core fork 的 AnimParamDecl.CODEC 与 AnimParamCatalog（见 core/animParams.js 头部注释）
const assert = require('assert');
const P = require('../core/animParams');
const A = require('../core/animation');
const Kf = require('../core/keyframeOps');
const E = require('../core/evaluate');
const SC = require('../core/sparkCodec');

let passed = 0, failed = 0;
function test(name, fn) {
    try {
        fn();
        passed++;
        console.log(`  ✅ ${name}`);
    } catch (e) {
        failed++;
        console.log(`  ❌ ${name}\n    ${e.message}`);
    }
}

console.log('=== 1. 内置参数目录 ===');
test('内置目录与 Kotlin AnimParamCatalog 同步（存在 fork 源码时检查）', () => {
    const fs = require('fs');
    const path = require('path');
    const kt = path.join(__dirname, '../../../spark-core-fork/src/main/kotlin/cn/solarmoon/spark_core/animation/param/AnimParamCatalog.kt');
    if (!fs.existsSync(kt)) {
        console.log('    （跳过：未找到 fork 源码，编辑器可独立运行）');
        return;
    }
    const src = fs.readFileSync(kt, 'utf8');
    const names = new Set();
    const re = /(?:param|flag|substepEuler)\(\s*"([a-z0-9_]+)"/g;
    let m;
    while ((m = re.exec(src)) !== null) names.add(m[1]);
    const jsNames = new Set(P.BUILTIN.map((e) => e.name));
    const missing = [...names].filter((n) => !jsNames.has(n));
    const extra = [...jsNames].filter((n) => !names.has(n));
    assert.deepStrictEqual(missing, [], `JS 目录缺少 Kotlin 侧参数: ${missing.join(', ')}（请同步 core/animParams.js）`);
    assert.deepStrictEqual(extra, [], `JS 目录多出 Kotlin 侧没有的参数: ${extra.join(', ')}（请同步 AnimParamCatalog.kt）`);
});
test('名字唯一、都是合法标识符、相位/类型/分组合法', () => {
    const seen = new Set();
    for (const e of P.BUILTIN) {
        assert.ok(P.isIdentifier(e.name), `非法参数名: ${e.name}`);
        assert.ok(!seen.has(e.name), `重复参数名: ${e.name}`);
        seen.add(e.name);
        assert.ok(P.PHASES.includes(e.phase), `${e.name} 相位非法: ${e.phase}`);
        assert.ok(e.kind === 'num' || e.kind === 'str', `${e.name} 类型非法: ${e.kind}`);
        assert.ok(P.GROUP_LABELS[e.group], `${e.name} 分组非法: ${e.group}`);
        assert.ok(e.desc && e.desc.length > 0, `${e.name} 缺说明`);
    }
    assert.ok(P.BUILTIN.length >= 30, '内置参数表过小');
});
test('内置表含 substep 相位（body_*）与字符串参数（biome/dimension）', () => {
    assert.strictEqual(P.builtin('body_speed').phase, 'substep');
    assert.strictEqual(P.builtin('biome').kind, 'str');
    assert.strictEqual(P.builtin('dimension').kind, 'str');
});
test('未知名字 = 自定义（builtin → null），非法名字被识别', () => {
    assert.strictEqual(P.builtin('my_custom'), null);
    assert.ok(P.isIdentifier('my_custom'));
    assert.ok(!P.isIdentifier('my-custom'));
    assert.ok(!P.isIdentifier('1abc'));
    assert.ok(!P.isIdentifier(''));
});

console.log('=== 2. 声明归一（导入） ===');
test('简写：number / string → {default, phase:tick}', () => {
    assert.deepStrictEqual(P.normalizeDecl(0.5).decl, { default: 0.5, phase: 'tick' });
    assert.deepStrictEqual(P.normalizeDecl('minecraft:plains').decl, { default: 'minecraft:plains', phase: 'tick' });
});
test('完整形：{default, phase} 保相位；phase 缺省 tick', () => {
    assert.deepStrictEqual(P.normalizeDecl({ default: 0, phase: 'substep' }).decl, { default: 0, phase: 'substep' });
    assert.deepStrictEqual(P.normalizeDecl({ default: 1 }).decl, { default: 1, phase: 'tick' });
});
test('非法：非对象/缺 default 容错/phase 非法/default 类型非法', () => {
    assert.strictEqual(P.normalizeDecl(true).ok, false);
    assert.strictEqual(P.normalizeDecl([1, 2]).ok, false);
    const miss = P.normalizeDecl({ phase: 'tick' });
    assert.ok(miss.ok && miss.decl.default === 0 && miss.warnings.length === 1, '缺 default 应兜底 0 并警告');
    assert.strictEqual(P.normalizeDecl({ default: 0, phase: 'frame' }).ok, false);
    assert.strictEqual(P.normalizeDecl({ default: [0, 0, 0] }).ok, false);
    assert.strictEqual(P.normalizeDecl({ default: null }).ok, false);
});
test('未知字段告警但不阻断', () => {
    const r = P.normalizeDecl({ default: 1, phase: 'tick', note: 'x' });
    assert.ok(r.ok);
    assert.ok(r.warnings.some((w) => w.includes('note')));
});

console.log('=== 3. normalizeParams ===');
test('混合简写/完整形，保持键顺序', () => {
    const r = P.normalizeParams({ pitch: 0, biome: 'x', body_speed: { default: 2, phase: 'substep' } });
    assert.deepStrictEqual(r.errors, []);
    assert.deepStrictEqual(Object.keys(r.params), ['pitch', 'biome', 'body_speed']);
    assert.deepStrictEqual(r.params.body_speed, { default: 2, phase: 'substep' });
});
test('非法参数名 → error；未知内置名 → warning', () => {
    const r = P.normalizeParams({ 'bad-name': 0, my_custom: 1 });
    assert.ok(r.errors.length === 1 && r.errors[0].includes('bad-name'));
    assert.ok(r.warnings.some((w) => w.includes('my_custom') && w.includes('非内置')));
    assert.ok(r.params.my_custom, '自定义参数仍保留');
});
test('内置参数相位不一致 → 以声明为准并警告', () => {
    const r = P.normalizeParams({ body_speed: 0 });
    assert.deepStrictEqual(r.params.body_speed, { default: 0, phase: 'tick' });
    assert.ok(r.warnings.some((w) => w.includes('body_speed') && w.includes('substep')));
});
test('params 非对象 → error；null → 空', () => {
    assert.ok(P.normalizeParams([1]).errors.length === 1);
    assert.deepStrictEqual(P.normalizeParams(null).params, {});
});

console.log('=== 4. validateParams / 导出形态 ===');
test('validateParams 报错：非法名 / 坏 default / 坏 phase', () => {
    const r = P.validateParams({ 'bad-name': { default: 0, phase: 'tick' }, x: { default: [], phase: 'tick' }, y: { default: 0, phase: 'nope' } });
    assert.strictEqual(r.errors.length, 3);
});
test('validateParams 允许自定义参数（只警告）', () => {
    const r = P.validateParams({ my_custom: { default: 0, phase: 'tick' } });
    assert.deepStrictEqual(r.errors, []);
    assert.ok(r.warnings.length === 1);
});
test('导出：tick → 简写（数值/字符串）；substep → 完整形', () => {
    const raw = P.paramsToRaw({
        pitch: { default: 0, phase: 'tick' },
        biome: { default: 'minecraft:plains', phase: 'tick' },
        body_speed: { default: 3, phase: 'substep' },
    });
    assert.deepStrictEqual(raw, { pitch: 0, biome: 'minecraft:plains', body_speed: { default: 3, phase: 'substep' } });
});
test('round-trip：normalizeParams(paramsToRaw(x)) 与 x 等价（含键顺序）', () => {
    const x = {
        pitch: { default: -12.3, phase: 'tick' },
        biome: { default: 'minecraft:plains', phase: 'tick' },
        body_rot_y: { default: 0, phase: 'substep' },
    };
    const back = P.normalizeParams(P.paramsToRaw(x)).params;
    assert.deepStrictEqual(back, x);
    assert.deepStrictEqual(Object.keys(back), Object.keys(x));
});
test('空表：paramsToRaw({}) → {}；isParamsEmpty', () => {
    assert.deepStrictEqual(P.paramsToRaw({}), {});
    assert.ok(P.isParamsEmpty({}));
    assert.ok(!P.isParamsEmpty({ a: { default: 0, phase: 'tick' } }));
});

console.log('=== 5. 增删改 ===');
test('withDecl 新增在末尾、修改保留原位置', () => {
    let p = P.withDecl({}, 'b', { default: 0, phase: 'tick' });
    p = P.withDecl(p, 'a', { default: 1, phase: 'tick' });
    p = P.withDecl(p, 'b', { default: 2, phase: 'substep' });
    assert.deepStrictEqual(Object.keys(p), ['b', 'a']);
    assert.deepStrictEqual(p.b, { default: 2, phase: 'substep' });
});
test('withoutDecl 不修改输入', () => {
    const p = { a: { default: 0, phase: 'tick' } };
    const q = P.withoutDecl(p, 'a');
    assert.deepStrictEqual(Object.keys(q), []);
    assert.ok(p.a);
});
test('renameDecl：保序改名；非法/重名 → 返回 error 且不改', () => {
    const p = { a: { default: 0, phase: 'tick' }, b: { default: 1, phase: 'substep' } };
    const ok = P.renameDecl(p, 'a', 'c');
    assert.strictEqual(ok.error, undefined);
    assert.deepStrictEqual(Object.keys(ok.params), ['c', 'b']);
    assert.ok(P.renameDecl(p, 'a', 'bad-name').error);
    assert.ok(P.renameDecl(p, 'a', 'b').error);
});
test('cloneParams 深拷贝且保序', () => {
    const p = { a: { default: 'x', phase: 'tick' } };
    const q = P.cloneParams(p);
    q.a.default = 'y';
    assert.strictEqual(p.a.default, 'x');
    assert.deepStrictEqual(Object.keys(q), ['a']);
});

console.log('=== 6. 动画模型 / clone / 校验集成 ===');
test('createAnimation 带 params（归一）', () => {
    const anim = A.createAnimation('x', 'once', [], null, { pitch: 0, body_speed: { default: 1, phase: 'substep' } });
    assert.deepStrictEqual(anim.params, {
        pitch: { default: 0, phase: 'tick' },
        body_speed: { default: 1, phase: 'substep' },
    });
});
test('createAnimation 非法 params → 抛错', () => {
    assert.throws(() => A.createAnimation('x', 'once', [], null, { 'bad-name': 0 }), /params/);
});
test('normalizeAnimation 保留 params；validateAnimation 校验 params', () => {
    const anim = A.createAnimation('x', 'once', [], null, { pitch: 0 });
    const norm = A.normalizeAnimation(anim);
    assert.deepStrictEqual(norm.params, anim.params);
    const bad = A.createAnimation('x', 'once', []);
    bad.params = { 'bad-name': { default: 0, phase: 'tick' } };
    assert.ok(A.validateAnimation(bad).errors.some((e) => e.includes('bad-name')));
});
test('KeyframeOps.cloneAnimation 深拷贝 params（时间线编辑不丢声明）', () => {
    const anim = A.createAnimation('x', 'once', []);
    anim.params = { pitch: { default: 0, phase: 'tick' } };
    const out = Kf.cloneAnimation(anim);
    out.params.pitch.default = 5;
    assert.strictEqual(anim.params.pitch.default, 0);
});

console.log('=== 7. 编解码（JSON round-trip） ===');
test('import：简写 + 完整形都能读，导出形态与引擎一致', () => {
    const text = JSON.stringify({
        animations: {
            a: {
                loop: 'once',
                animation_length: 0.5,
                params: { pitch: 0, biome: 'minecraft:plains', body_speed: { default: 2, phase: 'substep' } },
                bones: { root: { rotation: { '0.0': { post: ['v.pitch * -0.5', 0, 0] } } } },
            },
        },
    });
    const { data } = SC.importAnimationFile(text);
    const anim = data.get('a');
    assert.deepStrictEqual(anim.params.pitch, { default: 0, phase: 'tick' });
    assert.deepStrictEqual(anim.params.body_speed, { default: 2, phase: 'substep' });
    const file = JSON.parse(SC.exportAnimationFile(anim));
    const raw = file.animations.a;
    assert.deepStrictEqual(raw.params, { pitch: 0, biome: 'minecraft:plains', body_speed: { default: 2, phase: 'substep' } });
    const kfs = Object.values(raw.bones.root.rotation);
    assert.deepStrictEqual(kfs[0].post, ['v.pitch * -0.5', 0, 0], 'MoLang 分量原样透传');
});
test('import：params 非法 → 抛错（不静默丢声明）', () => {
    const text = JSON.stringify({ animations: { a: { loop: 'once', animation_length: 1, params: { 'bad-name': 0 }, bones: {} } } });
    assert.throws(() => SC.importAnimationFile(text), /params/);
});
test('无 params 的旧文件 | import→export 不产生 params 字段', () => {
    const text = JSON.stringify({ animations: { a: { loop: 'once', animation_length: 1, bones: { root: { rotation: { '0.0': { post: [0, 0, 0] } } } } } } });
    const anim = SC.importAnimationFile(text).data.get('a');
    assert.ok(!('params' in anim));
    assert.ok(!('params' in JSON.parse(SC.exportAnimationFile(anim)).animations.a));
});

console.log('=== 8. 预览求值（v.x 四则运算） ===');
test('evalMolangExpr：数字/参数/四则/括号/一元/取模', () => {
    const values = { pitch: 10, speed: 2.5 };
    assert.strictEqual(E.evalMolangExpr('1 + 2 * 3', values), 7);
    assert.strictEqual(E.evalMolangExpr('(1 + 2) * 3', values), 9);
    assert.strictEqual(E.evalMolangExpr('v.pitch * -0.5', values), -5);
    assert.strictEqual(E.evalMolangExpr('variable.speed / 2', values), 1.25);
    assert.strictEqual(E.evalMolangExpr('5 % 2', values), 1);
    assert.strictEqual(E.evalMolangExpr('-v.pitch + 3', values), -7);
});
test('evalMolangExpr：不支持的语法/未知参数/非法字符 → null', () => {
    const values = { pitch: 10 };
    assert.strictEqual(E.evalMolangExpr('math.sin(v.pitch)', values), null);
    assert.strictEqual(E.evalMolangExpr('query.anim_time()', values), null);
    assert.strictEqual(E.evalMolangExpr('v.unknown + 1', values), null);
    assert.strictEqual(E.evalMolangExpr('v.pitch > 5', values), null);
    assert.strictEqual(E.evalMolangExpr('1 / 0', values), null);
    assert.strictEqual(E.evalMolangExpr('', values), null);
});
test('resolveChannelValue：标量 / 三元组（含字符串分量）/ 失败', () => {
    const values = { pitch: 10 };
    assert.strictEqual(E.resolveChannelValue('v.pitch * 2', values), 20);
    assert.deepStrictEqual(E.resolveChannelValue(['v.pitch', 0, '1 + 1'], values), [10, 0, 2]);
    assert.strictEqual(E.resolveChannelValue(['v.unknown', 0, 0], values), null);
    assert.deepStrictEqual(E.resolveChannelValue([1, 2, 3], values), [1, 2, 3], '纯数值原样返回');
});
test('evaluatePose：声明参数后 MoLang 通道按默认值应用；覆盖值生效', () => {
    const model = { bones: [{ name: 'root', pivot: [0, 0, 0], rotation: [0, 0, 0] }] };
    const anim = {
        name: 'a', loop: 'once', durationSec: 1,
        params: { pitch: { default: 10, phase: 'tick' } },
        tracks: [{ bone: 'root', rotation: [{ timeSec: 0, post: ['v.pitch * -0.5', 0, 0], lerpMode: 'linear' }] }],
    };
    const pose = E.evaluatePose(model, anim, 0);
    assert.deepStrictEqual(pose.get('root').rotation, [-5, 0, 0]);
    const over = E.evaluatePose(model, anim, 0, { paramValues: { pitch: 40 } });
    assert.deepStrictEqual(over.get('root').rotation, [-20, 0, 0]);
});
test('evaluatePose：未声明/求不出 → 通道不应用（与旧行为一致）', () => {
    const model = { bones: [{ name: 'root', pivot: [0, 0, 0], rotation: [0, 0, 0] }] };
    const anim = {
        name: 'a', loop: 'once', durationSec: 1,
        tracks: [{ bone: 'root', rotation: [{ timeSec: 0, post: ['v.pitch * -0.5', 0, 0], lerpMode: 'linear' }] }],
    };
    assert.deepStrictEqual(E.evaluatePose(model, anim, 0).get('root').rotation, [0, 0, 0]);
});

console.log(`\n${failed === 0 ? '✅' : '❌'} animParams: ${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);
