//// WeaponDamageSystem.java
//package com.catoxide.catoxidesbattlerebuild.weapon;
//
//import com.catoxide.catoxidesbattlerebuild.IWeapon;
//import com.catoxide.catoxidesbattlerebuild.damage.*;
//import net.minecraft.world.entity.LivingEntity;
//
//import java.util.Map;
//
//public class WeaponDamageSystem {
//
//    public static void applyWeaponDamage(LivingEntity attacker, LivingEntity target, IWeapon weapon) {
//        // 创建复合伤害
//        CompositeDamage composite = createWeaponDamage(attacker, target, weapon);
//
//        // 计算伤害结果
//        DamageCalculator calculator = new DamageCalculator();
//        DamageResult result = calculator.calculateDamage(composite);
//
//        // 应用伤害
//        applyDamageResult(attacker, target, result);
//
//        // 触发武器特效
//        triggerWeaponEffects(attacker, target, weapon, result);
//    }
//
//    private static CompositeDamage createWeaponDamage(LivingEntity attacker, LivingEntity target, IWeapon weapon) {
//        CompositeDamage composite = new CompositeDamage(
//                DamageSource.mobAttack(attacker),
//                attacker,
//                target
//        );
//
//        // 添加武器的基础伤害类型
//        for (Map.Entry<DamageType, Float> entry : weapon.getBaseDamages().entrySet()) {
//            DamageComponent component = weapon.createDamageComponent(
//                    entry.getKey(),
//                    entry.getValue()
//            );
//            composite.addComponent(component);
//        }
//
//        // 添加武器特定标签
//        applyWeaponTags(composite, weapon);
//
//        return composite;
//    }
//
//    private static void applyWeaponTags(CompositeDamage composite, IWeapon weapon) {
//        // 根据武器类型添加标签
//        if (weapon instanceof SwordWeapon) {
//            composite.addTag(DamageTag.SHARP);
//        } else if (weapon instanceof BluntWeapon) {
//            composite.addTag(DamageTag.BLUNT);
//            composite.addTag(DamageTag.IGNORES_ARMOR);
//        }
//
//        // 添加武器穿透属性
//        if (weapon.getHitbox().getHitboxType() == HitboxType.THRUST) {
//            composite.addTag(DamageTag.ARMOR_PIERCING);
//        }
//    }
//
//    private static void applyDamageResult(LivingEntity attacker, LivingEntity target, DamageResult result) {
//        // 这里可以调用你之前写的伤害应用逻辑
//        // 或者直接使用：target.hurt(result.getDamageSource(), result.getTotalDamage());
//
//        // 应用击退等效果
//        applyKnockback(attacker, target, result);
//    }
//
//    private static void applyKnockback(LivingEntity attacker, LivingEntity target, DamageResult result) {
//        Vec3 knockbackVec = target.position()
//                .subtract(attacker.position())
//                .normalize()
//                .scale(result.getTotalDamage() * 0.1);
//
//        target.setDeltaMovement(
//                target.getDeltaMovement().add(knockbackVec.x, 0.1, knockbackVec.z)
//        );
//    }
//
//    private static void triggerWeaponEffects(LivingEntity attacker, LivingEntity target, IWeapon weapon, DamageResult result) {
//        // 生成粒子效果、播放音效等
//        if (target.level() instanceof ServerLevel serverLevel) {
//            // 武器命中特效
//            serverLevel.sendParticles(
//                    /* 粒子类型 */,
//                    target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ(),
//                    5, // 数量
//                    target.getBbWidth() * 0.5, target.getBbHeight() * 0.2, target.getBbWidth() * 0.5,
//                    0.1
//            );
//        }
//    }
//}