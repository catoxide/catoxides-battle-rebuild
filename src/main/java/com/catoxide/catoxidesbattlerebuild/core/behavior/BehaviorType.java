package com.catoxide.catoxidesbattlerebuild.core.behavior;

/**
 * 行为能力点清单
 * <p>定义主 mod 向插件开放的行为分发点。插件通过 {@link BehaviorRouter} 注册对应类型的处理器，
 * 即可接管/参与该行为的处理。这是插件体系的「能力清单」——插件能接管哪些行为，
 * 取决于这里列出了哪些类型（以及对应的 Router 方法是否实现）。
 *
 * <p>当前已实现：{@link #HURT}（受击）。
 * 其余为预留能力点，后续按相同模式实现。
 */
public enum BehaviorType {
    /** 受击处理（部位伤害 / 自定义伤害逻辑）—— 已实现 */
    HURT,
    /** 攻击处理 */
    ATTACK,
    /** 死亡处理 */
    DEATH,
    /** 每 tick 行为 */
    TICK,
    /** 目标选择 */
    TARGETING;
}
