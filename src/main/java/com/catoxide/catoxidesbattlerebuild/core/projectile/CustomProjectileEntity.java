package com.catoxide.catoxidesbattlerebuild.core.projectile;

import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimController;
import cn.solarmoon.spark_core.animation.model.ModelController;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import com.catoxide.catoxidesbattlerebuild.core.collision.ArrowBoneCollision;
import com.catoxide.catoxidesbattlerebuild.core.combat.AttackProperties;
import com.catoxide.catoxidesbattlerebuild.core.combat.CombatResult;
import com.catoxide.catoxidesbattlerebuild.core.combat.CombatSystem;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量自定义投射物实体。
 * <p>
 * 核心职责：
 * - 沿矢量轨迹飞行（自定义物理）
 * - 射线-球体骨骼碰撞检测
 * - 命中后走 CombatSystem 结算
 * <p>
 * 设计特点：
 * - 极小碰撞箱（0.05 × 0.05）
 * - 无 vanilla 伤害链路
 * - Spark 自动同步位置/旋转
 * - 寿命有限，超时自动销毁
 */
public class CustomProjectileEntity extends Entity implements IEntityAnimatable<CustomProjectileEntity> {

    private static final String TAG = "Projectile";
    private static final EntityDataAccessor<Integer> DATA_LIFETIME =
            SynchedEntityData.defineId(CustomProjectileEntity.class, EntityDataSerializers.INT);

    // Spark-Core 动画
    protected final AnimController animController = new AnimController(this);
    protected final ModelController modelController = new ModelController(this);

    // 投射物配置
    protected ProjectileConfig config;

    // 物理状态
    protected Vec3 velocity;
    protected Vec3 lastPosition;
    protected int lifetime = 0;

    // 射手引用
    protected LivingEntity shooter;

    public CustomProjectileEntity(EntityType<? extends CustomProjectileEntity> entityType, Level level) {
        super(entityType, level);
        this.config = ProjectileConfig.DEFAULT_ARROW;
    }

    public CustomProjectileEntity(EntityType<? extends CustomProjectileEntity> entityType, Level level,
                                  LivingEntity shooter, Vec3 direction, float power, ProjectileConfig config) {
        this(entityType, level);
        this.shooter = shooter;
        this.config = config;
        this.velocity = direction.scale(config.baseSpeed() * power);
        this.lastPosition = this.position();
    }

    /**
     * 工厂方法：创建并生成投射物到世界。
     */
    public static CustomProjectileEntity shoot(Level level, LivingEntity shooter,
                                               Vec3 direction, float power,
                                               ProjectileConfig config,
                                               EntityType<? extends CustomProjectileEntity> entityType) {
        CustomProjectileEntity projectile = new CustomProjectileEntity(entityType, level, shooter, direction, power, config);
        projectile.setPos(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
        level.addFreshEntity(projectile);
        LogManager.serverInfo(TAG, "Projectile added to world: id=%d", projectile.getId());
        return projectile;
    }

    // ========== IEntityAnimatable (Spark 同步) ==========

    @Override
    public CustomProjectileEntity getAnimatable() { return this; }

    @Override
    public AnimController getAnimController() { return animController; }

    @Override
    public ModelController getModelController() { return modelController; }

    @Override
    public ModelIndex getDefaultModelIndex() {
        return new ModelIndex("entity",
                ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "projectile"));
    }

    @Override
    public Level getAnimLevel() { return level(); }

