package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.mob.server.HitboxSyncPacket;
import com.catoxide.catoxidesbattlerebuild.network.NetworkHandler;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class SyncManager {
    private final ModularZombie parent;
    private final BodyPartManager partManager;

    public SyncManager(ModularZombie parent, BodyPartManager partManager) {
        this.parent = parent;
        this.partManager = partManager;
    }

    public void syncHitboxesToClient() {
        if (parent.level().isClientSide) return;

        List<HitboxSyncPacket.HitboxData> hitboxDataList = new ArrayList<>();

        for (BodyPart part : partManager.getBodyParts().values()) {
            if (part.isDestroyed()) continue;

            BoneTransform boneTransform = parent.getServerAnimationSystem().getBoneTransform(part.getBoneName());
            if (boneTransform != null) {
                Vec3 boneWorldPos = boneTransform.position;
                AABB baseHitbox = part.getBaseHitbox();
                AABB worldHitbox = baseHitbox.move(boneWorldPos);

                hitboxDataList.add(new HitboxSyncPacket.HitboxData(
                        part.getPartName(),
                        boneWorldPos,
                        boneTransform.rotation,
                        worldHitbox.minX, worldHitbox.minY, worldHitbox.minZ,
                        worldHitbox.maxX, worldHitbox.maxY, worldHitbox.maxZ
                ));
            }
        }

        if (!hitboxDataList.isEmpty()) {
            HitboxSyncPacket packet = new HitboxSyncPacket(parent.getId(), hitboxDataList);
            NetworkHandler.sendToAllTracking(packet, parent);
        }
    }

    public void syncBodyPartStates() {
        // TODO: 同步身体部位状态（破坏状态、血量等）
        // 这可以是一个新的数据包类型
    }

    public void syncAll() {
        syncHitboxesToClient();
        syncBodyPartStates();
        // 可以添加其他同步方法
    }
}
