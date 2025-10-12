package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class HitboxPart extends Entity {
    private ModularZombie parent;
    private String bodyPart;
    private float damageMultiplier;

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
}