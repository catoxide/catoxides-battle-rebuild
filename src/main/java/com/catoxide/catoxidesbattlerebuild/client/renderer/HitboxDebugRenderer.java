package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

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
    
    public static void renderDebugCubes(PoseStack poseStack) {
        if (!debugEnabled) {
            return;
        }
        
        LogManager.clientDebug("HitboxDebugRenderer", "Rendering debug cubes...");
        renderedEntities = 0;
        renderedBones = 0;
        renderedCubes = 0;
        
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        
        renderDebugCube(poseStack, bufferBuilder);
        renderTiltedDebugCube(poseStack, bufferBuilder);
        
        RenderSystem.enableDepthTest();
        
        LogManager.clientInfo("HitboxDebugRenderer", "Rendered: entities={}, bones={}, cubes={}",
                renderedEntities, renderedBones, renderedCubes);
    }
    
    public static void renderDebugCubes(PoseStack poseStack, VertexConsumer buffer) {
        if (!debugEnabled) {
            return;
        }
        
        LogManager.clientDebug("HitboxDebugRenderer", "Rendering debug cubes...");
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
        Matrix4f poseMatrix = poseStack.last().pose();
        Vector3f normal = new Vector3f(0, 1, 0);

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