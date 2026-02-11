package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.geometry.ClientBoneCollection;
import com.catoxide.catoxidesbattlerebuild.client.geometry.ClientCubeCollection;
import com.catoxide.catoxidesbattlerebuild.client.manager.ClientHitboxManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 受击盒调试渲染器
 * 负责渲染客户端的受击盒调试信息
 */
public class HitboxDebugRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(HitboxDebugRenderer.class);
    
    // 渲染类型
    private static final RenderType HITBOX_RENDER_TYPE = RenderType.lines();
    
    // 是否启用调试渲染
    private static boolean debugEnabled = false;
    
    // 渲染统计
    private static int renderedEntities = 0;
    private static int renderedBones = 0;
    private static int renderedCubes = 0;
    
    private HitboxDebugRenderer() {
        // 私有构造函数
    }
    
    /**
     * 设置调试渲染启用状态
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        LOGGER.info("[HitboxDebugRenderer] Debug rendering {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * 获取调试渲染启用状态
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }
    
    /**
     * 渲染所有实体的受击盒
     */
    public static void renderAllHitboxes(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, 
                                         Vec3 cameraPosition, float partialTick) {
        if (!debugEnabled) {
            return;
        }
        
        LOGGER.debug("[HitboxDebugRenderer] Rendering all hitboxes...");
        renderedEntities = 0;
        renderedBones = 0;
        renderedCubes = 0;
        
        // 获取所有实体UUID
        Map<UUID, Map<String, ClientBoneCollection>> allBoneCollections = 
            ClientHitboxManager.getInstance().getAllBoneCollections();
        
        for (UUID entityUuid : allBoneCollections.keySet()) {
            renderEntityHitboxes(poseStack, bufferSource, cameraPosition, entityUuid, partialTick);
        }
        
        LOGGER.info("[HitboxDebugRenderer] Rendered: entities={}, bones={}, cubes={}", 
                renderedEntities, renderedBones, renderedCubes);
    }
    
    /**
     * 渲染指定实体的受击盒
     */
    public static void renderEntityHitboxes(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                             Vec3 cameraPosition, UUID entityUuid, float partialTick) {
        if (!debugEnabled) {
            return;
        }
        
        LOGGER.debug("[HitboxDebugRenderer] Rendering hitboxes for entity: uuid={}", entityUuid);
        
        // 获取骨骼集合
        Map<String, ClientBoneCollection> boneCollections = 
            ClientHitboxManager.getInstance().getBoneCollections(entityUuid);
        
        if (boneCollections.isEmpty()) {
            LOGGER.warn("[HitboxDebugRenderer] No bone collections found for entity: uuid={}", entityUuid);
            return;
        }
        
        renderedEntities++;
        
        // 渲染每个骨骼的受击盒
        for (Map.Entry<String, ClientBoneCollection> entry : boneCollections.entrySet()) {
            String boneName = entry.getKey();
            ClientBoneCollection boneCollection = entry.getValue();
            
            renderBoneHitboxes(poseStack, bufferSource, cameraPosition, boneCollection, boneName);
        }
    }
    
    /**
     * 渲染骨骼的受击盒
     */
    public static void renderBoneHitboxes(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                          Vec3 cameraPosition, ClientBoneCollection boneCollection, 
                                          String boneName) {
        if (!debugEnabled) {
            return;
        }
        
        LOGGER.debug("[HitboxDebugRenderer] Rendering hitboxes for bone: name={}", boneName);
        
        renderedBones++;
        
        // 获取立方体集合
        List<ClientCubeCollection> cubeCollections = boneCollection.getCubeCollections();
        
        // 渲染每个立方体
        for (ClientCubeCollection cube : cubeCollections) {
            renderCubeHitbox(poseStack, bufferSource, cameraPosition, cube);
            renderedCubes++;
        }
    }
    
    /**
     * 渲染立方体受击盒
     */
    public static void renderCubeHitbox(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                        Vec3 cameraPosition, ClientCubeCollection cube) {
        if (!debugEnabled) {
            return;
        }
        
        LOGGER.debug("[HitboxDebugRenderer] Rendering cube: id={}", cube.getId());
        
        // 创建局部AABB（基于局部坐标，以原点为中心）
        double halfSizeX = cube.getSize().x / 2.0;
        double halfSizeY = cube.getSize().y / 2.0;
        double halfSizeZ = cube.getSize().z / 2.0;
        
        AABB localAABB = new AABB(
            -halfSizeX, -halfSizeY, -halfSizeZ,
            halfSizeX, halfSizeY, halfSizeZ
        );
        
        // 获取旋转（四元数）
        org.joml.Quaternionf rotation = cube.getRotationQuaternion();
        
        // 获取枢轴点（世界坐标）
        Vector3f pivot = new Vector3f(
            (float) cube.getPivot().x,
            (float) cube.getPivot().y,
            (float) cube.getPivot().z
        );
        
        // 获取VertexConsumer
        VertexConsumer vertexConsumer = bufferSource.getBuffer(HITBOX_RENDER_TYPE);
        
        // 使用OBBRenderer渲染（参照之前的debug方块代码）
        OBBRenderer.renderOBB(
            poseStack,
            vertexConsumer,
            localAABB,
            rotation,
            Color.YELLOW,  // 使用黄色作为debug颜色
            1.0f,          // alpha值
            pivot          // 世界坐标作为枢轴点
        );
        
        LOGGER.debug("[HitboxDebugRenderer] Rendered cube using OBBRenderer");
    }
    
    /**
     * 获取渲染统计
     */
    public static String getRenderStats() {
        String stats = String.format(
            "[HitboxDebugRenderer] Render Stats: entities=%d, bones=%d, cubes=%d, enabled=%s",
            renderedEntities, renderedBones, renderedCubes, debugEnabled
        );
        LOGGER.info(stats);
        return stats;
    }
    
    /**
     * 重置渲染统计
     */
    public static void resetRenderStats() {
        renderedEntities = 0;
        renderedBones = 0;
        renderedCubes = 0;
        LOGGER.debug("[HitboxDebugRenderer] Render stats reset");
    }
}