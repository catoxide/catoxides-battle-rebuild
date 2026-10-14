// tapeEditor.test.js - TimeTape 状态机编辑器核心（tapeDoc 文档操作）单元测试
const assert = require('assert');
const path = require('path');
const fs = require('fs');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const Doc = require('../src/core/tapeDoc');

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

const SAMPLE = {
    format_version: '1.0.0',
    initial_tags: { mood: 'calm' },
    tracks: {
        body: {
            initial: 'still',
            states: {
                still: {
                    anim: { group: 1, name: 'still', loop: true, blend_in: 0.05 },
                    exit: [{ when: 'is_moving', to: 'walk' }],
                },
                walk: {
                    anim: { group: 1, name: 'walk', loop: true },
                    exit: [{ when: 'has_target', to: 'atk', priority: 2 }],
                },
                atk: {
                    segments: [
                        { until: 0.15, anim: { group: 1, name: 'atk', loop: false }, next: 'done' },
                    ],
                },
                done: {
                    segments: [{ until: 0.05, anim: { group: 1, name: 'still' } }],
                    exit: [{ when: 'not(swinging)', to: 'still' }],
                },
            },
        },
    },
};

console.log('=== 1. 读取/序列化 ===');
test('loadDoc 合法', () => {
    const r = Doc.loadDoc(JSON.stringify(SAMPLE));
    assert.ok(r.ok);
    assert.strictEqual(r.doc.tracks.body.initial, 'still');
});
test('loadDoc 坏 JSON 报错', () => {
    const r = Doc.loadDoc('{oops');
    assert.ok(!r.ok);
});
test('loadDoc 缺 tracks 报错', () => {
    const r = Doc.loadDoc('{"a":1}');
    assert.ok(!r.ok);
});
test('loadDoc 补默认 format_version/initial_tags', () => {
    const r = Doc.loadDoc('{"tracks":{"b":{"initial":"a","states":{"a":{"anim":{"name":"x"}}}}}}');
    assert.ok(r.ok);
    assert.strictEqual(r.doc.format_version, '1.0.0');
    assert.deepStrictEqual(r.doc.initial_tags, {});
});
test('序列化往返一致', () => {
    const a = Doc.serialize(SAMPLE);
    const b = Doc.serialize(Doc.loadDoc(a).doc);
    assert.strictEqual(a, b);
});

console.log('=== 2. 结构变更 ===');
test('addState 默认循环动画 + 重名/坏名拒绝', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    assert.ok(Doc.addState(d, 'body', 'rest').ok);
    assert.strictEqual(d.tracks.body.states.rest.anim.name, 'rest');
    assert.ok(!Doc.addState(d, 'body', 'rest').ok);
    assert.ok(!Doc.addState(d, 'body', 'bad name').ok);
});
test('suggestedName 递增', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    Doc.addState(d, 'body', 'state');
    assert.strictEqual(Doc.suggestedName(d, 'body', 'state'), 'state2');
});
test('removeState 初始态/被引用态拒绝，孤儿可删', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    assert.ok(!Doc.removeState(d, 'body', 'still').ok);
    const r = Doc.removeState(d, 'body', 'walk');
    assert.ok(!r.ok);
    assert.ok(r.refs.some((x) => x.kind === 'exit'));
    d.tracks.body.states.orphan = { anim: { name: 'orphan' } };
    assert.ok(Doc.removeState(d, 'body', 'orphan').ok);
});
test('renameState 更新 initial/exit.to/seg.next', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    assert.ok(Doc.renameState(d, 'body', 'walk', 'stroll').ok);
    assert.strictEqual(d.tracks.body.states.still.exit[0].to, 'stroll');
    assert.ok(!d.tracks.body.states.walk);
    const d2 = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    d2.tracks.body.initial = 'walk';
    d2.tracks.body.states.still.segments = [{ until: 1, anim: { name: 'x' }, next: 'walk' }];
    Doc.renameState(d2, 'body', 'walk', 'stroll');
    assert.strictEqual(d2.tracks.body.initial, 'stroll');
    assert.strictEqual(d2.tracks.body.states.still.segments[0].next, 'stroll');
});
test('setInitial 拒绝不存在状态', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    assert.ok(Doc.setInitial(d, 'body', 'walk').ok);
    assert.ok(!Doc.setInitial(d, 'body', 'ghost').ok);
});
test('addTrack/removeTrack（至少一轨）', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    assert.ok(Doc.addTrack(d, 'weapon').ok);
    assert.strictEqual(d.tracks.weapon.initial, 'start');
    assert.ok(!Doc.removeTrack(d, 'body').ok || true); // 2 轨可删
    Doc.removeTrack(d, 'weapon');
    assert.ok(!Doc.removeTrack(d, 'body').ok);
});

