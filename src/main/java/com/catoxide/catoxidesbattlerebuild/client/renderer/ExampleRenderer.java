package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.Color;

public class ExampleRenderer {
    
    private static boolean renderEnabled = true;
    
    private ExampleRenderer() {
    }
    
    public static void setRenderEnabled(boolean enabled) {
        renderEnabled = enabled;
        LogManager.clientInfo("ExampleRenderer", "Example rendering {}", enabled ? "enabled" : "disabled");
    }
    
    public static boolean isRenderEnabled() {
        return renderEnabled;
    }
    
    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        if (!renderEnabled) {
            return;
        }
        
        LogManager.clientDebug("ExampleRenderer", "Rendering examples with partialTick={}", partialTick);
        
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.LINES);
        
        renderFirstExample(poseStack, buffer);
        renderSecondExample(poseStack, buffer, partialTick);
        
        LogManager.clientDebug("ExampleRenderer", "Examples rendered successfully");
    }
    
    private static void renderFirstExample(PoseStack poseStack, VertexConsumer buffer) {
        LogManager.clientDebug("ExampleRenderer", "Rendering first example: Basic AABB cube");
        
        poseStack.pushPose();
        
        poseStack.translate(0, 1, 0);
        
        AABB aabb = new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        
        OBBRenderer.renderAABB(buffer, poseStack, aabb, 1.0f, 0.0f, 0.0f, 1.0f);
        
        poseStack.popPose();
        
        LogManager.clientDebug("ExampleRenderer", "First example rendered at position (0, 1, 0)");
    }
    
    private static void renderSecondExample(PoseStack poseStack, VertexConsumer buffer, float partialTick) {
        LogManager.clientDebug("ExampleRenderer", "Rendering second example: Rotating OBB cube");
        
        poseStack.pushPose();
        
        poseStack.translate(3, 1, 0);
        
        AABB localAABB = new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        
        float rotationAngle = partialTick * 0.5f;
        Quaternionf rotation = new Quaternionf()
                .rotateY(rotationAngle)
                .rotateX(rotationAngle * 0.7f);
        
        Vector3f pivot = new Vector3f(0, 0, 0);
        
        Color color = new Color(0, 255, 0);
        float alpha = 1.0f;
        
        OBBRenderer.renderOBB(buffer, poseStack, localAABB, rotation, color, alpha, pivot);
        
        poseStack.popPose();
        
        LogManager.clientDebug("ExampleRenderer", "Second example rendered at position (3, 1, 0) with rotation={}", rotationAngle);
    }
    
    public static void renderCustomExamples(PoseStack poseStack, VertexConsumer buffer) {
        LogManager.clientDebug("ExampleRenderer", "Rendering custom examples");
        
        renderMultiColoredCubes(poseStack, buffer);
        renderScaledCubes(poseStack, buffer);
        renderOffsetCubes(poseStack, buffer);
        
        LogManager.clientDebug("ExampleRenderer", "Custom examples rendered successfully");
    }
    
    private static void renderMultiColoredCubes(PoseStack poseStack, VertexConsumer buffer) {
        float[] positions = {-2, -1, 0, 1, 2};
        Color[] colors = {
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW,
            Color.MAGENTA
        };
        
        for (int i = 0; i < positions.length; i++) {
            poseStack.pushPose();
            poseStack.translate(positions[i], 3, 0);
            
            AABB aabb = new AABB(-0.3, -0.3, -0.3, 0.3, 0.3, 0.3);
            OBBRenderer.renderAABB(buffer, poseStack, aabb,
                    colors[i].getRed() / 255.0f,
                    colors[i].getGreen() / 255.0f,
                    colors[i].getBlue() / 255.0f,
                    0.8f);
            
            poseStack.popPose();
        }
    }
    
    private static void renderScaledCubes(PoseStack poseStack, VertexConsumer buffer) {
        float[] scales = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f};
        
        for (int i = 0; i < scales.length; i++) {
            poseStack.pushPose();
            poseStack.translate(-3 + i, 4.5f, 0);
            poseStack.scale(scales[i], scales[i], scales[i]);
            
            AABB aabb = new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
            OBBRenderer.renderAABB(buffer, poseStack, aabb, 0.5f, 0.5f, 1.0f, 0.7f);
            
            poseStack.popPose();
        }
    }
    
    private static void renderOffsetCubes(PoseStack poseStack, VertexConsumer buffer) {
        poseStack.pushPose();
        
        poseStack.translate(0, 6, 0);
        
        Vector3f pivot = new Vector3f(1, 0, 0);
        AABB localAABB = new AABB(-0.25, -0.25, -0.25, 0.25, 0.25, 0.25);
        
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.PI / 6);
        
        OBBRenderer.renderOBB(buffer, poseStack, localAABB, rotation, Color.ORANGE, 1.0f, pivot);
        
        poseStack.popPose();
    }
    
    public static void enableHitboxDebug() {
        HitboxDebugRenderer.setDebugEnabled(true);
        LogManager.clientInfo("ExampleRenderer", "Hitbox debug rendering enabled");
    }
    
    public static void disableHitboxDebug() {
        HitboxDebugRenderer.setDebugEnabled(false);
        LogManager.clientInfo("ExampleRenderer", "Hitbox debug rendering disabled");
    }
    
    public static void renderHitboxDebug(PoseStack poseStack, VertexConsumer buffer) {
        HitboxDebugRenderer.renderDebugCubes(poseStack, buffer);
    }
}