// tapeDoc.js — TimeTape 状态机 JSON 文档操作（纯逻辑，无 DOM；编辑器视图 + 测试共用）
// 文档形态 = 引擎 JSON：{format_version, initial_tags, tracks:{id:{initial,states:{name:{...}}}}}
// 引擎语义/词汇表见 core/tapeEngine.js（单一真源 = 主 mod TapeStateMachine.java）

'use strict';

const Engine = (typeof require !== 'undefined' && typeof module !== 'undefined')
    ? require('./tapeEngine')
    : window.TapeEngine;

// ---------- 读取/序列化 ----------

function loadDoc(jsonText) {
    let doc;
    try {
        doc = JSON.parse(jsonText);
    } catch (e) {
        return { ok: false, error: 'JSON 解析失败: ' + e.message };
    }
    if (typeof doc !== 'object' || doc === null || typeof doc.tracks !== 'object' || doc.tracks === null) {
        return { ok: false, error: '缺少 tracks 对象' };
    }
    if (!doc.format_version) doc.format_version = '1.0.0';
    if (!doc.initial_tags || typeof doc.initial_tags !== 'object') doc.initial_tags = {};
    return { ok: true, doc };
}

function serialize(doc) {
    return JSON.stringify(doc, null, 2) + '\n';
}

// ---------- 结构查询 ----------

function listTracks(doc) { return Object.keys(doc.tracks); }
function listStates(doc, track) { return Object.keys(doc.tracks[track].states); }
function getState(doc, track, name) { return doc.tracks[track].states[name]; }

function suggestedName(doc, track, base) {
    const names = new Set(listStates(doc, track));
    if (!names.has(base)) return base;
    let i = 2;
    while (names.has(base + i)) i++;
    return base + i;
}

// 引用某状态的所有位置：[{kind:'initial'|'exit'|'next', where}]
function findRefs(doc, track, name) {
    const refs = [];
    const tr = doc.tracks[track];
    if (tr.initial === name) refs.push({ kind: 'initial', where: 'track.initial' });
    for (const [sn, st] of Object.entries(tr.states)) {
        (st.exit || []).forEach((ex, i) => {
            if (ex.to === name) refs.push({ kind: 'exit', where: `${sn}.exit[${i}]` });
        });
        (st.segments || []).forEach((seg, i) => {
            if (seg.next === name) refs.push({ kind: 'next', where: `${sn}.segments[${i}].next` });
        });
    }
    return refs;
}

// ---------- 结构变更（doc 原地修改；返回 {ok, error?, refs?}） ----------

function addTrack(doc, id) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(id)) return { ok: false, error: '轨名需为 [A-Za-z_][A-Za-z0-9_]*' };
    if (doc.tracks[id]) return { ok: false, error: '轨已存在: ' + id };
    doc.tracks[id] = { initial: 'start', states: { start: { anim: { group: 1, name: 'start', loop: true } } } };
    return { ok: true };
}

function removeTrack(doc, id) {
    if (listTracks(doc).length <= 1) return { ok: false, error: '至少保留一条轨' };
    delete doc.tracks[id];
    return { ok: true };
}

function addState(doc, track, name) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) return { ok: false, error: '状态名需为 [A-Za-z_][A-Za-z0-9_]*' };
    if (doc.tracks[track].states[name]) return { ok: false, error: '状态已存在: ' + name };
    // 默认 = 循环态，动画名同状态名（约定；动画存在性由校验提示）
    doc.tracks[track].states[name] = { anim: { group: 1, name, loop: true, blend_in: 0.05 } };
    return { ok: true };
}

function removeState(doc, track, name) {
    const tr = doc.tracks[track];
    if (tr.initial === name) return { ok: false, error: '不能删除初始状态（先改初始）' };
    const refs = findRefs(doc, track, name);
    if (refs.length) return { ok: false, error: '被引用: ' + refs.map((r) => r.where).join(', '), refs };
    delete tr.states[name];
    return { ok: true };
}

