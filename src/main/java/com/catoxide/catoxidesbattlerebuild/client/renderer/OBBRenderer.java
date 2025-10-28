package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.*;

public class OBBRenderer {

    public static void renderOBB(PoseStack poseStack, VertexConsumer vertexConsumer,
                                 AABB aabb, Quaternionf rotation, Color color) {
        renderOBB(poseStack, vertexConsumer, aabb, rotation, color, 1.0f);
    }

    public static void renderOBB(PoseStack poseStack, VertexConsumer vertexConsumer,
                                 AABB aabb, Quaternionf rotation, Color color, float alpha) {
        // 计算AABB的中心点
        Vec3 center = new Vec3(
                (aabb.minX + aabb.maxX) / 2,
                (aabb.minY + aabb.maxY) / 2,
                (aabb.minZ + aabb.maxZ) / 2
        );

        // 计算半尺寸
        double halfSizeX = (aabb.maxX - aabb.minX) / 2;
        double halfSizeY = (aabb.maxY - aabb.minY) / 2;
        double halfSizeZ = (aabb.maxZ - aabb.minZ) / 2;

        // 定义立方体的8个顶点（局部坐标）
        Vector3f[] localVertices = {
                new Vector3f((float)-halfSizeX, (float)-halfSizeY, (float)-halfSizeZ),
                new Vector3f((float) halfSizeX, (float)-halfSizeY, (float)-halfSizeZ),
                new Vector3f((float) halfSizeX, (float) halfSizeY, (float)-halfSizeZ),
                new Vector3f((float)-halfSizeX, (float) halfSizeY, (float)-halfSizeZ),
                new Vector3f((float)-halfSizeX, (float)-halfSizeY, (float) halfSizeZ),
                new Vector3f((float) halfSizeX, (float)-halfSizeY, (float) halfSizeZ),
                new Vector3f((float) halfSizeX, (float) halfSizeY, (float) halfSizeZ),
                new Vector3f((float)-halfSizeX, (float) halfSizeY, (float) halfSizeZ)
        };

        // 应用旋转变换
        Vector3f[] worldVertices = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            worldVertices[i] = rotateVector(localVertices[i], rotation);
            // 加上中心点偏移
            worldVertices[i].add((float)center.x, (float)center.y, (float)center.z);
        }

        // 定义立方体的12条边（顶点索引对）
        int[][] edges = {
                {0,1}, {1,2}, {2,3}, {3,0}, // 底面
                {4,5}, {5,6}, {6,7}, {7,4}, // 顶面
                {0,4}, {1,5}, {2,6}, {3,7}  // 侧面连接
        };

        // 渲染所有边
        float r = color.getRed() / 255.0f;
        float g = color.getGreen() / 255.0f;
        float b = color.getBlue() / 255.0f;

        for (int[] edge : edges) {
            Vector3f start = worldVertices[edge[0]];
            Vector3f end = worldVertices[edge[1]];

            renderLine(poseStack, vertexConsumer, start, end, r, g, b, alpha);
        }
    }

    private static Vector3f rotateVector(Vector3f vector, Quaternionf rotation) {
        // 创建旋转矩阵
        Matrix4f matrix = new Matrix4f().rotation(rotation);
        // 变换向量
        return matrix.transformPosition(vector);
    }

    private static void renderLine(PoseStack poseStack, VertexConsumer vertexConsumer,
                                   Vector3f start, Vector3f end,
                                   float r, float g, float b, float alpha) {
        PoseStack.Pose pose = poseStack.last();

        // 将世界坐标转换为渲染坐标（减去相机位置已经在外部处理）
        vertexConsumer.vertex(pose.pose(), start.x(), start.y(), start.z())
                .color(r, g, b, alpha)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();

        vertexConsumer.vertex(pose.pose(), end.x(), end.y(), end.z())
                .color(r, g, b, alpha)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
    }
}