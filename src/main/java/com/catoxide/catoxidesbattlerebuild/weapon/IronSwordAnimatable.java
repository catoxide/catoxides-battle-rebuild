package com.catoxide.catoxidesbattlerebuild.weapon;

import cn.solarmoon.spark_core.animation.model.ModelIndex;
import com.catoxide.catoxidesbattlerebuild.registry.ModWeapons;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 铁剑武器动画体
 * 处理铁剑的骨骼动画和物理碰撞检测
 * 继承自 ItemInHandAnimatable 以支持手持状态的特殊处理
 */
public class IronSwordAnimatable extends ItemInHandAnimatable {

    public IronSwordAnimatable(net.minecraft.world.item.ItemStack itemStack, Level level) {
        super(itemStack, level);
        // 设置攻击范围为 2.5 格
        setAttackRange(2.5);
        // 设置碰撞箱骨骼名称为剑刃
        setHitboxBoneName("blade");
        LogManager.serverDebug("IronSwordAnimatable", 
            "IronSwordAnimatable initialized with hitbox bone: '{}', attack range: {}", 
            getHitboxBoneName(), getAttackRange());
    }

    @Override
    public ModelIndex getDefaultModelIndex() {
        // 返回共享的剑模型索引
        // 模型文件位置: models/catoxidesbattlerebuild/item/swords.json
        // 动画文件位置: animations/catoxidesbattlerebuild/item/swords.json
        // 状态机位置: anim_state/catoxidesbattlerebuild/swords/default.json
        return new ModelIndex("item", ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "swords"));
    }

    @Override
    protected String getHitboxBoneName() {
        LogManager.serverDebug("IronSwordAnimatable", "getHitboxBoneName called, returning: 'blade'");
        return "blade";
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
