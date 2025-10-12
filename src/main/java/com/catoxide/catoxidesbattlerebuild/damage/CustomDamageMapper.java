package com.catoxide.catoxidesbattlerebuild.damage;

import com.catoxide.catoxidesbattlerebuild.damage.CompositeDamage;
import com.catoxide.catoxidesbattlerebuild.damage.DamageComponent;
import com.catoxide.catoxidesbattlerebuild.damage.DamageType;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Minecart;

// 文件：CustomDamageMapper.java
public class CustomDamageMapper {

    public static CompositeDamage mapToCustomDamage(DamageSource source, float amount, LivingEntity target) {
        // 🎯 这里创建 CompositeDamage 实例，不是定义类
        LivingEntity attacker = source.getEntity() instanceof LivingEntity ?
                (LivingEntity) source.getEntity() : null;

        // 创建 CompositeDamage 对象实例
        CompositeDamage compositeDamage = new CompositeDamage(source, attacker, target);

        if(source.is(DamageTypeTags.BYPASSES_ARMOR)){
            compositeDamage.addComponent(new DamageComponent(DamageType.TRUE_DAMAGE, amount));
        }else if(source.is(DamageTypeTags.IS_EXPLOSION)){
            compositeDamage.addComponent(new DamageComponent(DamageType.PHYSICS, amount));
        }else if(source.is(DamageTypeTags.IS_FIRE)){
            compositeDamage.addComponent(new DamageComponent(DamageType.FIRE, amount));
        }else if(source.is(DamageTypeTags.IS_FREEZING)){
            compositeDamage.addComponent(new DamageComponent(DamageType.COLD, amount));
        }else if(source.is(DamageTypeTags.IS_LIGHTNING)){
            compositeDamage.addComponent(new DamageComponent(DamageType.LIGHTNING, amount));
        }else if(true){
            compositeDamage.addComponent(new DamageComponent(DamageType.PHYSICS, amount));
        }

        return compositeDamage; // 返回创建好的实例
    }

    private static boolean isFireDamage(DamageSource source) {
        // 1.20.1 的火焰伤害检测逻辑
        return source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE);
    }

    private static boolean isProjectileDamage(DamageSource source) {
        return source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE);
    }
}