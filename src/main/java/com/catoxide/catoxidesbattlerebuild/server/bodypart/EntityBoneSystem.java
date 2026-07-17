package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EntityBoneSystem {
    private static final EntityBoneSystem INSTANCE = new EntityBoneSystem();

    private final Map<Long, EntityBoneData> entityDataMap = new HashMap<>();

    private EntityBoneSystem() {
        LogManager.serverStartup("EntityBoneSystem", "EntityBoneSystem initialized");
    }

    public static EntityBoneSystem getInstance() {
        return INSTANCE;
    }

    public void initEntity(int entityId, List<BodyPartConfig> configs) {
        LogManager.serverDebug("EntityBoneSystem", "Initializing entity bone system for entityId={}, partCount={}",
                entityId, configs.size());
        
        EntityBoneData data = new EntityBoneData();
        int totalUnits = 0;
        
        for (BodyPartConfig config : configs) {
            BodyPart part = new BodyPart(config.partName(), config.maxModuleHealth(),
                    config.isFatal(), config.transmissionToBase());
            for (BodyPartConfig.BodyUnitConfig unitConfig : config.units()) {
                BodyUnit unit = new BodyUnit(unitConfig.boneName(), unitConfig.transmissionCoeff(),
                        unitConfig.armorValue(), unitConfig.collisionTag(), unitConfig.specialEffects(),
                        unitConfig.hitSound(), part);
                part.addUnit(unit);
                totalUnits++;
            }
            data.addPart(part);
            LogManager.serverDebug("EntityBoneSystem", "Added part: {}, fatal={}", config.partName(), config.isFatal());
        }
        
        entityDataMap.put((long) entityId, data);
        LogManager.serverInfo("EntityBoneSystem", "Entity bone system initialized for entityId={}, parts={}, units={}",
                entityId, configs.size(), totalUnits);
    }

    public DamageResult processEntityHit(int entityId, String boneName, float rawDamage) {
        LogManager.serverDebug("EntityBoneSystem", "Processing hit: entityId={}, boneName={}, rawDamage={}",
                entityId, boneName, rawDamage);
        
        EntityBoneData data = entityDataMap.get((long) entityId);
        if (data == null) {
            LogManager.serverDebug("EntityBoneSystem", "No entity data found for entityId={}", entityId);
            return null;
        }

        BodyUnit unit = data.getUnitByBone(boneName);
        if (unit == null) {
            LogManager.serverDebug("EntityBoneSystem", "No bone unit found for boneName={}", boneName);
            return null;
        }

        float unitDamage = unit.applyDamage(rawDamage);
        float baseDamage = unit.getParentPart().applyDamage(unitDamage);

        boolean isFatal = unit.getParentPart().isFatal() && unit.getParentPart().isDestroyed();
        
        LogManager.serverInfo("EntityBoneSystem", "Hit processed: entityId={}, boneName={}, partName={}, " +
                        "unitDamage={}, baseDamage={}, isFatal={}",
                entityId, boneName, unit.getParentPart().getPartName(), unitDamage, baseDamage, isFatal);

        return new DamageResult(unit.getParentPart().getPartName(), boneName, unitDamage, baseDamage, isFatal,
                unit.getSpecialEffects(), unit.getHitSound());
    }

    public List<BodyPart> getBodyParts(int entityId) {
        EntityBoneData data = entityDataMap.get((long) entityId);
        return data != null ? data.getParts() : List.of();
    }

    public BodyPart getPartByBone(int entityId, String boneName) {
        EntityBoneData data = entityDataMap.get((long) entityId);
        if (data == null) return null;
        BodyUnit unit = data.getUnitByBone(boneName);
        return unit != null ? unit.getParentPart() : null;
    }

    public BodyUnit getUnitByBone(int entityId, String boneName) {
        EntityBoneData data = entityDataMap.get((long) entityId);
        return data != null ? data.getUnitByBone(boneName) : null;
    }

    public List<BodyUnit> getDefaultBodyUnits(int entityId) {
        EntityBoneData data = entityDataMap.get((long) entityId);
        if (data == null) return new ArrayList<>();
        List<BodyUnit> allUnits = new ArrayList<>();
        for (BodyPart part : data.getParts()) {
            allUnits.addAll(part.getUnits());
        }
        return allUnits;
    }

    public void removeEntity(int entityId) {
        entityDataMap.remove((long) entityId);
        LogManager.serverDebug("EntityBoneSystem", "Removed entity data for entityId={}", entityId);
    }

    private static class EntityBoneData {
        private final List<BodyPart> parts = new ArrayList<>();
        private final Map<String, BodyUnit> boneToUnitMap = new HashMap<>();

        public void addPart(BodyPart part) {
            parts.add(part);
            for (BodyUnit unit : part.getUnits()) {
                boneToUnitMap.put(unit.getBoneName(), unit);
            }
        }

        public List<BodyPart> getParts() { return parts; }
        public BodyUnit getUnitByBone(String boneName) { return boneToUnitMap.get(boneName); }
    }

    public record DamageResult(
            String partName,
            String boneName,
            float moduleDamage,
            float baseDamage,
            boolean isFatal,
            List<String> specialEffects,
            String hitSound
    ) {}
}
