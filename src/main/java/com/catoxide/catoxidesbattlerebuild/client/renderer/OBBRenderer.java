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
                                 AABB localAABB, Quaternionf rotation, Color color, float alpha) {
        renderOBB(poseStack, vertexConsumer, localAABB, rotation, color, alpha, new Vector3f(0, 0, 0));
    }

    public static void renderOBB(PoseStack poseStack, VertexConsumer vertexConsumer,
                                 AABB localAABB, Quaternionf rotation, Color color, float alpha, Vector3f pivot) {
        // 计算AABB的中心点（相对于枢轴点）
        Vec3 center = new Vec3(
                (localAABB.minX + localAABB.maxX) / 2 - pivot.x,
                (localAABB.minY + localAABB.maxY) / 2 - pivot.y,
                (localAABB.minZ + localAABB.maxZ) / 2 - pivot.z
        );

        // 计算半尺寸
        double halfSizeX = (localAABB.maxX - localAABB.minX) / 2;
        double halfSizeY = (localAABB.maxY - localAABB.minY) / 2;
        double halfSizeZ = (localAABB.maxZ - localAABB.minZ) / 2;

        // 定义立方体的8个顶点（相对于枢轴点的局部坐标）
        Vector3f[] localVertices = {
                new Vector3f((float)(-halfSizeX - center.x), (float)(-halfSizeY - center.y), (float)(-halfSizeZ - center.z)),
                new Vector3f((float)( halfSizeX - center.x), (float)(-halfSizeY - center.y), (float)(-halfSizeZ - center.z)),
                new Vector3f((float)( halfSizeX - center.x), (float)( halfSizeY - center.y), (float)(-halfSizeZ - center.z)),
                new Vector3f((float)(-halfSizeX - center.x), (float)( halfSizeY - center.y), (float)(-halfSizeZ - center.z)),
                new Vector3f((float)(-halfSizeX - center.x), (float)(-halfSizeY - center.y), (float)( halfSizeZ - center.z)),
                new Vector3f((float)( halfSizeX - center.x), (float)(-halfSizeY - center.y), (float)( halfSizeZ - center.z)),
                new Vector3f((float)( halfSizeX - center.x), (float)( halfSizeY - center.y), (float)( halfSizeZ - center.z)),
                new Vector3f((float)(-halfSizeX - center.x), (float)( halfSizeY - center.y), (float)( halfSizeZ - center.z))
        };

        // 应用旋转变换（绕枢轴点）
        Vector3f[] rotatedVertices = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            rotatedVertices[i] = rotation.transform(localVertices[i]);
            // 加回枢轴点偏移，得到最终世界位置
            rotatedVertices[i].add(pivot.x + (float)center.x, pivot.y + (float)center.y, pivot.z + (float)center.z);
        }

        // 定义立方体的12条边
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
            Vector3f start = rotatedVertices[edge[0]];
            Vector3f end = rotatedVertices[edge[1]];
            renderLine(poseStack, vertexConsumer, start, end, r, g, b, alpha);
        }
    }

    private static void renderLine(PoseStack poseStack, VertexConsumer vertexConsumer,
                                   Vector3f start, Vector3f end,
                                   float r, float g, float b, float alpha) {
        PoseStack.Pose pose = poseStack.last();

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