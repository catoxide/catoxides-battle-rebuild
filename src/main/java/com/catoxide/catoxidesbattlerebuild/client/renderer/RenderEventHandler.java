package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.client.HitboxSystemClient;
import com.catoxide.catoxidesbattlerebuild.server.hitboxsystem.BoneHitboxComponent;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.*;
import java.util.Collection;

@Mod.EventBusSubscriber(modid = CatoxidesBattleRebuild.MODID, value = Dist.CLIENT)
public class RenderEventHandler {
    private static boolean isRendering = false;
    private static long lastRenderTime = 0;
    private static final long MIN_RENDER_INTERVAL_MS = 10; // 最小渲染间隔10毫秒

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 在渲染完透明块之后渲染，这样可以确保我们的渲染在大多数内容之后，避免被遮挡
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }
            
            long currentTime = System.currentTimeMillis();
            
            // 检查是否在短时间内已经渲染过（防止重复调用）
            if (currentTime - lastRenderTime < MIN_RENDER_INTERVAL_MS) {
                System.out.println("[RenderEventHandler] Warning: Rendered too recently, skipping duplicate call");
                return;
            }
            
            // 检查是否正在渲染（防止并发问题）
            if (isRendering) {
                System.out.println("[RenderEventHandler] Warning: Already rendering, skipping concurrent call");
                return;
            }
            
            lastRenderTime = currentTime;
            isRendering = true;
            
            // Debug日志：渲染事件被调用
            System.out.println("[RenderEventHandler] RenderLevelStageEvent triggered at stage AFTER_TRANSLUCENT_BLOCKS");
            
            try {
                renderAllHitboxes(event.getPoseStack(), event.getPartialTick());
            } catch (Exception e) {
                System.err.println("[RenderEventHandler] Error rendering hitboxes: " + e.getMessage());
                e.printStackTrace();
            } finally {
                isRendering = false;
            }
        }
    }

    private static void renderAllHitboxes(PoseStack poseStack, float partialTick) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        // Debug日志：开始渲染
        System.out.println("[RenderEventHandler] Starting to render all hitboxes...");

        // 获取相机位置
        net.minecraft.world.phys.Vec3 camPos = minecraft.gameRenderer.getMainCamera().getPosition();
        System.out.println("[RenderEventHandler] Camera position: (" + camPos.x + ", " + camPos.y + ", " + camPos.z + ")");

        // 创建BufferBuilder并开始绘制
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        
        // 设置渲染状态
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        
        bufferBuilder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);

        // Debug: 在(0,0,0)位置渲染一个红色立方体
        renderDebugCube(bufferBuilder, poseStack, camPos);

        int entityCount = 0;
        int hitboxCount = 0;
        int renderedHitboxCount = 0;

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            entityCount++;
            
            Collection<BoneHitboxComponent> hitboxes =
                    HitboxSystemClient.getInstance().getEntityHitboxes(entity.getId());
            
            if (hitboxes.isEmpty()) {
                continue;
            }

            // Debug日志：找到有受击盒的实体
            System.out.println("[RenderEventHandler] Found entity with hitboxes: " + entity.getName().getString() + 
                    " (ID: " + entity.getId() + ", Hitboxes: " + hitboxes.size() + ")");
            
            hitboxCount += hitboxes.size();

            for (BoneHitboxComponent hitbox : hitboxes) {
                if (hitbox.isActive()) {
                    renderedHitboxCount++;
                    // Debug日志：渲染单个受击盒
                    System.out.println("[RenderEventHandler] Rendering hitbox: " + hitbox.getBoneName() + 
                            " (Active: " + hitbox.isActive() + ", Critical: " + hitbox.isCritical() + 
                            ", Armored: " + hitbox.isArmored() + ")");
                    
                    renderHitbox(hitbox, poseStack, bufferBuilder, camPos);
                }
            }
        }
        
        // 结束绘制
        tesselator.end();
        
        // 恢复渲染状态
        RenderSystem.enableCull();

        // Debug日志：渲染完成
        System.out.println("[RenderEventHandler] Render complete. Entities: " + entityCount + 
                ", Hitboxes found: " + hitboxCount + ", Hitboxes rendered: " + renderedHitboxCount);
    }

    /**
     * 在(0,0,0)位置渲染一个debug立方体（使用OBBRenderer方法）
     */
    private static void renderDebugCube(BufferBuilder bufferBuilder, PoseStack poseStack, net.minecraft.world.phys.Vec3 camPos) {
        float size = 1.0f; // 立方体大小
        float red = 1.0f, green = 0.0f, blue = 0.0f; // 红色
        float alpha = 1.0f;

        // 创建AABB（以(0,0,0)为中心）
        net.minecraft.world.phys.AABB aabb = new net.minecraft.world.phys.AABB(
                -size, -size, -size,
                size, size, size
        );

        // 使用单位四元数（无旋转）
        Quaternionf rotation = new Quaternionf();

        // 枢轴点为(0,0,0)
        Vector3f pivot = new Vector3f(0, 0, 0);

        // 计算AABB的中心点（相对于枢轴点）
        net.minecraft.world.phys.Vec3 center = new net.minecraft.world.phys.Vec3(
                (aabb.minX + aabb.maxX) / 2 - pivot.x,
                (aabb.minY + aabb.maxY) / 2 - pivot.y,
                (aabb.minZ + aabb.maxZ) / 2 - pivot.z
        );

        // 计算半尺寸
        double halfSizeX = (aabb.maxX - aabb.minX) / 2;
        double halfSizeY = (aabb.maxY - aabb.minY) / 2;
        double halfSizeZ = (aabb.maxZ - aabb.minZ) / 2;

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
        PoseStack.Pose pose = poseStack.last();

        for (int[] edge : edges) {
            Vector3f start = rotatedVertices[edge[0]];
            Vector3f end = rotatedVertices[edge[1]];

            // 直接使用世界坐标
            bufferBuilder.vertex(pose.pose(), start.x(), start.y(), start.z())
                    .color(red, green, blue, alpha)
                    .normal(pose.normal(), 0, 1, 0)
                    .endVertex();

            bufferBuilder.vertex(pose.pose(), end.x(), end.y(), end.z())
                    .color(red, green, blue, alpha)
                    .normal(pose.normal(), 0, 1, 0)
                    .endVertex();
        }

        System.out.println("[RenderEventHandler] Debug cube rendered at (0,0,0) with size: " + size);
    }

    private static void renderHitbox(BoneHitboxComponent hitbox, PoseStack poseStack, BufferBuilder bufferBuilder, net.minecraft.world.phys.Vec3 camPos) {
        org.joml.Vector3f worldCenter = hitbox.getWorldCenter();
        org.joml.Vector3f halfExtents = hitbox.getHalfExtents();

        // Debug：输出受击盒的基本信息
        System.out.println("[RenderEventHandler] Hitbox " + hitbox.getBoneName() + 
                " - WorldCenter: (" + worldCenter.x + ", " + worldCenter.y + ", " + worldCenter.z + ")" +
                ", HalfExtents: (" + halfExtents.x + ", " + halfExtents.y + ", " + halfExtents.z + ")");

        // 创建AABB（基于局部坐标，以原点为中心）
        net.minecraft.world.phys.AABB aabb = new net.minecraft.world.phys.AABB(
                -halfExtents.x, -halfExtents.y, -halfExtents.z,
                halfExtents.x, halfExtents.y, halfExtents.z
        );

        // 渲染OBB（使用世界坐标作为枢轴点，并传入相机位置）
        renderOBB(
                poseStack,
                bufferBuilder,
                aabb,
                hitbox.getWorldOrientation(),
                getHitboxColor(hitbox),
                0.8f,
                worldCenter,
                camPos
        );
    }

    private static void renderOBB(PoseStack poseStack, BufferBuilder bufferBuilder,
                                   net.minecraft.world.phys.AABB localAABB, org.joml.Quaternionf rotation,
                                   Color color, float alpha, org.joml.Vector3f pivot, net.minecraft.world.phys.Vec3 camPos) {
        // 计算AABB的中心点（相对于枢轴点）
        net.minecraft.world.phys.Vec3 center = new net.minecraft.world.phys.Vec3(
                (localAABB.minX + localAABB.maxX) / 2,
                (localAABB.minY + localAABB.maxY) / 2,
                (localAABB.minZ + localAABB.maxZ) / 2
        );

        // 计算半尺寸
        double halfSizeX = (localAABB.maxX - localAABB.minX) / 2;
        double halfSizeY = (localAABB.maxY - localAABB.minY) / 2;
        double halfSizeZ = (localAABB.maxZ - localAABB.minZ) / 2;

        // 定义立方体的8个顶点（相对于枢轴点的局部坐标）
        org.joml.Vector3f[] localVertices = {
                new org.joml.Vector3f((float)(-halfSizeX - center.x), (float)(-halfSizeY - center.y), (float)(-halfSizeZ - center.z)),
                new org.joml.Vector3f((float)( halfSizeX - center.x), (float)(-halfSizeY - center.y), (float)(-halfSizeZ - center.z)),
                new org.joml.Vector3f((float)( halfSizeX - center.x), (float)( halfSizeY - center.y), (float)(-halfSizeZ - center.z)),
                new org.joml.Vector3f((float)(-halfSizeX - center.x), (float)( halfSizeY - center.y), (float)(-halfSizeZ - center.z)),
                new org.joml.Vector3f((float)(-halfSizeX - center.x), (float)(-halfSizeY - center.y), (float)( halfSizeZ - center.z)),
                new org.joml.Vector3f((float)( halfSizeX - center.x), (float)(-halfSizeY - center.y), (float)( halfSizeZ - center.z)),
                new org.joml.Vector3f((float)( halfSizeX - center.x), (float)( halfSizeY - center.y), (float)( halfSizeZ - center.z)),
                new org.joml.Vector3f((float)(-halfSizeX - center.x), (float)( halfSizeY - center.y), (float)( halfSizeZ - center.z))
        };

        // 应用旋转变换（绕枢轴点）
        org.joml.Vector3f[] rotatedVertices = new org.joml.Vector3f[8];
        for (int i = 0; i < 8; i++) {
            rotatedVertices[i] = rotation.transform(localVertices[i]);
            // 加上枢轴点偏移，得到最终世界位置
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
            org.joml.Vector3f start = rotatedVertices[edge[0]];
            org.joml.Vector3f end = rotatedVertices[edge[1]];
            
            // 直接使用世界坐标，不减去相机位置
            float startX = start.x();
            float startY = start.y();
            float startZ = start.z();
            float endX = end.x();
            float endY = end.y();
            float endZ = end.z();
            
            // Debug：输出第一条边的顶点位置
            if (edge[0] == 0 && edge[1] == 1) {
                System.out.println("[RenderEventHandler] Edge 0-1: Start(" + startX + ", " + startY + ", " + startZ + 
                        ") -> End(" + endX + ", " + endY + ", " + endZ + ")");
            }
            
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

    private static Color getHitboxColor(BoneHitboxComponent hitbox) {
        if (hitbox.isCritical()) {
            return Color.RED; // 暴击部位：红色
        } else if (hitbox.isArmored()) {
            return Color.BLUE; // 装甲部位：蓝色
        } else {
            return Color.GREEN; // 普通部位：绿色
        }
    }
}