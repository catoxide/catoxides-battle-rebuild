package com.catoxide.catoxidesbattlerebuild.damage;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Event;


/**
 * 自定义伤害应用事件
 */
public class CustomDamageAppliedEvent extends Event {
    private final LivingEntity target;          // 伤害目标
    private final DamageResult damageResult;          // 伤害结果
    private final boolean damageApplied;        // 伤害是否成功应用
    private boolean canceled = false;           // 事件是否被取消

    public CustomDamageAppliedEvent(LivingEntity target, DamageResult result, boolean damageApplied) {
        this.target = target;
        this.damageResult = result;
        this.damageApplied = damageApplied;
    }

    // Getter 方法
    public LivingEntity getTarget() {
        return target;
    }

    public DamageResult getDamageResult() {
        return damageResult;
    }

    public boolean isDamageApplied() {
        return damageApplied;
    }

    // Forge 事件标准方法
    @Override
    public boolean isCancelable() {
        return true; // 表示这个事件可以被取消
    }

    @Override
    public void setCanceled(boolean cancel) {
        this.canceled = cancel;
    }

    @Override
    public boolean isCanceled() {
        return canceled;
    }
}
