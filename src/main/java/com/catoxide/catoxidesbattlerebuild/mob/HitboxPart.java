package com.catoxide.catoxidesbattlerebuild.mob;

import com.catoxide.catoxidesbattlerebuild.damage.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

public class HitboxPart extends Entity {
    private ModularZombie parent;
    private String bodyPart;
    private float damageMultiplier;
    private Quaternionf rotation = new Quaternionf();
    private AABB localAABB;

    public HitboxPart(EntityType<? extends HitboxPart> type, Level level) {
        super(type, level);
        setupHitboxProperties();
    }

    public void initialize(ModularZombie parent, String bodyPart) {
        this.parent = parent;
        this.bodyPart = bodyPart;
        this.damageMultiplier = getMultiplierForPart(bodyPart);
    }

    private void setupHitboxProperties() {
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.noPhysics = true;

        // 确保实体完全不可见
        this.setInvisible(true);
        this.setSilent(true);
//        this.setNoAi(true);

        // 设置一个很小的碰撞箱
        this.setBoundingBox(this.getBoundingBox().deflate(0.1, 0.1, 0.1));
    }

    private float getMultiplierForPart(String bodyPart) {
        switch (bodyPart) {
            case "head": return 2.0f;
            case "torso": return 1.0f;
            case "arm_left": case "arm_right": return 0.6f;
            case "leg_left": case "leg_right": return 0.7f;
            default: return 1.0f;
        }
    }

    @Override
    protected void defineSynchedData() {
        // 不需要同步数据
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        //
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        // 不需要保存数据
    }

    @Override
    public void tick() {
        super.tick();

        // 如果父实体不存在或已死亡，则移除这个碰撞箱
        if (parent == null || !parent.isAlive()) {
            this.discard();
            return;
        }

        // 跟随父实体位置
        this.setPos(parent.getX(), parent.getY(), parent.getZ());
    }

    @Override
    public boolean isInvisible() {
        // 始终不可见
        return true;
    }

    @Override
    public boolean isSilent() {
        // 始终静音
        return true;
    }

    // Getter 方法
    public ModularZombie getParent() {
        return parent;
    }

    public String getBodyPart() {
        return bodyPart;
    }

    public float getDamageMultiplier() {
        return damageMultiplier;
    }
    // 在 HitboxPart.java 中添加伤害处理方法
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || parent == null || !parent.isAlive()) {
            return false;
        }

        // 将伤害转发给父实体的部位伤害系统
        if (parent instanceof ModularZombie) {
            ModularZombie zombie = (ModularZombie) parent;

            // 使用正确的 CompositeDamage 构造函数
            LivingEntity attacker = source.getEntity() instanceof LivingEntity ?
                    (LivingEntity) source.getEntity() : null;

            CompositeDamage compositeDamage = new CompositeDamage(source, attacker, zombie);

            // 添加基础伤害组件 - 使用正确的 DamageType
            DamageComponent baseDamage = new DamageComponent(DamageType.PHYSICS, amount);
            compositeDamage.addComponent(baseDamage);

            // 根据部位设置伤害倍率和标签
            applyPartDamageMultiplier(compositeDamage, bodyPart);

            // 计算伤害
            DamageCalculator calculator = new DamageCalculator();
            DamageResult result = calculator.calculateDamage(compositeDamage);

            // 应用部位伤害
            zombie.onPartHit(bodyPart, result.getTotalDamage());

            // 触发伤害效果
            applyPartDamageEffects(source, result);

            return true;
        }

        return false;
    }

//    private CompositeDamage createCompositeDamageForPart(DamageSource source, float amount, String bodyPart) {
//        // 创建针对特定部位的伤害组合
//        CompositeDamage composite = new CompositeDamage(source, amount, bodyPart));
//
//        // 根据伤害来源和部位设置基础伤害
//        DamageComponent baseDamage = new DamageComponent(DamageType.PHYSICS, amount);
//        composite.addComponent(baseDamage);
//
//        // 根据部位设置伤害倍率
//        applyPartDamageMultiplier(composite, bodyPart);
//
//        return composite;
//    }

    private void applyPartDamageMultiplier(CompositeDamage composite, String bodyPart) {
        // 根据部位调整伤害
        switch (bodyPart) {
            case "head":
            case "arm_left":
            case "arm_right":
            case "leg_left":
            case "leg_right":
        }
    }

    private DamageResult calculatePartDamage(CompositeDamage damage) {
        // 使用你的伤害计算器
        DamageCalculator calculator = new DamageCalculator();
        return calculator.calculateDamage(damage);
    }

    private void applyPartDamageEffects(DamageSource source, DamageResult result) {
        // 在服务端生成部位伤害特效
        if (!level().isClientSide) {
            ServerLevel serverLevel = (ServerLevel) level();

            // 生成部位命中粒子
            serverLevel.sendParticles(
                    ParticleTypes.CRIT,
                    getX(), getY() + getBbHeight() * 0.5, getZ(),
                    3, // 粒子数量
                    getBbWidth() * 0.3,
                    getBbHeight() * 0.3,
                    getBbWidth() * 0.3,
                    0.1
            );
        }
    }
    public void setRotation(Quaternionf rotation) {
        this.rotation = rotation != null ? new Quaternionf(rotation) : new Quaternionf();
    }

    public Quaternionf getRotation() {
        return new Quaternionf(rotation);
    }

    public AABB getLocalAABB() {
        return localAABB;
    }

    public void setLocalAABB(AABB aabb) {
        this.localAABB = aabb;
    }


}