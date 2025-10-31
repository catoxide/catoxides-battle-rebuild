// ClientBoneDebugManager.java
package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.mob.server.BoneDebugPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClientBoneDebugManager {
    private static final Map<Integer, List<BoneDebugPacket.BoneData>> clientBoneData = new HashMap<>();

    public static void handleBoneDebugSync(BoneDebugPacket packet) {
        clientBoneData.put(packet.getParentId(), packet.getBoneDataList());

        System.out.println("客户端收到骨骼调试数据 - 父实体ID: " + packet.getParentId() +
                ", 骨骼数量: " + packet.getBoneDataList().size());

        for (BoneDebugPacket.BoneData data : packet.getBoneDataList()) {
            System.out.println("  骨骼: " + data.boneName +
                    ", 位置: " + data.position +
                    ", 父骨骼位置: " + data.parentPosition);
        }
    }

    public static List<BoneDebugPacket.BoneData> getBoneDataForParent(int parentId) {
        return clientBoneData.get(parentId);
    }

    public static void removeBoneData(int parentId) {
        clientBoneData.remove(parentId);
    }

    public static void clearAll() {
        clientBoneData.clear();
    }

    public static Set<Integer> getAllParentIds() {
        return clientBoneData.keySet();
    }
}
