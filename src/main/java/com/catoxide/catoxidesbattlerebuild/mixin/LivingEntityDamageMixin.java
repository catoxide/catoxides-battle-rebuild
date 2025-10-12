package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.damage.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.data.tags.DamageTypeTagsProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
public class LivingEntityDamageMixin {

    public boolean hurt(DamageSource originalSource, float originalAmount) {
        LivingEntity target = (LivingEntity) (Object) this;

        // 🛡️ 第一步：伤害预处理和转换
        DamageInterceptResult intercept = preProcessDamage(originalSource, originalAmount, target);
        if (intercept.shouldCancel()) {
            return false; // 完全取消伤害
        }

        // 🔄 第二步：转换为自定义伤害系统
        CompositeDamage compositeDamage = convertToCustomDamageSystem(
                intercept.getEffectiveSource(),
                intercept.getEffectiveAmount(),
                target
        );

        // ⚡ 第三步：应用你的伤害计算框架
        DamageResult result = calculateCustomDamage(compositeDamage);

        // 🎯 第四步：应用伤害结果
        boolean damageApplied = applyCustomDamageResult(target, result);

        // 📢 第五步：触发兼容性事件（让其他模组知道伤害发生了）
        postDamageEvents(originalSource, result, damageApplied);

        return damageApplied;
    }

    private DamageInterceptResult preProcessDamage(DamageSource source, float amount, LivingEntity target) {
        // 这里可以添加伤害预处理逻辑
        // 比如无敌帧检查、创造性模式免疫等

        DamageInterceptResult result = new DamageInterceptResult(source, amount);

        // 🎯 原版免疫检查（选择性保留）
        if (target.isInvulnerableTo(source)) {
            result.setCancel(true);
            return result;
        }

        // 🎯 创造性模式免疫（选择性保留）
        if (target instanceof Player player && player.getAbilities().invulnerable) {
            result.setCancel(true);
            return result;
        }

        return result;
    }

    private CompositeDamage convertToCustomDamageSystem(DamageSource source, float amount, LivingEntity target) {
        LivingEntity attacker = source.getEntity() instanceof LivingEntity ?
                (LivingEntity) source.getEntity() : null;

        // ✅ 直接使用新的映射器
        CompositeDamage composite = CustomDamageMapper.mapToCustomDamage(source, amount, target);

        // ✅ 原有的标签系统可以保留（如果需要）
        applyDamageTags(composite, source);

        return composite;
    }


    private void mapModSpecificDamage(CompositeDamage composite, DamageSource source, float amount) {
        String damageTypeName = source.getMsgId();

        // 示例：识别特定模组的伤害类型
        if (damageTypeName.contains("electric") || damageTypeName.contains("lightning")) {
            composite.addComponent(new DamageComponent(DamageType.LIGHTNING, amount));
        }
        else if (damageTypeName.contains("ice") || damageTypeName.contains("cold")) {
            composite.addComponent(new DamageComponent(DamageType.COLD, amount));
        }
        // 更多模组特定映射...
    }

    private void applyDamageTags(CompositeDamage composite, DamageSource source) {
        // 🏷️ 基于原版特性添加标签
        if (source.is(DamageTypeTags.BYPASSES_ARMOR)) {composite.addTag(DamageTag.BYPASS_ARMOR);}
        if(source.is(DamageTypeTags.BYPASSES_SHIELD)){composite.addTag(DamageTag.BYPASS_SHIELD); }
        if(source.is(DamageTypeTags.IS_EXPLOSION)){composite.addTag(DamageTag.IS_EXPLOSION); }
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {composite.addTag(DamageTag.PROJECTILE);}
        if (source.is(DamageTypeTags.DAMAGES_HELMET)) {composite.addTag(DamageTag.PROJECTILE);}
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {composite.addTag(DamageTag.IGNORES_INVULNERABILITY);}



    }

    private DamageResult calculateCustomDamage(CompositeDamage damage) {
        // 使用你之前设计的伤害计算框架
        DamageCalculator calculator = new DamageCalculator();
        return calculator.calculateDamage(damage);
    }
    private void applyDamageEffects(LivingEntity target, DamageResult result) {
        // 只在服务端执行逻辑
        if (target.level().isClientSide) return;

        // 应用击退效果
        applyKnockback(target, result);

        // 播放受伤音效
        playHurtSound(target, result);

        // 生成伤害粒子
        spawnDamageParticles(target, result);

        // 设置受伤动画
        setupHurtAnimation(target,result);
    }

    private void applyKnockback(LivingEntity target, DamageResult result) {
        LivingEntity attacker = result.getOriginalDamage().getAttacker();

        // 只有存在攻击者时才应用击退
        if (attacker != null) {
            // 计算击退方向 (从攻击者指向目标)
            double deltaX = target.getX() - attacker.getX();
            double deltaZ = target.getZ() - attacker.getZ();

            // 标准化方向向量
            double length = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            if (length > 0) {
                double knockbackStrength = calculateKnockbackStrength(result);

                // 🎯 应用击退运动
                target.setDeltaMovement(
                        target.getDeltaMovement().add(
                                deltaX / length * knockbackStrength,
                                0.1f, // 轻微的垂直击退
                                deltaZ / length * knockbackStrength
                        )
                );

                // 🎯 重要：标记实体运动已更新（用于网络同步）
                target.hurtMarked = true;
            }
        }
    }

