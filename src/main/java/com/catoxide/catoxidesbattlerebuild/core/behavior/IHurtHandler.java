package com.catoxide.catoxidesbattlerebuild.core.behavior;

import net.minecraft.world.entity.LivingEntity;

/**
 * 受击行为处理器接口
 * <p>插件通过 {@link BehaviorRouter#registerHurtHandler(IHurtHandler)} 注册，
 * 即可接管主 mod 的受击伤害链路（原 {@code DamageProcessor} 逻辑）。
 *
 * <p><b>接管语义</b>：{@link #handles} 判断是否处理该实体；
 * {@link #handleHurt} 返回 {@code true} 表示已完全接管（短路默认伤害逻辑），
 * 返回 {@code false} 表示放弃处理、继续走默认逻辑。
 */
public interface IHurtHandler {

    /**
     * 判断是否处理该实体（按实体类型 / 特性 / 命名空间筛选）。
     */
    boolean handles(LivingEntity target);

    /**
     * 处理受击事件。
     *
     * @return true = 已接管（短路默认伤害逻辑）；false = 不处理，走默认
     */
    boolean handleHurt(HurtContext ctx);
}
