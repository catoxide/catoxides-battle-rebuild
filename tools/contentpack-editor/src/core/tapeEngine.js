// tapeEngine.js — TimeTape 引擎 JS 移植（编辑器侧：实时仿真 + 编辑校验）
// 单一真源 = src/main/java/.../core/anim/tape/TapeStateMachine.java（改引擎必须同步本文件）
//
// 时间语义（不变式，与 Java 一致）：
//   · 每状态本地时钟 t 只增（20Hz 固定步 0.05s）；离开状态后其里程碑永不补发/重放
//   · 序列态 = 纸带：段按 until 绝对时间戳推进；t 越界 = 前进下一段/目标态，禁止回退
//   · 条件转移 = 前跳：目标状态 t 归零重新计
//   · 转移 tick 不推进目标态的 t
// tick 顺序（每轨）：onEnter(首次) → t += 0.05 → 里程碑(at<=t 且未触发)
//   → 条件转移(priority 降序，窗口 [after, before)，命中即跳)
//   → 纸带前进(t 越过末段 until → next 态或停末帧) → 动画绑定通知(变化才通知)
//
// 词汇表（与 Java 同步）：
//   条件: is_moving/is_sprinting/has_target/is_on_ground/is_in_water/is_on_climbable/died/swinging
//         裸名 = tag 读(存在且非 "false"/空) · tag(x)=v · tag(x)!=v · all(...) · any(...) · not(...)
//   动作: none/swing/apply_damage/play_sound/set_tag/spawn_particle（JSON "do" 字段派发）

'use strict';

const TICK_SEC = 0.05;
const EPS = 1e-6;

const FLAGS = [
    'is_moving', 'is_sprinting', 'has_target', 'is_on_ground',
    'is_in_water', 'is_on_climbable', 'died', 'swinging',
];
const ACTIONS = ['none', 'swing', 'apply_damage', 'play_sound', 'set_tag', 'spawn_particle'];

class TapeError extends Error {
    constructor(msg) {
        super(msg);
        this.name = 'TapeError';
    }
}

// ==================== 条件 ====================
// AST: {kind:'flag', name} | {kind:'tag', name, op:'', '='|'!=', value}
//      | {kind:'all'|'any', parts:[...]} | {kind:'not', inner}

function condCheck(c, ctx) {
    switch (c.kind) {
        case 'flag': {
            if (FLAGS.includes(c.name)) {
                const f = ctx.owner[c.name];
                return !!(typeof f === 'function' ? f.call(ctx.owner) : f);
            }
            // 未识别裸名 = tag 读（存在且非 "false"/空）
            const v = ctx.tags[c.name];
            return v != null && v !== '' && v !== 'false';
        }
        case 'tag': {
            const v = ctx.tags[c.name];
            if (c.op === '=') return c.value === v;
            if (c.op === '!=') return c.value !== v;
            return v != null && v !== '' && v !== 'false';
        }
        case 'all': return c.parts.every((p) => condCheck(p, ctx));
        case 'any': return c.parts.some((p) => condCheck(p, ctx));
        case 'not': return !condCheck(c.inner, ctx);
        default: throw new TapeError('未知条件节点: ' + c.kind);
    }
}

function parseCondition(s, where) {
    if (typeof s !== 'string') throw new TapeError(where + ' 条件不是字符串');
    return parseExpr(s.trim(), where);
}

