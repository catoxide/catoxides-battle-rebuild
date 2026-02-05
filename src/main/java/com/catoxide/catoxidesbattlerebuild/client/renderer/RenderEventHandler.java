package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.client.HitboxSystemClient;
import com.catoxide.catoxidesbattlerebuild.server.temp.BoneHitboxComponent;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.GeckoLib;

import java.awt.*;
import java.util.Collection;

@Mod.EventBusSubscriber(modid = CatoxidesBattleRebuild.MODID, value = Dist.CLIENT)
public class RenderEventHandler {
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 在渲染实体之后渲染，这样可以确保我们的渲染在实体之上
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }
            
            try {
                renderAllHitboxes(event.getPoseStack(), event.getPartialTick());
            } catch (Exception e) {
                GeckoLib.LOGGER.error("[RenderEventHandler] Error rendering hitboxes: {}", e.getMessage(), e);
            }
        }
    }

    private static void renderAllHitboxes(PoseStack poseStack, float partialTick) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        // 获取相机位置
        net.minecraft.world.phys.Vec3 camPos = minecraft.gameRenderer.getMainCamera().getPosition();

        // 创建BufferBuilder并开始绘制
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        
        // 设置渲染状态 - 使用更激进的设置确保线条可见
        RenderSystem.disableDepthTest();  // 禁用深度测试，线条不会被遮挡
        RenderSystem.depthMask(false);    // 禁用深度写入
        RenderSystem.disableCull();       // 禁用背面剔除
        RenderSystem.enableBlend();       // 启用混合
        RenderSystem.defaultBlendFunc();  // 使用默认混合函数
        RenderSystem.lineWidth(5.0f);     // 增加线宽到5.0，更容易看到
        
        // 设置正确的渲染批次 - 使用线条渲染类型
        PoseStack.Pose pose = poseStack.last();
        
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

            hitboxCount += hitboxes.size();

            for (BoneHitboxComponent hitbox : hitboxes) {
                if (hitbox.isActive()) {
                    renderedHitboxCount++;
                    renderHitbox(hitbox, poseStack, bufferBuilder, camPos);
                }
            }
        }
        
        // 结束绘制
        tesselator.end();
        
        // 恢复渲染状态
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f); // 恢复默认线宽

        // 只在debug模式下输出渲染统计
        if (GeckoLib.LOGGER.isDebugEnabled()) {
            GeckoLib.LOGGER.debug("[RenderEventHandler] Rendered {} entities, {} hitboxes ({} active)",
                    entityCount, hitboxCount, renderedHitboxCount);
        }
    }

    /**
     * 在(0,0,0)位置渲染一个debug立方体（使用OBBRenderer方法）
     */
    private static void renderDebugCube(BufferBuilder bufferBuilder, PoseStack poseStack, net.minecraft.world.phys.Vec3 camPos) {
        float size = 5.0f; // 调整为5.0，更合理的大小
        float red = 1.0f, green = 1.0f, blue = 0.0f; // 亮黄色
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

        // 计算半尺寸
        double halfSizeX = (aabb.maxX - aabb.minX) / 2;
        double halfSizeY = (aabb.maxY - aabb.minY) / 2;
        double halfSizeZ = (aabb.maxZ - aabb.minZ) / 2;

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

        // 应用旋转变换（绕枢轴点）
        Vector3f[] rotatedVertices = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            rotatedVertices[i] = rotation.transform(localVertices[i]);
            // 加回枢轴点偏移，得到最终世界位置
            rotatedVertices[i].add(pivot);
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

            // 将世界坐标转换为相对于相机的坐标
            float startX = start.x() - (float)camPos.x;
            float startY = start.y() - (float)camPos.y;
            float startZ = start.z() - (float)camPos.z;
            
            float endX = end.x() - (float)camPos.x;
            float endY = end.y() - (float)camPos.y;
            float endZ = end.z() - (float)camPos.z;

            bufferBuilder.vertex(pose.pose(), startX, startY, startZ)
                    .color(red, green, blue, alpha)
                    .normal(pose.normal(), 0, 1, 0) // 对于线条，法线影响不大
                    .endVertex();

            bufferBuilder.vertex(pose.pose(), endX, endY, endZ)
                    .color(red, green, blue, alpha)
                    .normal(pose.normal(), 0, 1, 0)
                    .endVertex();
        }

        System.out.println("[RenderEventHandler] Debug cube rendered at (0,0,0) with size: " + size + " (YELLOW)");
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

        // 渲染OBB（使用世界坐标作为枢轴点）
        OBBRenderer.renderOBB(
                poseStack,
                bufferBuilder,
                aabb,
                hitbox.getWorldOrientation(),
                getHitboxColor(hitbox),
                0.8f,
                worldCenter
        );
    }

    /**
     * 在受击盒中心渲染一个十字标记点
     */
    private static void renderCenterPoint(BufferBuilder bufferBuilder, PoseStack poseStack, 
                                           org.joml.Vector3f center, Color color, net.minecraft.world.phys.Vec3 camPos) {
        float pointSize = 0.15f; // 十字标记的大小
        float r = color.getRed() / 255.0f;
        float g = color.getGreen() / 255.0f;
        float b = color.getBlue() / 255.0f;
        
        PoseStack.Pose pose = poseStack.last();
        
        // 直接使用世界坐标，PoseStack会自动处理相机变换
        
        // 渲染X轴方向的线
        bufferBuilder.vertex(pose.pose(), center.x - pointSize, center.y, center.z)
                .color(r, g, b, 1.0f)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
        
        bufferBuilder.vertex(pose.pose(), center.x + pointSize, center.y, center.z)
                .color(r, g, b, 1.0f)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
        
        // 渲染Y轴方向的线
        bufferBuilder.vertex(pose.pose(), center.x, center.y - pointSize, center.z)
                .color(r, g, b, 1.0f)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
        
        bufferBuilder.vertex(pose.pose(), center.x, center.y + pointSize, center.z)
                .color(r, g, b, 1.0f)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
        
        // 渲染Z轴方向的线
        bufferBuilder.vertex(pose.pose(), center.x, center.y, center.z - pointSize)
                .color(r, g, b, 1.0f)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
        
        bufferBuilder.vertex(pose.pose(), center.x, center.y, center.z + pointSize)
                .color(r, g, b, 1.0f)
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
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