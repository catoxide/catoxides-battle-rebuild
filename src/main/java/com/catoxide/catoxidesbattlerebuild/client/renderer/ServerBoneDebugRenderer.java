package com.catoxide.catoxidesbattlerebuild.client.renderer;

import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.model.BonePose;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.ModelPose;
import com.catoxide.catoxidesbattlerebuild.mob.zombie2.ModularZombie2;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.Color;
import java.util.Map;

/**
 * 骨骼 Pivot 矩阵调试渲染器
 * <p>验证从 Spark-Core {@link BonePose#getWorldBonePivotMatrix(Number)} 拉下来的矩阵是否正确：
 * <ul>
 *   <li>用矩阵 translation + 局部偏移 画 OBB（青色），应贴合每个肢体部位</li>
 *   <li>用矩阵 rotation 画三轴指示（X红/Y绿/Z蓝），每轴 0.3m，便于看朝向</li>
 * </ul>
 * 偏移和尺寸数据来自 Bedrock geo.json，1px = 1/16m = 0.0625m
 */
public final class ServerBoneDebugRenderer {

    /** 三轴指示长度 */
    private static final float AXIS_LEN = 0.3f;

    // 颜色（ARGB int）
    private static final int COLOR_AXIS_X = 0xFF0000; // 红
    private static final int COLOR_AXIS_Y = 0x00FF00; // 绿
    private static final int COLOR_AXIS_Z = 0x0080FF; // 蓝

    /**
     * 骨骼受击盒配置
     * @param boneName    骨骼名
     * @param localOffset 从 pivot 到 cube 中心的偏移（局部空间，米）
     * @param halfExtents 半尺寸（局部空间，米）
     */
    private record BoneHitboxConfig(String boneName, Vector3f localOffset, Vector3f halfExtents) {}

    // 1px = 0.0625m
    private static final float PX = 1f / 16f;

    // 默认配置：来自 modular_zombie_2.geo.json
    // 注意：geo.json 用 Bedrock 坐标系（X+左, Y+上），
    // 但 getWorldBonePivotMatrix 返回的矩阵已包含 Bedrock→MC 的 X 轴翻转。
    // 所以 localOffset 的 X 要取反，否则偏移方向会反过来。
    private static final Map<String, BoneHitboxConfig> CONFIGS = Map.of(
        // body: pivot[0,12,0] cube_center[0,18,0] size[8,12,4]
        "body",      new BoneHitboxConfig("body",      new Vector3f(0,         6*PX,  0),    new Vector3f(4*PX, 6*PX, 2*PX)),
        // head: pivot[0,24,0] cube_center[0,28,0] size[8,8,8]
        "head",      new BoneHitboxConfig("head",      new Vector3f(0,         4*PX,  0),    new Vector3f(4*PX, 4*PX, 4*PX)),
        // left_arm: pivot[5,22,0] cube_center[6,18,0] size[4,12,4], offset_x = +1px → 翻转后 -1px
        "left_arm",  new BoneHitboxConfig("left_arm",  new Vector3f(-1*PX,     -4*PX,  0),    new Vector3f(2*PX, 6*PX, 2*PX)),
        // right_arm: pivot[-5,22,0] cube_center[-6,18,0] size[4,12,4], offset_x = -1px → 翻转后 +1px
        "right_arm", new BoneHitboxConfig("right_arm", new Vector3f(1*PX,      -4*PX,  0),    new Vector3f(2*PX, 6*PX, 2*PX)),
        // left_leg: pivot[1.9,12,0] cube_center[2,6,0] size[4,12,4], offset_x = +0.1px → 翻转后 -0.1px
        "left_leg",  new BoneHitboxConfig("left_leg",  new Vector3f(-0.1f*PX,  -6*PX,  0),    new Vector3f(2*PX, 6*PX, 2*PX)),
        // right_leg: pivot[-1.9,12,0] cube_center[-2,6,0] size[4,12,4], offset_x = -0.1px → 翻转后 +0.1px
        "right_leg", new BoneHitboxConfig("right_leg", new Vector3f(0.1f*PX,   -6*PX,  0),    new Vector3f(2*PX, 6*PX, 2*PX))
    );

    private ServerBoneDebugRenderer() {}

    /**
     * 在 RenderLevelStageEvent 里调用
     * @param poseStack    当前 PoseStack（不会被使用，内部用 identity 避免双重 camera 偏移）
     * @param cameraPos    相机世界坐标
     * @param partialTick  插值 partialTick
     */
    public static void render(PoseStack poseStack, Vec3 cameraPos, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getCameraEntity() == null) return;

        // 诊断：每 20 tick 打一次状态
        long gt = mc.level.getGameTime();
        boolean logThisFrame = gt % 20 == 0;

        ClientLevel level = mc.level;

        // 用 identity PoseStack + 手动减 camPos（和原版 HitboxDebugRenderer 一致）
        PoseStack identity = new PoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        int rendered = 0;
        int zombieCount = 0;
        for (net.minecraft.world.entity.Entity e : level.entitiesForRendering()) {
            if (!(e instanceof ModularZombie2 zombie)) continue;
            zombieCount++;
            // 距离剔除放宽到 64m
            if (zombie.distanceToSqr(mc.getCameraEntity()) > 64 * 64) continue;
            rendered += renderEntityBones(consumer, identity, zombie, cameraPos, partialTick, logThisFrame);
        }

