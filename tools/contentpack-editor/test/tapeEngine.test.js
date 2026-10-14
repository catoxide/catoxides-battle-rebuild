// tapeEngine.test.js - TimeTape JS 引擎（编辑器侧移植）单元测试
// 与 Java TapeEngineTest（36 项）语义对齐：纸带/里程碑单发/条件前跳/after 窗口/多轨/确定性回放
const assert = require('assert');
const T = require('../src/core/tapeEngine');

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

// ---- FakeOwner：条件布尔 + 动作记录 ----
class FakeOwner {
    constructor() {
        this.is_moving = false;
        this.is_sprinting = false;
        this.has_target = false;
        this.is_on_ground = true;
        this.is_in_water = false;
        this.is_on_climbable = false;
        this.died = false;
        this.swinging = false;
        this.actions = [];
        this.tapeActions = [];
    }
    playSound(s, v, p) { this.actions.push({ kind: 'play_sound', s, v, p }); }
    applyDamage(a) { this.actions.push({ kind: 'apply_damage', a }); }
    swing() { this.actions.push({ kind: 'swing' }); this.swinging = true; }
    spawnParticle(id) { this.actions.push({ kind: 'spawn_particle', id }); }
    onTapeAction(track, state, action) { this.tapeActions.push(track + ':' + state + ':' + action); }
}

function driver() {
    const events = [];
    return { events, onAnim(track, state, b) { events.push({ track, state, name: b.name, loop: b.loop }); } };
}

const MACH1 = {
    format_version: '1.0.0',
    initial_tags: {},
    tracks: {
        body: {
            initial: 'still',
            states: {
                still: {
                    anim: { group: 1, name: 'still', loop: true },
                    exit: [
                        { when: 'is_moving', to: 'walk' },
                        { when: 'has_target', to: 'alert', priority: 2 },
                        { when: 'swinging', to: 'atk', priority: 5 },
                    ],
                },
                walk: {
                    anim: { group: 1, name: 'walk', loop: true },
                    exit: [{ when: 'not(is_moving)', to: 'still', after: 2.0 }],
                },
                alert: {
                    segments: [{ until: 0.75, anim: { group: 1, name: 'alert', loop: false } }],
                    exit: [
                        { when: 'has_target', to: 'aggr', priority: 1, after: 0.75 },
                        { when: 'is_moving', to: 'walk', priority: 0, after: 0.75 },
                    ],
                },
                aggr: {
                    anim: { group: 1, name: 'aggr', loop: true },
                    exit: [
                        { when: 'not(has_target)', to: 'still', priority: 1 },
                        { when: 'is_moving', to: 'walk', priority: 0 },
                    ],
                },
                atk: {
                    segments: [{ until: 0.15, anim: { group: 1, name: 'atk', loop: false }, next: 'done' }],
                },
                done: {
                    // 无 exit 的 hold 态（纸带终点停末帧）
                    segments: [{ until: 0.05, anim: { group: 1, name: 'still' } }],
                },
            },
        },
    },
};

console.log('=== 1. 解析与校验 ===');
test('合法状态机解析通过', () => {
    const m = T.parse(JSON.stringify({ ...MACH1, tracks: { body: { initial: 'still', states: { still: { anim: { name: 'still' }, exit: [] }, other: { anim: { name: 'x' } } } } } }));
    assert.deepStrictEqual(m.trackOrder, ['body']);
});
test('缺少 tracks 报错', () => {
    assert.throws(() => T.parse('{}'), /tracks/);
});
test('轨缺 initial 报错', () => {
    assert.throws(() => T.parse(JSON.stringify({ tracks: { b: { states: { a: { anim: { name: 'x' } } } } } })), /initial/);
});
test('initial 指向不存在状态报错', () => {
    assert.throws(() => T.parse(JSON.stringify({ tracks: { b: { initial: 'zz', states: { a: { anim: { name: 'x' } } } } } })), /不存在/);
});
test('纸带 until 非严格递增报错', () => {
    assert.throws(() => T.parse(JSON.stringify({ tracks: { b: { initial: 'a', states: { a: { segments: [{ until: 1, anim: { name: 'x' } }, { until: 1, anim: { name: 'y' } }] } } } } })), /递增/);
});
test('里程碑越界报错', () => {
    assert.throws(() => T.parse(JSON.stringify({ tracks: { b: { initial: 'a', states: { a: { segments: [{ until: 1, anim: { name: 'x' }, milestones: [{ at: 2, do: 'swing' }] }] } } } } })), /越界/);
});
test('exit 目标不存在报错', () => {
    assert.throws(() => T.parse(JSON.stringify({ tracks: { b: { initial: 'a', states: { a: { anim: { name: 'x' }, exit: [{ when: 'swinging', to: 'nope' }] } } } } })), /目标不存在/);
});
test('段 next 目标不存在报错', () => {
    assert.throws(() => T.parse(JSON.stringify({ tracks: { b: { initial: 'a', states: { a: { segments: [{ until: 1, anim: { name: 'x' }, next: 'nope' }] } } } } })), /next 目标不存在/);
});
test('非 terminal 状态无动画无纸带报错', () => {
    assert.throws(() => T.parse(JSON.stringify({ tracks: { b: { initial: 'a', states: { a: { exit: [] } } } } })), /无动画且无纸带/);
});
test('未注册动作报错', () => {
    assert.throws(() => T.parse(JSON.stringify({ tracks: { b: { initial: 'a', states: { a: { anim: { name: 'x' }, on_enter: [{ do: 'teleport' }] } } } } })), /未注册/);
});
test('无法识别的条件报错', () => {
    assert.throws(() => T.parse(JSON.stringify({ tracks: { b: { initial: 'a', states: { a: { anim: { name: 'x' }, exit: [{ when: 'a(b)', to: 'a' }] } } } } })), /条件/);
});

