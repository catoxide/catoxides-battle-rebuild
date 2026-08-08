package com.catoxide.catoxidesbattlerebuild.core.behavior;

import net.minecraft.world.entity.LivingEntity;

/**
 * 受击上下文
 * <p>插件受击处理器（{@link IHurtHandler}）收到的参数载体。
 *
 * @param attacker 攻击者
 * @param target   被攻击实体
 * @param boneName 命中的骨骼名（可为 null / 空，表示非部位命中）
 * @param damage   原始伤害
 */
public record HurtContext(
        LivingEntity attacker,
        LivingEntity target,
        String boneName,
        float damage
) {}
