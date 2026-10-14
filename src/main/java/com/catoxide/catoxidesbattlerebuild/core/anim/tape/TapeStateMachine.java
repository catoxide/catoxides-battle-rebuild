package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * TimeTape 引擎：多轨 + 单向时间纸带状态机（纯 Java，零 MC 依赖）。
 *
 * <h3>时间语义（不变式）</h3>
 * <ul>
 *   <li>每状态本地时钟 t 只增（20Hz 固定步进 0.05s）；状态离开后其里程碑永不补发/重放</li>
 *   <li>序列态 = 纸带：段按 untilSec 绝对时间戳推进；t 越界 = 前进下一段/目标状态，禁止回退</li>
 *   <li>条件转移 = 前跳：目标状态 t 归零重新计（不是回到旧纸带）</li>
 *   <li>循环（A→A 显式边）语义上是新的一次前进</li>
 * </ul>
 *
 * <h3>tick 顺序（每轨）</h3>
 * <pre>
 * onEnter(首次) → t += 0.05 → 里程碑提交(at <= t 且未触发) → 条件转移(priority 降序，命中即跳)
 *   → 纸带前进(t 越过末段 until → next 态或停末帧) → 动画绑定通知(变化才通知)
 * </pre>
 */
public final class TapeStateMachine {

    /** 固定时间步（秒）：20Hz，与服务端 tick 对齐 */
    public static final double TICK_SEC = 0.05;
    private static final double EPS = 1e-6;

    /** 动画驱动：引擎发现某轨绑定变化时通知（P2 = Spark AnimController 适配；单测 = 记录器） */
    public interface AnimDriver {
        void onAnim(String track, String state, TapeAnimBinding binding);
    }

    /** 动画绑定（已按 alt/tag 解析的最终值） */
    public record TapeAnimBinding(int group, String name, boolean loop, float blendIn) {}

    /** 轨同步快照（客户端纯回放输入） */
    public record TrackSync(String track, String state, float t) {}

    private final Map<String, TapeTrackDef> tracks;          // 插入序
    private final Map<String, String> tags;                  // 跨轨共享 tag 存储
    private final Map<String, TrackRuntime> runtimes;
    private final Map<String, List<TapeExit>> sortedExits;   // "track:state" → priority 降序

    private static final class TrackRuntime {
        String state;
        double t = 0.0;
        boolean started = false;
        final Set<String> fired = new java.util.HashSet<>(); // "segIdx:mi"
        String lastBindingKey = null;
    }

    private TapeStateMachine(Map<String, TapeTrackDef> tracks, Map<String, String> initialTags) {
        this.tracks = tracks;
        this.tags = new LinkedHashMap<>(initialTags);
        this.runtimes = new LinkedHashMap<>();
        this.sortedExits = new LinkedHashMap<>();
        for (TapeTrackDef tr : tracks.values()) {
            TrackRuntime rt = new TrackRuntime();
            rt.state = tr.initial();
            runtimes.put(tr.id(), rt);
            for (Map.Entry<String, TapeStateDef> e : tr.states().entrySet()) {
                List<TapeExit> exits = e.getValue().exits() == null ? List.of() : e.getValue().exits();
                List<TapeExit> sorted = new ArrayList<>(exits);
                // priority 降序；同 priority 保持声明序（稳定排序）
                sorted.sort(Comparator.comparingInt((TapeExit x) -> x.priority()).reversed());
                sortedExits.put(tr.id() + ":" + e.getKey(), sorted);
            }
        }
    }

    // ==================== 解析（Gson，惯例同 MobDefinitionParser） ====================

