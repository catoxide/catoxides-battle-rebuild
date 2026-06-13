package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import com.catoxide.catoxidesbattlerebuild.mob.zombie2.ModularZombie2;
import com.catoxide.catoxidesbattlerebuild.network.SyncBoneDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ServerBoneSyncManager {
    
    private static final ServerBoneSyncManager INSTANCE = new ServerBoneSyncManager();
    
    // Track sync intervals per entity
    private final Map<UUID, Integer> syncTimers = new HashMap<>();
    // Sync every 5 ticks (roughly 4 times per second)
    private static final int SYNC_INTERVAL = 5;

    private ServerBoneSyncManager() {
        // Register event listener
        NeoForge.EVENT_BUS.addListener(this::onEntityTick);
    }

    public static ServerBoneSyncManager getInstance() {
        return INSTANCE;
    }

    private void onEntityTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        
        // Only sync ModularZombie2 entities on server side
        if (!(entity instanceof ModularZombie2 zombie) || entity.level().isClientSide()) {
            return;
        }

        // Check if it's time to sync
        UUID entityId = entity.getUUID();
        int timer = syncTimers.getOrDefault(entityId, 0);
        
        if (timer >= SYNC_INTERVAL) {
            syncBoneData(zombie);
            syncTimers.put(entityId, 0);
        } else {
            syncTimers.put(entityId, timer + 1);
        }
    }

    private void syncBoneData(ModularZombie2 zombie) {
        Map<String, org.joml.Vector3f> bonePositions = zombie.getAllServerBonePositions();
        
        if (bonePositions.isEmpty()) {
            return;
        }

        SyncBoneDataPacket packet = new SyncBoneDataPacket(zombie.getId(), bonePositions);
        
        // Send to all players tracking this entity
        for (ServerPlayer player : zombie.getServer().getPlayerList().getPlayers()) {
            if (player.getId() == zombie.getId() || player.distanceToSqr(zombie) < 4096) {
                packet.send(player.connection::send);
            }
        }
    }

    /**
     * Force sync bone data immediately
     */
    public void forceSync(ModularZombie2 zombie) {
        Map<String, org.joml.Vector3f> bonePositions = zombie.getAllServerBonePositions();
        if (!bonePositions.isEmpty()) {
            SyncBoneDataPacket packet = new SyncBoneDataPacket(zombie.getId(), bonePositions);
            for (ServerPlayer player : zombie.getServer().getPlayerList().getPlayers()) {
                if (player.getId() == zombie.getId() || player.distanceToSqr(zombie) < 4096) {
                    packet.send(player.connection::send);
                }
            }
        }
    }

    /**
     * Clean up timer for dead entities
     */
    public void cleanup(Entity entity) {
        syncTimers.remove(entity.getUUID());
    }
}