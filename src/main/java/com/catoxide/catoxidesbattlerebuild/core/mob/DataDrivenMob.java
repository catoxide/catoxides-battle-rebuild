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
 * <p><b>懒初始化设计</b>：{@code definition} / {@code behaviors} 不在构造时初始化，
 * 而在首次访问时从静态注册表装配。原因：Mob 父类构造器会调用 {@code registerGoals()}，
 * AnimatedMob 构造器会调用 {@code getTextureLocation()}——这些虚方法在 {@code super()} 链中
 * 被调用时，实例字段尚未初始化（Java 字段初始化在 super() 之后）。走静态注册表可规避此时序问题。
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

    /** 懒初始化：首次访问时从 MobDefinitionRegistry 装配 */
    private MobDefinition definition;
    private List<IMobBehavior> behaviors;

    public DataDrivenMob(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        // 不在此装配：父类构造链中 registerGoals()/getTextureLocation() 已被调用，
        // 懒初始化在首次访问时完成（getType() 此时已可用，注册表已填充）
    }

    // ==================== 懒初始化访问器 ====================

    private MobDefinition def() {
        MobDefinition d = this.definition;
        if (d == null) {
            d = MobDefinitionRegistry.get(EntityType.getKey(this.getType()));
            if (d == null) {
                LogManager.serverError(TAG, "No MobDefinition for entity key {} - using empty fallback",
                        EntityType.getKey(this.getType()));
                d = emptyDefinition(EntityType.getKey(this.getType()));
            }
            this.definition = d;
        }
        return d;
    }

    private List<IMobBehavior> behaviors() {
        List<IMobBehavior> b = this.behaviors;
        if (b == null) {
            synchronized (this) {
                b = this.behaviors;
                if (b == null) {
                    b = BehaviorAssembler.assemble(def().behaviors());
                    for (IMobBehavior behavior : b) {
                        try {
                            behavior.onRegister(this);
                        } catch (Exception e) {
                            LogManager.serverError(TAG, "Behavior onRegister threw: {}", e.getMessage(), e);
                        }
                    }
                    this.behaviors = b;
                }
            }
        }
        return b;
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
        return def().stateAnimations().get(state);
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
        return new ModelIndex(def().modelType(), def().modelId());
    }

    @Override
    public ResourceLocation getTextureLocation() {
        return def().texture();
    }

    // ==================== 音效（从定义读取）====================

    @Override
    protected SoundEvent getAmbientSound() {
        return toSound(def().sounds().ambient());
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return toSound(def().sounds().hurt());
    }

    @Override
    protected SoundEvent getDeathSound() {
        return toSound(def().sounds().death());
    }

    @Override
    public SoundEvent getStepSound() {
        return toSound(def().sounds().step());
    }

    @Override
    public SoundEvent getSwingSound() {
        return toSound(def().sounds().swing());
    }

    private static SoundEvent toSound(ResourceLocation id) {
        return id == null ? null : SoundEvent.createVariableRangeEvent(id);
    }

    // ==================== 生命周期分发到行为组件 ====================

    @Override
    public void tick() {
        super.tick();
        // tickAnimation() 内部区分双端：服务端 updateAnimationState / 客户端 syncClientAnimation。
        // 必须无条件调用（客户端需要 syncClientAnimation 来播放动画）——与手写类（zombie3）一致
        tickAnimation();
        if (!this.level().isClientSide) {
            for (IMobBehavior behavior : behaviors()) {
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
            for (IMobBehavior behavior : behaviors()) {
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
            for (IMobBehavior behavior : behaviors()) {
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
        // 父类 Mob 构造器也会调用本方法——懒初始化保证此时也能正确装配
        for (IMobBehavior behavior : behaviors()) {
            try {
                behavior.registerGoals(this);
            } catch (Exception e) {
                LogManager.serverError(TAG, "Behavior registerGoals threw: {}", e.getMessage(), e);
            }
        }
    }

    /** 获取本实体的数据驱动定义（懒装配） */
    public MobDefinition definition() {
        return def();
    }

    /**
     * 向行为组件广播受击事件（供外部/其他系统调用）。
     * @return true = 已有组件接管该次受击
     */
    public boolean routeHurt(LivingEntity attacker, String boneName, float damage) {
        HurtContext ctx = new HurtContext(attacker, this, boneName, damage);
        for (IMobBehavior behavior : behaviors()) {
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