function parseExpr(s, where) {
    if (!s) throw new TapeError(where + ' 空条件');
    if (fnStart(s, 'all(')) return { kind: 'all', parts: parseList(s, where) };
    if (fnStart(s, 'any(')) return { kind: 'any', parts: parseList(s, where) };
    if (fnStart(s, 'not(')) return { kind: 'not', inner: parseExpr(stripParens(s, where), where) };
    // tag(x) / tag(x)=v / tag(x)!=v（Java 1.0 版 startsWithFn 要求以 ) 结尾 = 带操作符的写法解析不了，
    // 2026-10-14 两侧同步修复：按第一个 ) 切分 name 与操作符）
    if (s.startsWith('tag(')) {
        const close = s.indexOf(')');
        if (close < 0) throw new TapeError(where + ' tag 条件括号不配平 ["' + s + '"]');
        const name = s.slice(4, close).trim();
        const rest = s.slice(close + 1).trim();
        if (rest.startsWith('!=')) return { kind: 'tag', name, op: '!=', value: rest.slice(2).trim() };
        if (rest.startsWith('=')) return { kind: 'tag', name, op: '=', value: rest.slice(1).trim() };
        if (rest === '') return { kind: 'tag', name, op: '', value: null };
        throw new TapeError(where + ' tag 条件语法错误 ["' + s + '"]');
    }
    if (FLAGS.includes(s)) return { kind: 'flag', name: s };
    if (/^[A-Za-z_][A-Za-z0-9_:.\-]*$/.test(s)) return { kind: 'flag', name: s }; // 裸 tag
    throw new TapeError(where + ' 无法识别的条件 ["' + s + '"]');
}

function fnStart(s, fn) {
    return s.startsWith(fn) && s.endsWith(')');
}

function stripParens(s, where) {
    const open = s.indexOf('(');
    const close = s.lastIndexOf(')');
    if (open < 0 || close <= open) throw new TapeError(where + ' 括号不配平 ["' + s + '"]');
    return s.slice(open + 1, close);
}

function parseList(s, where) {
    const body = stripParens(s, where);
    if (!body.trim()) return [];
    return splitTop(body).map((p) => parseExpr(p.trim(), where));
}

function splitTop(s) {
    const out = [];
    let depth = 0;
    let start = 0;
    for (let i = 0; i < s.length; i++) {
        const c = s[i];
        if (c === '(') depth++;
        else if (c === ')') depth--;
        else if (c === ',' && depth === 0) {
            out.push(s.slice(start, i));
            start = i + 1;
        }
    }
    out.push(s.slice(start));
    return out;
}

// ==================== 动画绑定 ====================
// {group, name, loop, blendIn, alt:{tag, if, then}|null}

function parseAnim(o, where) {
    if (!o || typeof o !== 'object' || typeof o.name !== 'string' || !o.name) {
        throw new TapeError(where + ' 动画绑定缺少 name: ' + JSON.stringify(o));
    }
    const a = {
        group: o.group != null ? o.group : 1,
        name: o.name,
        loop: o.loop === undefined ? true : !!o.loop,
        blendIn: o.blend_in != null ? o.blend_in : 0.05,
        alt: null,
    };
    if (o.alt && typeof o.alt === 'object') {
        a.alt = {
            tag: o.alt.tag || null,
            if: o.alt.if != null ? String(o.alt.if) : null,
            then: o.alt.then || null,
        };
    }
    return a;
}

function animResolveName(a, tags) {
    if (a.alt && a.alt.then != null) {
        const v = tags[a.alt.tag];
        if (a.alt.if == null ? v != null : (a.alt.if != null && a.alt.if === v)) return a.alt.then;
    }
    return a.name;
}

function animBindingKey(a, tags) {
    return a.group + ':' + animResolveName(a, tags);
}

// ==================== 动作 ====================
// {kind, fields...} + execute(ctx)

