package com.catoxide.catoxidesbattlerebuild.weapon;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 手持物品动画体
 * 专门处理手持状态下的武器动画和物理碰撞检测
 */
public class ItemInHandAnimatable extends WeaponPhysicsItemAnimatable {

    /** 持有者实体 */
    protected LivingEntity holder;

    public ItemInHandAnimatable(net.minecraft.world.item.ItemStack itemStack, Level level) {
        super(itemStack, level);
        LogManager.serverDebug("ItemInHandAnimatable", "ItemInHandAnimatable initialized");
    }

    /**
     * 设置持有者
     */
    public void setHolder(LivingEntity holder) {
        this.holder = holder;
        LogManager.serverDebug("ItemInHandAnimatable", "Holder set to: {}", 
            holder != null ? holder.getName().getString() : "null");
    }

    /**
     * 获取持有者
     */
    public LivingEntity getHolder() {
        return holder;
    }

    @Override
    protected void onWeaponHit(LivingEntity attacker, LivingEntity target, Vec3 hitPoint) {
        // 手持状态下的命中处理
        LogManager.serverDebug("ItemInHandAnimatable", 
            "Weapon hit! Attacker: {}, Target: {}, HitPoint: {}", 
            attacker.getName().getString(), 
            target.getName().getString(), 
            hitPoint);
        
        // 默认使用基础伤害
        float damage = 4.0f;
        target.hurt(target.damageSources().mobAttack(attacker), damage);
    }

    @Override
    public void physicsTick() {
        super.physicsTick();
        // 额外的手持物品物理更新
        if (holder != null && isAttacking) {
            LogManager.serverDebug("ItemInHandAnimatable", 
                "Physics tick - isAttacking: {}, cooldown: {}", 
                isAttacking, attackCooldown);
        }
    }
}
