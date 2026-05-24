package com.catoxide.catoxidesbattlerebuild.client.geometry;

import com.catoxide.catoxidesbattlerebuild.client.models.ClientBoneModelData;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EntityBoneManager {
    private static final EntityBoneManager INSTANCE = new EntityBoneManager();

    private final Map<Long, Map<String, ClientBoneCollection>> entityBones = new HashMap<>();
    private final Map<Long, Set<String>> trackedBones = new HashMap<>();
    private final Map<Long, DoubleBufferedBoneData> doubleBufferedData = new HashMap<>();

    private EntityBoneManager() {}

    public static EntityBoneManager getInstance() {
        return INSTANCE;
    }

    public void initEntity(long entityId, ClientBoneModelData modelData) {
        Map<String, ClientBoneCollection> bones = new HashMap<>();
        for (String boneName : modelData.boneHierarchy()) {
            ClientBoneModelData.BoneStaticData boneData = modelData.getBoneStaticData(boneName);
            ClientBoneCollection bone = ClientBoneCollection.fromStaticData(entityId, boneData);
            bone.setChildBoneNames(modelData.getChildBones(boneName));
            bones.put(boneName, bone);
        }
        entityBones.put(entityId, bones);
        doubleBufferedData.put(entityId, new DoubleBufferedBoneData());

        LogManager.clientDebug("EntityBoneManager", "Initialized entity bones: entityId={}, bones={}",
                entityId, bones.size());
    }

    public void onEntityEnterView(long entityId) {
        trackedBones.put(entityId, new HashSet<>());
        LogManager.clientDebug("EntityBoneManager", "Entity entered view: entityId={}", entityId);
    }

    public void onEntityAimed(long entityId, Set<String> bones) {
        trackedBones.put(entityId, bones);
        LogManager.clientDebug("EntityBoneManager", "Entity aimed, tracking bones: entityId={}, count={}",
                entityId, bones.size());
    }

    public boolean isBoneTracked(long entityId, String boneName) {
        Set<String> tracked = trackedBones.get(entityId);
        return tracked != null && tracked.contains(boneName);
    }

    public ClientBoneCollection getBone(long entityId, String boneName) {
        Map<String, ClientBoneCollection> bones = entityBones.get(entityId);
        return bones != null ? bones.get(boneName) : null;
    }

    public DoubleBufferedBoneData getDoubleBufferedData(long entityId) {
        return doubleBufferedData.get(entityId);
    }

    public void removeEntity(long entityId) {
        entityBones.remove(entityId);
        trackedBones.remove(entityId);
        doubleBufferedData.remove(entityId);
        LogManager.clientDebug("EntityBoneManager", "Removed entity: entityId={}", entityId);
    }
}
