package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

/**
 * TimeTape 引擎的实体能力接口（纯 Java，无 MC 依赖）。
 * <p>引擎只通过本接口感知实体：条件读（is_*）、动作写（playSound/applyDamage/…）。
 * 服务端由 {@code AnimatedMob} 的适配层实现（P2 集成），单测用 FakeOwner。
 * <p>所有方法必须有稳定的确定性语义（同输入同输出）——客户端纯回放依赖这一点。
 */
public interface TapeOwner {

    /** 移动中（moveControl 有目标 或 导航未结束） */
    boolean isMoving();

    /** 疾跑中 */
    boolean isSprinting();

    /** 有存活目标 */
    boolean hasTarget();

    /** 在地面 */
    boolean isOnGround();

    /** 在水中 */
    boolean isInWater();

    /** 在可攀爬物（梯子/藤蔓等）上 */
    boolean isOnClimbable();

    /** 已死亡/垂死 */
    boolean isDead();

    /** 挥动中（swing 生命周期内） */
    boolean isSwinging();

    // ---------- 动作（里程碑 / onEnter 执行） ----------

    /** 播放音效（sound = 声音事件 id 字符串；vol/pitch 常规语义） */
    void playSound(String sound, float vol, float pitch);

    /** 对目标造成攻击伤害（走实体现有攻击管线；amount<=0 = 默认攻击伤害） */
    void applyDamage(float amount);

    /** 触发实体挥击生命周期（swinging=true，供 isSwinging 条件消费） */
    void swing();

    /** 生成粒子（id = 粒子类型；实体相对坐标） */
    void spawnParticle(String id, float x, float y, float z, float vx, float vy, float vz);

    /**
     * 里程碑/动作执行钩子（日志/调试/契约校验用；默认空实现）。
     * @param track 轨 id
     * @param state 当前状态
     * @param action 动作类型名
     */
    default void onTapeAction(String track, String state, String action) {}
}
