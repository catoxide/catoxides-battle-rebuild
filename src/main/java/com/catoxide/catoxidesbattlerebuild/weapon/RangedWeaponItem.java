package com.catoxide.catoxidesbattlerebuild.weapon;

import com.catoxide.catoxidesbattlerebuild.core.projectile.CustomProjectileEntity;
import com.catoxide.catoxidesbattlerebuild.core.projectile.ProjectileConfig;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 远程武器物品基类。
 * <p>处理蓄力/射击逻辑，生成 CustomProjectileEntity。
 * <p>子类实现 getProjectileConfig() 和 getProjectileEntityType()。
 */
public abstract class RangedWeaponItem extends Item {

    protected RangedWeaponItem(Properties properties) {
        super(properties);
    }

    public abstract ProjectileConfig getProjectileConfig();
    public abstract net.minecraft.world.entity.EntityType<? extends CustomProjectileEntity> getProjectileEntityType();

    public int getDrawDuration() { return 20; }
    public float getMaxPower() { return 1.0f; }
    public boolean consumesDurability() { return true; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remainingUseDuration) {
        if (!(entity instanceof Player player)) return;
        if (level.isClientSide) return;

        int maxDuration = this.getUseDuration(stack, entity);
        int usedDuration = maxDuration - remainingUseDuration;
        float power = calculatePower(usedDuration);
        if (power < 0.1f) return;

        releaseProjectile(level, player, stack, power);

        // TODO: 耐久消耗 — hurtAndBreak API 变更，后续修复
        // if (consumesDurability() && stack.isDamaged()) { ... }
    }

    protected float calculatePower(int usedDuration) {
        float ratio = (float) usedDuration / getDrawDuration();
        return Math.min(ratio, 1.0f) * this.getMaxPower();
    }

    protected void releaseProjectile(Level level, Player player, ItemStack stack, float power) {
        float rotation = player.getYRot();
        float pitch = player.getXRot();
        double mx = -Math.sin(Math.toRadians(rotation)) * Math.cos(Math.toRadians(pitch));
        double my = -Math.sin(Math.toRadians(pitch));
        double mz = Math.cos(Math.toRadians(rotation)) * Math.cos(Math.toRadians(pitch));
        Vec3 direction = new Vec3(mx, my, mz).normalize();

        ProjectileConfig config = getProjectileConfig();
        net.minecraft.world.entity.EntityType<? extends CustomProjectileEntity> entityType = getProjectileEntityType();

        CustomProjectileEntity.shoot(level, player, direction, power, config, entityType);
        LogManager.serverInfo("RangedWeapon", "Projectile released: player=%s, power=%.2f",
                player.getName().getString(), power);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }
}
