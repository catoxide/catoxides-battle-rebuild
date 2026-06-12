package com.catoxide.catoxidesbattlerebuild.weapon;

import com.catoxide.catoxidesbattlerebuild.registry.ModWeapons;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 铁剑武器动画体
 * 处理铁剑的骨骼动画和物理碰撞检测
 */
public class IronSwordAnimatable extends WeaponPhysicsItemAnimatable {

    public IronSwordAnimatable(net.minecraft.world.item.ItemStack itemStack, Level level) {
        super(itemStack, level);
        // 设置攻击范围为 2.5 格
        setAttackRange(2.5);
    }

    @Override
    protected String getHitboxBoneName() {
        return "hitbox_blade";
    }

    @Override
    protected float getAttackCooldown() {
        return 0.6f; // 铁剑攻击冷却 0.6 秒
    }

    @Override
    protected void onWeaponHit(LivingEntity attacker, LivingEntity target, Vec3 hitPoint) {
        // 可以在这里添加自定义命中逻辑
        // 例如：应用特殊效果、播放音效等
        
        // 默认使用原版攻击伤害
        float damage = 3.0f; // 铁剑基础伤害
        target.hurt(target.damageSources().mobAttack(attacker), damage);
    }
}
