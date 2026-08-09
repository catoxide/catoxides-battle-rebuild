package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.network.SyncBoneDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;

/**
 * 骨骼位置同步管理器（服务端 → 客户端）。
 * <p>对每个 {@link AnimatedMob}（有骨骼数据的实体）**每 tick 全量**同步骨骼世界位置到附近玩家。
 * <p>位置是绝对浮点坐标，必须全量同步保证精度（不做差值/增量编码）；
 * 客户端 {@code BoneDataManager} 每 tick 全量替换。
 */
public class ServerBoneSyncManager {

    private static final ServerBoneSyncManager INSTANCE = new ServerBoneSyncManager();

    private ServerBoneSyncManager() {
        NeoForge.EVENT_BUS.addListener(this::onEntityTick);
    }

    public static ServerBoneSyncManager getInstance() {
        return INSTANCE;
    }

    @SubscribeEvent
    private void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }
        if (!(entity instanceof AnimatedMob<?> mob)) {
            return;
        }
        syncBoneData(mob);
    }

    private void syncBoneData(AnimatedMob<?> mob) {
        Map<String, org.joml.Vector3f> bonePositions = mob.getAllServerBonePositions();
        if (bonePositions.isEmpty()) {
            return;
        }

        SyncBoneDataPacket packet = new SyncBoneDataPacket(mob.getId(), bonePositions);

        // Send to all players tracking this entity
        for (ServerPlayer player : mob.getServer().getPlayerList().getPlayers()) {
            if (player.getId() == mob.getId() || player.distanceToSqr(mob) < 4096) {
                packet.send(player.connection::send);
            }
        }
    }
}
