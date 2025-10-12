package com.catoxide.catoxidesbattlerebuild.damage;

import jdk.jfr.Event;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 伤害拦截结果
 */
public class DamageInterceptResult {
    private final DamageSource originalSource;
    private final float originalAmount;
    private boolean cancel = false;
    private DamageSource effectiveSource;
    private float effectiveAmount;

    public DamageInterceptResult(DamageSource source, float amount) {
        this.originalSource = source;
        this.originalAmount = amount;
        this.effectiveSource = source;
        this.effectiveAmount = amount;
    }

    // Getter和Setter
    public boolean shouldCancel() { return cancel; }
    public void setCancel(boolean cancel) { this.cancel = cancel; }
    public DamageSource getEffectiveSource() { return effectiveSource; }
    public void setEffectiveSource(DamageSource source) { this.effectiveSource = source; }
    public float getEffectiveAmount() { return effectiveAmount; }
    public void setEffectiveAmount(float amount) { this.effectiveAmount = amount; }
}

