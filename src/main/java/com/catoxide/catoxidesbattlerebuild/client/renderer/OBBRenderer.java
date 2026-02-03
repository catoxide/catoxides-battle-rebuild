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
        // 计算半尺寸
        double halfSizeX = (localAABB.maxX - localAABB.minX) / 2;
        double halfSizeY = (localAABB.maxY - localAABB.minY) / 2;
        double halfSizeZ = (localAABB.maxZ - localAABB.minZ) / 2;

        // 定义立方体的8个顶点（相对于原点的局部坐标）
        Vector3f[] localVertices = {
                new Vector3f((float)(-halfSizeX), (float)(-halfSizeY), (float)(-halfSizeZ)),
                new Vector3f((float)( halfSizeX), (float)(-halfSizeY), (float)(-halfSizeZ)),
                new Vector3f((float)( halfSizeX), (float)( halfSizeY), (float)(-halfSizeZ)),
                new Vector3f((float)(-halfSizeX), (float)( halfSizeY), (float)(-halfSizeZ)),
                new Vector3f((float)(-halfSizeX), (float)(-halfSizeY), (float)( halfSizeZ)),
                new Vector3f((float)( halfSizeX), (float)(-halfSizeY), (float)( halfSizeZ)),
                new Vector3f((float)( halfSizeX), (float)( halfSizeY), (float)( halfSizeZ)),
                new Vector3f((float)(-halfSizeX), (float)( halfSizeY), (float)( halfSizeZ))
        };

        // 应用旋转变换并加枢轴点偏移
        Vector3f[] rotatedVertices = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            // 先旋转
            rotatedVertices[i] = rotation.transform(localVertices[i]);
            // 再加枢轴点偏移，得到最终世界位置
            rotatedVertices[i].add(pivot);
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

        // 获取相机位置
        net.minecraft.world.phys.Vec3 camPos = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

        // 将世界坐标转换为相对于相机的坐标
        float startX = start.x() - (float)camPos.x;
        float startY = start.y() - (float)camPos.y;
        float startZ = start.z() - (float)camPos.z;
        
        float endX = end.x() - (float)camPos.x;
        float endY = end.y() - (float)camPos.y;
        float endZ = end.z() - (float)camPos.z;

        vertexConsumer.vertex(pose.pose(), startX, startY, startZ)
                .color(r, g, b, alpha)
                .normal(pose.normal(), 0, 1, 0) // 对于线条，法线影响不大
                .endVertex();

        vertexConsumer.vertex(pose.pose(), endX, endY, endZ)
                .color(r, g, b, alpha)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
    }
}









