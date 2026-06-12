package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.weapon.IronSwordWeapon;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void onAttack(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (!(target instanceof LivingEntity livingTarget)) {
            return;
        }

        Player player = (Player) (Object) this;
        ItemStack heldItem = player.getMainHandItem();

        if (heldItem.getItem() instanceof IronSwordWeapon) {
            cir.cancel();
            
            // 使用玩家攻击距离（约4.5格）
            double playerReach = 4.5;
            
            // 计算玩家到目标的距离
            Vec3 playerPos = player.position().add(0, player.getEyeHeight(), 0);
            Vec3 targetPos = target.position().add(0, target.getEyeHeight() * 0.5, 0);
            double distance = playerPos.distanceTo(targetPos);
            
            // 检查是否在攻击范围内
            if (distance <= playerReach) {
                float damage = 6.0f * (0.5f + player.getAttackStrengthScale(1.0f) * 0.5f);
                
                boolean hurt = livingTarget.hurt(livingTarget.damageSources().playerAttack(player), damage);
                
                if (hurt) {
                    // 原版击退
                    double knockbackStrength = 0.5;
                    double dx = -Math.sin(player.getYRot() * (Math.PI / 180.0)) * knockbackStrength;
                    double dz = Math.cos(player.getYRot() * (Math.PI / 180.0)) * knockbackStrength;
                    livingTarget.knockback(knockbackStrength, dx, dz);
                    livingTarget.hurtMarked = true;
                }
            }
        }
    }
}