// ClientHitboxManager.java
package com.catoxide.catoxidesbattlerebuild.client;


import com.catoxide.catoxidesbattlerebuild.mob.server.HitboxSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClientHitboxManager {
    private static final Map<Integer, List<HitboxSyncPacket.HitboxData>> clientHitboxData = new HashMap<>();

    public static void handleHitboxSync(HitboxSyncPacket packet) {
        clientHitboxData.put(packet.getParentId(), packet.getHitboxDataList());

        // 调试输出
//        System.out.println("客户端收到碰撞箱同步数据 - 父实体ID: " + packet.getParentId() +
//                ", 碰撞箱数量: " + packet.getHitboxDataList().size());
//
//        for (HitboxSyncPacket.HitboxData data : packet.getHitboxDataList()) {
//            System.out.println("  部位: " + data.partName +
//                    ", 位置: " + data.position +
//                    ", AABB: " + new AABB(data.minX, data.minY, data.minZ, data.maxX, data.maxY, data.maxZ));
//        }
    }

    public static List<HitboxSyncPacket.HitboxData> getHitboxDataForParent(int parentId) {
        return clientHitboxData.get(parentId);
    }

    public static void removeHitboxData(int parentId) {
        clientHitboxData.remove(parentId);
        System.out.println("移除父实体 " + parentId + " 的碰撞箱数据");
    }

    public static void clearAll() {
        clientHitboxData.clear();
        System.out.println("清空所有客户端碰撞箱数据");
    }
    public static Set<Integer> getAllParentIds() {
        return clientHitboxData.keySet();
    }
}