function renameState(doc, track, from, to) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(to)) return { ok: false, error: '状态名需为 [A-Za-z_][A-Za-z0-9_]*' };
    const tr = doc.tracks[track];
    if (!tr.states[from]) return { ok: false, error: '状态不存在: ' + from };
    if (tr.states[to]) return { ok: false, error: '状态已存在: ' + to };
    tr.states[to] = tr.states[from];
    delete tr.states[from];
    if (tr.initial === from) tr.initial = to;
    for (const st of Object.values(tr.states)) {
        for (const ex of st.exit || []) if (ex.to === from) ex.to = to;
        for (const seg of st.segments || []) if (seg.next === from) seg.next = to;
    }
    return { ok: true };
}

function setInitial(doc, track, name) {
    if (!doc.tracks[track].states[name]) return { ok: false, error: '状态不存在: ' + name };
    doc.tracks[track].initial = name;
    return { ok: true };
}

// ---------- 转移 / 纸带 / 动作 ----------

function addExit(doc, track, state, exit) {
    const st = getState(doc, track, state);
    if (!st) return { ok: false, error: '状态不存在: ' + state };
    st.exit = st.exit || [];
    st.exit.push({
        when: exit.when || 'is_moving',
        to: exit.to || state,
        ...(exit.priority ? { priority: exit.priority } : {}),
        ...(exit.after ? { after: exit.after } : {}),
        ...(exit.before != null ? { before: exit.before } : {}),
    });
    return { ok: true, index: st.exit.length - 1 };
}

function removeExit(doc, track, state, index) {
    const st = getState(doc, track, state);
    if (!st || !st.exit || !st.exit[index]) return { ok: false, error: '转移不存在' };
    st.exit.splice(index, 1);
    if (st.exit.length === 0) delete st.exit;
    return { ok: true };
}

function addSegment(doc, track, state, seg) {
    const st = getState(doc, track, state);
    if (!st) return { ok: false, error: '状态不存在: ' + state };
    if (st.anim) delete st.anim; // 序列态与循环动画互斥
    st.segments = st.segments || [];
    const lastUntil = st.segments.length ? st.segments[st.segments.length - 1].until : 0;
    st.segments.push({
        until: seg.until != null ? seg.until : lastUntil + 0.5,
        anim: seg.anim || { group: 1, name: state, loop: false },
        ...(seg.next ? { next: seg.next } : {}),
    });
    return { ok: true, index: st.segments.length - 1 };
}

function removeSegment(doc, track, state, index) {
    const st = getState(doc, track, state);
    if (!st || !st.segments || !st.segments[index]) return { ok: false, error: '段不存在' };
    st.segments.splice(index, 1);
    if (st.segments.length === 0) {
        delete st.segments;
        if (!st.anim) st.anim = { group: 1, name: state, loop: true, blend_in: 0.05 };
    }
    return { ok: true };
}

function addMilestone(doc, track, state, segIndex, ms) {
    const st = getState(doc, track, state);
    const seg = st && st.segments && st.segments[segIndex];
    if (!seg) return { ok: false, error: '段不存在' };
    seg.milestones = seg.milestones || [];
    seg.milestones.push({ at: ms.at != null ? ms.at : 0, do: ms.do || 'none' });
    return { ok: true };
}

function addOnEnter(doc, track, state, action) {
    const st = getState(doc, track, state);
    if (!st) return { ok: false, error: '状态不存在: ' + state };
    st.on_enter = st.on_enter || [];
    st.on_enter.push(action || { do: 'none' });
    return { ok: true };
}

function setTags(doc, tags) {
    doc.initial_tags = {};
    if (tags) for (const [k, v] of Object.entries(tags)) {
        if (k && v != null && String(v) !== '') doc.initial_tags[k] = String(v);
    }
    return { ok: true };
}

// ---------- 自动布局（BFS 分层；不可达状态放最后） ----------
// 返回 { "track:state": {x, y} }

