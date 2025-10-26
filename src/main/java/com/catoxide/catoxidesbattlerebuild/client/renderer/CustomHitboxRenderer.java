package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.mob.HitboxPart;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.awt.*;

@OnlyIn(Dist.CLIENT)
public class CustomHitboxRenderer {

    // 控制是否渲染自定义碰撞箱
    public static boolean renderCustomHitboxes = false;

    // 切换渲染状态的方法
    public static void toggleRendering() {
        renderCustomHitboxes = !renderCustomHitboxes;
        System.out.println("自定义碰撞箱渲染: " + (renderCustomHitboxes ? "开启" : "关闭"));
    }

    // 设置渲染状态的方法
    public static void setRendering(boolean enabled) {
        renderCustomHitboxes = enabled;
        System.out.println("自定义碰撞箱渲染: " + (enabled ? "开启" : "关闭"));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!renderCustomHitboxes || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();

        // 渲染所有碰撞箱
        renderAllHitboxParts(mc, poseStack, bufferSource);

        bufferSource.endBatch();
    }

    private static void renderAllHitboxParts(Minecraft mc, PoseStack poseStack, MultiBufferSource bufferSource) {
        // 遍历所有实体，找到HitboxPart
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof HitboxPart) {
                renderHitboxPart((HitboxPart) entity, poseStack, bufferSource);
            }
        }
    }

    private static void renderHitboxPart(HitboxPart hitboxPart, PoseStack poseStack, MultiBufferSource bufferSource) {
        try {
            AABB aabb = hitboxPart.getBoundingBox();
            VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.lines());

            // 获取部位名称
            String partName = getPartName(hitboxPart);

            // 选择颜色
            Color color = getColorForPart(partName);

            // 渲染碰撞箱
            LevelRenderer.renderLineBox(poseStack, vertexConsumer, aabb,
                    color.getRed() / 255.0f,
                    color.getGreen() / 255.0f,
                    color.getBlue() / 255.0f,
                    1.0f);

        } catch (Exception e) {
            System.err.println("渲染碰撞箱时出错: " + e.getMessage());
        }
    }

    private static String getPartName(HitboxPart hitboxPart) {
        // 尝试从实体名称推断部位
        try {
            String name = hitboxPart.getName().getString().toLowerCase();
            if (name.contains("head")) return "head";
            if (name.contains("torso") || name.contains("body")) return "torso";
            if (name.contains("arm_left")) return "arm_left";
            if (name.contains("arm_right")) return "arm_right";
            if (name.contains("leg_left")) return "leg_left";
            if (name.contains("leg_right")) return "leg_right";
        } catch (Exception e) {
            System.err.println("获取部位名称失败: " + e.getMessage());
        }

        return "unknown";
    }

    private static Color getColorForPart(String partName) {
        switch (partName) {
            case "head": return Color.RED;
            case "torso": return Color.BLUE;
            case "arm_left":
            case "arm_right": return Color.GREEN;
            case "leg_left":
            case "leg_right": return Color.YELLOW;
            default: return Color.WHITE;
        }
    }
}