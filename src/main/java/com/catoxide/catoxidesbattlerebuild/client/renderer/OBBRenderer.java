package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.awt.*;

public class OBBRenderer {

    public static void renderOBB(VertexConsumer buffer, PoseStack poseStack,
                                 AABB localAABB, Quaternionf rotation, Color color, float alpha,
                                 Vector3f pivot) {
        double halfSizeX = (localAABB.maxX - localAABB.minX) / 2;
        double halfSizeY = (localAABB.maxY - localAABB.minY) / 2;
        double halfSizeZ = (localAABB.maxZ - localAABB.minZ) / 2;

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

        Vector3f[] rotatedVertices = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            rotatedVertices[i] = rotation.transform(localVertices[i]);
            rotatedVertices[i].add(pivot);
        }

        int[][] edges = {
                {0,1}, {1,2}, {2,3}, {3,0},
                {4,5}, {5,6}, {6,7}, {7,4},
                {0,4}, {1,5}, {2,6}, {3,7}
        };

        float r = color.getRed() / 255.0f;
        float g = color.getGreen() / 255.0f;
        float b = color.getBlue() / 255.0f;

        Matrix4f poseMatrix = poseStack.last().pose();
        Vector3f normal = new Vector3f(0, 1, 0);

        for (int[] edge : edges) {
            Vector3f start = rotatedVertices[edge[0]];
            Vector3f end = rotatedVertices[edge[1]];
            renderLine(buffer, poseMatrix, start, end, r, g, b, alpha, normal);
        }
    }

    private static void renderLine(VertexConsumer buffer, Matrix4f poseMatrix,
                                   Vector3f start, Vector3f end,
                                   float r, float g, float b, float alpha, Vector3f normal) {
        Vector4f startVec = poseMatrix.transform(new Vector4f(start.x(), start.y(), start.z(), 1.0f));
        Vector4f endVec = poseMatrix.transform(new Vector4f(end.x(), end.y(), end.z(), 1.0f));

        buffer.addVertex(startVec.x(), startVec.y(), startVec.z())
              .setColor((int)(r * 255), (int)(g * 255), (int)(b * 255), (int)(alpha * 255))
              .setNormal(normal.x(), normal.y(), normal.z());
        buffer.addVertex(endVec.x(), endVec.y(), endVec.z())
              .setColor((int)(r * 255), (int)(g * 255), (int)(b * 255), (int)(alpha * 255))
              .setNormal(normal.x(), normal.y(), normal.z());
    }

    public static void renderAABB(VertexConsumer buffer, PoseStack poseStack, AABB aabb,
                                  float r, float g, float b, float alpha) {
        Vector3f[] vertices = {
            new Vector3f((float)aabb.minX, (float)aabb.minY, (float)aabb.minZ),
            new Vector3f((float)aabb.maxX, (float)aabb.minY, (float)aabb.minZ),
            new Vector3f((float)aabb.maxX, (float)aabb.maxY, (float)aabb.minZ),
            new Vector3f((float)aabb.minX, (float)aabb.maxY, (float)aabb.minZ),
            new Vector3f((float)aabb.minX, (float)aabb.minY, (float)aabb.maxZ),
            new Vector3f((float)aabb.maxX, (float)aabb.minY, (float)aabb.maxZ),
            new Vector3f((float)aabb.maxX, (float)aabb.maxY, (float)aabb.maxZ),
            new Vector3f((float)aabb.minX, (float)aabb.maxY, (float)aabb.maxZ)
        };

        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        Matrix4f poseMatrix = poseStack.last().pose();
        Vector3f normal = new Vector3f(0, 1, 0);
        int color = (int)(alpha * 255) << 24 | (int)(r * 255) << 16 | (int)(g * 255) << 8 | (int)(b * 255);

        for (int[] edge : edges) {
            Vector3f v1 = vertices[edge[0]];
            Vector3f v2 = vertices[edge[1]];

            Vector4f v1Transformed = poseMatrix.transform(new Vector4f(v1.x(), v1.y(), v1.z(), 1.0f));
            Vector4f v2Transformed = poseMatrix.transform(new Vector4f(v2.x(), v2.y(), v2.z(), 1.0f));

            buffer.addVertex(v1Transformed.x(), v1Transformed.y(), v1Transformed.z())
                  .setColor((int)(r * 255), (int)(g * 255), (int)(b * 255), (int)(alpha * 255))
                  .setNormal(normal.x(), normal.y(), normal.z());
            buffer.addVertex(v2Transformed.x(), v2Transformed.y(), v2Transformed.z())
                  .setColor((int)(r * 255), (int)(g * 255), (int)(b * 255), (int)(alpha * 255))
                  .setNormal(normal.x(), normal.y(), normal.z());
        }
    }
}