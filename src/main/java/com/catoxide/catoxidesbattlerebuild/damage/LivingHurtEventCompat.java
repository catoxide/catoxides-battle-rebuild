package com.catoxide.catoxidesbattlerebuild.damage;

import net.minecraftforge.eventbus.api.Event;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 兼容性的生物受伤事件
 * 用于在自定义伤害系统中触发类似原版事件的通知
 */
public class LivingHurtEventCompat extends Event { // ✅ 必须继承 Event
    private final LivingEntity entity;
    private final DamageSource source;
    private final float amount;

    public LivingHurtEventCompat(LivingEntity entity, DamageSource source, float amount) {
        this.entity = entity;
        this.source = source;
        this.amount = amount;
    }

    // Getter 方法
    public LivingEntity getEntity() {
        return entity;
    }

    public DamageSource getSource() {
        return source;
    }

    public float getAmount() {
        return amount;
    }

}
