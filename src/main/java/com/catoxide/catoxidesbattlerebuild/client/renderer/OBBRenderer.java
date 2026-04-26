package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.*;

public class OBBRenderer {

    /**
     * 使用BufferBuilder渲染OBB（带枢轴点）
     * @param bufferBuilder BufferBuilder实例
     * @param poseStack 姿态堆栈
     * @param localAABB 局部AABB
     * @param rotation 旋转四元数
     * @param color 颜色
     * @param alpha 透明度
     * @param pivot 枢轴点（世界坐标）
     * @param camPos 相机位置
     */
    public static void renderOBB(BufferBuilder bufferBuilder, PoseStack poseStack,
                                 AABB localAABB, Quaternionf rotation, Color color, float alpha, 
                                 Vector3f pivot, Vec3 camPos) {
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

        PoseStack.Pose pose = poseStack.last();

        for (int[] edge : edges) {
            Vector3f start = rotatedVertices[edge[0]];
            Vector3f end = rotatedVertices[edge[1]];
            renderLine(bufferBuilder, pose, start, end, r, g, b, alpha, camPos);
        }
    }

    /**
     * 使用BufferBuilder渲染线条
     */
    private static void renderLine(BufferBuilder bufferBuilder, PoseStack.Pose pose,
                                   Vector3f start, Vector3f end,
                                   float r, float g, float b, float alpha, Vec3 camPos) {
        // 将世界坐标转换为相对于相机的坐标
        float startX = start.x() - (float)camPos.x;
        float startY = start.y() - (float)camPos.y;
        float startZ = start.z() - (float)camPos.z;
        
        float endX = end.x() - (float)camPos.x;
        float endY = end.y() - (float)camPos.y;
        float endZ = end.z() - (float)camPos.z;

        // 使用BufferBuilder的vertex方法添加顶点
        bufferBuilder.vertex(pose.pose(), startX, startY, startZ)
                .color(r, g, b, alpha)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();

        bufferBuilder.vertex(pose.pose(), endX, endY, endZ)
                .color(r, g, b, alpha)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
    }
}