function parseAction(o, where) {
    if (!o || typeof o !== 'object' || typeof o.do !== 'string') {
        throw new TapeError(where + ' 动作缺少 "do" 字段: ' + JSON.stringify(o));
    }
    const kind = o.do;
    switch (kind) {
        case 'none': return { kind, execute() {} };
        case 'swing':
            return { kind, execute(ctx) { ctx.owner.swing(); } };
        case 'apply_damage':
            return { kind, amount: o.amount != null ? o.amount : 0, execute(ctx) { ctx.owner.applyDamage(this.amount); } };
        case 'play_sound':
            return {
                kind, sound: str(o.sound, ''), vol: num(o.vol, 1.0), pitch: num(o.pitch, 1.0),
                execute(ctx) { ctx.owner.playSound(this.sound, this.vol, this.pitch); },
            };
        case 'set_tag':
            return {
                kind, name: str(o.name, ''), value: o.value != null ? String(o.value) : null,
                execute(ctx) {
                    if (this.value == null || this.value === '') delete ctx.tags[this.name];
                    else ctx.tags[this.name] = this.value;
                },
            };
        case 'spawn_particle':
            return {
                kind, id: str(o.id, ''),
                x: num(o.x, 0), y: num(o.y, 0), z: num(o.z, 0),
                vx: num(o.vx, 0), vy: num(o.vy, 0), vz: num(o.vz, 0),
                execute(ctx) { ctx.owner.spawnParticle(this.id, this.x, this.y, this.z, this.vx, this.vy, this.vz); },
            };
        default:
            throw new TapeError(where + ' 未注册的动作类型: ' + kind);
    }
}
function str(v, d) { return typeof v === 'string' || typeof v === 'number' ? String(v) : d; }
function num(v, d) { return typeof v === 'number' && isFinite(v) ? v : d; }

// ==================== 全量解析（镜像 TapeStateMachine.parse + 校验） ====================

function parse(jsonText) {
    let root;
    try {
        root = JSON.parse(jsonText);
    } catch (e) {
        throw new TapeError('JSON 解析失败: ' + e.message);
    }
    if (typeof root !== 'object' || root === null || Array.isArray(root)) {
        throw new TapeError('JSON 解析失败: 根不是对象');
    }
    const initialTags = {};
    if (root.initial_tags && typeof root.initial_tags === 'object') {
        for (const [k, v] of Object.entries(root.initial_tags)) initialTags[k] = String(v);
    }
    if (!root.tracks || typeof root.tracks !== 'object') throw new TapeError('缺少 tracks');
    const trackOrder = [];
    const tracks = {};
    for (const [trackId, to] of Object.entries(root.tracks)) {
        if (!to || typeof to !== 'object') throw new TapeError('轨 ' + trackId + ' 不是对象');
        const initial = to.initial != null ? String(to.initial) : null;
        if (initial == null) throw new TapeError('轨 ' + trackId + ' 缺少 initial');
        if (!to.states || typeof to.states !== 'object') throw new TapeError('轨 ' + trackId + ' 缺少 states');
        const states = {};
        for (const [name, so] of Object.entries(to.states)) {
            states[name] = parseState(trackId, name, so || {});
        }
        if (!(initial in states)) throw new TapeError('轨 ' + trackId + ' 的 initial 状态不存在: ' + initial);
        validateTrack(trackId, states);
        trackOrder.push(trackId);
        tracks[trackId] = { initial, states };
    }
    if (trackOrder.length === 0) throw new TapeError('tracks 为空');
    return { trackOrder, tracks, initialTags };
}

