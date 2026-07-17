package com.catoxide.catoxidesbattlerebuild.client.bodypart;

import com.catoxide.catoxidesbattlerebuild.network.combat.BodyDestructionSyncPacket;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DestructionStateManager {
    private static final DestructionStateManager INSTANCE = new DestructionStateManager();

    private final Map<Integer, List<String>> entityDestroyedBones = new HashMap<>();

    private DestructionStateManager() {
        LogManager.clientStartup("DestructionStateManager", "DestructionStateManager initialized");
    }

    public static DestructionStateManager getInstance() {
        return INSTANCE;
    }

    public void handlePacket(BodyDestructionSyncPacket packet) {
        int entityId = packet.entityId();
        List<String> destroyedBones = packet.destroyedBones();

        entityDestroyedBones.put(entityId, new ArrayList<>(destroyedBones));
        LogManager.clientDebug("DestructionStateManager", "Received destruction state for entityId={}, destroyedBones={}",
                entityId, destroyedBones.size());

        BodyDestructionRenderer.updateEntityBones(entityId, destroyedBones);
    }

    public boolean isBoneDestroyed(int entityId, String boneName) {
        List<String> destroyedBones = entityDestroyedBones.get(entityId);
        return destroyedBones != null && destroyedBones.contains(boneName);
    }

    public List<String> getDestroyedBones(int entityId) {
        return entityDestroyedBones.getOrDefault(entityId, List.of());
    }

    public void clearEntity(int entityId) {
        entityDestroyedBones.remove(entityId);
    }

    public void clearAll() {
        entityDestroyedBones.clear();
    }
}