    private float calculateKnockbackStrength(DamageResult result) {
        // 基础击退强度
        float baseKnockback = 0.4f;

        // 根据伤害标签调整击退强度
        if (result.getOriginalDamage().hasTag(DamageTag.IGNORES_KNOCKBACK)) {
            baseKnockback *= 1.5f;
        }

        return baseKnockback;
    }

    private void playHurtSound(LivingEntity target, DamageResult result) {
        // 🎯 根据伤害类型播放不同的受伤音效
        if (result.getOriginalDamage().hasTag(DamageTag.MAGIC)) {
            target.playSound(SoundEvents.GENERIC_HURT, 1.0f, 0.8f);
        } else {
            target.playSound(SoundEvents.GENERIC_HURT, 1.0f, 1.0f);
        }
    }

    private void spawnDamageParticles(LivingEntity target, DamageResult result) {
        // 🎯 生成伤害粒子效果（在客户端执行）
        if (!target.level().isClientSide) {
            // 通过数据包通知客户端生成粒子
            ServerLevel serverLevel = (ServerLevel) target.level();

            // 发送数据包给周围玩家显示粒子效果
            serverLevel.sendParticles(
                    ParticleTypes.DAMAGE_INDICATOR,
                    target.getX(),
                    target.getY() + target.getBbHeight() * 0.5,
                    target.getZ(),
                    5, // 粒子数量
                    target.getBbWidth() * 0.5,
                    target.getBbHeight() * 0.2,
                    target.getBbWidth() * 0.5,
                    0.1
            );
        } else {
            // 客户端粒子效果生成（用于调试）
            for (int i = 0; i < 5; i++) {
                target.level().addParticle(ParticleTypes.DAMAGE_INDICATOR,
                        target.getX() + (Math.random() - 0.5) * target.getBbWidth(),
                        target.getY() + Math.random() * target.getBbHeight(),
                        target.getZ() + (Math.random() - 0.5) * target.getBbWidth(),
                        0, 0.1, 0);
            }
        }
    }

    private void setupHurtAnimation(LivingEntity target,DamageResult result) {
        // 🎯 设置受伤动画参数:cite[2]
        target.hurtDuration = 10; // 受伤动画总时间
        target.hurtTime = target.hurtDuration; // 受伤动画当前时间:cite[8]

        // 触发原版的受伤动画
        target.setLastHurtByMob(result.getOriginalDamage().getAttacker());
    }
    private boolean applyCustomDamageResult(LivingEntity target, DamageResult result) {
        if (result.getTotalDamage() <= 0) {
            return false;
        }

        float newHealth = target.getHealth() - result.getTotalDamage();

        // 🎯 直接设置生命值，绕过所有原版逻辑
        target.setHealth(newHealth);

        // 💀 死亡检查
        if (newHealth <= 0) {
            triggerCustomDeath(target, result);
            return true;
        }

        // 🎭 应用伤害效果（击退、音效、粒子等）
        applyDamageEffects(target, result);

        return true;
    }

    private void triggerCustomDeath(LivingEntity target, DamageResult result) {
        // 🎯 自定义死亡处理
        target.setHealth(0);

        // 可以在这里添加自定义死亡逻辑
        // 比如不同的死亡动画、特殊效果等

        // 🎯 仍然触发原版死亡事件以保持兼容性
        if (target instanceof ServerPlayer serverPlayer) {
            serverPlayer.gameEvent(GameEvent.ENTITY_DIE);
        }
    }

    private void postDamageEvents(DamageSource originalSource, DamageResult result, boolean damageApplied) {
        LivingEntity target = (LivingEntity) (Object) this;

        // 📢 触发自定义事件
        CustomDamageAppliedEvent customEvent = new CustomDamageAppliedEvent(target, result, damageApplied);
        MinecraftForge.EVENT_BUS.post(customEvent);

        // 📢 为了兼容性，选择性触发原版风格的事件
        // 注意：这些事件中的参数可能已经被修改
        if (damageApplied && result.getTotalDamage() > 0) {
            // 转换回原版格式用于事件触发
            DamageSource compatibleSource = createCompatibleDamageSource(originalSource, result);
            float compatibleAmount = result.getTotalDamage();

            // 触发类似原版的事件（但参数可能不同）
            LivingHurtEventCompat compatEvent = new LivingHurtEventCompat(target, compatibleSource, compatibleAmount);
            MinecraftForge.EVENT_BUS.post(compatEvent);
        }
    }

    private DamageSource createCompatibleDamageSource(DamageSource original, DamageResult result) {
        // 创建一个兼容性的DamageSource用于事件系统
        // 可以基于原始来源或伤害结果来创建
        return original;
    }
}