console.log('=== 2. 条件词汇 ===');
const C = (s) => T.parseCondition(s, 't: ');
test('实体标志', () => {
    const o = new FakeOwner(); o.has_target = true;
    const ctx = { owner: o, tags: {} };
    assert.strictEqual(T.condCheck(C('has_target'), ctx), true);
    assert.strictEqual(T.condCheck(C('is_moving'), ctx), false);
    assert.strictEqual(T.condCheck(C('not(has_target)'), ctx), false);
});
test('all/any/not 组合', () => {
    const o = new FakeOwner(); o.has_target = true; o.is_moving = true;
    const ctx = { owner: o, tags: {} };
    assert.strictEqual(T.condCheck(C('all(has_target, is_moving)'), ctx), true);
    assert.strictEqual(T.condCheck(C('all(has_target, not(is_moving))'), ctx), false);
    assert.strictEqual(T.condCheck(C('any(is_moving, is_sprinting)'), ctx), true);
    assert.strictEqual(T.condCheck(C('not(any(has_target, is_moving))'), ctx), false);
});
test('tag(x)=v / tag(x)!=v / tag(x)', () => {
    const o = new FakeOwner();
    const ctx = { owner: o, tags: { hit_dir: 'back' } };
    assert.strictEqual(T.condCheck(C('tag(hit_dir)=back'), ctx), true);
    assert.strictEqual(T.condCheck(C('tag(hit_dir)=front'), ctx), false);
    assert.strictEqual(T.condCheck(C('tag(hit_dir)!=front'), ctx), true);
    assert.strictEqual(T.condCheck(C('tag(hit_dir)'), ctx), true);
    assert.strictEqual(T.condCheck(C('tag(missing)'), ctx), false);
});
test('裸名 = tag 读（存在且非 false/空）', () => {
    const o = new FakeOwner();
    assert.strictEqual(T.condCheck(C('custom_flag'), { owner: o, tags: {} }), false);
    assert.strictEqual(T.condCheck(C('custom_flag'), { owner: o, tags: { custom_flag: '1' } }), true);
    assert.strictEqual(T.condCheck(C('custom_flag'), { owner: o, tags: { custom_flag: 'false' } }), false);
});

