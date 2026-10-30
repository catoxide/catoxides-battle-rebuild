package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

import java.nio.file.Files;
import java.nio.file.Path;

/** zombie-4 状态机 JSON 解析验证（一次性）：javac 独立编译运行 */
public final class Zombie4JsonCheck {
    public static void main(String[] args) throws Exception {
        String json = Files.readString(Path.of(args[0]));
        TapeStateMachine m = TapeStateMachine.parse(json);
        System.out.println("解析 OK: 轨=" + m.trackIds() + " 初始=" + m.trackStates());
        // 行为流：still→walking→[索敌]alert 抬手 0.75s→running→[站定]aggressive→attack→settle
        //       →aggressive→[丢目标]still→[受击]hit_reaction→still→[再索敌]alert
        FakeOwner o = new FakeOwner();
        AnimLog log = new AnimLog();
        m.tick(o, log);                                   // still t=0.05
        o.moving = true; m.tick(o, log);
        check("walking（无目标移动）", "walking", m.trackStates().get("body"));
        o.hasTarget = true; m.tick(o, log);               // 索敌瞬间（无目标态 + has_target p2）
        check("alert（索敌单次抬手）", "alert", m.trackStates().get("body"));
        for (int i = 0; i < 15; i++) m.tick(o, log);      // 0.75s 抬手窗口 → has_target+is_moving → running
        check("running（抬手后继续追击）", "running", m.trackStates().get("body"));
        o.moving = false; m.tick(o, log);                 // 有目标站定 → aggressive（p0，无 after）
        check("aggressive（站定警戒姿态）", "aggressive", m.trackStates().get("body"));
        o.swinging = true; m.tick(o, log);
        check("attack（挥击 p5 前跳）", "attack", m.trackStates().get("body"));
        for (int i = 0; i < 6; i++) m.tick(o, log);       // t=0.30（6tick 挥动时长）→ 纸带终点 next=settle
        check("settle（挥击回落）", "settle", m.trackStates().get("body"));
        o.swinging = false;
        m.tick(o, log);                                    // settle t=0.05（保持窗口内，转移未就绪）
        m.tick(o, log);                                    // settle t=0.10 → has_target → aggressive
        check("aggressive（攻击后回落站定）", "aggressive", m.trackStates().get("body"));
        o.hasTarget = false; m.tick(o, log);              // 目标丢失 → still
        check("still（目标丢失）", "still", m.trackStates().get("body"));
        m.tags().put("hit_dir", "back");
        m.forceState(o, log, "body", "hit_reaction");     // 受击 hook（任意态强制前跳）
        check("hit_reaction（受击 0.5s）", "hit_reaction", m.trackStates().get("body"));
        for (int i = 0; i < 10; i++) m.tick(o, log);      // 0.5s → 无目标站定 → still
        check("still（受击结束）", "still", m.trackStates().get("body"));
        o.hasTarget = true; m.tick(o, log);               // 重新索敌 → 抬手重播（边沿由状态结构保证）
        check("alert（重新索敌再抬手）", "alert", m.trackStates().get("body"));
        System.out.println("动画事件: " + log.events);
        if (fail > 0) { System.out.println("ZOMBIE4 JSON CHECK FAIL (" + fail + " 项)"); System.exit(1); }
        System.out.println("ZOMBIE4 JSON CHECK PASS (" + pass + " 项)");
    }

    static int pass = 0, fail = 0;
    static void check(String label, String expected, String actual) {
        if (expected.equals(actual)) { pass++; System.out.println("  \u2713 " + label + " = " + actual); }
        else { fail++; System.out.println("  \u2717 " + label + " 期望=" + expected + " 实际=" + actual); }
    }

    static final class AnimLog implements TapeStateMachine.AnimDriver {
        final java.util.List<String> events = new java.util.ArrayList<>();
        public void onAnim(String track, String state, TapeStateMachine.TapeAnimBinding b) {
            events.add(state + ":" + b.name());
        }
    }

    static final class FakeOwner implements TapeOwner {
        boolean moving, sprinting, hasTarget, onGround = true, inWater, onClimbable, dead, swinging;
        public boolean isMoving() { return moving; }
        public boolean isSprinting() { return sprinting; }
        public boolean hasTarget() { return hasTarget; }
        public boolean isOnGround() { return onGround; }
        public boolean isInWater() { return inWater; }
        public boolean isOnClimbable() { return onClimbable; }
        public boolean isDead() { return dead; }
        public boolean isSwinging() { return swinging; }
        public void playSound(String s, float v, float p) {}
        public void applyDamage(float a) {}
        public void swing() {}
        public void spawnParticle(String id, float x, float y, float z, float vx, float vy, float vz) {}
    }
}
