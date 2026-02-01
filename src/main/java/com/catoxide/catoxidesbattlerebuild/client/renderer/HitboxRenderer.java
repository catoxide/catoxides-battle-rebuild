package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.HitboxSystemClient;
import com.catoxide.catoxidesbattlerebuild.server.hitboxsystem.BoneHitboxComponent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Vector3f;

import java.awt.*;
import java.util.Collection;

/**
 * Hitbox渲染器 - 使用OBBRenderer渲染骨骼受击盒
 */
public class HitboxRenderer {
    private static final HitboxRenderer INSTANCE = new HitboxRenderer();
    private static final RenderType HITBOX_RENDER_TYPE = RenderType.lines();

    public static HitboxRenderer getInstance() {
        return INSTANCE;
    }

    /**
     * 渲染单个实体的所有受击盒
     */

    public void renderEntityHitboxes(Entity entity, PoseStack poseStack,
                                     MultiBufferSource bufferSource, float partialTick) {
        // 从客户端系统获取受击盒数据
        Collection<BoneHitboxComponent> hitboxes =
                HitboxSystemClient.getInstance().getEntityHitboxes(entity.getId());
        System.out.print("渲染实体 {} (ID: {}) 的受击盒，数量: {}"+
                entity.getName().getString()+entity.getId()+hitboxes.size());

        if (hitboxes.isEmpty()) {
            return;
        }

        VertexConsumer vertexConsumer = bufferSource.getBuffer(HITBOX_RENDER_TYPE);

        for (BoneHitboxComponent hitbox : hitboxes) {
            if (hitbox.isActive()) {
                renderHitbox(hitbox, poseStack, vertexConsumer);
            }
        }
    }
    /**
     * 渲染单个受击盒
     */
    private void renderHitbox(BoneHitboxComponent hitbox, PoseStack poseStack, VertexConsumer vertexConsumer) {
        // 获取受击盒的世界中心和半边长
        Vector3f worldCenter = hitbox.getWorldCenter();
        Vector3f halfExtents = hitbox.getHalfExtents();

        // 创建AABB
        net.minecraft.world.phys.AABB aabb = new net.minecraft.world.phys.AABB(
                worldCenter.x - halfExtents.x, worldCenter.y - halfExtents.y, worldCenter.z - halfExtents.z,
                worldCenter.x + halfExtents.x, worldCenter.y + halfExtents.y, worldCenter.z + halfExtents.z
        );

        // 渲染OBB
        OBBRenderer.renderOBB(
                poseStack,
                vertexConsumer,
                aabb,
                hitbox.getWorldOrientation(),
                getHitboxColor(hitbox),
                0.8f,
                new Vector3f(worldCenter.x, worldCenter.y, worldCenter.z)
        );
    }

    /**
     * 根据受击盒类型获取渲染颜色
     */
    private Color getHitboxColor(BoneHitboxComponent hitbox) {
        if (hitbox.isCritical()) {
            return Color.RED; // 暴击部位：红色
        } else if (hitbox.isArmored()) {
            return Color.BLUE; // 装甲部位：蓝色
        } else {
            return Color.GREEN; // 普通部位：绿色
        }
    }

    /**
     * 渲染所有实体的受击盒（调试用）
     */
    public void renderAllHitboxes(PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            renderEntityHitboxes(entity, poseStack, bufferSource, partialTick);
        }
    }
    public void renderDebugInfo(PoseStack poseStack, MultiBufferSource bufferSource) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 获取玩家指向的实体
        HitResult hitResult = mc.hitResult;
        if (hitResult instanceof EntityHitResult entityHitResult) {
            Entity entity = entityHitResult.getEntity();
            Collection<BoneHitboxComponent> hitboxes =
                    HitboxSystemClient.getInstance().getEntityHitboxes(entity.getId());

            // 在屏幕上显示信息
            if (!hitboxes.isEmpty()) {
                String info = String.format("Entity: %s, Hitboxes: %d",
                        entity.getName().getString(), hitboxes.size());
                // 这里可以添加屏幕渲染代码
            }
        }
    }
}