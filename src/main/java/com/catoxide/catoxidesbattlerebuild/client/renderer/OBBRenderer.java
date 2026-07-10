package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.Color;

/**
 * OBB（有向包围盒）线框渲染器
 * <p>用法：传入 {@link RenderType#lines()} 的 VertexConsumer，
 * 在 PoseStack（identity）+ 世界坐标 - camPos 体系下渲染。
 */
public final class OBBRenderer {

    /** 立方体 12 条边的顶点索引 */
    private static final int[][] EDGES = {
            {0,1}, {1,2}, {2,3}, {3,0},
            {4,5}, {5,6}, {6,7}, {7,4},
            {0,4}, {1,5}, {2,6}, {3,7}
    };

    private OBBRenderer() {}

    /**
     * 渲染 OBB 线框
     * @param consumer   来自 RenderType.lines() 的 VertexConsumer
     * @param poseStack  identity PoseStack
     * @param localAABB   局部 AABB（以原点为中心）
     * @param rotation   旋转四元数
     * @param color      颜色
     * @param alpha       透明度
     * @param pivot       枢轴点世界坐标
     * @param camPos     相机位置
     */
    public static void renderOBB(VertexConsumer consumer, PoseStack poseStack,
                                 AABB localAABB, Quaternionf rotation, Color color, float alpha,
                                 Vector3f pivot, Vec3 camPos) {
        float halfX = (float) ((localAABB.maxX - localAABB.minX) / 2);
        float halfY = (float) ((localAABB.maxY - localAABB.minY) / 2);
        float halfZ = (float) ((localAABB.maxZ - localAABB.minZ) / 2);

        Vector3f[] localVertices = {
                new Vector3f(-halfX, -halfY, -halfZ),
                new Vector3f( halfX, -halfY, -halfZ),
                new Vector3f( halfX,  halfY, -halfZ),
                new Vector3f(-halfX,  halfY, -halfZ),
                new Vector3f(-halfX, -halfY,  halfZ),
                new Vector3f( halfX, -halfY,  halfZ),
                new Vector3f( halfX,  halfY,  halfZ),
                new Vector3f(-halfX,  halfY,  halfZ)
        };

        // 旋转 + 平移到 pivot
        Vector3f[] worldVerts = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            worldVerts[i] = rotation.transform(localVertices[i]).add(pivot);
        }

        int rgb = (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        int rgba = (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue() | ((int)(alpha * 255) << 24);

        PoseStack.Pose pose = poseStack.last();
        for (int[] edge : EDGES) {
            Vector3f s = worldVerts[edge[0]];
            Vector3f e = worldVerts[edge[1]];
            // 法线用边的方向（LINES 模式需要）
            Vector3f n = new Vector3f(e).sub(s);
            if (n.lengthSquared() > 1e-8f) n.normalize();
            else n.set(0, 1, 0);

            consumer.addVertex(pose.pose(),
                            s.x() - (float) camPos.x,
                            s.y() - (float) camPos.y,
                            s.z() - (float) camPos.z)
                    .setColor(rgba)
                    .setNormal(pose, n.x, n.y, n.z);

            consumer.addVertex(pose.pose(),
                            e.x() - (float) camPos.x,
                            e.y() - (float) camPos.y,
                            e.z() - (float) camPos.z)
                    .setColor(rgba)
                    .setNormal(pose, n.x, n.y, n.z);
        }
    }

    /**
     * 画一条从 start 到 end 的线
     */
    public static void renderLine(VertexConsumer consumer, PoseStack poseStack,
                                  Vector3f start, Vector3f end,
                                  int rgba, Vec3 camPos) {
        PoseStack.Pose pose = poseStack.last();
        Vector3f n = new Vector3f(end).sub(start);
        if (n.lengthSquared() > 1e-8f) n.normalize();
        else n.set(0, 1, 0);

        consumer.addVertex(pose.pose(),
                        start.x() - (float) camPos.x,
                        start.y() - (float) camPos.y,
                        start.z() - (float) camPos.z)
                .setColor(rgba)
                .setNormal(pose, n.x, n.y, n.z);

        consumer.addVertex(pose.pose(),
                        end.x() - (float) camPos.x,
                        end.y() - (float) camPos.y,
                        end.z() - (float) camPos.z)
                .setColor(rgba)
                .setNormal(pose, n.x, n.y, n.z);
    }
}
