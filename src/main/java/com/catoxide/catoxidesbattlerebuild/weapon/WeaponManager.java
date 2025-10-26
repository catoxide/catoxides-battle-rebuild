//package com.catoxide.catoxidesbattlerebuild.weapon;
//
//import com.catoxide.catoxidesbattlerebuild.IWeapon;
//import com.catoxide.catoxidesbattlerebuild.IWeaponHitbox;
//import net.minecraft.world.entity.LivingEntity;
//import net.minecraft.world.phys.AABB;
//import net.minecraft.world.phys.Vec3;
//import net.minecraft.server.level.ServerLevel;
//import java.util.*;
//
//public class WeaponManager {
//    private static final Map<LivingEntity, ActiveWeaponAttack> activeAttacks = new WeakHashMap<>();
//
//    public static void startWeaponAttack(LivingEntity attacker, IWeapon weapon) {
//        activeAttacks.put(attacker, new ActiveWeaponAttack(attacker, weapon));
//    }
//
//    public static void updateWeaponAttacks(ServerLevel level) {
//        Iterator<Map.Entry<LivingEntity, ActiveWeaponAttack>> iterator = activeAttacks.entrySet().iterator();
//
//        while (iterator.hasNext()) {
//            Map.Entry<LivingEntity, ActiveWeaponAttack> entry = iterator.next();
//            ActiveWeaponAttack attack = entry.getValue();
//
//            if (!attack.isValid() || !attack.update(level)) {
//                iterator.remove();
//            }
//        }
//    }
//
//    public static void endWeaponAttack(LivingEntity attacker) {
//        activeAttacks.remove(attacker);
//    }
//}
//
//// ActiveWeaponAttack.java
//public class ActiveWeaponAttack {
//    private final LivingEntity attacker;
//    private final IWeapon weapon;
//    private final Set<LivingEntity> alreadyHit = new HashSet<>();
//    private int ticksActive = 0;
//
//    public ActiveWeaponAttack(LivingEntity attacker, IWeapon weapon) {
//        this.attacker = attacker;
//        this.weapon = weapon;
//    }
//
//    public boolean update(ServerLevel level) {
//        ticksActive++;
//
//        // 获取武器碰撞箱
//        IWeaponHitbox hitbox = weapon.getHitbox();
//        if (!hitbox.isActive(attacker)) {
//            return false; // 攻击结束
//        }
//
//        // 计算武器位置和旋转
//        Vec3 weaponPos = calculateWeaponPosition();
//        Vec3 weaponRot = calculateWeaponRotation();
//
//        // 获取实际碰撞箱
//        AABB weaponAABB = hitbox.getHitbox(attacker, weaponPos, weaponRot);
//
//        // 检测碰撞
//        List<LivingEntity> targets = level.getEntitiesOfClass(
//                LivingEntity.class,
//                weaponAABB,
//                this::canHitTarget
//        );
//
//        // 处理击中目标
//        for (LivingEntity target : targets) {
//            if (!alreadyHit.contains(target)) {
//                onWeaponHit(target);
//                alreadyHit.add(target);
//            }
//        }
//
//        return ticksActive < 20; // 最多持续20ticks
//    }
//
//    private Vec3 calculateWeaponPosition() {
//        // 基于攻击者位置和朝向计算武器位置
//        Vec3 lookVec = attacker.getLookAngle();
//        double reach = 2.0; // 武器攻击距离
//
//        return attacker.getEyePosition()
//                .add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach);
//    }
//
//    private Vec3 calculateWeaponRotation() {
//        // 计算武器旋转（简化版）
//        return attacker.getLookAngle();
//    }
//
//    private boolean canHitTarget(LivingEntity target) {
//        return target != attacker &&
//                target.isAlive() &&
//                !target.isInvulnerable() &&
//                attacker.hasLineOfSight(target);
//    }
//
//    private void onWeaponHit(LivingEntity target) {
//        // 应用武器伤害
//        WeaponDamageSystem.applyWeaponDamage(attacker, target, weapon);
//    }
//
//    public boolean isValid() {
//        return attacker != null &&
//                attacker.isAlive() &&
//                weapon != null;
//    }
//}
