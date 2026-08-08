package com.catoxide.catoxidesbattlerebuild.core.anim;

import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimController;
import cn.solarmoon.spark_core.animation.anim.AnimInstance;
import cn.solarmoon.spark_core.animation.anim.AnimInstanceBuilderKt;
import cn.solarmoon.spark_core.animation.model.ModelController;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import cn.solarmoon.spark_core.event.BoneUpdateEvent;
import com.catoxide.catoxidesbattlerebuild.core.combat.EntityBoneHealthConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPartConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * AnimatedMob 基类：Spark-Core 动画生物的通用基类
 * <p>封装 Spark-Core 集成、骨骼数据管理、动画状态机骨架、零姿态恢复、客户端同步等公共逻辑。
 *
 * <h3>子类职责</h3>
 * <ul>
 *   <li>实现 {@link #determineAnimationState()} 提供状态判定逻辑</li>
 *   <li>实现 {@link #getStateAnimationName(int)} 提供状态→动画名映射</li>
 *   <li>实现 {@link #getDefaultModelIndex()} 提供模型索引</li>
 *   <li>实现 {@link #getTextureLocation()} 提供贴图路径</li>
 *   <li>在 {@link Mob#registerGoals()} 注册 AI Goal</li>
 *   <li>定义状态枚举常量</li>
 * </ul>
 *
 * <h3>动画状态机</h3>
 * <ul>
 *   <li>服务端：tick 中调用 {@link #updateAnimationState()}，比较当前状态和新状态</li>
 *   <li>客户端：tick 中调用 {@link #syncClientAnimation()}，根据同步的状态播放动画</li>
 *   <li>零姿态恢复：自动检测无动画播放状态，调用 {@link #recoverFromZeroPose()}</li>
 * </ul>
 */
public abstract class AnimatedMob<T extends AnimatedMob<T>> extends PathfinderMob implements IEntityAnimatable<T> {

    // Spark-Core 动画系统
    protected final AnimController animController;
    protected final ModelController modelController;

    // 骨骼数据管理（服务端+客户端共用）
    protected final BoneDataManager boneData = new BoneDataManager();

    // 动画状态机
    protected static final long MIN_STATE_HOLD_TICKS = 10;
    protected static final long ZERO_POSE_GRACE_TICKS = 10;

    // 挥动保持窗口：挥动结束后极短保持攻击动画。
    // 原 8 tick 让攻击反复打断移动（走走停停→"打后停/走得慢"）。
    // 缩到 2 tick：攻击瞬间抬手，随即切回 walking，移动不被攻击打断
    protected static final long SWING_HOLD_TICKS = 2;
    protected long lastSwingTick = 0;

    protected boolean serverInitialAnimPlayed = false;
    protected long lastStateChangeTick = 0;
    protected long lastAnimRequestTick = 0;
    protected int lastClientAnimState = -1;

    // 动画过渡时间（子类可覆盖）
    protected float inTransitionTime = 0.05f;
    protected float outTransitionTime = 0.05f;

    protected AnimatedMob(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.animController = new AnimController(this);
        this.modelController = new ModelController(this);
        ResourceLocation tex = getTextureLocation();
        if (tex != null) {
            this.modelController.setTextureLocation(tex);
        }
        // Initialize bone system on server-side spawn
        this.initializeBoneSystem();
    }

    // ========== EntityBoneSystem Integration ==========

    /**
     * 初始化实体骨骼系统（服务端）
     * <p>从 health_config JSON 文件加载部位配置，注册到 EntityBoneSystem。
     * 如果找不到配置文件，静默跳过。
     */
    private void initializeBoneSystem() {
        if (this.level() == null || this.level().isClientSide()) {
            return;
        }

        net.minecraft.server.packs.resources.ResourceManager rm = this.level().getServer().getResourceManager();

        // Get entity type key (e.g., "zombie3pack:modular_zombie_3" or "catoxidesbattlerebuild:modular_zombie_2")
        String entityKey = EntityType.getKey(this.getType()).toString();
        String namespace = entityKey.substring(0, entityKey.indexOf(':'));
        String entityName = entityKey.substring(entityKey.indexOf(':') + 1);

        // Try loading from the entity's own namespace first
        List<BodyPartConfig> configs = EntityBoneHealthConfig.load(rm, namespace, entityName);

        // Fallback: try catoxidesbattlerebuild namespace
        if (configs == null || configs.isEmpty()) {
            configs = EntityBoneHealthConfig.load(rm, "catoxidesbattlerebuild", entityName);
        }

        // Ultimate fallback: try "modular_zombie" as generic config
        if (configs == null || configs.isEmpty()) {
            configs = EntityBoneHealthConfig.load(rm, "catoxidesbattlerebuild", "modular_zombie");
        }

        if (configs != null && !configs.isEmpty()) {
            EntityBoneSystem.getInstance().initEntity(this.getId(), configs);
            LogManager.serverInfo("AnimatedMob",
                    "Bone system initialized for entityId=%d, entityKey=%s, configParts=%d",
                    this.getId(), entityKey, configs.size());
        } else {
            LogManager.serverDebug("AnimatedMob",
                    "No health config found for entityKey=%s", entityKey);
        }
    }

    // ========== 子类必须实现 ==========

    /**
     * 返回状态→动画名映射
     * @param state 状态 ID
     * @return 动画名（对应 animation.json 中的 name），null 表示无动画
     */
    public abstract String getStateAnimationName(int state);

    /**
     * 判定当前应该处于哪个状态
     * @return 状态 ID
     */
    protected abstract int determineAnimationState();

    /**
     * 获取当前动画状态 ID
     */
    public abstract int getAnimState();

    /**
     * 设置当前动画状态 ID
     */
    public abstract void setAnimState(int state);

    /**
     * 获取模型索引（geo.json 路径）
     */
    @Override
    public abstract ModelIndex getDefaultModelIndex();

    /**
     * 获取贴图路径
     */
    public abstract ResourceLocation getTextureLocation();

    // ========== IEntityAnimatable ==========

    @Override
    @SuppressWarnings("unchecked")
    public T getAnimatable() {
        return (T) this;
    }

    @Override
    public AnimController getAnimController() {
        return animController;
    }

    @Override
    public ModelController getModelController() {
        return modelController;
    }

    @Override
    public Level getAnimLevel() {
        return this.level();
    }

    @Override
    public void onBoneUpdate(BoneUpdateEvent event) {
        if (!level().isClientSide()) {
            boneData.onBoneUpdate(event, level().getGameTime());
        }
    }

    // ========== Bone Data API ==========

    public Vector3f getServerBonePosition(String boneName) {
        return boneData.getPosition(boneName);
    }

    public Matrix4f getServerBoneMatrix(String boneName) {
        return boneData.getMatrix(boneName);
    }

    public Map<String, Vector3f> getAllServerBonePositions() {
        return boneData.getPositions();
    }

    public Map<String, Matrix4f> getAllServerBoneMatrices() {
        return boneData.getMatrices();
    }

    /**
     * 客户端调用：从同步数据更新骨骼位置
     */
    public void updateClientBonePositions(Map<String, Vector3f> positions) {
        if (level().isClientSide()) {
            boneData.updateFromClient(positions, level().getGameTime());
            LogManager.boneSyncClient(getId(), positions.size());
        }
    }

    /**
     * 客户端调用：从 BonePose 矩阵直接更新（用于调试渲染）
     */
    public void updateClientBoneMatrix(String boneName, Matrix4f mat) {
        if (level().isClientSide()) {
            boneData.updateMatrixFromClient(boneName, mat);
        }
    }

    // ========== Animation State Machine ==========

    /**
     * 服务端：更新动画状态
     * <p><b>重构要点</b>：determineAnimationState() 是纯判定（稳定输出），
     * 状态变化<b>立即切换</b>——不再被 MIN_STATE_HOLD_TICKS 阻挡。
     * 原实现用 hold 防抖动，但会导致攻击结束后切不回 walking（"攻击脱离后脚停/切不回去"）。
     */
    protected void updateAnimationState() {
        int currentState = getAnimState();

        // 子类 hook：受击中等状态可跳过更新
        if (!shouldUpdateState(currentState)) return;

        // 首次播放 idle
        if (!serverInitialAnimPlayed) {
            serverInitialAnimPlayed = true;
            lastStateChangeTick = this.level().getGameTime();
            playAnimationForState(currentState);
            return;
        }

        int newState = determineAnimationState();

        if (currentState != newState) {
            String fromName = getStateAnimationName(currentState);
            String toName = getStateAnimationName(newState);
            setAnimState(newState);
            lastStateChangeTick = this.level().getGameTime();
            playAnimationForState(newState);
            LogManager.zombie2StateChanged(getId(), fromName, toName);
        }
    }

    /**
     * 客户端：根据同步的状态播放动画
     */
    protected void syncClientAnimation() {
        int currentState = getAnimState();
        if (currentState != lastClientAnimState) {
            lastClientAnimState = currentState;
            playAnimationForState(currentState);
            LogManager.zombie2ClientSync(getId(), getStateAnimationName(currentState));
        }
    }

    /**
     * 零姿态恢复：当检测到没有动画在播放时，立即重新请求当前状态的动画
     * <p>这可以处理因动画冲突、状态机异步处理等原因导致的零姿态问题
     */
    protected void recoverFromZeroPose() {
        int currentState = getAnimState();
        String animName = getStateAnimationName(currentState);
        LogManager.zombie2ZeroPoseRecover(this.level().isClientSide, getId(), animName);
        playAnimationForState(currentState);
    }

    /**
     * 播放指定状态对应的动画
     * <p>使用 AnimInstanceBuilderKt.animInstance() 构造动画实例，
     * 设置过渡时间，然后调用 independentEnter() 启动动画。
     */
    protected void playAnimationForState(int state) {
        String animName = getStateAnimationName(state);
        if (animName == null) return;

        lastAnimRequestTick = this.level().getGameTime();
        try {
            AnimInstance anim = AnimInstanceBuilderKt.animInstance(
                    this,
                    animName,
                    true,
                    (animInstance) -> {
                        animInstance.setInTransitionTime(inTransitionTime);
                        animInstance.setOutTransitionTime(outTransitionTime);
                        return null;
                    }
            );

            if (anim != null) {
                anim.independentEnter();
                LogManager.zombie2AnimStarted(getId(), animName, String.valueOf(anim.getState()));
            } else {
                LogManager.zombie2AnimFailed(getId(), animName);
            }
        } catch (Exception e) {
            LogManager.zombie2AnimError(getId(), animName, e.getMessage(), e);
        }
    }

    /** 零姿态恢复冷却（防止 hold_on_last_frame 动画播完被无限重播 → 抬手持续） */
    private long lastRecoverTick = 0;

    /**
     * tick 中调用：检测零姿态并恢复
     * <p><b>重构要点</b>：恢复带 20 tick 冷却。原实现每 10 tick 重播当前状态动画，
     * 对 hold_on_last_frame 动画（如 attack）会导致无限重播（抬手持续）。
     */
    protected void checkZeroPose() {
        if (serverInitialAnimPlayed || lastClientAnimState != -1) {
            if (!this.animController.isPlayingAnim()
                    && this.level().getGameTime() - lastAnimRequestTick > ZERO_POSE_GRACE_TICKS
                    && this.level().getGameTime() - lastRecoverTick > 20) {
                lastRecoverTick = this.level().getGameTime();
                recoverFromZeroPose();
            }
        }
    }

    /**
     * 子类 hook：是否应该跳过状态更新（如受击中不允许切状态）
     * @param currentState 当前状态
     * @return true 允许更新，false 跳过本次更新
     */
    protected boolean shouldUpdateState(int currentState) {
        return true;
    }

    /**
     * tick 中调用：处理动画状态机更新
     * <p>子类在 {@link Mob#tick()} 中调用此方法，避免重复编写动画 tick 逻辑。
     */
    protected void tickAnimation() {
        // 跟踪挥动时刻（供 isAttackStateActive 的保持窗口使用）
        if (this.swinging) {
            lastSwingTick = this.level().getGameTime();
        }
        if (!this.level().isClientSide) {
            updateAnimationState();
            checkZeroPose();
        } else {
            syncClientAnimation();
            checkZeroPose();
        }
    }

    /**
     * 是否处于攻击动画状态（挥动中，或挥动结束后的保持窗口内）。
     * <p>子类的 {@code determineAnimationState()} 应使用本方法替代裸 {@code this.swinging}：
     * 挥空/目标脱离后 swinging 立即变 false，若直接回退会导致 attack 动画被截断、
     * 状态机在 attack 与 walking/idle 之间闪烁或卡死。保持窗口让攻击动画播完再平滑回落。
     */
    protected boolean isAttackStateActive() {
        return this.swinging
                || (this.level().getGameTime() - lastSwingTick < SWING_HOLD_TICKS);
    }

    // ========== Sound Hooks ==========
    //
    // 默认所有 override 返回 null（无声音）。Mob.playAmbientSound() / LivingEntity
    // 在 aiStep/tick 中自动调用这些方法，null 时会跳过播放。
    //
    // 子类按需 override 即可：
    //   - 短期：返回 vanilla SoundEvents（如 SoundEvents.ZOMBIE_AMBIENT）
    //   - 长远：返回 ModSounds.SOME_SOUND.get()（自定义 SoundEvent，由 ModSounds
    //     或 ContentPack 的 MobSoundLoader 自动从 .ogg 资源注册）

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return null;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return null;
    }

    /**
     * 行走声音（非 vanilla Mob 默认方法，模仿 Zombie 模式）
     * <p>由 {@link #playStepSound(BlockPos, BlockState)} 调用。
     * 子类可 override 返回声音事件（如 SoundEvents.ZOMBIE_STEP）。
     */
    public SoundEvent getStepSound() {
        return null;
    }

    /**
     * 行走时播放脚步声
     * <p>vanilla hook：Entity 在 move 到新方块时调用。默认从 {@link #getStepSound()}
     * 取声音事件，未配置则跳过。
     */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        SoundEvent event = getStepSound();
        if (event != null && !this.isSilent()) {
            this.playSound(event, 0.15F, 1.0F);
        }
    }

    /**
     * 攻击挥击音效（非 vanilla hook）
     * <p>由 {@link #playSwingSound()} 触发，子类可在 attack goal / mixin 中调用。
     * TODO: 后续在 attack mixin 中 hook vanilla swing 时调用此方法
     */
    public SoundEvent getSwingSound() {
        return null;
    }

    /**
     * 播放挥击音效（攻击时调用）
     */
    public void playSwingSound() {
        SoundEvent event = getSwingSound();
        if (event != null && !this.isSilent()) {
            this.playSound(event, this.getSoundVolume(), this.getVoicePitch());
        }
    }
}