function parseState(trackId, name, o) {
    let anim = null;
    if (o.anim) anim = parseAnim(o.anim, `轨 ${trackId} 状态 ${name} `);
    const segments = [];
    if (Array.isArray(o.segments)) {
        let prevUntil = -1;
        for (const so of o.segments) {
            const until = so.until != null ? so.until : -1;
            if (!(until > prevUntil)) {
                throw new TapeError(`轨 ${trackId} 状态 ${name} 段 until 未严格递增（${until} <= ${prevUntil}）`);
            }
            prevUntil = until;
            const segAnim = so.anim ? parseAnim(so.anim, `轨 ${trackId} 状态 ${name} 段 `) : null;
            const milestones = [];
            if (Array.isArray(so.milestones)) {
                for (const mo of so.milestones) {
                    const at = mo.at != null ? mo.at : -1;
                    if (at < 0 || at > until + EPS) {
                        throw new TapeError(`轨 ${trackId} 状态 ${name} 里程碑 at=${at} 越界 [0, ${until}]`);
                    }
                    milestones.push({ at, action: parseAction(mo, `轨 ${trackId} 状态 ${name} 里程碑 `) });
                }
            }
            segments.push({
                until,
                anim: segAnim,
                milestones,
                next: so.next != null ? String(so.next) : null,
            });
        }
    }
    const exits = [];
    if (Array.isArray(o.exit)) {
        for (const eo of o.exit) {
            if (!eo.when || !eo.to) throw new TapeError(`轨 ${trackId} 状态 ${name} 转移缺少 when/to`);
            exits.push({
                when: parseCondition(String(eo.when), `轨 ${trackId} 状态 ${name} `),
                whenText: String(eo.when),
                to: String(eo.to),
                priority: eo.priority != null ? eo.priority : 0,
                after: eo.after != null ? eo.after : 0.0,
                before: eo.before != null ? eo.before : -1.0,
            });
        }
    }
    // priority 降序；同 priority 保持声明序（稳定排序）
    exits.sort((a, b) => b.priority - a.priority);
    const onEnter = [];
    if (Array.isArray(o.on_enter)) {
        for (const el of o.on_enter) onEnter.push(parseAction(el, `轨 ${trackId} 状态 ${name} on_enter `));
    }
    const terminal = !!(o.terminal);
    if (!terminal && !anim && segments.length === 0) {
        throw new TapeError(`轨 ${trackId} 状态 ${name} 无动画且无纸带段（循环态需 anim，序列态需 segments）`);
    }
    return { name, anim, segments, exits, onEnter, terminal };
}

function validateTrack(trackId, states) {
    for (const st of Object.values(states)) {
        for (const ex of st.exits) {
            if (!(ex.to in states)) {
                throw new TapeError(`轨 ${trackId} 状态 ${st.name} 转移目标不存在: ${ex.to}`);
            }
        }
        for (const seg of st.segments) {
            if (seg.next != null && !(seg.next in states)) {
                throw new TapeError(`轨 ${trackId} 状态 ${st.name} 段 next 目标不存在: ${seg.next}`);
            }
        }
    }
}

// ==================== 运行时（镜像 TapeStateMachine 引擎） ====================

class TapeMachine {
    constructor(def, initialTags) {
        this.def = def;
        this.initialTags0 = { ...(initialTags || def.initialTags) };
        this.tags = {};
        this.rts = {};
        this._resetRuntime();
    }

    static fromJson(jsonText) {
        return new TapeMachine(parse(jsonText));
    }

    _resetRuntime() {
        this.tags = { ...this.initialTags0 };
        this.rts = {};
        this._clientKeys = {};
        for (const id of this.def.trackOrder) {
            this.rts[id] = {
                state: this.def.tracks[id].initial,
                t: 0.0,
                started: false,
                fired: new Set(),
                lastBindingKey: null,
            };
        }
    }

    reset() {
        this._resetRuntime();
    }

    get trackOrder() { return this.def.trackOrder; }

    statesOf(trackId) { return this.def.tracks[trackId].states; }

    trackStates() {
        const out = {};
        for (const id of this.def.trackOrder) out[id] = this.rts[id].state;
        return out;
    }

    snapshot() {
        return this.def.trackOrder.map((id) => ({
            track: id, state: this.rts[id].state, t: this.rts[id].t,
        }));
    }

    tick(owner, driver, observer) {
        for (const id of this.def.trackOrder) this._tickTrack(id, owner, driver, observer);
    }

