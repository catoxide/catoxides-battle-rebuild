package com.catoxide.catoxidesbattlerebuild.client.renderer;

import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.model.BonePose;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.ModelPose;
import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.core.hitbox.HitboxConfig;
import com.catoxide.catoxidesbattlerebuild.core.hitbox.HitboxResolver;
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
 * 通用骨骼调试渲染器
 * <p>接受任意 {@link AnimatedMob} 子类，从 {@link HitboxResolver} 动态提取 OBB 配置。
 *
 * <h3>渲染内容</h3>
 * <ul>
 *   <li>OBB 线框（青色）：用矩阵 translation + 局部偏移画有向包围盒，贴合每个肢体部位</li>
 *   <li>三轴指示（X红/Y绿/Z蓝）：用矩阵 rotation 画三轴，每轴 0.3m，便于看朝向</li>
 * </ul>
 *
 * <p>TODO: 后续接入装甲厚度可视化不同颜色
 * <p>当 {@link HitboxConfig#armor()} > 0 时，根据 armor 值渲染不同颜色：
 * <ul>
 *   <li>armor 0：青色（默认，无装甲）</li>
 *   <li>armor 1-2：黄色（轻装甲）</li>
 *   <li>armor 3-4：橙色（中装甲）</li>
 *   <li>armor 5+：红色（重装甲）</li>
 * </ul>
 */
public final class BoneDebugRenderer {

    /** 三轴指示长度 */
    private static final float AXIS_LEN = 0.3f;

    // 颜色（ARGB int）
    private static final int COLOR_AXIS_X = 0xFF0000; // 红
    private static final int COLOR_AXIS_Y = 0x00FF00; // 绿
    private static final int COLOR_AXIS_Z = 0x0080FF; // 蓝

    private BoneDebugRenderer() {}

    /**
     * 在 RenderLevelStageEvent 里调用
     * @param poseStack    当前 PoseStack（不会被使用，内部用 identity 避免双重 camera 偏移）
     * @param cameraPos    相机世界坐标
     * @param partialTick  插值 partialTick
     */
    public static void render(PoseStack poseStack, Vec3 cameraPos, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getCameraEntity() == null) return;

        ClientLevel level = mc.level;

        PoseStack identity = new PoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        for (net.minecraft.world.entity.Entity e : level.entitiesForRendering()) {
            if (!(e instanceof AnimatedMob mob)) continue;
            if (mob.distanceToSqr(mc.getCameraEntity()) > 64 * 64) continue;
            renderEntityBones(consumer, identity, mob, cameraPos, partialTick);
        }

        bufferSource.endBatch(RenderType.lines());
    }

    /**
     * 渲染单个实体所有骨骼的 pivot matrix
     */
    private static void renderEntityBones(VertexConsumer consumer, PoseStack poseStack,
                                          AnimatedMob entity, Vec3 camPos, float partialTick) {
        if (!(entity instanceof IEntityAnimatable<?>)) {
            LogManager.boneDebugWarn("Entity {} not IEntityAnimatable", entity.getId());
            return;
        }

        IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) entity;
        ModelInstance model = animatable.getModelController().getModel();
        if (model == null) return;

        ModelPose poseModel = model.getPose();
        if (poseModel == null) return;

        Map<String, BonePose> bonePoses = poseModel.getBonePoses();
        if (bonePoses.isEmpty()) return;

        // 从 geo.json 动态提取 hitbox 配置（通用化，不硬编码）
        Map<String, HitboxConfig> configs = HitboxResolver.resolveFromModel(model);

        for (Map.Entry<String, BonePose> entry : bonePoses.entrySet()) {
            String boneName = entry.getKey();
            BonePose bonePose = entry.getValue();
            HitboxConfig cfg = configs.get(boneName);
            if (cfg == null) continue;
            try {
                renderBonePivotMatrix(consumer, poseStack, boneName, bonePose, cfg, partialTick, camPos);
            } catch (Exception e) {
                LogManager.boneDebugWarn("Failed to render bone {}: {}", boneName, e.getMessage());
            }
        }
    }

    /**
     * 用 getWorldBonePivotMatrix 画 OBB + 三轴
     * OBB 中心 = pivot + R * localOffset（通过 transformPosition 一步到位）
     * OBB 尺寸 = halfExtents（非正方体，贴合每个肢体）
     */
    private static void renderBonePivotMatrix(VertexConsumer consumer, PoseStack poseStack,
                                             String boneName, BonePose bonePose,
                                             HitboxConfig cfg, float partialTick, Vec3 camPos) {
        Matrix4f worldMat = bonePose.getWorldBonePivotMatrix(partialTick);

        Vector3f pivot = new Vector3f();
        worldMat.getTranslation(pivot);

        Quaternionf rot = new Quaternionf();
        worldMat.getUnnormalizedRotation(rot);

        // OBB 中心 = pivot + R * localOffset
        Vector3f obbCenter = new Vector3f();
        worldMat.transformPosition(cfg.localOffset(), obbCenter);

        // TODO: 后续接入装甲厚度可视化不同颜色
        // 根据 cfg.armor() 选择颜色：0=青色, 1-2=黄色, 3-4=橙色, 5+=红色
        Color color = Color.CYAN;

        AABB localAABB = new AABB(
                -cfg.halfExtents().x(), -cfg.halfExtents().y(), -cfg.halfExtents().z(),
                cfg.halfExtents().x(),  cfg.halfExtents().y(),  cfg.halfExtents().z()
        );
        OBBRenderer.renderOBB(consumer, poseStack, localAABB, rot, color, 0.8f, obbCenter, camPos);

        // 三轴指示（从 pivot 出发，X红/Y绿/Z蓝）
        Vector3f xAxis = rot.transform(new Vector3f(AXIS_LEN, 0, 0)).add(pivot);
        Vector3f yAxis = rot.transform(new Vector3f(0, AXIS_LEN, 0)).add(pivot);
        Vector3f zAxis = rot.transform(new Vector3f(0, 0, AXIS_LEN)).add(pivot);
        OBBRenderer.renderLine(consumer, poseStack, pivot, xAxis, COLOR_AXIS_X, camPos);
        OBBRenderer.renderLine(consumer, poseStack, pivot, yAxis, COLOR_AXIS_Y, camPos);
        OBBRenderer.renderLine(consumer, poseStack, pivot, zAxis, COLOR_AXIS_Z, camPos);
    }
}
