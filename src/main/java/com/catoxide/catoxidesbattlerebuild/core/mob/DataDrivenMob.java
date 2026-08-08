package com.catoxide.catoxidesbattlerebuild.core.mob;

import cn.solarmoon.spark_core.animation.model.ModelIndex;
import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.core.behavior.HurtContext;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 数据驱动通用实体
 * <p>所有 JSON 定义实体的统一实体类：模型/贴图/尺寸/属性/音效/动画状态/行为组件
 * 全部由 {@link MobDefinition}（实体定义 JSON）驱动。加新生物 = 加 JSON + 资源，零 Java。
 *
 * <p><b>对照意义</b>：zombie2（内部手写类注册）vs DataDrivenMob（JSON 装配）
 * 用于验证 mob 加载器两种模式的正确性与等价性。
 */
public class DataDrivenMob extends AnimatedMob<DataDrivenMob> {

    private static final String TAG = "DataDrivenMob";

    // 动画状态常量（与僵尸动画约定一致）
    public static final int STATE_IDLE = 0;
    public static final int STATE_WALKING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_ATTACK = 3;
    public static final int STATE_ALERT = 4;
    public static final int STATE_HIT_FRONT = 5;
    public static final int STATE_HIT_BACK = 6;
    public static final int STATE_WINDING = 7;

    private static final EntityDataAccessor<Integer> DATA_ANIM_STATE =
            SynchedEntityData.defineId(DataDrivenMob.class, EntityDataSerializers.INT);

    private final MobDefinition definition;
    private final List<IMobBehavior> behaviors;

    public DataDrivenMob(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        MobDefinition def = MobDefinitionRegistry.get(EntityType.getKey(type));
        if (def == null) {
            LogManager.serverError(TAG, "No MobDefinition registered for entity key {} - using empty fallback",
                    EntityType.getKey(type));
            def = emptyDefinition(EntityType.getKey(type));
        }
        this.definition = def;
        this.behaviors = BehaviorAssembler.assemble(def.behaviors());
        for (IMobBehavior behavior : behaviors) {
            behavior.onRegister(this);
        }
    }

    private static MobDefinition emptyDefinition(ResourceLocation key) {
        return new MobDefinition(
                key.getPath(), key.getNamespace(), key.toString(),
                0.6f, 1.95f,
                java.util.Map.of(),
                "entity",
                key,
                null,
                MobSoundConfig.NONE,
                java.util.Map.of(),
                List.of()
        );
    }

    // ==================== 数据驱动实现（AnimatedMob 抽象方法）====================

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ANIM_STATE, STATE_IDLE);
    }

    @Override
    public String getStateAnimationName(int state) {
        return definition.stateAnimations().get(state);
    }

    @Override
    protected int determineAnimationState() {
        if (this.swinging) {
            return STATE_ATTACK;
        }
        boolean isMoving = this.moveControl.hasWanted() || !this.getNavigation().isDone();
        return isMoving ? STATE_WALKING : STATE_IDLE;
    }

    @Override
    public int getAnimState() {
        return this.entityData.get(DATA_ANIM_STATE);
    }

    @Override
    public void setAnimState(int state) {
        this.entityData.set(DATA_ANIM_STATE, state);
    }

    @Override
    public ModelIndex getDefaultModelIndex() {
        return new ModelIndex(definition.modelType(), definition.modelId());
    }

    @Override
    public ResourceLocation getTextureLocation() {
        return definition.texture();
    }

    // ==================== 音效（从定义读取）====================

    @Override
    protected SoundEvent getAmbientSound() {
        return toSound(definition.sounds().ambient());
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return toSound(definition.sounds().hurt());
    }

    @Override
    protected SoundEvent getDeathSound() {
        return toSound(definition.sounds().death());
    }

    @Override
    public SoundEvent getStepSound() {
        return toSound(definition.sounds().step());
    }

    @Override
    public SoundEvent getSwingSound() {
        return toSound(definition.sounds().swing());
    }

    private static SoundEvent toSound(ResourceLocation id) {
        return id == null ? null : SoundEvent.createVariableRangeEvent(id);
    }

    // ==================== 生命周期分发到行为组件 ====================

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            tickAnimation();
            for (IMobBehavior behavior : behaviors) {
                try {
                    behavior.tick(this);
                } catch (Exception e) {
                    LogManager.serverError(TAG, "Behavior tick threw: {}", e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!this.level().isClientSide) {
            for (IMobBehavior behavior : behaviors) {
                try {
                    behavior.onSpawn(this);
                } catch (Exception e) {
                    LogManager.serverError(TAG, "Behavior onSpawn threw: {}", e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.level().isClientSide) {
            for (IMobBehavior behavior : behaviors) {
                try {
                    behavior.onDeath(this);
                } catch (Exception e) {
                    LogManager.serverError(TAG, "Behavior onDeath threw: {}", e.getMessage(), e);
                }
            }
        }
    }

    @Override
    protected void registerGoals() {
        for (IMobBehavior behavior : behaviors) {
            try {
                behavior.registerGoals(this);
            } catch (Exception e) {
                LogManager.serverError(TAG, "Behavior registerGoals threw: {}", e.getMessage(), e);
            }
        }
    }

    /** 获取本实体的数据驱动定义 */
    public MobDefinition definition() {
        return definition;
    }

    /** 获取装配的行为组件列表 */
    public List<IMobBehavior> behaviors() {
        return behaviors;
    }

    /**
     * 向行为组件广播受击事件（供外部/其他系统调用）。
     * @return true = 已有组件接管该次受击
     */
    public boolean routeHurt(LivingEntity attacker, String boneName, float damage) {
        HurtContext ctx = new HurtContext(attacker, this, boneName, damage);
        for (IMobBehavior behavior : behaviors) {
            try {
                if (behavior.onHit(this, ctx)) {
                    return true;
                }
            } catch (Exception e) {
                LogManager.serverError(TAG, "Behavior onHit threw: {}", e.getMessage(), e);
            }
        }
        return false;
    }
}