    _tickTrack(trackId, owner, driver, observer) {
        const rt = this.rts[trackId];
        const track = this.def.tracks[trackId];
        const def = track.states[rt.state];
        const ctx0 = { owner, tags: this.tags, track: trackId, state: rt.state, t: 0.0 };

        // 首次进入：onEnter 动作（t=0）
        if (!rt.started) {
            rt.started = true;
            for (const a of def.onEnter) {
                a.execute(ctx0);
                if (typeof owner.onTapeAction === 'function') owner.onTapeAction(trackId, rt.state, a.kind);
            }
        }

        if (def.terminal) {
            this._emitBinding(rt, trackId, def, null, owner, driver);
            rt.t += TICK_SEC;
            return;
        }

        rt.t += TICK_SEC;
        const ctx = { owner, tags: this.tags, track: trackId, state: rt.state, t: rt.t };

        // 1) 里程碑提交（单向：at <= t 且未触发）
        if (def.segments.length) {
            def.segments.forEach((seg, si) => {
                seg.milestones.forEach((m, mi) => {
                    const key = si + ':' + mi;
                    if (m.at <= rt.t + EPS && !rt.fired.has(key)) {
                        rt.fired.add(key);
                        m.action.execute(ctx);
                        if (typeof owner.onTapeAction === 'function') owner.onTapeAction(trackId, rt.state, m.action.kind);
                        if (observer && observer.onMilestone) observer.onMilestone({ track: trackId, state: rt.state, at: m.at, action: m.action.kind });
                    }
                });
            });
        }

        // 2) 条件转移（priority 降序；窗口 [after, before)；命中 = 前跳）
        for (const ex of def.exits) {
            const afterOk = rt.t + EPS >= ex.after;
            const beforeOk = ex.before < 0 || rt.t < ex.before + EPS;
            if (afterOk && beforeOk && condCheck(ex.when, ctx)) {
                if (observer && observer.onTransition) {
                    observer.onTransition({ track: trackId, from: rt.state, to: ex.to, via: 'exit', exit: ex });
                }
                this._transition(trackId, ex.to, owner, driver);
                return;
            }
        }

        // 3) 纸带前进（t 越过末段 until → next 态；缺省 = 停末帧等条件转移）
        if (def.segments.length) {
            const last = def.segments[def.segments.length - 1];
            if (rt.t + EPS >= last.until) {
                if (last.next != null) {
                    if (observer && observer.onTransition) {
                        observer.onTransition({ track: trackId, from: rt.state, to: last.next, via: 'tape', exit: null });
                    }
                    this._transition(trackId, last.next, owner, driver);
                    return; // transition 已发新绑定；fall-through 会用旧 def 误发
                }
                // else: hold_on_last_frame
            }
        }

        // 4) 动画绑定通知（仅变化时）
        const activeSeg = def.segments.length ? activeSegment(def, rt.t) : null;
        const anim = activeSeg != null ? activeSeg.anim : def.anim;
        this._emitBinding(rt, trackId, def, anim, owner, driver);
    }

    _transition(trackId, to, owner, driver) {
        const rt = this.rts[trackId];
        const track = this.def.tracks[trackId];
        rt.state = to;
        rt.t = 0.0;
        rt.fired.clear();
        rt.lastBindingKey = null;
        const def = track.states[to];
        const ctx = { owner, tags: this.tags, track: trackId, state: to, t: 0.0 };
        for (const a of def.onEnter) {
            a.execute(ctx);
            if (typeof owner.onTapeAction === 'function') owner.onTapeAction(trackId, to, a.kind);
        }
        const firstAnim = def.segments.length ? def.segments[0].anim : def.anim;
        this._emitBinding(rt, trackId, def, firstAnim, owner, driver);
    }

    _emitBinding(rt, trackId, def, anim, owner, driver) {
        if (!driver || !anim) return;
        const key = animBindingKey(anim, this.tags);
        if (key === rt.lastBindingKey) return;
        rt.lastBindingKey = key;
        driver.onAnim(trackId, rt.state, {
            group: anim.group,
            name: animResolveName(anim, this.tags),
            loop: anim.loop,
            blendIn: anim.blendIn,
        });
    }

    // 强制转移（受击/死亡等外部事件）：目标状态 t=0（前跳）。不检查条件。
    forceState(trackId, state, owner, driver, observer) {
        const rt = this.rts[trackId];
        if (!rt) return;
        const track = this.def.tracks[trackId];
        if (!track || !track.states[state]) return;
        if (observer && observer.onTransition) {
            observer.onTransition({ track: trackId, from: rt.state, to: state, via: 'force', exit: null });
        }
        this._transition(trackId, state, owner, driver);
    }