    // ========== NBT 序列化 ==========

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_LIFETIME, 0);
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag nbt) {
        nbt.putFloat("Vx", (float) this.velocity.x);
        nbt.putFloat("Vy", (float) this.velocity.y);
        nbt.putFloat("Vz", (float) this.velocity.z);
        nbt.putInt("Lifetime", this.lifetime);
        if (this.shooter != null) nbt.putInt("OwnerId", this.shooter.getId());
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag nbt) {
        this.velocity = new Vec3(nbt.getFloat("Vx"), nbt.getFloat("Vy"), nbt.getFloat("Vz"));
        this.lifetime = nbt.getInt("Lifetime");
        if (nbt.contains("OwnerId")) {
            Entity owner = this.level().getEntity(nbt.getInt("OwnerId"));
            if (owner instanceof LivingEntity) this.shooter = (LivingEntity) owner;
        }
    }

    // ========== 核心 tick ==========

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || this.isRemoved()) return;

        this.lifetime++;
        this.entityData.set(DATA_LIFETIME, this.lifetime);
        if (this.lifetime >= this.config.maxLifetime()) {
            this.discard(); return;
        }
        if (this.shooter != null && !this.shooter.isAlive()) {
            this.discard(); return;
        }

        this.lastPosition = this.position();
        applyPhysics();
        if (this.isRemoved()) return;

        this.move(MoverType.SELF, this.velocity);
        updateRotation();
        processBoneCollision();
    }

    protected void applyPhysics() {
        double vx = this.velocity.x, vy = this.velocity.y, vz = this.velocity.z;
        vy -= this.config.gravity();
        float r = this.config.airResistance();
        vx *= r; vy *= r; vz *= r;
        if (vx * vx + vy * vy + vz * vz < 1e-6) {
            this.discard(); return;
        }
        this.velocity = new Vec3(vx, vy, vz);
    }

    protected void updateRotation() {
        float yaw = (float) Math.toDegrees(Math.atan2(this.velocity.z, this.velocity.x));
        float pitch = (float) Math.toDegrees(Math.atan2(-this.velocity.y,
                Math.sqrt(this.velocity.x * this.velocity.x + this.velocity.z * this.velocity.z)));
        this.setYRot(yaw);
        this.setXRot(pitch);
    }

    // ========== 骨骼碰撞 ==========

    protected void processBoneCollision() {
        if (this.shooter == null) return;
        Vec3 rayOrigin = this.lastPosition;
        Vec3 rayEnd = this.position();
        Vec3 rayDelta = rayEnd.subtract(rayOrigin);
        if (rayDelta.x * rayDelta.x + rayDelta.y * rayDelta.y + rayDelta.z * rayDelta.z < 1e-8) return;
        Vec3 rayDir = rayDelta.normalize();

        double searchRadius = 2.0;
        AABB searchBox = new AABB(
                rayEnd.x - searchRadius, rayEnd.y - searchRadius, rayEnd.z - searchRadius,
                rayEnd.x + searchRadius, rayEnd.y + searchRadius, rayEnd.z + searchRadius);

        List<Entity> nearby = this.level().getEntities(this, searchBox,
                e -> e instanceof LivingEntity && ((LivingEntity) e).isAlive() && e != this.shooter);

        for (Entity e : nearby) {
            LivingEntity target = (LivingEntity) e;
            ArrowBoneCollision.BoneCollisionResult hitResult = rayBoneCollision(rayOrigin, rayDir, target);
            if (hitResult != null && hitResult.hit()) {
                LogManager.serverInfo(TAG, "Bone HIT! Entity=%d, Part=%s, Unit=%s",
                        target.getId(), hitResult.partName(), hitResult.unitName());
                processCombat(target, hitResult);
                this.discard();
                return;
            }
        }
    }

    protected ArrowBoneCollision.BoneCollisionResult rayBoneCollision(
            Vec3 rayOrigin, Vec3 rayDir, LivingEntity target) {
        EntityBoneSystem boneSystem = EntityBoneSystem.getInstance();
        List<BodyUnit> units = boneSystem.getDefaultBodyUnits(target.getId());
        if (units == null || units.isEmpty()) return null;

        for (BodyUnit unit : units) {
            Vec3 bonePos = getBoneWorldPosition(target, unit.getBoneName());
            if (bonePos == null) continue;
            float radius = getBoneRadius(unit);
            Vec3 hitPoint = raySphereIntersect(rayOrigin, rayDir, bonePos, radius);
            if (hitPoint != null) {
                return new ArrowBoneCollision.BoneCollisionResult(
                        true, unit.getParentPart().getPartName(), unit.getBoneName(), bonePos, hitPoint);
            }
        }
        return null;
    }

    protected Vec3 getBoneWorldPosition(LivingEntity target, String boneName) {
        if (target instanceof com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob<?> am) {
            org.joml.Vector3f pos = am.getServerBonePosition(boneName);
            if (pos != null) return new Vec3(pos.x(), pos.y(), pos.z());
        }
        return target.position();
    }

    protected float getBoneRadius(BodyUnit unit) {
        String tag = unit.getCollisionTag();
        if (tag != null && !tag.isEmpty()) {
            try { return Float.parseFloat(tag); } catch (NumberFormatException ignored) {}
        }
        return this.config.collisionRadius();
    }

    protected Vec3 raySphereIntersect(Vec3 origin, Vec3 dir, Vec3 center, float radius) {
        double ocX = origin.x - center.x, ocY = origin.y - center.y, ocZ = origin.z - center.z;
        double a = dir.x * dir.x + dir.y * dir.y + dir.z * dir.z;
        double b = 2.0 * (ocX * dir.x + ocY * dir.y + ocZ * dir.z);
        double c = ocX * ocX + ocY * ocY + ocZ * ocZ - (double) radius * radius;
        double disc = b * b - 4.0 * a * c;
        if (disc < 0.0) return null;
        double t = (-b - Math.sqrt(disc)) / (2.0 * a);
        if (t < 0.0) t = 0.0;
        return origin.add(dir.x * t, dir.y * t, dir.z * t);
    }

    // ========== 战斗结算 ==========

    protected void processCombat(LivingEntity target, ArrowBoneCollision.BoneCollisionResult hitResult) {
        try {
            if (this.shooter == null) return;
            float baseDamage = this.config.baseDamage();
            float armorPen = this.config.armorPenetration();
            float piercing = this.config.piercing();

            AttackProperties attack = AttackProperties.of(armorPen, piercing, baseDamage);
            EntityBoneSystem boneSystem = EntityBoneSystem.getInstance();
            List<BodyUnit> units = new ArrayList<>();
            for (BodyPart part : boneSystem.getBodyParts(target.getId())) {
                units.addAll(part.getUnits());
            }
            if (units.isEmpty()) units = boneSystem.getDefaultBodyUnits(target.getId());
            if (units.isEmpty()) return;

            List<BodyUnit> hitUnits = units.stream()
                    .filter(u -> u.getBoneName().equals(hitResult.unitName())).toList();
            if (hitUnits.isEmpty()) hitUnits = units;

            float[] healths = new float[hitUnits.size()];
            for (int i = 0; i < hitUnits.size(); i++)
                healths[i] = hitUnits.get(i).getParentPart().getModuleHealth();

            CombatResult result = CombatSystem.getInstance().processAttack(attack, hitUnits, healths);
            if (result.totalDamage() > 0) {
                Map<BodyPart, Float> dmgMap = new HashMap<>();
                for (CombatResult.HitResult hit : result.hitResults()) {
                    BodyPart part = hit.bodyUnit().getParentPart();
                    dmgMap.merge(part, hit.actualDamage(), Float::sum);
                }
                boolean fatal = false;
                for (Map.Entry<BodyPart, Float> e : dmgMap.entrySet()) {
                    e.getKey().applyDamage(e.getValue());
                    if (e.getKey().isDestroyed() && e.getKey().isFatal()) fatal = true;
                }
                target.hurt(target.damageSources().mobAttack(this.shooter), result.totalDamage());
                if (fatal) target.kill();
            }
        } catch (Exception e) {
            LogManager.serverWarn(TAG, "Combat failed: %s", e.getMessage());
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (!this.level().isClientSide)
            LogManager.serverDebug(TAG, "Projectile id=%d removed", this.getId());
    }
}
