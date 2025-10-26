//package com.catoxide.catoxidesbattlerebuild.weapon;
//
//import com.catoxide.catoxidesbattlerebuild.IWeaponHitbox;
//import net.minecraft.world.phys.AABB;
//import net.minecraft.world.phys.Vec3;
//import net.minecraft.world.entity.LivingEntity;
//
//public class WeaponHitboxImpl implements IWeaponHitbox {
//    private final AABB baseHitbox;
//    private final HitboxType hitboxType;
//
//    public WeaponHitboxImpl(AABB baseHitbox, HitboxType type) {
//        this.baseHitbox = baseHitbox;
//        this.hitboxType = type;
//    }
//
//    @Override
//    public AABB getHitbox(LivingEntity wielder, Vec3 position, Vec3 rotation) {
//        // 根据持有者位置和旋转计算实际碰撞箱
//        return baseHitbox.move(position)
//                .inflate(hitboxType.getExpansion());
//    }
//
//    @Override
//    public HitboxType getHitboxType() {
//        return hitboxType;
//    }
//
//    @Override
//    public boolean isActive(LivingEntity wielder) {
//        // 检查武器是否处于攻击状态
//        return wielder.getAttackAnim(0) > 0;
//    }
//}
//
