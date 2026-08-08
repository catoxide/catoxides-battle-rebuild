package com.catoxide.catoxidesbattlerebuild.core.mob;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * 近战攻击行为组件
 * <p>实体定义 JSON：{ "type": "attack", "damage": 6.0, "cooldown": 20 }
 * 攻击伤害走原版 MeleeAttackGoal → LivingEntityHurtMixin → 部位伤害链路（引擎自动接管）。
 *
 * <p><b>固定攻击间隔</b>：使用自定义 goal 固定 {@code cooldown} 攻击间隔（默认 20 tick），
 * 不依赖 ATTACK_SPEED（裸 MeleeAttackGoal 的冷却 = getCurrentSwingDuration = 6/ATTACK_SPEED，
 * ATTACK_SPEED=4 时仅 2 tick → 疯狂挥动 → swinging 持续 → 动画卡死/移动异常）。
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
        // 固定攻击间隔（JSON cooldown 控制），挥动动画完整播放后 swinging 正常重置
        mob.goalSelector.addGoal(1, new FixedIntervalAttackGoal(mob, speedModifier, true, cooldown));
    }

    public float damage() { return damage; }
    public int cooldown() { return cooldown; }

    /** 固定攻击间隔的近战目标（参考原版 ZombieAttackGoal 的做法） */
    private static final class FixedIntervalAttackGoal extends MeleeAttackGoal {
        private final int attackInterval;

        FixedIntervalAttackGoal(PathfinderMob mob, double speedModifier, boolean followUnseen, int attackInterval) {
            super(mob, speedModifier, followUnseen);
            this.attackInterval = attackInterval;
        }

        @Override
        protected int getTicksUntilNextAttack() {
            return this.attackInterval;
        }
    }

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