console.log('=== 3. tick 语义 ===');
test('循环态绑定只发一次（变化才通知）', () => {
    const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d); m.tick(o, d); m.tick(o, d);
    assert.strictEqual(d.events.length, 1);
    assert.strictEqual(d.events[0].name, 'still');
});
test('条件前跳：目标 t 归零，转移 tick 不推进', () => {
    const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);                 // still t=0.05
    o.is_moving = true; m.tick(o, d); // → walk（t=0）
    assert.strictEqual(m.trackStates().body, 'walk');
    const snap = m.snapshot()[0];
    assert.strictEqual(snap.t, 0, '转移 tick 不应推进目标态 t');
    m.tick(o, d);
    assert.strictEqual(m.snapshot()[0].t, 0.05, '下一 tick t=0.05');
});
test('priority 降序命中', () => {
    const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);
    o.is_moving = true; o.has_target = true; // p2(has_target→alert) 压过 p0(is_moving→walk)
    m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'alert');
});
test('p5 最高优先（swinging 打断一切）', () => {
    const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);
    o.has_target = true; o.is_moving = true; o.swinging = true;
    m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'atk');
});
test('after 窗口：不到时间不转移', () => {
    const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);
    o.is_moving = true; m.tick(o, d);   // → walk
    o.is_moving = false;
    for (let i = 0; i < 10; i++) m.tick(o, d); // t=0.5 < 2.0
    assert.strictEqual(m.trackStates().body, 'walk');
    for (let i = 0; i < 30; i++) m.tick(o, d); // t=2.0
    assert.strictEqual(m.trackStates().body, 'still');
});
test('纸带终点 next 前进', () => {
    const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);
    o.swinging = true; m.tick(o, d); // → atk
    for (let i = 0; i < 3; i++) m.tick(o, d); // t=0.15 → next=done
    assert.strictEqual(m.trackStates().body, 'done');
});
test('缺省 next = hold_on_last_frame（停末帧仍接受条件转移）', () => {
    const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);
    o.has_target = true; m.tick(o, d); // → alert
    for (let i = 0; i < 15; i++) m.tick(o, d); // t=0.75：has_target → aggr（p1）
    assert.strictEqual(m.trackStates().body, 'aggr');
});
test('里程碑单发 + 重进状态重计', () => {
    const doc = {
        tracks: { b: {
            initial: 'a',
            states: {
                a: {
                    anim: { name: 'a' },
                    segments: [],
                    exit: [{ when: 'swinging', to: 'b' }],
                },
                b: {
                    anim: { name: 'b' },
                    exit: [{ when: 'not(swinging)', to: 'a' }],
                },
            },
        } },
    };
    // 用 on_enter 动作当"里程碑"等价物：进 b 发一次 swing 记录
    doc.tracks.b.states.b.on_enter = [{ do: 'apply_damage', amount: 7 }];
    const m = T.TapeMachine.fromJson(JSON.stringify(doc));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);
    o.swinging = true; m.tick(o, d);  // → b（onEnter 触发）
    assert.strictEqual(o.actions.filter((x) => x.kind === 'apply_damage').length, 1);
    o.swinging = false; m.tick(o, d); // → a
    o.swinging = true; m.tick(o, d);  // → b 再进（重计）
    assert.strictEqual(o.actions.filter((x) => x.kind === 'apply_damage').length, 2);
});
test('onEnter 只执行一次', () => {
    const doc = { tracks: { b: { initial: 'a', states: { a: { anim: { name: 'a' }, on_enter: [{ do: 'apply_damage', amount: 1 }] } } } } };
    const m = T.TapeMachine.fromJson(JSON.stringify(doc));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d); m.tick(o, d); m.tick(o, d);
    assert.strictEqual(o.actions.length, 1);
});
test('set_tag 动作 + alt 动画解析（hit_dir）', () => {
    const doc = {
        tracks: { b: {
            initial: 'hit',
            states: {
                hit: {
                    segments: [{
                        until: 0.5,
                        anim: { name: 'hit_front', alt: { tag: 'hit_dir', if: 'back', then: 'hit_back' } },
                        milestones: [{ at: 0, do: 'set_tag', name: 'hit_dir', value: 'back' }],
                    }],
                },
            },
        } },
    };
    const m = T.TapeMachine.fromJson(JSON.stringify(doc));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d); // t=0.05：里程碑 at=0 先提交（set_tag back）→ 绑定按 tag 解析
    assert.strictEqual(m.tags.hit_dir, 'back');
    assert.strictEqual(d.events[0].name, 'hit_back', 'alt 应按 tag 解析');
});
test('多轨独立时钟', () => {
    const doc = {
        tracks: {
            body: { initial: 'a', states: { a: { anim: { name: 'a' }, exit: [{ when: 'is_moving', to: 'a2' }] }, a2: { anim: { name: 'a2' } } } },
            weapon: { initial: 'w', states: { w: { anim: { name: 'w' }, exit: [{ when: 'is_moving', to: 'w2' }] }, w2: { anim: { name: 'w2' } } } },
        },
    };
    const m = T.TapeMachine.fromJson(JSON.stringify(doc));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);
    o.is_moving = true; m.tick(o, d);
    assert.deepStrictEqual(m.trackStates(), { body: 'a2', weapon: 'w2' });
    // 各轨独立：body 转移不影响 weapon 的 t
    assert.strictEqual(m.snapshot().find((s) => s.track === 'body').t, 0);
});
test('forceState：t=0 + 发新绑定', () => {
    const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);
    const before = d.events.length;
    m.forceState('body', 'aggr', o, d);
    assert.strictEqual(m.trackStates().body, 'aggr');
    assert.strictEqual(m.snapshot()[0].t, 0);
    assert.ok(d.events.length > before, '应发新绑定');
});
test('确定性：同输入序列 → 同事件序列', () => {
    function run() {
        const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
        const o = new FakeOwner(); const d = driver();
        m.tick(o, d);
        o.is_moving = true; m.tick(o, d);
        o.has_target = true; o.swinging = true; m.tick(o, d);
        for (let i = 0; i < 5; i++) m.tick(o, d);
        o.swinging = false; o.is_moving = false;
        for (let i = 0; i < 25; i++) m.tick(o, d);
        return d.events.map((e) => e.state + ':' + e.name).join('|');
    }
    assert.strictEqual(run(), run());
});
test('clientApply：同 (state,t) 同绑定，不重复发', () => {
    const m = T.TapeMachine.fromJson(JSON.stringify(MACH1));
    const d = driver();
    m.clientApply('body', 'still', 0.3, d);
    assert.strictEqual(d.events.length, 1);
    m.clientApply('body', 'still', 0.35, d);
    assert.strictEqual(d.events.length, 1, '同绑定不重发');
    m.clientApply('body', 'alert', 0.4, d);
    assert.strictEqual(d.events.length, 2);
});
test('clientApply 与 tick 同数据一致（alt 经 mirrorTags）', () => {
    const doc = {
        tracks: { b: { initial: 'hit', states: { hit: { segments: [{ until: 0.5, anim: { name: 'hit_front', alt: { tag: 'hit_dir', if: 'back', then: 'hit_back' } } }] } } } },
    };
    const sv = T.TapeMachine.fromJson(JSON.stringify(doc));
    const cl = T.TapeMachine.fromJson(JSON.stringify(doc));
    const o = new FakeOwner();
    const ds = driver(); const dc = driver();
    sv.mirrorTags({ hit_dir: 'back' });
    cl.mirrorTags({ hit_dir: 'back' });
    sv.tick(o, ds); // 服务端：t=0.05
    cl.clientApply('b', 'hit', 0.05, dc); // 客户端同 (state,t)
    assert.strictEqual(ds.events[0].name, dc.events[0].name, 'hit_back');
});