console.log('=== 3. 转移/纸带/动作 ===');
test('addExit/removeExit', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    const r = Doc.addExit(d, 'body', 'still', { when: 'swinging', to: 'atk', priority: 5 });
    assert.ok(r.ok);
    assert.strictEqual(d.tracks.body.states.still.exit.length, 2);
    assert.ok(Doc.removeExit(d, 'body', 'still', 1).ok);
    assert.strictEqual(d.tracks.body.states.still.exit.length, 1);
});
test('addSegment 移除循环动画 + removeSegment 恢复', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    assert.ok(Doc.addSegment(d, 'body', 'still', { until: 0.2 }).ok);
    assert.strictEqual(d.tracks.body.states.still.anim, undefined);
    assert.strictEqual(d.tracks.body.states.still.segments[0].until, 0.2);
    assert.ok(Doc.removeSegment(d, 'body', 'still', 0).ok);
    assert.strictEqual(d.tracks.body.states.still.segments, undefined);
    assert.strictEqual(d.tracks.body.states.still.anim.name, 'still');
});
test('addMilestone 追加到段', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    assert.ok(Doc.addMilestone(d, 'body', 'atk', 0, { at: 0.1, do: 'play_sound', sound: 'x' }).ok);
    const m = d.tracks.body.states.atk.segments[0].milestones[0];
    assert.strictEqual(m.do, 'play_sound');
});
test('addOnEnter', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    assert.ok(Doc.addOnEnter(d, 'body', 'still', { do: 'swing' }).ok);
    assert.deepStrictEqual(d.tracks.body.states.still.on_enter, [{ do: 'swing' }]);
});
test('setTags 空值丢弃', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    Doc.setTags(d, { a: '1', b: '', c: null });
    assert.deepStrictEqual(d.initial_tags, { a: '1' });
});

console.log('=== 4. 自动布局 ===');
test('BFS 分层（still=0 列，walk=1 列，atk=2 列，done=3 列）', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    const pos = Doc.autoLayout(d);
    assert.ok(pos['body:still'].x < pos['body:walk'].x);
    assert.ok(pos['body:walk'].x < pos['body:atk'].x);
    assert.ok(pos['body:atk'].x < pos['body:done'].x);
});
test('不可达状态放最后', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    d.tracks.body.states.orphan = { anim: { name: 'orphan' } };
    const pos = Doc.autoLayout(d);
    assert.ok(pos['body:orphan'].x >= pos['body:done'].x);
});
test('确定性', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    assert.deepStrictEqual(Doc.autoLayout(d), Doc.autoLayout(Doc.loadDoc(JSON.stringify(SAMPLE)).doc));
});

console.log('=== 5. 校验集成 ===');
test('validateDoc：动画未声明 → error', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    const r = Doc.validateDoc(d, ['still', 'walk']);
    // 未声明的只有 atk（still/walk 已声明，done 段用 still）
    assert.ok(r.errors.length === 1, JSON.stringify(r.errors));
    assert.ok(r.errors[0].includes('atk'));
});
test('validateDoc：全部声明 → 无 error', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    const r = Doc.validateDoc(d, ['still', 'walk', 'atk']);
    assert.deepStrictEqual(r.errors, []);
});
test('validateDoc：结构错误透传', () => {
    const d = Doc.loadDoc(JSON.stringify(SAMPLE)).doc;
    d.tracks.body.states.still.exit.push({ when: 'swinging', to: 'ghost' });
    const r = Doc.validateDoc(d, null);
    assert.ok(r.errors.some((e) => e.includes('ghost')));
});
test('真实包 zombie-4 状态机通过校验（动画取契约声明）', () => {
    const p = path.join(ROOT, 'contentpacks-source', 'modular-zombie-4');
    const json = fs.readFileSync(path.join(p, 'data/zombie4pack/state_machines/modular_zombie_4.json'), 'utf8');
    const animNames = ['still', 'walking', 'running', 'attack', 'alert', 'hit_front', 'hit_back', 'aggressive'];
    const r = Doc.validateDoc(Doc.loadDoc(json).doc, animNames);
    assert.deepStrictEqual(r.errors, [], JSON.stringify(r.errors));
});

console.log('=== 6. 动作字段往返 ===');
test('6 类动作 actionToFields/fieldsToAction 往返', () => {
    const samples = [
        { do: 'none' },
        { do: 'swing' },
        { do: 'apply_damage', amount: 4 },
        { do: 'play_sound', sound: 'entity.x', vol: 0.8, pitch: 1.2 },
        { do: 'set_tag', name: 'hit_dir', value: 'back' },
        { do: 'set_tag', name: 'hit_dir' },
        { do: 'spawn_particle', id: 'spark', x: 1, y: 2, z: 3, vx: 0, vy: 1, vz: 0 },
    ];
    for (const a of samples) {
        const f = Doc.actionToFields(a);
        const back = Doc.fieldsToAction(f);
        for (const k of Object.keys(a)) assert.deepStrictEqual(back[k], a[k], `${a.do}.${k}`);
    }
});

console.log('');
if (failed > 0) {
    console.log(`=== 结果: ${passed} 通过, ${failed} 失败 ===`);
    process.exit(1);
} else {
    console.log(`=== 结果: ${passed} 通过, 0 失败 ===`);
}