    // 客户端纯回放：不评条件、不推进 t——按 (轨,状态,t) 定位当前段动画
    clientApply(trackId, state, t, driver) {
        const tr = this.def.tracks[trackId];
        if (!tr || !driver) return;
        const def = tr.states[state];
        if (!def) return;
        const anim = def.segments.length ? activeSegment(def, t).anim : def.anim;
        if (!anim) return;
        const key = animBindingKey(anim, this.tags);
        if (key === (this._clientKeys || {})[trackId]) return;
        if (!this._clientKeys) this._clientKeys = {};
        this._clientKeys[trackId] = key;
        driver.onAnim(trackId, state, {
            group: anim.group,
            name: animResolveName(anim, this.tags),
            loop: anim.loop,
            blendIn: anim.blendIn,
        });
    }

    // 客户端 tag 镜像（alt 动画选择必须与服务端一致）
    mirrorTags(incoming) {
        this.tags = {};
        if (incoming) for (const [k, v] of Object.entries(incoming)) this.tags[k] = String(v);
    }
}

function activeSegment(def, t) {
    let cur = def.segments[def.segments.length - 1];
    for (const seg of def.segments) {
        if (t + EPS < seg.until) {
            cur = seg;
            break;
        }
    }
    return cur;
}

// ==================== 编辑器侧校验（parse + 动画存在性 + 词汇提示） ====================
// animNames = 契约声明的必备动画名列表（null = 不查动画存在性）
function validate(jsonText, opts) {
    const errors = [];
    const warnings = [];
    const o = opts || {};
    let def = null;
    try {
        def = parse(jsonText);
    } catch (e) {
        errors.push(e.message);
        return { def: null, errors, warnings };
    }
    const animSet = o.animNames ? new Set(o.animNames) : null;
    const walkAnim = (a, where) => {
        if (!a) return;
        if (animSet && !animSet.has(a.name)) errors.push(`${where} 动画 "${a.name}" 未在契约必备动画中声明`);
        if (animSet && a.alt && a.alt.then && !animSet.has(a.alt.then)) {
            errors.push(`${where} alt 动画 "${a.alt.then}" 未在契约必备动画中声明`);
        }
    };
    for (const trackId of def.trackOrder) {
        const track = def.tracks[trackId];
        for (const st of Object.values(track.states)) {
            const w = `轨 ${trackId} 状态 ${st.name}`;
            walkAnim(st.anim, w);
            for (const seg of st.segments) walkAnim(seg.anim, w + ' 段');
            for (const ex of st.exits) {
                // 未知裸名条件 = tag 读（Java 语义）→ 提示而非报错
                warnUnknownFlags(ex.whenText, w + ' 转移', warnings);
            }
        }
    }
    return { def, errors, warnings };
}

function warnUnknownFlags(text, where, warnings) {
    const re = /[A-Za-z_][A-Za-z0-9_:.\-]*/g;
    const seen = new Set();
    for (const m of text.matchAll(re)) {
        const name = m[0];
        if (seen.has(name)) continue;
        seen.add(name);
        if (FLAGS.includes(name) || name === 'tag' || name === 'all' || name === 'any' || name === 'not') continue;
        warnings.push(`${where}：条件 "${name}" 不是已知实体标志（按 tag 读处理）`);
    }
}

// UMD：node（测试）require / 浏览器 <script>（window.TapeEngine）
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        TICK_SEC, EPS, FLAGS, ACTIONS, TapeError,
        parse, validate, TapeMachine,
        parseCondition, condCheck, parseAnim, animResolveName, animBindingKey, parseAction,
    };
} else if (typeof window !== 'undefined') {
    window.TapeEngine = {
        TICK_SEC, EPS, FLAGS, ACTIONS, TapeError,
        parse, validate, TapeMachine,
        parseCondition, condCheck, parseAnim, animResolveName, animBindingKey, parseAction,
    };
}