console.log('=== 4. validate（编辑器校验） ===');
test('动画不在契约声明内 → error', () => {
    const doc = { tracks: { b: { initial: 'a', states: { a: { anim: { name: 'ghost_anim' } } } } } };
    const r = T.validate(JSON.stringify(doc), { animNames: ['still', 'walk'] });
    assert.ok(r.errors.some((e) => e.includes('ghost_anim')), JSON.stringify(r.errors));
});
test('MACH1 全部动画已声明 → 无 error（对照）', () => {
    const r = T.validate(JSON.stringify(MACH1), { animNames: ['still', 'walk', 'alert', 'aggr', 'atk'] });
    assert.deepStrictEqual(r.errors, []);
});
test('未知裸名条件 → warning（不阻断）', () => {
    const doc = { tracks: { b: { initial: 'a', states: { a: { anim: { name: 'a' }, exit: [{ when: 'some_custom_thing', to: 'a' }] } } } } };
    const r = T.validate(JSON.stringify(doc));
    assert.deepStrictEqual(r.errors, []);
    assert.ok(r.warnings.some((w) => w.includes('some_custom_thing')));
});
test('坏 JSON → errors 有内容且 def=null', () => {
    const r = T.validate('{oops');
    assert.strictEqual(r.def, null);
    assert.ok(r.errors.length);
});

console.log('=== 5. zombie-4 行为流（与 Zombie4JsonCheck 对齐） ===');
test('still→walking→alert→running→aggressive→attack→settle 全流程', () => {
    const fs = require('fs');
    const path = require('path');
    const ROOT = path.resolve(__dirname, '..', '..', '..');
    const json = fs.readFileSync(path.join(ROOT, 'contentpacks-source', 'modular-zombie-4', 'data', 'zombie4pack', 'state_machines', 'modular_zombie_4.json'), 'utf8');
    const m = T.TapeMachine.fromJson(json);
    const o = new FakeOwner(); const d = driver();
    m.tick(o, d);
    o.is_moving = true; m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'walking');
    o.has_target = true; m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'alert');
    for (let i = 0; i < 15; i++) m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'running');
    o.is_moving = false; m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'aggressive');
    o.swinging = true; m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'attack');
    for (let i = 0; i < 6; i++) m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'settle');
    o.swinging = false;
    m.tick(o, d); m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'aggressive');
    o.has_target = false; m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'still');
    m.tags.hit_dir = 'back';
    m.forceState('body', 'hit_reaction', o, d);
    for (let i = 0; i < 10; i++) m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'still');
    o.has_target = true; m.tick(o, d);
    assert.strictEqual(m.trackStates().body, 'alert');
});

console.log('');
if (failed > 0) {
    console.log(`=== 结果: ${passed} 通过, ${failed} 失败 ===`);
    process.exit(1);
} else {
    console.log(`=== 结果: ${passed} 通过, 0 失败 ===`);
}
