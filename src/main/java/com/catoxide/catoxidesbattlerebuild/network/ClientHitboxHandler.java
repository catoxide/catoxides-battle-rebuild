package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.client.ClientEntityManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端受击盒处理器
 * 负责接收服务端发送的受击盒同步数据
 * 
 * 核心功能：
 * 1. 接收HitboxSyncPacket数据包
 * 2. 提取动画状态信息
 * 3. 更新ClientEntityManager的动画状态
 * 4. 触发客户端动画解算
 * 
 * 注意：这是初版实现，专注于核心功能
 */
public class ClientHitboxHandler {
    
    /**
     * 处理受击盒同步数据包
     * 
     * @param packet 受击盒同步数据包
     */
    public static void handleHitboxSync(HitboxSyncPacket packet) {
        if (packet == null) {
            System.err.println("[ClientHitboxHandler] Received null packet");
            return;
        }
        
        Map<Integer, List<HitboxSyncPacket.BoneHitboxData>> entityHitboxesMap = 
                packet.getEntityHitboxesMap();
        
        if (entityHitboxesMap == null || entityHitboxesMap.isEmpty()) {
            System.out.println("[ClientHitboxHandler] No hitbox data in packet");
            return;
        }
        
        // 获取客户端实体管理器
        ClientEntityManager entityManager = ClientEntityManager.getInstance();
        
        // 遍历所有实体数据
        for (Map.Entry<Integer, List<HitboxSyncPacket.BoneHitboxData>> entry : 
                entityHitboxesMap.entrySet()) {
            
            int entityId = entry.getKey();
            List<HitboxSyncPacket.BoneHitboxData> hitboxList = entry.getValue();
            
            if (hitboxList == null || hitboxList.isEmpty()) {
                continue;
            }
            
            // 从Minecraft客户端获取实体
            Entity entity = Minecraft.getInstance().level.getEntity(entityId);
            if (entity == null) {
                System.out.println("[ClientHitboxHandler] Entity not found: " + entityId);
                continue;
            }
            
            UUID entityUUID = entity.getUUID();
            
            // 从第一个骨骼数据中提取动画状态
            // 注意：所有骨骼共享同一个动画状态
            HitboxSyncPacket.BoneHitboxData firstHitbox = hitboxList.get(0);
            String animationName = firstHitbox.animationName;
            double animationTime = firstHitbox.animationTime;
            double animationSpeed = firstHitbox.animationSpeed;
            boolean looping = firstHitbox.looping;
            
            // 更新动画状态到ClientEntityManager
            entityManager.updateAnimationState(
                    entityUUID,
                    animationName,
                    animationTime,
                    animationSpeed,
                    looping
            );
            
            // 解算动画（获取骨骼矩阵）
            Map<String, Matrix4f> boneMatrices = entityManager.resolveAnimation(
                    entityUUID, 
                    0.0f // partialTick - 初版不使用插值
            );
            
            // 输出调试信息
            System.out.println("[ClientHitboxHandler] Processed entity: " + entityId + 
                    ", animation: " + animationName + 
                    ", time: " + animationTime +
                    ", bones: " + boneMatrices.size());
        }
    }
}
