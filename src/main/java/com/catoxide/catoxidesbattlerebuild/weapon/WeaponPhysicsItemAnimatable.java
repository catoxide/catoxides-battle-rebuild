package com.catoxide.catoxidesbattlerebuild.weapon;

import cn.solarmoon.spark_core.animation.ItemAnimatable;
import cn.solarmoon.spark_core.animation.model.BonePose;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.ModelPose;
import cn.solarmoon.spark_core.animation.model.origin.OCube;
import cn.solarmoon.spark_core.animation.model.origin.OLocator;
import cn.solarmoon.spark_core.animation.model.origin.OModel;
import com.catoxide.catoxidesbattlerebuild.weapon.physics.OBBCollisionUtil;
import com.catoxide.catoxidesbattlerebuild.weapon.physics.OBBCollisionUtil.OBB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 武器物理动画基类
 * 继承自 Spark-Core 的 ItemAnimatable，提供基于骨骼的碰撞检测功能
 * 
 * 子类需要实现 getWeaponHitboxBoneName() 来指定碰撞箱使用的骨骼名称
 */
public abstract class WeaponPhysicsItemAnimatable extends ItemAnimatable {

    /** 攻击冷却追踪 */
    protected float attackCooldown = 0f;
    /** 是否正在攻击中 */
    protected boolean isAttacking = false;
    /** 攻击范围 */
    protected double attackRange = 3.0;
    /** 碰撞箱碰撞检测使用的骨骼名称 */
    protected String hitboxBoneName = "hitbox_blade";

    public WeaponPhysicsItemAnimatable(net.minecraft.world.item.ItemStack itemStack, Level level) {
        super(itemStack, level);
    }

    /**
     * 获取碰撞箱使用的骨骼名称
     * 子类可重写以使用不同的骨骼
     */
    protected String getHitboxBoneName() {
        return hitboxBoneName;
    }

    /**
     * 设置碰撞箱使用的骨骼名称
     */
    public void setHitboxBoneName(String boneName) {
        this.hitboxBoneName = boneName;
    }

    /**
     * 获取攻击范围
     */
    public double getAttackRange() {
        return attackRange;
    }

    /**
     * 设置攻击范围
     */
    public void setAttackRange(double range) {
        this.attackRange = range;
    }

    /**
     * 物理 Tick - 每帧调用
     * 在此进行碰撞检测和伤害处理
     */
    @Override
    public void physicsTick() {
        super.physicsTick();
        
        // 减少攻击冷却
        if (attackCooldown > 0) {
            attackCooldown -= 1f / 20f; // 假设 20 tick/s
        }

        // 执行碰撞检测
        if (isAttacking && attackCooldown <= 0) {
            performCollisionDetection();
        }
    }

    /**
     * 开始攻击
     */
    public void startAttack() {
        this.isAttacking = true;
        this.attackCooldown = getAttackCooldown();
    }

    /**
     * 结束攻击
     */
    public void endAttack() {
        this.isAttacking = false;
    }

    /**
     * 获取攻击冷却时间（秒）
     */
    protected float getAttackCooldown() {
        return 0.5f; // 默认 0.5 秒冷却
    }

