// CustomHitboxRenderer.java - 修复版本
package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.ClientHitboxManager;
import com.catoxide.catoxidesbattlerebuild.mob.server.HitboxSyncPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.*;
import java.util.List;
import java.util.Set;

import static com.catoxide.catoxidesbattlerebuild.client.ClientHitboxManager.getAllParentIds;

@OnlyIn(Dist.CLIENT)
public class CustomHitboxRenderer {

    // 添加回渲染开关（但默认开启）
    public static boolean renderCustomHitboxes = true;

    // 添加回切换方法（为了兼容性）
    public static void toggleRendering() {
        renderCustomHitboxes = !renderCustomHitboxes;
        System.out.println("自定义碰撞箱渲染: " + (renderCustomHitboxes ? "开启" : "关闭"));
    }

    // 添加回设置方法（为了兼容性）
    public static void setRendering(boolean enabled) {
        renderCustomHitboxes = enabled;
        System.out.println("自定义碰撞箱渲染: " + (enabled ? "开启" : "关闭"));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 检查开关状态
        if (!renderCustomHitboxes || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();

        // 渲染所有碰撞箱
        renderAllHitboxData(poseStack, bufferSource);

        bufferSource.endBatch();
    }

    private static void renderAllHitboxData(PoseStack poseStack, MultiBufferSource bufferSource) {
        Set<Integer> parentIds = getAllParentIds();

        // 如果没有数据，渲染一个调试立方体
        if (parentIds.isEmpty()) {
            renderDebugFallback(poseStack, bufferSource);
            return;
        }

        System.out.println("渲染 " + parentIds.size() + " 个父实体的碰撞箱");

        // 从客户端管理器获取所有碰撞箱数据并渲染
        for (Integer parentId : parentIds) {
            List<HitboxSyncPacket.HitboxData> hitboxDataList = ClientHitboxManager.getHitboxDataForParent(parentId);
            if (hitboxDataList != null && !hitboxDataList.isEmpty()) {
                System.out.println("父实体 " + parentId + " 有 " + hitboxDataList.size() + " 个碰撞箱");

                for (HitboxSyncPacket.HitboxData data : hitboxDataList) {
                    renderHitboxData(data, poseStack, bufferSource);
                }
            } else {
                System.out.println("父实体 " + parentId + " 没有碰撞箱数据");
            }
        }
    }

    private static void renderHitboxData(HitboxSyncPacket.HitboxData hitboxData, PoseStack poseStack, MultiBufferSource bufferSource) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.cameraEntity == null) return;

            Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();

            // 创建世界坐标的AABB
            AABB worldAABB = new AABB(
                    hitboxData.minX, hitboxData.minY, hitboxData.minZ,
                    hitboxData.maxX, hitboxData.maxY, hitboxData.maxZ
            );

            // 转换为相机相对坐标
            AABB relativeAABB = new AABB(
                    worldAABB.minX - cameraPos.x,
                    worldAABB.minY - cameraPos.y,
                    worldAABB.minZ - cameraPos.z,
                    worldAABB.maxX - cameraPos.x,
                    worldAABB.maxY - cameraPos.y,
                    worldAABB.maxZ - cameraPos.z
            );

            VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.lines());
            Color color = getColorForPart(hitboxData.partName);

            poseStack.pushPose();

            // 计算枢轴点（使用骨骼位置）
            Vector3f pivot = new Vector3f(
                    (float)(hitboxData.position.x - cameraPos.x),
                    (float)(hitboxData.position.y - cameraPos.y),
                    (float)(hitboxData.position.z - cameraPos.z)
            );

            // 使用修复的OBB渲染器
            OBBRenderer.renderOBB(poseStack, vertexConsumer, relativeAABB,
                    hitboxData.rotation != null ? hitboxData.rotation : new Quaternionf(),
                    color, 1.0f, pivot);

            poseStack.popPose();

        } catch (Exception e) {
            System.err.println("渲染OBB时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 调试回退渲染 - 当没有数据时显示
    private static void renderDebugFallback(PoseStack poseStack, MultiBufferSource bufferSource) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 在玩家位置渲染一个测试立方体
        AABB testAABB = new AABB(
                mc.player.getX() - 0.5, mc.player.getY(), mc.player.getZ() - 0.5,
                mc.player.getX() + 0.5, mc.player.getY() + 1, mc.player.getZ() + 0.5
        );

        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, vertexConsumer, testAABB, 1.0f, 0.0f, 0.0f, 1.0f);

        // 每5秒输出一次调试信息
        if (mc.level.getGameTime() % 100 == 0) {
            System.out.println("没有碰撞箱数据，渲染了调试立方体");
        }
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