        bufferSource.endBatch(RenderType.lines());

        if (logThisFrame) {
            LogManager.clientInfo("BoneDebug", "Frame: zombies={} rendered={} partialTick={} camPos=({:.2f},{:.2f},{:.2f})",
                    zombieCount, rendered, partialTick,
                    cameraPos.x, cameraPos.y, cameraPos.z);
        }
    }

    /**
     * 渲染单个实体所有骨骼的 pivot matrix
     * @return 渲染的骨骼数量
     */
    private static int renderEntityBones(VertexConsumer consumer, PoseStack poseStack,
                                        ModularZombie2 entity, Vec3 camPos, float partialTick,
                                        boolean logThisFrame) {
        if (!(entity instanceof IEntityAnimatable<?>)) {
            if (logThisFrame) LogManager.clientWarn("BoneDebug", "Entity {} not IEntityAnimatable", entity.getId());
            return 0;
        }

        IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) entity;
        ModelInstance model = animatable.getModelController().getModel();
        if (model == null) {
            if (logThisFrame) LogManager.clientWarn("BoneDebug", "Model is null for entity {} at {}",
                    entity.getId(), entity.blockPosition());
            return 0;
        }

        ModelPose poseModel = model.getPose();
        if (poseModel == null) {
            if (logThisFrame) LogManager.clientWarn("BoneDebug", "ModelPose is null for entity {}", entity.getId());
            return 0;
        }

        Map<String, BonePose> bonePoses = poseModel.getBonePoses();
        if (bonePoses.isEmpty()) {
            if (logThisFrame) LogManager.clientWarn("BoneDebug", "No bones for entity {} at {}",
                    entity.getId(), entity.blockPosition());
            return 0;
        }

        if (logThisFrame) {
            LogManager.clientInfo("BoneDebug", "Entity {} at {} bones={}",
                    entity.getId(), entity.blockPosition(), bonePoses.size());
        }

        int count = 0;
        for (Map.Entry<String, BonePose> entry : bonePoses.entrySet()) {
            String boneName = entry.getKey();
            BonePose bonePose = entry.getValue();
            // 跳过没有配置的骨骼（如 headwear）
            if (!CONFIGS.containsKey(boneName)) continue;
            try {
                renderBonePivotMatrix(consumer, poseStack, boneName, bonePose, partialTick, camPos);
                count++;
            } catch (Exception e) {
                LogManager.clientWarn("BoneDebug", "Failed to render bone {}: {}", boneName, e.getMessage());
            }
        }
        return count;
    }

    /**
     * 用 getWorldBonePivotMatrix 画 OBB + 三轴
     * OBB 中心 = pivot + R * localOffset（通过 transformPosition 一步到位）
     * OBB 尺寸 = halfExtents（非正方体，贴合每个肢体）
     */
    private static void renderBonePivotMatrix(VertexConsumer consumer, PoseStack poseStack,
                                             String boneName, BonePose bonePose,
                                             float partialTick, Vec3 camPos) {
        // ★ 这就是我们要验证的调用 —— 和服务端 onBoneUpdate 里一模一样
        Matrix4f worldMat = bonePose.getWorldBonePivotMatrix(partialTick);

        // 取 translation（骨骼 pivot 的世界坐标）
        Vector3f pivot = new Vector3f();
        worldMat.getTranslation(pivot);

        // 取 rotation（用于三轴和 OBB 旋转）
        Quaternionf rot = new Quaternionf();
        worldMat.getUnnormalizedRotation(rot);

        BoneHitboxConfig cfg = CONFIGS.get(boneName);

        // OBB 中心 = pivot + R * localOffset
        // 用 worldMat.transformPosition 一步完成（自动处理 scale/rotation）
        Vector3f obbCenter = new Vector3f();
        worldMat.transformPosition(cfg.localOffset(), obbCenter);

        // 1. 画 OBB（青色，非正方体）
        AABB localAABB = new AABB(
                -cfg.halfExtents().x(), -cfg.halfExtents().y(), -cfg.halfExtents().z(),
                cfg.halfExtents().x(),  cfg.halfExtents().y(),  cfg.halfExtents().z()
        );
        OBBRenderer.renderOBB(consumer, poseStack, localAABB, rot, Color.CYAN, 0.8f, obbCenter, camPos);

        // 2. 画三轴（从 pivot 出发，X红/Y绿/Z蓝）—— 保留用于验证朝向
        Vector3f xAxis = rot.transform(new Vector3f(AXIS_LEN, 0, 0)).add(pivot);
        Vector3f yAxis = rot.transform(new Vector3f(0, AXIS_LEN, 0)).add(pivot);
        Vector3f zAxis = rot.transform(new Vector3f(0, 0, AXIS_LEN)).add(pivot);
        OBBRenderer.renderLine(consumer, poseStack, pivot, xAxis, COLOR_AXIS_X, camPos);
        OBBRenderer.renderLine(consumer, poseStack, pivot, yAxis, COLOR_AXIS_Y, camPos);
        OBBRenderer.renderLine(consumer, poseStack, pivot, zAxis, COLOR_AXIS_Z, camPos);
    }
}