    /**
     * 执行碰撞检测
     */
    protected void performCollisionDetection() {
        Entity owner = getOwner();
        if (!(owner instanceof LivingEntity livingOwner)) {
            return;
        }

        // 获取武器碰撞箱的世界坐标
        Vec3 weaponTipPos = getWeaponBoneWorldPosition(1.0f);
        if (weaponTipPos == null) {
            return;
        }

        // 查找范围内的实体
        AABB searchBox = new AABB(
            livingOwner.getX() - attackRange, livingOwner.getY() - attackRange, livingOwner.getZ() - attackRange,
            livingOwner.getX() + attackRange, livingOwner.getY() + attackRange, livingOwner.getZ() + attackRange
        );

        List<Entity> entities = livingOwner.level().getEntities(owner, searchBox, 
            entity -> entity != owner && entity instanceof LivingEntity);

        // 获取武器 OBB
        OBB weaponOBB = getWeaponOBB(1.0f);
        
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity livingTarget) {
                // OBB vs AABB 碰撞检测
                AABB targetAABB = livingTarget.getBoundingBox();
                
                if (weaponOBB != null && OBBCollisionUtil.obbIntersectsAABB(weaponOBB, targetAABB)) {
                    // 碰撞发生，触发伤害
                    onWeaponHit(livingOwner, livingTarget, weaponTipPos);
                    
                    // 触发命中回调
                    getHitCallback().accept(livingOwner, livingTarget);
                    
                    // 设置冷却
                    attackCooldown = getAttackCooldown();
                }
            }
        }
    }

    /**
     * 获取武器骨骼的世界位置
     */
    protected Vec3 getWeaponBoneWorldPosition(float partialTick) {
        try {
            ModelInstance model = getModelController().getModel();
            if (model == null) return null;
            
            ModelPose pose = model.getPose();
            if (pose == null) return null;
            
            BonePose bonePose = pose.getBonePose(hitboxBoneName);
            if (bonePose == null) return null;
            
            Vector3f worldPos = bonePose.getWorldBonePivot(
                Vec3.ZERO, partialTick
            );
            
            return new Vec3(worldPos.x, worldPos.y, worldPos.z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取武器的 OBB（定向包围盒）
     */
    protected OBB getWeaponOBB(float partialTick) {
        try {
            ModelInstance model = getModelController().getModel();
            if (model == null) return null;
            
            ModelPose pose = model.getPose();
            if (pose == null) return null;
            
            OModel origin = model.getOrigin();
            if (origin == null) return null;
            
            // 获取 locator（通过 OModel.getLocator 获取）
            OLocator locator = origin.getLocator(hitboxBoneName);
            if (locator == null) return null;
            
            // 获取骨骼
            var bone = origin.getBone(hitboxBoneName);
            if (bone == null) return null;
            
            // 获取骨骼姿态
            BonePose bonePose = pose.getBonePose(hitboxBoneName);
            Matrix4f worldMatrix = bonePose.getWorldBoneMatrix(partialTick);
            
            // 获取 locator 的世界坐标
            Vector3f locatorWorld = bonePose.getWorldBonePivot(
                locator.getOffset(), partialTick
            );
            
            // 从 locator 世界坐标创建简单的 OBB
            // 假设剑刃是一个长方体
            double halfLength = 0.4; // 剑刃半长
            double halfWidth = 0.05;  // 剑刃半宽
            double halfHeight = 0.02; // 剑刃半高
            
            // 计算剑刃的三个轴（基于骨骼旋转）
            Vec3 u = getBoneAxis(worldMatrix, 0); // X 轴
            Vec3 v = getBoneAxis(worldMatrix, 1); // Y 轴
            Vec3 w = getBoneAxis(worldMatrix, 2); // Z 轴
            
            Vec3 center = new Vec3(locatorWorld.x, locatorWorld.y, locatorWorld.z);
            Vec3[] axes = new Vec3[]{u.normalize(), v.normalize(), w.normalize()};
            Vec3[] halfExtents = new Vec3[]{
                u.scale(halfLength),
                v.scale(halfWidth),
                w.scale(halfHeight)
            };
            
            return new OBB(center, axes, halfExtents);
            
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从变换矩阵获取轴向量
     */
    private Vec3 getBoneAxis(Matrix4f matrix, int axisIndex) {
        return switch (axisIndex) {
            case 0 -> new Vec3(matrix.m00(), matrix.m10(), matrix.m20()); // X 轴
            case 1 -> new Vec3(matrix.m01(), matrix.m11(), matrix.m21()); // Y 轴
            case 2 -> new Vec3(matrix.m02(), matrix.m12(), matrix.m22()); // Z 轴
            default -> Vec3.ZERO;
        };
    }

    /**
     * 获取武器碰撞的世界变换矩阵
     */
    protected Matrix4f getWeaponWorldMatrix(float partialTick) {
        try {
            ModelInstance model = getModelController().getModel();
            if (model == null) return new Matrix4f();
            
            ModelPose pose = model.getPose();
            if (pose == null) return new Matrix4f();
            
            BonePose bonePose = pose.getBonePose(hitboxBoneName);
            if (bonePose == null) return new Matrix4f();
            
            return bonePose.getWorldBoneMatrix(partialTick);
        } catch (Exception e) {
            return new Matrix4f();
        }
    }

    /**
     * 武器命中时的回调
     * 子类可重写以实现自定义命中逻辑
     */
    protected void onWeaponHit(LivingEntity attacker, LivingEntity target, Vec3 hitPoint) {
        // 默认实现：应用原版攻击伤害
    }

    /**
     * 获取命中回调
     */
    protected BiConsumer<LivingEntity, LivingEntity> getHitCallback() {
        return (attacker, target) -> {
            // 默认：使用原版攻击逻辑
            if (attacker instanceof Player player) {
                player.attack(target);
            } else {
                attacker.doHurtTarget(target);
            }
        };
    }

    /**
     * 获取武器尖端的碰撞形状（用于视觉调试）
     */
    public VoxelShape getWeaponHitboxShape(float partialTick) {
        OBB obb = getWeaponOBB(partialTick);
        if (obb == null) {
            return Shapes.empty();
        }

        Vec3[] verts = obb.getVertices();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (Vec3 v : verts) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }

        return Shapes.create(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /**
     * 判断攻击是否在冷却中
     */
    public boolean isOnCooldown() {
        return attackCooldown > 0;
    }

    /**
     * 获取当前冷却进度 (0-1)
     */
    public float getCooldownProgress() {
        if (attackCooldown <= 0) return 1f;
        return 1f - (attackCooldown / getAttackCooldown());
    }
}
