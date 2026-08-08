package com.catoxide.catoxidesbattlerebuild.weapon;

import com.catoxide.catoxidesbattlerebuild.core.projectile.CustomProjectileEntity;
import com.catoxide.catoxidesbattlerebuild.core.projectile.ProjectileConfig;

/**
 * 示例远程武器：自定义弓箭。
 * <p>
 * 发射 CustomProjectileEntity，使用默认箭矢配置。
 * <p>
 * 后续可替换为 ContentPack JSON 驱动的实例。
 */
public class CustomBowWeapon extends RangedWeaponItem {

    public CustomBowWeapon(Properties properties) {
        super(properties.stacksTo(1).durability(384));
    }

    @Override
    public ProjectileConfig getProjectileConfig() {
        return ProjectileConfig.DEFAULT_ARROW;
    }

    @Override
    public net.minecraft.world.entity.EntityType<? extends CustomProjectileEntity> getProjectileEntityType() {
        return com.catoxide.catoxidesbattlerebuild.registry.ModEntities.CUSTOM_PROJECTILE.get();
    }

    @Override
    public int getDrawDuration() {
        return 20; // 1 秒满蓄力
    }

    @Override
    public float getMaxPower() {
        return 1.5f; // 弓箭可以射得比基础速度更远
    }
}
