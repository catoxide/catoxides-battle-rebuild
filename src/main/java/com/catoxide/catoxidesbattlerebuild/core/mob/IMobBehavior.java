package com.catoxide.catoxidesbattlerebuild.core.mob;

import com.catoxide.catoxidesbattlerebuild.core.behavior.HurtContext;

/**
 * 数据驱动实体的行为组件接口
 * <p>实体定义 JSON 中 {@code behaviors} 数组装配出的一组可插拔行为。
 * 组件由 {@link IBehaviorFactory} 按 type 创建，装配到 {@link DataDrivenMob}。
 *
 * <p>所有方法默认空实现——组件只覆盖自己关心的生命周期。
 * <b>「接管式」语义</b>：返回 boolean 的方法返回 true 表示已处理（短路默认逻辑）。
 */
public interface IMobBehavior {

    /** 装配完成后（实体构造时）调用一次 */
    default void onRegister(DataDrivenMob mob) {}

    /** 实体生成时调用（服务端） */
    default void onSpawn(DataDrivenMob mob) {}

    /** 每 tick 调用（服务端） */
    default void tick(DataDrivenMob mob) {}

    /**
     * 受击回调（实体被攻击时）。
     * @return true = 已接管该次受击（短路默认受击处理）
     */
    default boolean onHit(DataDrivenMob mob, HurtContext ctx) { return false; }

    /** 死亡时调用 */
    default void onDeath(DataDrivenMob mob) {}

    /** 注册 AI Goal（在 Mob.registerGoals() 中调用） */
    default void registerGoals(DataDrivenMob mob) {}
}
