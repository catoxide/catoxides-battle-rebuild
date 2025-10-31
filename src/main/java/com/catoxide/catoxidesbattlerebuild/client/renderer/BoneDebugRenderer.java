// BoneDebugRenderer.java
package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.ClientBoneDebugManager;
import com.catoxide.catoxidesbattlerebuild.mob.server.BoneDebugPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.*;
import java.util.List;

public class BoneDebugRenderer {

    public static boolean renderBoneDebug = true;

    public static void renderAllBones(PoseStack poseStack, MultiBufferSource bufferSource) {
        if (!renderBoneDebug) return;

        for (Integer parentId : ClientBoneDebugManager.getAllParentIds()) {
            List<BoneDebugPacket.BoneData> boneDataList = ClientBoneDebugManager.getBoneDataForParent(parentId);
            if (boneDataList != null) {
                for (BoneDebugPacket.BoneData boneData : boneDataList) {
                    renderBone(boneData, poseStack, bufferSource);
                }
            }
        }
    }

    private static void renderBone(BoneDebugPacket.BoneData boneData, PoseStack poseStack, MultiBufferSource bufferSource) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.cameraEntity == null) return;

        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();

        // 转换为相机相对坐标
        Vec3 relativePos = boneData.position.subtract(cameraPos);
        Vec3 relativeParentPos = boneData.parentPosition.subtract(cameraPos);

        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();

        // 渲染骨骼位置点
        renderBonePoint(poseStack, vertexConsumer, relativePos, Color.CYAN);

        // 渲染骨骼连线（到父骨骼）
        if (!boneData.position.equals(boneData.parentPosition)) {
            renderBoneLine(poseStack, vertexConsumer, relativeParentPos, relativePos, Color.WHITE);
        }

        // 渲染骨骼坐标系
        renderBoneAxes(poseStack, vertexConsumer, relativePos, boneData.rotation);

        poseStack.popPose();
    }

    private static void renderBonePoint(PoseStack poseStack, VertexConsumer vertexConsumer, Vec3 position, Color color) {
        // 渲染一个小立方体表示骨骼位置
        float size = 0.05f;
        AABB pointAABB = new AABB(
                position.x - size, position.y - size, position.z - size,
                position.x + size, position.y + size, position.z + size
        );

        LevelRenderer.renderLineBox(poseStack, vertexConsumer, pointAABB,
                color.getRed() / 255.0f,
                color.getGreen() / 255.0f,
                color.getBlue() / 255.0f,
                1.0f);
    }

    private static void renderBoneLine(PoseStack poseStack, VertexConsumer vertexConsumer,
                                       Vec3 start, Vec3 end, Color color) {
        float r = color.getRed() / 255.0f;
        float g = color.getGreen() / 255.0f;
        float b = color.getBlue() / 255.0f;

        PoseStack.Pose pose = poseStack.last();

        vertexConsumer.vertex(pose.pose(), (float)start.x, (float)start.y, (float)start.z)
                .color(r, g, b, 1.0f)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();

        vertexConsumer.vertex(pose.pose(), (float)end.x, (float)end.y, (float)end.z)
                .color(r, g, b, 1.0f)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
    }

    private static void renderBoneAxes(PoseStack poseStack, VertexConsumer vertexConsumer,
                                       Vec3 position, Quaternionf rotation) {
        float axisLength = 0.2f;

        // 定义局部坐标系的三个轴
        Vector3f[] localAxes = {
                new Vector3f(axisLength, 0, 0), // X轴 (红色)
                new Vector3f(0, axisLength, 0), // Y轴 (绿色)
                new Vector3f(0, 0, axisLength)  // Z轴 (蓝色)
        };

        Color[] axisColors = {Color.RED, Color.GREEN, Color.BLUE};

        // 应用骨骼旋转
        Vector3f[] worldAxes = new Vector3f[3];
        for (int i = 0; i < 3; i++) {
            worldAxes[i] = rotateVector(localAxes[i], rotation);
        }

        // 渲染三个轴
        for (int i = 0; i < 3; i++) {
            Vector3f axisEnd = new Vector3f(worldAxes[i]);
            axisEnd.add((float)position.x, (float)position.y, (float)position.z);

            renderBoneLine(poseStack, vertexConsumer,
                    position,
                    new Vec3(axisEnd.x, axisEnd.y, axisEnd.z),
                    axisColors[i]);
        }
    }

    private static Vector3f rotateVector(Vector3f vector, Quaternionf rotation) {
        // 如果旋转是单位四元数，直接返回原向量
        if (rotation.x == 0 && rotation.y == 0 && rotation.z == 0 && rotation.w == 1) {
            return vector;
        }

        // 使用四元数旋转向量
        return rotation.transform(vector);
    }
}