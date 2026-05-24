package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.*;

public class HitboxDebugRenderer {

    private static boolean debugEnabled = false;

    private static int renderedEntities = 0;
    private static int renderedBones = 0;
    private static int renderedCubes = 0;

    private HitboxDebugRenderer() {
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        LogManager.clientInfo("HitboxDebugRenderer", "Debug rendering {}", enabled ? "enabled" : "disabled");
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void renderDebugCubes(PoseStack poseStack, MultiBufferSource bufferSource) {
        if (!debugEnabled) {
            return;
        }

        LogManager.clientDebug("HitboxDebugRenderer", "Rendering debug cubes...");
        renderedEntities = 0;
        renderedBones = 0;
        renderedCubes = 0;

        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());

        renderDebugCube(poseStack, buffer);
        renderTiltedDebugCube(poseStack, buffer);

        LogManager.clientInfo("HitboxDebugRenderer", "Rendered: entities={}, bones={}, cubes={}",
                renderedEntities, renderedBones, renderedCubes);
    }

    public static void renderDebugCubes(PoseStack poseStack, VertexConsumer buffer) {
        if (!debugEnabled) {
            return;
        }

        LogManager.clientDebug("HitboxDebugRenderer", "Rendering debug cubes (VertexConsumer)...");
        renderedEntities = 0;
        renderedBones = 0;
        renderedCubes = 0;

        renderDebugCube(poseStack, buffer);
        renderTiltedDebugCube(poseStack, buffer);

        LogManager.clientInfo("HitboxDebugRenderer", "Rendered: entities={}, bones={}, cubes={}",
                renderedEntities, renderedBones, renderedCubes);
    }

    private static void renderDebugCube(PoseStack poseStack, VertexConsumer buffer) {
        LogManager.clientDebug("HitboxDebugRenderer", "Rendering debug cube at (0,0,0) with size 1m");

        Vector3f[] vertices = {
            new Vector3f(-0.5f, -0.5f, -0.5f),
            new Vector3f(0.5f, -0.5f, -0.5f),
            new Vector3f(0.5f, 0.5f, -0.5f),
            new Vector3f(-0.5f, 0.5f, -0.5f),
            new Vector3f(-0.5f, -0.5f, 0.5f),
            new Vector3f(0.5f, -0.5f, 0.5f),
            new Vector3f(0.5f, 0.5f, 0.5f),
            new Vector3f(-0.5f, 0.5f, 0.5f)
        };

        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        renderEdges(poseStack, buffer, vertices, edges, 1.0f, 0.0f, 0.0f, 1.0f);
        renderedCubes++;
        LogManager.clientDebug("HitboxDebugRenderer", "Debug cube rendered successfully");
    }

    private static void renderTiltedDebugCube(PoseStack poseStack, VertexConsumer buffer) {
        LogManager.clientDebug("HitboxDebugRenderer", "Rendering tilted debug cube at (2,0,0) with 45-degree Y rotation");

        Vector3f pivot = new Vector3f(2.0f, 0.0f, 0.0f);
        AABB localAABB = new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        Quaternionf rotation = new Quaternionf().rotateY((float) (Math.PI / 4));
        Color color = Color.GREEN;
        float alpha = 1.0f;

        OBBRenderer.renderOBB(buffer, poseStack, localAABB, rotation, color, alpha, pivot);
        renderedCubes++;

        LogManager.clientDebug("HitboxDebugRenderer", "Tilted debug cube rendered successfully");
    }

    private static void renderEdges(PoseStack poseStack, VertexConsumer buffer,
            Vector3f[] vertices, int[][] edges, float r, float g, float b, float alpha) {
        float normalX = 0.0f;
        float normalY = 1.0f;
        float normalZ = 0.0f;
        int color = (int)(alpha * 255) << 24 | (int)(r * 255) << 16 | (int)(g * 255) << 8 | (int)(b * 255);

        for (int[] edge : edges) {
            Vector3f v1 = vertices[edge[0]];
            Vector3f v2 = vertices[edge[1]];

            buffer.addVertex(poseStack.last().pose(), v1.x(), v1.y(), v1.z())
                  .setColor((int)(r * 255), (int)(g * 255), (int)(b * 255), (int)(alpha * 255))
                  .setNormal(normalX, normalY, normalZ);
            buffer.addVertex(poseStack.last().pose(), v2.x(), v2.y(), v2.z())
                  .setColor((int)(r * 255), (int)(g * 255), (int)(b * 255), (int)(alpha * 255))
                  .setNormal(normalX, normalY, normalZ);
        }
    }

    public static void renderAABB(PoseStack poseStack, VertexConsumer buffer,
            AABB aabb, float r, float g, float b, float alpha) {
        OBBRenderer.renderAABB(buffer, poseStack, aabb, r, g, b, alpha);
    }

    public static String getRenderStats() {
        String stats = String.format(
            "[HitboxDebugRenderer] Render Stats: entities=%d, bones=%d, cubes=%d, enabled=%s",
            renderedEntities, renderedBones, renderedCubes, debugEnabled
        );
        LogManager.clientInfo("HitboxDebugRenderer", stats);
        return stats;
    }

    public static void resetRenderStats() {
        renderedEntities = 0;
        renderedBones = 0;
        renderedCubes = 0;
        LogManager.clientDebug("HitboxDebugRenderer", "Render stats reset");
    }
}