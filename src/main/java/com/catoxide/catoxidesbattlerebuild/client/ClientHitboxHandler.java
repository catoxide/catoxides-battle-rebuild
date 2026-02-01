// [file name]: ClientHitboxHandler.java
package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.network.HitboxSyncPacket;
import com.catoxide.catoxidesbattlerebuild.server.hitboxsystem.BoneHitboxComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端受击盒处理器
 */
public class ClientHitboxHandler {
    // 客户端存储的受击盒数据
    private static final Map<Integer, List<BoneHitboxComponent>> clientHitboxes = new ConcurrentHashMap<>();

    /**
     * 处理从服务端接收的受击盒同步数据
     */
    public static void handleHitboxSync(HitboxSyncPacket packet) {
        if (Minecraft.getInstance().level == null) return;

        clientHitboxes.clear();

        for (Map.Entry<Integer, List<HitboxSyncPacket.BoneHitboxData>> entry :
                packet.getEntityHitboxesMap().entrySet()) {

            int entityId = entry.getKey();
            Entity entity = Minecraft.getInstance().level.getEntity(entityId);

            if (entity == null) {
                // 实体不存在，跳过
                continue;
            }

            List<BoneHitboxComponent> hitboxList = new ArrayList<>();

            for (HitboxSyncPacket.BoneHitboxData data : entry.getValue()) {
                // 创建客户端的受击盒组件
                BoneHitboxComponent hitbox = new BoneHitboxComponent(
                        entity.getUUID(),
                        data.boneName,
                        data.worldCenter,  // 注意：服务器发来的已经是世界坐标
                        data.halfExtents,  // 半边长
                        data.worldOrientation
                );

                hitbox.setCritical(data.isCritical);
                hitbox.setArmored(data.isArmored);
                hitbox.setActive(data.isActive);

                // 注意：客户端不需要updateWorldTransform，因为数据已经是世界坐标
                // 但我们可以设置一个标记表明这是已经变换过的数据
                hitboxList.add(hitbox);
            }

            clientHitboxes.put(entityId, hitboxList);
        }

        // 更新客户端的HitboxSystem
        HitboxSystemClient.getInstance().updateHitboxes(clientHitboxes);
    }

    /**
     * 获取实体的客户端受击盒
     */
    public static List<BoneHitboxComponent> getEntityHitboxes(int entityId) {
        return clientHitboxes.getOrDefault(entityId, Collections.emptyList());
    }

    /**
     * 清理旧数据
     */
    public static void cleanup() {
        if (Minecraft.getInstance().level == null) {
            clientHitboxes.clear();
            return;
        }

        // 移除不存在的实体
        Iterator<Integer> iterator = clientHitboxes.keySet().iterator();
        while (iterator.hasNext()) {
            int entityId = iterator.next();
            if (Minecraft.getInstance().level.getEntity(entityId) == null) {
                iterator.remove();
            }
        }
    }
}