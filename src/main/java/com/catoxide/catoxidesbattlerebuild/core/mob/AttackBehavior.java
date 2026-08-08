package com.catoxide.catoxidesbattlerebuild.core.mob;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * 近战攻击行为组件
 * <p>实体定义 JSON：{ "type": "attack", "damage": 6.0, "cooldown": 20 }
 * 攻击伤害走原版 MeleeAttackGoal → LivingEntityHurtMixin → 部位伤害链路（引擎自动接管）。
 */
public class AttackBehavior implements IMobBehavior {

    private final float damage;
    private final int cooldown;
    private final double speedModifier;

    public AttackBehavior(float damage, int cooldown, double speedModifier) {
        this.damage = damage;
        this.cooldown = cooldown;
        this.speedModifier = speedModifier;
    }

    @Override
    public void registerGoals(DataDrivenMob mob) {
        // 近战攻击目标（原版逻辑，伤害链路由 mixin + DamageProcessor 接管）
        mob.goalSelector.addGoal(1, new MeleeAttackGoal(mob, speedModifier, true));
    }

    public float damage() { return damage; }
    public int cooldown() { return cooldown; }

    /** 工厂 */
    public static final class Factory implements IBehaviorFactory {
        @Override
        public String type() { return "attack"; }

        @Override
        public IMobBehavior create(JsonObject config) {
            float damage = config.has("damage") ? config.get("damage").getAsFloat() : 4.0f;
            int cooldown = config.has("cooldown") ? config.get("cooldown").getAsInt() : 20;
            double speed = config.has("speed") ? config.get("speed").getAsDouble() : 1.0;
            return new AttackBehavior(damage, cooldown, speed);
        }
    }
}