function autoLayout(doc, opts) {
    const o = opts || {};
    const COL = o.col || 280;
    const ROW = o.row || 150;
    const pos = {};
    for (const track of listTracks(doc)) {
        const tr = doc.tracks[track];
        const names = listStates(doc, track);
        const depth = { [tr.initial]: 0 };
        const queue = [tr.initial];
        while (queue.length) {
            const cur = queue.shift();
            const st = tr.states[cur];
            const targets = new Set();
            for (const ex of st.exit || []) targets.add(ex.to);
            for (const seg of st.segments || []) if (seg.next) targets.add(seg.next);
            for (const t2 of targets) {
                if (tr.states[t2] && depth[t2] == null) {
                    depth[t2] = depth[cur] + 1;
                    queue.push(t2);
                }
            }
        }
        // 不可达（含环外）：按声明序排到最大深度之后
        let maxD = 0;
        for (const d of Object.values(depth)) maxD = Math.max(maxD, d);
        const byDepth = new Map();
        for (const n of names) {
            const d = depth[n] != null ? depth[n] : maxD + 1;
            if (!byDepth.has(d)) byDepth.set(d, []);
            byDepth.get(d).push(n);
        }
        let row = 0;
        for (const d of [...byDepth.keys()].sort((a, b) => a - b)) {
            for (const n of byDepth.get(d)) {
                pos[track + ':' + n] = { x: 40 + d * COL, y: 40 + row * ROW };
                row++;
            }
        }
    }
    return pos;
}

// ---------- 校验（引擎解析 + 动画存在性） ----------

function validateDoc(doc, animNames) {
    return Engine.validate(serialize(doc), { animNames: animNames || null });
}

// ---------- 动作 JSON 辅助（检查器 UI 用） ----------
// 每类动作的可编辑字段：[key, 默认值]（do 单独选）

const ACTION_FIELDS = {
    none: [],
    swing: [],
    apply_damage: [['amount', 0]],
    play_sound: [['sound', ''], ['vol', 1.0], ['pitch', 1.0]],
    set_tag: [['name', ''], ['value', '']],
    spawn_particle: [['id', ''], ['x', 0], ['y', 0], ['z', 0], ['vx', 0], ['vy', 0], ['vz', 0]],
};

function actionToFields(action) {
    const out = { do: action.do || 'none' };
    for (const [k, d] of ACTION_FIELDS[out.do] || []) out[k] = action[k] != null ? action[k] : d;
    return out;
}

function fieldsToAction(fields) {
    const do_ = fields.do || 'none';
    const out = { do: do_ };
    for (const [k] of ACTION_FIELDS[do_] || []) {
        if (k === 'sound' || k === 'name' || k === 'id') {
            if (fields[k] != null && String(fields[k]) !== '') out[k] = String(fields[k]);
        } else if (fields[k] != null && String(fields[k]) !== '') {
            out[k] = Number(fields[k]);
        }
    }
    if (do_ === 'set_tag' && fields.name != null && String(fields.name) !== '') {
        // value 可空 = 清除 tag
        if (fields.value != null && String(fields.value) !== '') out.value = String(fields.value);
    }
    return out;
}

// UMD：node（测试）require / 浏览器 <script>（window.TapeDoc）
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        Engine,
        loadDoc, serialize, listTracks, listStates, getState, suggestedName, findRefs,
        addTrack, removeTrack, addState, removeState, renameState, setInitial,
        addExit, removeExit, addSegment, removeSegment, addMilestone, addOnEnter, setTags,
        autoLayout, validateDoc, ACTION_FIELDS, actionToFields, fieldsToAction,
    };
} else if (typeof window !== 'undefined') {
    window.TapeDoc = {
        Engine,
        loadDoc, serialize, listTracks, listStates, getState, suggestedName, findRefs,
        addTrack, removeTrack, addState, removeState, renameState, setInitial,
        addExit, removeExit, addSegment, removeSegment, addMilestone, addOnEnter, setTags,
        autoLayout, validateDoc, ACTION_FIELDS, actionToFields, fieldsToAction,
    };
}