    /**
     * 解析状态机 JSON 并做全量校验（引用完整性/纸带单调性/条件可解析）。
     * 格式见 design/STATE_MACHINE_DESIGN.md §2.2。
     */
    public static TapeStateMachine parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new TapeParseException("JSON 解析失败: " + e.getMessage());
        }
        Map<String, String> initialTags = new LinkedHashMap<>();
        if (root.has("initial_tags")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("initial_tags").entrySet()) {
                initialTags.put(e.getKey(), e.getValue().getAsString());
            }
        }
        if (!root.has("tracks")) throw new TapeParseException("缺少 tracks");
        Map<String, TapeTrackDef> tracks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> te : root.getAsJsonObject("tracks").entrySet()) {
            String trackId = te.getKey();
            JsonObject to = te.getValue().getAsJsonObject();
            String initial = to.has("initial") ? to.get("initial").getAsString() : null;
            if (initial == null) throw new TapeParseException("轨 " + trackId + " 缺少 initial");
            if (!to.has("states")) throw new TapeParseException("轨 " + trackId + " 缺少 states");
            Map<String, TapeStateDef> states = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> se : to.getAsJsonObject("states").entrySet()) {
                states.put(se.getKey(), parseState(trackId, se.getKey(), se.getValue().getAsJsonObject()));
            }
            if (!states.containsKey(initial)) {
                throw new TapeParseException("轨 " + trackId + " 的 initial 状态不存在: " + initial);
            }
            validateTrack(trackId, states);
            tracks.put(trackId, new TapeTrackDef(trackId, initial, states));
        }
        if (tracks.isEmpty()) throw new TapeParseException("tracks 为空");
        return new TapeStateMachine(tracks, initialTags);
    }

    private static TapeStateDef parseState(String trackId, String name, JsonObject o) {
        TapeAnimDef loopAnim = null;
        if (o.has("anim")) loopAnim = parseAnim(o.getAsJsonObject("anim"));
        List<TapeSegment> segments = new ArrayList<>();
        if (o.has("segments")) {
            double prevUntil = -1;
            for (JsonElement el : o.getAsJsonArray("segments")) {
                JsonObject so = el.getAsJsonObject();
                double until = so.has("until") ? so.get("until").getAsDouble() : -1;
                if (until <= prevUntil) {
                    throw new TapeParseException("轨 " + trackId + " 状态 " + name
                            + " 段 until 未严格递增（" + until + " <= " + prevUntil + "）");
                }
                prevUntil = until;
                TapeAnimDef anim = so.has("anim") ? parseAnim(so.getAsJsonObject("anim")) : null;
                List<TapeMilestone> ms = new ArrayList<>();
                if (so.has("milestones")) {
                    for (JsonElement me : so.getAsJsonArray("milestones")) {
                        JsonObject mo = me.getAsJsonObject();
                        double at = mo.has("at") ? mo.get("at").getAsDouble() : -1;
                        if (at < 0 || at > until + EPS) {
                            throw new TapeParseException("轨 " + trackId + " 状态 " + name
                                    + " 里程碑 at=" + at + " 越界 [0, " + until + "]");
                        }
                        ms.add(new TapeMilestone(at, TapeAction.parse(mo)));
                    }
                }
                String next = so.has("next") ? so.get("next").getAsString() : null;
                segments.add(new TapeSegment(until, anim, ms, next));
            }
        }
        List<TapeExit> exits = new ArrayList<>();
        if (o.has("exit")) {
            for (JsonElement el : o.getAsJsonArray("exit")) {
                JsonObject eo = el.getAsJsonObject();
                if (!eo.has("when") || !eo.has("to")) {
                    throw new TapeParseException("轨 " + trackId + " 状态 " + name + " 转移缺少 when/to");
                }
                TapeCondition when = TapeCondition.parse(eo.get("when").getAsString());
                String to = eo.get("to").getAsString();
                int priority = eo.has("priority") ? eo.get("priority").getAsInt() : 0;
                double after = eo.has("after") ? eo.get("after").getAsDouble() : 0.0;
                double before = eo.has("before") ? eo.get("before").getAsDouble() : -1.0;
                exits.add(new TapeExit(when, to, priority, after, before));
            }
        }
        List<TapeAction> onEnter = new ArrayList<>();
        if (o.has("on_enter")) {
            for (JsonElement el : o.getAsJsonArray("on_enter")) {
                onEnter.add(TapeAction.parse(el.getAsJsonObject()));
            }
        }
        boolean terminal = o.has("terminal") && o.get("terminal").getAsBoolean();
        if (!terminal && loopAnim == null && segments.isEmpty()) {
            throw new TapeParseException("轨 " + trackId + " 状态 " + name + " 无动画且无纸带段（循环态需 anim，序列态需 segments）");
        }
        return new TapeStateDef(name, loopAnim, segments, exits, onEnter, terminal);
    }

    private static TapeAnimDef parseAnim(JsonObject o) {
        if (!o.has("name")) throw new TapeParseException("动画绑定缺少 name: " + o);
        int group = o.has("group") ? o.get("group").getAsInt() : 1;
        boolean loop = !o.has("loop") || o.get("loop").getAsBoolean();
        float blendIn = o.has("blend_in") ? o.get("blend_in").getAsFloat() : 0.05f;
        String altTag = null, altIf = null, altThen = null;
        if (o.has("alt")) {
            JsonObject a = o.getAsJsonObject("alt");
            altTag = a.has("tag") ? a.get("tag").getAsString() : null;
            altIf = a.has("if") ? a.get("if").getAsString() : null;
            altThen = a.has("then") ? a.get("then").getAsString() : null;
        }
        return new TapeAnimDef(group, o.get("name").getAsString(), loop, blendIn, altTag, altIf, altThen);
    }

    private static void validateTrack(String trackId, Map<String, TapeStateDef> states) {
        for (TapeStateDef st : states.values()) {
            for (TapeExit ex : st.exits()) {
                if (!states.containsKey(ex.to())) {
                    throw new TapeParseException("轨 " + trackId + " 状态 " + st.name() + " 转移目标不存在: " + ex.to());
                }
            }
            for (TapeSegment seg : st.segments()) {
                if (seg.next() != null && !states.containsKey(seg.next())) {
                    throw new TapeParseException("轨 " + trackId + " 状态 " + st.name() + " 段 next 目标不存在: " + seg.next());
                }
            }
        }
    }

    // ==================== 运行时 ====================

    /**
     * 推进一个 tick（20Hz）。服务端唯一入口；客户端不跑引擎（纯回放）。
     *
     * @param owner  实体能力（条件读/动作写）
     * @param driver 动画绑定变化回调（可为 null = 只要状态机逻辑不要动画通知）
     */
    public void tick(TapeOwner owner, AnimDriver driver) {
        for (Map.Entry<String, TapeTrackDef> te : tracks.entrySet()) {
            tickTrack(te.getKey(), te.getValue(), owner, driver);
        }
    }

    private void tickTrack(String trackId, TapeTrackDef track, TapeOwner owner, AnimDriver driver) {
        TrackRuntime rt = runtimes.get(trackId);
        TapeStateDef def = track.state(rt.state);

        // 首次进入：onEnter 动作（t=0）
        if (!rt.started) {
            rt.started = true;
            TapeContext ctx0 = new TapeContext(owner, tags, trackId, rt.state, 0.0);
            for (TapeAction a : def.onEnter()) {
                a.execute(ctx0);
                owner.onTapeAction(trackId, rt.state, a.type());
            }
        }

        if (def.terminal()) {
            emitBinding(rt, trackId, def, null, owner, driver);
            rt.t += TICK_SEC;
            return;
        }

        rt.t += TICK_SEC;
        TapeContext ctx = new TapeContext(owner, tags, trackId, rt.state, rt.t);

        // 1) 里程碑提交（单向：at <= t 且未触发 → 触发并标记；t 只增故永不重放）
        if (def.isSequence()) {
            List<TapeSegment> segs = def.segments();
            for (int si = 0; si < segs.size(); si++) {
                List<TapeMilestone> ms = segs.get(si).milestones();
                for (int mi = 0; mi < ms.size(); mi++) {
                    TapeMilestone m = ms.get(mi);
                    String key = si + ":" + mi;
                    if (m.atSec() <= rt.t + EPS && !rt.fired.contains(key)) {
                        rt.fired.add(key);
                        m.action().execute(ctx);
                        owner.onTapeAction(trackId, rt.state, m.action().type());
                    }
                }
            }
        }

        // 2) 条件转移（priority 降序；窗口 [after, before)；命中 = 前跳）
        List<TapeExit> exits = sortedExits.get(trackId + ":" + rt.state);
        if (exits != null) {
            for (TapeExit ex : exits) {
                boolean afterOk = rt.t + EPS >= ex.afterSec();
                boolean beforeOk = ex.beforeSec() < 0 || rt.t < ex.beforeSec() + EPS;
                if (afterOk && beforeOk && ex.when().check(ctx)) {
                    transition(rt, trackId, track, ex.to(), owner, driver);
                    return;
                }
            }
        }

        // 3) 纸带前进（序列态 t 越过末段 until → next 态；缺省 = 停末帧等条件转移）
        if (def.isSequence()) {
            TapeSegment last = def.segments().get(def.segments().size() - 1);
            if (rt.t + EPS >= last.untilSec()) {
                if (last.next() != null) {
                    transition(rt, trackId, track, last.next(), owner, driver);
                    return; // 必须 return：transition 已发新绑定；fall-through 会用旧 def 误发
                }
                // else: hold_on_last_frame（停在末段动画最后一帧，继续接受条件转移）
            }
        }

        // 4) 动画绑定通知（仅变化时）
        TapeSegment activeSeg = def.isSequence() ? activeSegment(def, rt.t) : null;
        TapeAnimDef anim = activeSeg != null ? activeSeg.anim() : def.loopAnim();
        emitBinding(rt, trackId, def, anim, owner, driver);
    }

    /** 当前段：第一个 until > t 的段；t 越过全部 until = 末段（hold） */
    private static TapeSegment activeSegment(TapeStateDef def, double t) {
        TapeSegment cur = def.segments().get(def.segments().size() - 1);
        for (TapeSegment seg : def.segments()) {
            if (t + EPS < seg.untilSec()) {
                cur = seg;
                break;
            }
        }
        return cur;
    }

    private void transition(TrackRuntime rt, String trackId, TapeTrackDef track, String to,
                            TapeOwner owner, AnimDriver driver) {
        rt.state = to;
        rt.t = 0.0;
        rt.fired.clear();
        rt.lastBindingKey = null;
        TapeStateDef def = track.state(to);
        TapeContext ctx = new TapeContext(owner, tags, trackId, to, 0.0);
        for (TapeAction a : def.onEnter()) {
            a.execute(ctx);
            owner.onTapeAction(trackId, to, a.type());
        }
        emitBinding(rt, trackId, def, def.isSequence() ? def.segments().get(0).anim() : def.loopAnim(),
                owner, driver);
    }

    private void emitBinding(TrackRuntime rt, String trackId, TapeStateDef def, TapeAnimDef anim,
                             TapeOwner owner, AnimDriver driver) {
        if (driver == null || anim == null) return;
        String key = anim.bindingKey(tags);
        if (key.equals(rt.lastBindingKey)) return;
        rt.lastBindingKey = key;
        String name = anim.resolveName(tags);
        driver.onAnim(trackId, rt.state,
                new TapeAnimBinding(anim.group(), name, anim.loop(), anim.blendIn()));
    }

    /**
     * 强制转移（仅服务端调用方）：受击/死亡等外部事件。目标状态从 t=0 开始（前跳，不回退）。
     * 不检查条件——引擎调用方的权威跳转（zombie-3 受击 = hurt() 里直接 setAnimState 的数据版）。
     */
    public void forceState(TapeOwner owner, AnimDriver driver, String track, String state) {
        TrackRuntime rt = runtimes.get(track);
        if (rt == null) return;
        TapeTrackDef trackDef = tracks.get(track);
        if (trackDef == null || !trackDef.states().containsKey(state)) return;
        transition(rt, track, trackDef, state, owner, driver);
    }

    // ==================== 客户端纯回放 ====================

    private final Map<String, String> clientLastKeys = new java.util.HashMap<>();

    /**
     * 客户端回放入口（S2C 同步包驱动）：不评条件、不推进 t——按 (轨,状态,t) 定位当前段动画，
     * 绑定变化时通知 driver（= Spark 播放）。服务端 tick 与客户端回放对同数据同 t 输出一致（确定性）。
     */
    public void clientApply(String track, String state, double t, AnimDriver driver) {
        TapeTrackDef tr = tracks.get(track);
        if (tr == null || driver == null) return;
        TapeStateDef def;
        try {
            def = tr.state(state);
        } catch (TapeParseException e) {
            return; // 数据不一致（包不同步）——静默跳过
        }
        TapeAnimDef anim = def.isSequence() ? activeSegment(def, t).anim() : def.loopAnim();
        if (anim == null) return;
        String key = anim.bindingKey(tags);
        String last = clientLastKeys.get(track);
        if (key != null && key.equals(last)) return;
        clientLastKeys.put(track, key);
        driver.onAnim(track, state,
                new TapeAnimBinding(anim.group(), anim.resolveName(tags), anim.loop(), anim.blendIn()));
    }

    /**
     * 客户端 tag 镜像（纯回放）：alt 动画选择（resolveName/bindingKey 读 tag）必须与服务端一致。
     * 仅客户端调用；服务端 tags 由引擎内部动作写入。
     */
    public void mirrorTags(java.util.Map<String, String> incoming) {
        this.tags.clear();
        if (incoming != null) this.tags.putAll(incoming);
    }

    // ==================== 查询 ====================

    /** 全部轨当前 (轨, 状态, t) 快照（P2 集成层自行节流发送） */
    public List<TrackSync> snapshot() {
        List<TrackSync> out = new ArrayList<>();
        for (Map.Entry<String, TrackRuntime> e : runtimes.entrySet()) {
            TrackSync s = new TrackSync(e.getKey(), e.getValue().state, (float) e.getValue().t);
            out.add(s);
        }
        return out;
    }

    public Map<String, String> trackStates() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, TrackRuntime> e : runtimes.entrySet()) out.put(e.getKey(), e.getValue().state);
        return out;
    }

    public Map<String, String> tags() {
        return tags;
    }

    public TapeTrackDef getTrack(String id) {
        return tracks.get(id);
    }

    public Set<String> trackIds() {
        return new TreeSet<>(tracks.keySet());
    }

    /** 轨内状态表（调试/契约校验） */
    public Map<String, TapeStateDef> states(String trackId) {
        return tracks.get(trackId).states();
    }
}
