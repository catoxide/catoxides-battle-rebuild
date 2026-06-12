package com.catoxide.catoxidesbattlerebuild.weapon;

import cn.solarmoon.spark_core.animation.ItemAnimatable;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID)
public class WeaponCollisionListener {

    private static final Map<UUID, Long> attackCooldown = new HashMap<>();
    private static final long COOLDOWN_MS = 300;

    @SubscribeEvent
    public static void onPlayerAttack(AttackEntityEvent event) {
        Player player = (Player) event.getEntity();
        ItemStack heldItem = player.getMainHandItem();

        if (heldItem.getItem() instanceof IronSwordWeapon) {
            event.setCanceled(true);
            
            if (!canAttack(player)) {
                return;
            }

            if (player.level().isClientSide()) {
                // 客户端：启动武器攻击动画
                startWeaponAttack(player, heldItem);
                return;
            }

            // 服务端：处理实际伤害
            if (event.getTarget() instanceof LivingEntity target) {
                processAttack(player, target, heldItem);
            }
        }
    }

    private static boolean canAttack(LivingEntity attacker) {
        UUID id = attacker.getUUID();
        long lastAttack = attackCooldown.getOrDefault(id, 0L);
        long now = System.currentTimeMillis();
        
        if (now - lastAttack < COOLDOWN_MS) {
            return false;
        }
        
        attackCooldown.put(id, now);
        return true;
    }

    /**
     * 在客户端启动武器攻击动画
     */
    private static void startWeaponAttack(Player player, ItemStack itemStack) {
        // 获取物品的动画实例并启动攻击
        IronSwordWeapon weapon = (IronSwordWeapon) itemStack.getItem();
        ItemAnimatable animatable = weapon.getRenderInstance(itemStack, player.level(), 
            net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        
        if (animatable instanceof WeaponPhysicsItemAnimatable weaponAnim) {
            weaponAnim.startAttack();
            LogManager.clientDebug("WeaponCollisionListener", 
                "Started weapon attack animation for player {}", player.getName().getString());
        }
    }

    /**
     * 处理服务端攻击
     */
    private static void processAttack(Player attacker, LivingEntity target, ItemStack itemStack) {
        IronSwordWeapon weapon = (IronSwordWeapon) itemStack.getItem();
        
        // 获取武器物理动画体
        ItemAnimatable animatable = weapon.getRenderInstance(itemStack, attacker.level(),
            net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        
        if (!(animatable instanceof WeaponPhysicsItemAnimatable weaponAnim)) {
            // 没有动画体，使用传统方式
            performFallbackAttack(attacker, target);
            return;
        }
        
        // 获取武器尖端正置
        var weaponTip = weaponAnim.getWeaponBoneWorldPosition(1.0f);
        if (weaponTip == null) {
            performFallbackAttack(attacker, target);
            return;
        }
        
        // 使用 OBB 碰撞检测
        var weaponOBB = weaponAnim.getWeaponOBB(1.0f);
        var targetAABB = target.getBoundingBox();
        
        if (weaponOBB != null && com.catoxide.catoxidesbattlerebuild.weapon.physics.OBBCollisionUtil.obbIntersectsAABB(weaponOBB, targetAABB)) {
            // 碰撞发生，触发命中
            weaponAnim.onWeaponHit(attacker, target, weaponTip);
            weaponAnim.getHitCallback().accept(attacker, target);
            
            LogManager.serverInfo("WeaponCollisionListener", 
                "Hit target {} with OBB collision", target.getName().getString());
        } else {
            // OBB 检测未命中，但如果是范围内的目标仍然造成部分伤害（fallback）
            double distance = weaponTip.distanceTo(target.position());
            if (distance <= weaponAnim.getAttackRange()) {
                performFallbackAttack(attacker, target);
            }
        }
    }

    /**
     * 后备攻击方式（原版攻击逻辑）
     */
    private static void performFallbackAttack(Player attacker, LivingEntity target) {
        float damage = calculateDamage(attacker);
        boolean success = target.hurt(
            target.damageSources().playerAttack(attacker),
            damage
        );
        
        if (success) {
            applyKnockback(attacker, target);
            LogManager.serverInfo("WeaponCollisionListener", 
                "Fallback hit on target {} for {} damage", target.getName().getString(), damage);
        }
    }

    private static float calculateDamage(Player attacker) {
        float damage = 6.0f; // 基础伤害
        float attackStrength = attacker.getAttackStrengthScale(1.0f);
        damage *= (0.5f + attackStrength * 0.5f);
        return damage;
    }

    private static void applyKnockback(Player attacker, LivingEntity target) {
        double knockbackStrength = 0.5;
        double dx = -Math.sin(attacker.getYRot() * (Math.PI / 180.0)) * knockbackStrength;
        double dz = Math.cos(attacker.getYRot() * (Math.PI / 180.0)) * knockbackStrength;
        target.push(dx, 0.15, dz);
        target.hurtMarked = true;
    }
}
