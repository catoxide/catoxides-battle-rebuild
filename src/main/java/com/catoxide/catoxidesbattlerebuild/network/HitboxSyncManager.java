package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.network.HitboxSyncPacket;
import com.catoxide.catoxidesbattlerebuild.network.NetworkHandler;
import com.catoxide.catoxidesbattlerebuild.server.hitboxsystem.BoneHitboxComponent;
import com.catoxide.catoxidesbattlerebuild.server.hitboxsystem.HitboxSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.PacketDistributor;
import software.bernie.geckolib.GeckoLib;

import java.util.*;

/**
 * 受击盒同步管理器
 */
public class HitboxSyncManager {
    private static final HitboxSyncManager INSTANCE = new HitboxSyncManager();

    // 同步频率控制（每多少tick同步一次）
    private static final int SYNC_INTERVAL = 5;
    private int tickCounter = 0;

    // 距离限制（只同步玩家32格内的实体）
    private static final double MAX_SYNC_DISTANCE = 32.0;
    private static final double MAX_SYNC_DISTANCE_SQ = MAX_SYNC_DISTANCE * MAX_SYNC_DISTANCE;

    // 性能统计
    private int totalPacketsSent = 0;
    private int totalHitboxesSent = 0;

    private HitboxSyncManager() {}

    public static HitboxSyncManager getInstance() {
        return INSTANCE;
    }

    /**
     * 服务器tick时调用
     */
    public void onServerTick(HitboxSystem hitboxSystem) {
        tickCounter++;

        if (tickCounter >= SYNC_INTERVAL) {
            tickCounter = 0;
            syncHitboxesToClients(hitboxSystem);
        }
    }

    /**
     * 同步受击盒到所有客户端
     */
    private void syncHitboxesToClients(HitboxSystem hitboxSystem) {
        if (hitboxSystem == null) return;

        try {
            // 使用 Forge 的方式获取服务器
            net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            net.minecraft.server.players.PlayerList playerList = server.getPlayerList();
            List<net.minecraft.server.level.ServerPlayer> players = playerList.getPlayers();

            for (net.minecraft.server.level.ServerPlayer player : players) {
                syncHitboxesToPlayer(player, hitboxSystem);
            }

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Error syncing hitboxes: {}", e.getMessage(), e);
        }
    }

    /**
     * 同步受击盒到特定玩家
     */
    private void syncHitboxesToPlayer(ServerPlayer player, HitboxSystem hitboxSystem) {
        Map<Integer, List<HitboxSyncPacket.BoneHitboxData>> dataMap = new HashMap<>();

        // 获取玩家位置
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();

        // 获取所有活跃实体
        var activeHitboxes = hitboxSystem.getAllActiveHitboxes();

        for (Map.Entry<UUID, Collection<BoneHitboxComponent>> entry : activeHitboxes.entrySet()) {
            UUID entityUUID = entry.getKey();
            Entity entity = null;
            if (player.level() instanceof ServerLevel serverLevel) {
                entity = serverLevel.getEntity(entityUUID);
            }


            if (entity == null) continue;

            // 距离检查
            double distanceSq = entity.distanceToSqr(playerX, playerY, playerZ);
            if (distanceSq > MAX_SYNC_DISTANCE_SQ) continue;

            // 转换为数据列表
            List<HitboxSyncPacket.BoneHitboxData> hitboxDataList = new ArrayList<>();

            for (BoneHitboxComponent hitbox : entry.getValue()) {
                if (hitbox.isActive()) {
                    hitboxDataList.add(new HitboxSyncPacket.BoneHitboxData(hitbox));
                }
            }

            if (!hitboxDataList.isEmpty()) {
                dataMap.put(entity.getId(), hitboxDataList);
                totalHitboxesSent += hitboxDataList.size();
            }
        }

        // 如果有数据，发送给玩家
        if (!dataMap.isEmpty()) {
            HitboxSyncPacket packet = new HitboxSyncPacket(dataMap);
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    packet
            );

            totalPacketsSent++;

            if (GeckoLib.LOGGER.isDebugEnabled()) {
                GeckoLib.LOGGER.debug("Sent {} entities with {} total hitboxes to player {}",
                        dataMap.size(),
                        dataMap.values().stream().mapToInt(List::size).sum(),
                        player.getName().getString());
            }
        }
    }

    /**
     * 立即同步单个实体的受击盒（用于调试等特殊需求）
     */
    public void syncEntityImmediately(Entity entity, HitboxSystem hitboxSystem) {
        if (entity == null || hitboxSystem == null) return;

        Collection<BoneHitboxComponent> hitboxes =
                hitboxSystem.getEntityHitboxes(entity.getUUID());

        if (hitboxes != null && !hitboxes.isEmpty()) {
            Map<Integer, List<HitboxSyncPacket.BoneHitboxData>> dataMap = new HashMap<>();
            List<HitboxSyncPacket.BoneHitboxData> hitboxDataList = new ArrayList<>();

            for (BoneHitboxComponent hitbox : hitboxes) {
                if (hitbox.isActive()) {
                    hitboxDataList.add(new HitboxSyncPacket.BoneHitboxData(hitbox));
                }
            }

            if (!hitboxDataList.isEmpty()) {
                dataMap.put(entity.getId(), hitboxDataList);

                HitboxSyncPacket packet = new HitboxSyncPacket(dataMap);

                // 发送给所有能看到这个实体的玩家
                entity.level().players().forEach(player -> {
                    if (player.distanceToSqr(entity) <= MAX_SYNC_DISTANCE_SQ) {
                        NetworkHandler.CHANNEL.send(
                                PacketDistributor.PLAYER.with(() -> (ServerPlayer) player),
                                packet
                        );
                    }
                });
            }
        }
    }

    /**
     * 获取统计信息
     */
    public SyncStats getStats() {
        return new SyncStats(totalPacketsSent, totalHitboxesSent);
    }

    /**
     * 同步统计信息
     */
    public static class SyncStats {
        public final int totalPacketsSent;
        public final int totalHitboxesSent;

        public SyncStats(int totalPacketsSent, int totalHitboxesSent) {
            this.totalPacketsSent = totalPacketsSent;
            this.totalHitboxesSent = totalHitboxesSent;
        }

        @Override
        public String toString() {
            return String.format("Packets: %d, Hitboxes: %d",
                    totalPacketsSent, totalHitboxesSent);
        }
    }
}