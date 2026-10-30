package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TimeTape 引擎单测（纯 main()，无 JUnit；javac 独立编译运行）。
 * <p>运行：见 tools/contentpack-editor/scratch/run-tape-test.ps1 或手动：
 * javac -cp gson.jar -d out (tape 包 + 本类) && java -cp out:gson ...TapeEngineTest
 * <p>覆盖：解析/校验 · 循环态条件离开 · after 窗口 · 纸带前进/里程碑单发 ·
 * 条件前跳打断 · tag alt 动画 · 多轨并行 + tag 通信 · 终态 · 确定性回放 · onEnter 单次。
 */
public final class TapeEngineTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond, Object extra) {
        if (cond) { pass++; System.out.println("  \u2713 " + name); }
        else { fail++; System.out.println("  \u2717 " + name + (extra != null ? " [" + extra + "]" : "")); }
    }

    static void check(String name, boolean cond) { check(name, cond, null); }

    // ---------- 假实体 ----------

    static final class FakeOwner implements TapeOwner {
        boolean moving = false, sprinting = false, hasTarget = false, onGround = true;
        boolean inWater = false, onClimbable = false, dead = false, swinging = false;
        final List<String> sounds = new ArrayList<>();
        final List<String> actions = new ArrayList<>();
        int damageCalls = 0;

        public boolean isMoving() { return moving; }
        public boolean isSprinting() { return sprinting; }
        public boolean hasTarget() { return hasTarget; }
        public boolean isOnGround() { return onGround; }
        public boolean isInWater() { return inWater; }
        public boolean isOnClimbable() { return onClimbable; }
        public boolean isDead() { return dead; }
        public boolean isSwinging() { return swinging; }
        public void playSound(String sound, float vol, float pitch) { sounds.add(sound); }
        public void applyDamage(float amount) { damageCalls++; }
        public void swing() { swinging = true; }
        public void spawnParticle(String id, float x, float y, float z, float vx, float vy, float vz) { actions.add("particle:" + id); }
        public void onTapeAction(String track, String state, String action) { actions.add(track + "/" + state + ":" + action); }
    }

    static final class AnimRecorder implements TapeStateMachine.AnimDriver {
        final List<String> events = new ArrayList<>(); // "track@state:group:name"
        @Override
        public void onAnim(String track, String state, TapeStateMachine.TapeAnimBinding b) {
            events.add(track + "@" + state + ":" + b.group() + ":" + b.name() + (b.loop() ? "" : "!once"));
        }
    }

    // ---------- 测试数据 ----------

    /** zombie 风格 body 轨：循环态 + attack 纸带 + 受击 alt 动画 + 终态 */
    static final String ZOMBIE_JSON = """
            {
              "initial_tags": {},
              "tracks": {
                "body": {
                  "initial": "still",
                  "states": {
                    "still": {
                      "anim": { "group": 1, "name": "still", "loop": true },
                      "exit": [
                        { "when": "is_moving", "to": "walking" },
                        { "when": "all(has_target, not(is_moving))", "to": "alert", "priority": 1 },
                        { "when": "died", "to": "die", "priority": 100 }
                      ]
                    },
                    "walking": {
                      "anim": { "group": 1, "name": "walking", "loop": true },
                      "exit": [
                        { "when": "not(is_moving)", "to": "still", "after": 1.0 },
                        { "when": "is_sprinting", "to": "running" },
                        { "when": "swinging", "to": "attack", "priority": 5 }
                      ]
                    },
                    "running": {
                      "anim": { "group": 1, "name": "running", "loop": true },
                      "exit": [ { "when": "not(is_sprinting)", "to": "walking" } ]
                    },
                    "alert": {
                      "anim": { "group": 1, "name": "alert", "loop": false, "blend_in": 0.1 },
                      "exit": [
                        { "when": "not(has_target)", "to": "still" },
                        { "when": "tag(alert_done)", "to": "aggressive" }
                      ]
                    },
                    "aggressive": {
                      "segments": [
                        { "until": 0.5, "anim": { "group": 1, "name": "aggressive" },
                          "milestones": [ { "at": 0.25, "do": "set_tag", "name": "alert_done", "value": "true" } ],
                          "next": "still" }
                      ],
                      "exit": [ { "when": "not(has_target)", "to": "still" } ]
                    },
                    "attack": {
                      "on_enter": [ { "do": "swing" } ],
                      "segments": [
                        { "until": 0.20, "anim": { "group": 1, "name": "windup" } },
                        { "until": 0.35, "anim": { "group": 1, "name": "strike" },
                          "milestones": [
                            { "at": 0.30, "do": "apply_damage" },
                            { "at": 0.30, "do": "play_sound", "sound": "entity/zombie/attack", "vol": 1.0, "pitch": 0.85 }
                          ] },
                        { "until": 0.60, "anim": { "group": 1, "name": "recover" }, "next": "still" }
                      ],
                      "exit": [ { "when": "died", "to": "die", "priority": 100 } ]
                    },
                    "hit_reaction": {
                      "on_enter": [ { "do": "set_tag", "name": "hit_dir", "value": "front" } ],
                      "segments": [
                        { "until": 0.50,
                          "anim": { "group": 1, "name": "hit_front",
                                    "alt": { "tag": "hit_dir", "if": "back", "then": "hit_back" } } }
                      ],
                      "exit": []
                    },
                    "die": {
                      "anim": { "group": 1, "name": "die", "loop": false },
                      "terminal": true
                    }
                  }
                },
                "weapon": {
                  "initial": "none",
                  "states": { "none": { "terminal": false, "anim": { "group": 4, "name": "sword_idle", "loop": true } } }
                }
              }
            }
            """;

    public static void main(String[] args) {
        testParseValidation();
        testTagCondition();
        testLoopStateAndExit();
        testAfterWindow();
        testTapeAdvanceAndMilestones();
        testInterruptJump();
        testTagAltAnim();
        testMultiTrackAndTagComms();
        testTerminalState();
        testDeterminism();
        testOnEnterOnce();

        System.out.println();
        System.out.println("=== TapeEngine: " + pass + " 通过, " + fail + " 失败 ===");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void testParseValidation() {
        System.out.println("① 解析/校验");
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        check("解析成功（2 轨）", m.trackIds().size() == 2, m.trackIds());
        check("初始状态 still", m.trackStates().get("body").equals("still"), m.trackStates());
        // 非法：转移目标不存在
        try {
            TapeStateMachine.parse("""
                    {"tracks":{"body":{"initial":"a","states":{
                      "a":{"anim":{"name":"x"},"exit":[{"when":"is_moving","to":"ghost"}]}}}}}
                    """);
            check("转移目标缺失 → 抛异常", false);
        } catch (TapeParseException e) {
            check("转移目标缺失 → 抛异常", e.getMessage().contains("ghost"), e.getMessage());
        }
        // 非法：until 未递增
        try {
            TapeStateMachine.parse("""
                    {"tracks":{"body":{"initial":"a","states":{
                      "a":{"segments":[
                        {"until":0.5,"anim":{"name":"x"}},
                        {"until":0.3,"anim":{"name":"y"}}]}}}}}
                    """);
            check("until 非递增 → 抛异常", false);
        } catch (TapeParseException e) {
            check("until 非递增 → 抛异常", e.getMessage().contains("递增"), e.getMessage());
        }
        // 非法：里程碑越界
        try {
            TapeStateMachine.parse("""
                    {"tracks":{"body":{"initial":"a","states":{
                      "a":{"segments":[{"until":0.5,"anim":{"name":"x"},
                        "milestones":[{"at":0.9,"do":"swing"}]}]}}}}}
                    """);
            check("里程碑越界 → 抛异常", false);
        } catch (TapeParseException e) {
            check("里程碑越界 → 抛异常", e.getMessage().contains("越界"), e.getMessage());
        }
        // 非法：条件不可解析
        try {
            TapeCondition.parse("all(is_moving, )");
            check("条件解析失败 → 抛异常", false);
        } catch (TapeParseException e) {
            check("条件解析失败 → 抛异常", true);
        }
    }

    /** 2026-10-14：tag(x)=v / tag(x)!=v / tag(x) 解析与求值（旧 startsWithFn 实现解析不了带操作符写法） */
    static void testTagCondition() {
        System.out.println("② tag 条件");
        FakeOwner o = new FakeOwner();
        Map<String, String> tags = new java.util.HashMap<>();
        tags.put("hit_dir", "back");
        TapeContext ctx = new TapeContext(o, tags, "body", "x", 0.0);
        check("tag(hit_dir)=back 为真", TapeCondition.parse("tag(hit_dir)=back").check(ctx));
        check("tag(hit_dir)=front 为假", !TapeCondition.parse("tag(hit_dir)=front").check(ctx));
        check("tag(hit_dir)!=front 为真", TapeCondition.parse("tag(hit_dir)!=front").check(ctx));
        check("tag(hit_dir) 裸读为真", TapeCondition.parse("tag(hit_dir)").check(ctx));
        check("tag(missing) 裸读为假", !TapeCondition.parse("tag(missing)").check(ctx));
        check("组合 all(has_target, tag(hit_dir)=back) 可解析",
                TapeCondition.parse("all(has_target, tag(hit_dir)=back)").check(ctx) == o.hasTarget());
        try {
            TapeCondition.parse("tag(hit_dir)~front");
            check("tag 非法操作符 → 抛异常", false);
        } catch (TapeParseException e) {
            check("tag 非法操作符 → 抛异常", e.getMessage().contains("tag"), e.getMessage());
        }
    }

    static void testLoopStateAndExit() {
        System.out.println("② 循环态 + 条件离开");
        FakeOwner o = new FakeOwner();
        AnimRecorder r = new AnimRecorder();
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        m.tick(o, r);
        check("首 tick 仍在 still", m.trackStates().get("body").equals("still"));
        check("绑定 still", r.events.contains("body@still:1:still"), r.events);
        o.moving = true;
        m.tick(o, r);
        check("is_moving → walking", m.trackStates().get("body").equals("walking"), m.trackStates());
        check("绑定 walking", r.events.contains("body@walking:1:walking"), r.events);
        o.sprinting = true;
        m.tick(o, r);
        check("is_sprinting → running", m.trackStates().get("body").equals("running"), m.trackStates());
        o.sprinting = false;
        m.tick(o, r);
        check("非疾跑 → 回 walking", m.trackStates().get("body").equals("walking"), m.trackStates());
    }

    static void testAfterWindow() {
        System.out.println("③ after 窗口（数据化 hold）");
        FakeOwner o = new FakeOwner();
        AnimRecorder r = new AnimRecorder();
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        o.moving = true;
        for (int i = 0; i < 1; i++) m.tick(o, r);      // → walking
        o.moving = false;
        for (int i = 0; i < 19; i++) m.tick(o, r);     // 0.95s < 1.0s 窗口内
        check("窗口内保持 walking（0.95s）", m.trackStates().get("body").equals("walking"), m.trackStates());
        m.tick(o, r);                                    // 1.0s
        check("窗口后 → still（1.0s）", m.trackStates().get("body").equals("still"), m.trackStates());
    }

    static void testTapeAdvanceAndMilestones() {
        System.out.println("④ 纸带前进 + 里程碑单发");
        FakeOwner o = new FakeOwner();
        AnimRecorder r = new AnimRecorder();
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        o.moving = true;
        m.tick(o, r);                                   // → walking
        o.swinging = true;
        m.tick(o, r);                                   // → attack（onEnter: swing）
        check("进入 attack", m.trackStates().get("body").equals("attack"), m.trackStates());
        // 转移 tick 不推进 t（进入后 t=0 重置）；段: windup[0,0.20) strike[0.20,0.35) recover[0.35,0.60]
        m.tick(o, r);                                   // t=0.05
        check("段1 windup", r.events.get(r.events.size() - 1).contains("1:windup"), r.events);
        for (int i = 0; i < 3; i++) m.tick(o, r);       // t=0.10/0.15/0.20 → 越过 0.20 进 strike 段
        check("段2 strike（t=0.20）", r.events.get(r.events.size() - 1).contains("1:strike"), r.events);
        for (int i = 0; i < 2; i++) m.tick(o, r);       // t=0.25/0.30 → 里程碑 at=0.30 触发
        check("里程碑 apply_damage 触发", o.damageCalls == 1, o.damageCalls);
        check("里程碑 play_sound 触发", o.sounds.stream().anyMatch(s -> s.equals("entity/zombie/attack")), o.sounds);
        for (int i = 0; i < 6; i++) m.tick(o, r);       // t=0.35…0.60 → 纸带终点 next=still
        check("纸带终点 → still", m.trackStates().get("body").equals("still"), m.trackStates());
        check("里程碑只发一次（damage=1）", o.damageCalls == 1, o.damageCalls);
        // 里程碑单发再验证：重入 attack 后重新触发（新纸带 = 新一次前进，允许）
        o.swinging = true;
        for (int i = 0; i < 12; i++) { m.tick(o, r); o.moving = true; }
        o.moving = false;
        check("重入后 damage 再触发（=2）", o.damageCalls == 2, o.damageCalls);
    }

    static void testInterruptJump() {
        System.out.println("⑤ 条件前跳打断（不回退不重放）");
        FakeOwner o = new FakeOwner();
        AnimRecorder r = new AnimRecorder();
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        o.moving = true;
        m.tick(o, r);
        o.swinging = true;
        m.tick(o, r);                                   // attack t=0.05
        // 在 attack 中途 died → 前跳 die（priority 100）
        o.dead = true;
        m.tick(o, r);
        check("died 前跳 die", m.trackStates().get("body").equals("die"), m.trackStates());
        check("die 绑定 once", r.events.get(r.events.size() - 1).endsWith(":die!once"), r.events);
    }

    static void testTagAltAnim() {
        System.out.println("⑥ tag alt 动画选择");
        FakeOwner o = new FakeOwner();
        AnimRecorder r = new AnimRecorder();
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        // 手工把状态推到 hit_reaction：用 still→... 不直接可达，改用 tags + 直接验证 resolveName
        TapeStateMachine.TapeAnimBinding b1 = null, b2 = null;
        // 通过 alt 解析逻辑验证
        var anim = m.states("body").get("hit_reaction").segments().get(0).anim();
        b1 = new TapeStateMachine.TapeAnimBinding(anim.group(), anim.resolveName(Map.of("hit_dir", "back")), anim.loop(), anim.blendIn());
        b2 = new TapeStateMachine.TapeAnimBinding(anim.group(), anim.resolveName(Map.of("hit_dir", "front")), anim.loop(), anim.blendIn());
        check("hit_dir=back → hit_back", b1.name().equals("hit_back"), b1);
        check("hit_dir=front → hit_front", b2.name().equals("hit_front"), b2);
        // onEnter set_tag 生效：手动触发进入（经 alert→aggressive 路径太长，直接查 tag 存储行为）
        check("tag 存储可读", m.tags() != null, m.tags());
    }

    static void testMultiTrackAndTagComms() {
        System.out.println("⑦ 多轨并行 + tag 通信");
        FakeOwner o = new FakeOwner();
        AnimRecorder r = new AnimRecorder();
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        m.tick(o, r);
        Map<String, String> st = m.trackStates();
        check("body=still 且 weapon=none 并行", st.get("body").equals("still") && st.get("weapon").equals("none"), st);
        check("weapon 轨绑定已通知", r.events.contains("weapon@none:4:sword_idle"), r.events);
        // tag 通信：body 轨动作写 tag，weapon 轨条件可读（此处验证同一 Map 实例语义）
        m.tags().put("weapon_mode", "drawn");
        check("共享 tag 存储", "drawn".equals(m.tags().get("weapon_mode")));
    }

    static void testTerminalState() {
        System.out.println("⑧ 终态冻结");
        FakeOwner o = new FakeOwner();
        AnimRecorder r = new AnimRecorder();
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        o.dead = true;
        m.tick(o, r);                                   // still --died--> die
        check("进入 die", m.trackStates().get("body").equals("die"), m.trackStates());
        o.moving = true; o.swinging = true;             // 终态不受条件影响（die 无 exits）
        for (int i = 0; i < 50; i++) m.tick(o, r);
        check("50 tick 后仍 die", m.trackStates().get("body").equals("die"), m.trackStates());
    }

    static void testDeterminism() {
        System.out.println("⑨ 确定性回放（同输入同输出）");
        List<String> run1 = simulate();
        List<String> run2 = simulate();
        check("两次运行同步序列完全一致", run1.equals(run2), run1.size() + " vs " + run2.size());
        check("序列非平凡（>50 事件）", run1.size() > 50, run1.size());
    }

    /** 固定输入脚本：moving/sprinting/swinging/dead 按 tick 切换 */
    static List<String> simulate() {
        FakeOwner o = new FakeOwner();
        AnimRecorder r = new AnimRecorder();
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        List<String> syncs = new ArrayList<>();
        for (int t = 0; t < 120; t++) {
            o.moving = (t % 40) < 30;
            o.sprinting = (t % 40) >= 20 && o.moving;
            o.swinging = (t % 60) == 30;
            o.dead = t > 115;
            m.tick(o, r);
            for (TapeStateMachine.TrackSync s : m.snapshot()) syncs.add(s.track() + s.state() + s.t());
        }
        return syncs;
    }

    static void testOnEnterOnce() {
        System.out.println("⑩ onEnter 单次执行");
        FakeOwner o = new FakeOwner();
        AnimRecorder r = new AnimRecorder();
        TapeStateMachine m = TapeStateMachine.parse(ZOMBIE_JSON);
        o.moving = true;
        m.tick(o, r);
        o.swinging = true;
        m.tick(o, r);                                   // → attack（onEnter swing）
        long swingAfterEnter = o.actions.stream().filter(a -> a.endsWith(":swing")).count();
        check("onEnter swing 执行一次", swingAfterEnter == 1, o.actions);
        for (int i = 0; i < 5; i++) m.tick(o, r);      // 停留 attack 中
        long swingAfterStay = o.actions.stream().filter(a -> a.endsWith(":swing")).count();
        check("停留期间不重发 onEnter", swingAfterStay == 1, o.actions);
    }
}
