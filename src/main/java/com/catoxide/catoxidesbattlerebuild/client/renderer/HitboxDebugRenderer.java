package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.geometry.ClientBoneCollection;
import com.catoxide.catoxidesbattlerebuild.client.geometry.ClientCubeCollection;
import com.catoxide.catoxidesbattlerebuild.client.manager.ClientHitboxManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
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
 * 使用BufferBuilder管线进行高效渲染
 */
public class HitboxDebugRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(HitboxDebugRenderer.class);
    
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
     * @param poseStack 姿态堆栈
     * @param cameraPosition 相机位置
     * @param partialTick 部分刻度
     */
    public static void renderAllHitboxes(PoseStack poseStack, Vec3 cameraPosition, float partialTick) {
        if (!debugEnabled) {
            return;
        }
        
        LOGGER.debug("[HitboxDebugRenderer] Rendering all hitboxes...");
        renderedEntities = 0;
        renderedBones = 0;
        renderedCubes = 0;
        
        // 获取Tesselator和BufferBuilder
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        
        // 设置渲染状态 - 使用更激进的设置确保线条可见
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();  // 禁用深度测试，线条不会被遮挡
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false);    // 禁用深度写入
        com.mojang.blaze3d.systems.RenderSystem.disableCull();       // 禁用背面剔除
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();       // 启用混合
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();  // 使用默认混合函数
        com.mojang.blaze3d.systems.RenderSystem.lineWidth(5.0f);     // 增加线宽到5.0，更容易看到
        
        // 开始构建：使用LINES模式和POSITION_COLOR_NORMAL格式
        bufferBuilder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        
        try {
            // 先渲染debug立方体（在0,0,0位置，大小1m）
            renderDebugCube(bufferBuilder, poseStack, cameraPosition);
            
            // 获取所有实体UUID
            Map<UUID, Map<String, ClientBoneCollection>> allBoneCollections = 
                ClientHitboxManager.getInstance().getAllBoneCollections();
            
            for (UUID entityUuid : allBoneCollections.keySet()) {
                renderEntityHitboxes(bufferBuilder, poseStack, cameraPosition, entityUuid, partialTick);
            }
            
            LOGGER.info("[HitboxDebugRenderer] Rendered: entities={}, bones={}, cubes={}", 
                    renderedEntities, renderedBones, renderedCubes);
        } finally {
            // 结束绘制并上传到GPU
            tesselator.end();
            
            // 恢复渲染状态
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            com.mojang.blaze3d.systems.RenderSystem.lineWidth(1.0f); // 恢复默认线宽
        }
    }
    
    /**
     * 渲染一个简单的debug立方体
     * 位置：0, 0, 0
     * 大小：1m (1.0 x 1.0 x 1.0)
     * 颜色：红色
     */
    private static void renderDebugCube(BufferBuilder bufferBuilder, PoseStack poseStack, Vec3 cameraPosition) {
        LOGGER.info("[HitboxDebugRenderer] Rendering debug cube at (0,0,0) with size 1m");
        
        // 定义立方体的8个顶点（世界坐标，以原点为中心）
        Vector3f[] vertices = {
            new Vector3f(-0.5f, -0.5f, -0.5f), // 0: 左下后
            new Vector3f(0.5f, -0.5f, -0.5f),  // 1: 右下后
            new Vector3f(0.5f, 0.5f, -0.5f),   // 2: 右上后
            new Vector3f(-0.5f, 0.5f, -0.5f),  // 3: 左上后
            new Vector3f(-0.5f, -0.5f, 0.5f),  // 4: 左下前
            new Vector3f(0.5f, -0.5f, 0.5f),   // 5: 右下前
            new Vector3f(0.5f, 0.5f, 0.5f),    // 6: 右上前
            new Vector3f(-0.5f, 0.5f, 0.5f)    // 7: 左上前
        };
        
        // 定义立方体的12条边（顶点索引对）
        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, // 后表面
            {4, 5}, {5, 6}, {6, 7}, {7, 4}, // 前表面
            {0, 4}, {1, 5}, {2, 6}, {3, 7}  // 连接前后
        };
        
        // 获取当前的pose（包含变换矩阵）
        PoseStack.Pose pose = poseStack.last();
        
        // 渲染每条边
        for (int[] edge : edges) {
            Vector3f v1 = vertices[edge[0]];
            Vector3f v2 = vertices[edge[1]];
            
            // 将世界坐标转换为相对于相机的坐标
            float x1 = v1.x - (float)cameraPosition.x;
            float y1 = v1.y - (float)cameraPosition.y;
            float z1 = v1.z - (float)cameraPosition.z;
            
            float x2 = v2.x - (float)cameraPosition.x;
            float y2 = v2.y - (float)cameraPosition.y;
            float z2 = v2.z - (float)cameraPosition.z;
            
            // 添加第一个顶点（使用pose.pose()应用变换矩阵）
            bufferBuilder.vertex(pose.pose(), x1, y1, z1)
                .color(1.0f, 0.0f, 0.0f, 1.0f) // 红色
                .normal(pose.normal(), 0, 1, 0) // 向上的法线
                .endVertex();
            
            // 添加第二个顶点
            bufferBuilder.vertex(pose.pose(), x2, y2, z2)
                .color(1.0f, 0.0f, 0.0f, 1.0f) // 红色
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
        }
        
        LOGGER.info("[HitboxDebugRenderer] Debug cube rendered successfully");
    }
    
    /**
     * 渲染指定实体的受击盒
     */
    private static void renderEntityHitboxes(BufferBuilder bufferBuilder, PoseStack poseStack,
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
            
            renderBoneHitboxes(bufferBuilder, poseStack, cameraPosition, boneCollection, boneName);
        }
    }
    
    /**
     * 渲染骨骼的受击盒
     */
    private static void renderBoneHitboxes(BufferBuilder bufferBuilder, PoseStack poseStack,
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
            renderCubeHitbox(bufferBuilder, poseStack, cameraPosition, cube);
            renderedCubes++;
        }
    }
    
    /**
     * 渲染立方体受击盒
     */
    private static void renderCubeHitbox(BufferBuilder bufferBuilder, PoseStack poseStack,
                                        Vec3 cameraPosition, ClientCubeCollection cube) {
        if (!debugEnabled) {
            return;
        }
        
        LOGGER.debug("[HitboxDebugRenderer] Rendering cube: id={}", cube.getId());
        
        // 直接使用已经计算好的世界顶点
        List<Vec3> worldVertices = cube.getWorldVertices();
        
        if (worldVertices.size() != 8) {
            LOGGER.warn("[HitboxDebugRenderer] Invalid vertex count: expected=8, actual={}", worldVertices.size());
            return;
        }
        
        // 定义立方体的12条边（顶点索引对）
        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, // 后表面
            {4, 5}, {5, 6}, {6, 7}, {7, 4}, // 前表面
            {0, 4}, {1, 5}, {2, 6}, {3, 7}  // 连接前后
        };
        
        // 获取当前的pose（包含变换矩阵）
        PoseStack.Pose pose = poseStack.last();
        
        // 渲染每条边（使用黄色）
        for (int[] edge : edges) {
            Vec3 v1 = worldVertices.get(edge[0]);
            Vec3 v2 = worldVertices.get(edge[1]);
            
            // 将世界坐标转换为相对于相机的坐标
            float x1 = (float) v1.x - (float) cameraPosition.x;
            float y1 = (float) v1.y - (float) cameraPosition.y;
            float z1 = (float) v1.z - (float) cameraPosition.z;
            
            float x2 = (float) v2.x - (float) cameraPosition.x;
            float y2 = (float) v2.y - (float) cameraPosition.y;
            float z2 = (float) v2.z - (float) cameraPosition.z;
            
            // 添加第一个顶点（使用pose.pose()应用变换矩阵）
            bufferBuilder.vertex(pose.pose(), x1, y1, z1)
                .color(1.0f, 1.0f, 0.0f, 1.0f) // 黄色
                .normal(pose.normal(), 0, 1, 0) // 向上的法线
                .endVertex();
            
            // 添加第二个顶点
            bufferBuilder.vertex(pose.pose(), x2, y2, z2)
                .color(1.0f, 1.0f, 0.0f, 1.0f) // 黄色
                .normal(pose.normal(), 0, 1, 0)
                .endVertex();
        }
        
        LOGGER.debug("[HitboxDebugRenderer] Rendered cube using world vertices");
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