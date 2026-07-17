package com.catoxide.catoxidesbattlerebuild.client.bodypart;

import cn.solarmoon.spark_core.animation.model.origin.OBone;
import cn.solarmoon.spark_core.animation.model.origin.OModel;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BodyDestructionRenderer {
    private static final Map<Integer, OModel> entityNormalModels = new ConcurrentHashMap<>();
    private static final Map<Integer, OModel> entityDamageModels = new ConcurrentHashMap<>();

    private static final boolean SPARK_VISIBILITY_SUPPORT;
    private static final boolean SPARK_DAMAGE_LEVEL_SUPPORT;

    private static Method setVisibleMethod;
    private static Method setDamageLevelMethod;

    static {
        boolean visibilitySupport = false;
        boolean damageLevelSupport = false;
        try {
            setVisibleMethod = OBone.class.getMethod("setVisible", boolean.class);
            visibilitySupport = true;
        } catch (NoSuchMethodException e) {
            LogManager.clientDebug("BodyDestructionRenderer", "OBone.setVisible() not found - visibility control disabled");
        }
        try {
            setDamageLevelMethod = OBone.class.getMethod("setDamageLevel", int.class);
            damageLevelSupport = true;
        } catch (NoSuchMethodException e) {
            LogManager.clientDebug("BodyDestructionRenderer", "OBone.setDamageLevel() not found - damage level control disabled");
        }
        SPARK_VISIBILITY_SUPPORT = visibilitySupport;
        SPARK_DAMAGE_LEVEL_SUPPORT = damageLevelSupport;
    }

    private static void setBoneVisibleSafe(OBone bone, boolean visible) {
        if (setVisibleMethod != null) {
            try {
                setVisibleMethod.invoke(bone, visible);
            } catch (Exception e) {
                LogManager.clientDebug("BodyDestructionRenderer", "Failed to invoke setVisible: {}", e.getMessage());
            }
        }
    }

    private static void setBoneDamageLevelSafe(OBone bone, int level) {
        if (setDamageLevelMethod != null) {
            try {
                setDamageLevelMethod.invoke(bone, level);
            } catch (Exception e) {
                LogManager.clientDebug("BodyDestructionRenderer", "Failed to invoke setDamageLevel: {}", e.getMessage());
            }
        }
    }

    public static void updateEntityBones(int entityId, List<String> destroyedBones) {
        OModel normalModel = entityNormalModels.get(entityId);
        OModel damageModel = entityDamageModels.get(entityId);

        if (normalModel == null && damageModel == null) {
            LogManager.clientDebug("BodyDestructionRenderer", "No model cached for entityId={}", entityId);
            return;
        }

        if (SPARK_VISIBILITY_SUPPORT && normalModel != null) {
            for (OBone bone : normalModel.getBones().values()) {
                setBoneVisibleSafe(bone, !destroyedBones.contains(bone.getName()));
            }
        }

        if (SPARK_VISIBILITY_SUPPORT && damageModel != null) {
            for (OBone bone : damageModel.getBones().values()) {
                setBoneVisibleSafe(bone, destroyedBones.contains(bone.getName()));
            }
        }

        LogManager.clientDebug("BodyDestructionRenderer",
                "Updated bone visibility for entityId={}, destroyed={} (visibilitySupport={})",
                entityId, destroyedBones.size(), SPARK_VISIBILITY_SUPPORT);
    }

    public static void cacheEntityModels(int entityId, OModel normalModel, OModel damageModel) {
        if (normalModel != null) {
            entityNormalModels.put(entityId, normalModel);
        }
        if (damageModel != null) {
            entityDamageModels.put(entityId, damageModel);
            if (SPARK_VISIBILITY_SUPPORT) {
                for (OBone bone : damageModel.getBones().values()) {
                    setBoneVisibleSafe(bone, false);
                }
            }
        }
        LogManager.clientDebug("BodyDestructionRenderer",
                "Cached models for entityId={}: normal={}, damage={}",
                entityId, normalModel != null, damageModel != null);
    }

    public static void cacheNormalModel(int entityId, OModel model) {
        entityNormalModels.put(entityId, model);
        LogManager.clientDebug("BodyDestructionRenderer", "Cached normal model for entityId={}", entityId);
    }

    public static void cacheDamageModel(int entityId, OModel model) {
        entityDamageModels.put(entityId, model);
        if (model != null && SPARK_VISIBILITY_SUPPORT) {
            for (OBone bone : model.getBones().values()) {
                setBoneVisibleSafe(bone, false);
            }
        }
        LogManager.clientDebug("BodyDestructionRenderer", "Cached damage model for entityId={}", entityId);
    }

    public static void removeEntityCache(int entityId) {
        entityNormalModels.remove(entityId);
        entityDamageModels.remove(entityId);
        LogManager.clientDebug("BodyDestructionRenderer", "Removed cache for entityId={}", entityId);
    }

    public static void setBoneVisible(int entityId, String boneName, boolean visible) {
        OModel normalModel = entityNormalModels.get(entityId);
        OModel damageModel = entityDamageModels.get(entityId);

        if (SPARK_VISIBILITY_SUPPORT && normalModel != null) {
            OBone bone = normalModel.getBones().get(boneName);
            if (bone != null) {
                setBoneVisibleSafe(bone, visible);
            }
        }

        if (SPARK_VISIBILITY_SUPPORT && damageModel != null) {
            OBone bone = damageModel.getBones().get(boneName);
            if (bone != null) {
                setBoneVisibleSafe(bone, !visible);
            }
        }

        LogManager.clientDebug("BodyDestructionRenderer",
                "setBoneVisible: entityId={}, bone={}, visible={} (visibilitySupport={})",
                entityId, boneName, visible, SPARK_VISIBILITY_SUPPORT);
    }

    public static void setDamageLevel(int entityId, String boneName, int level) {
        OModel normalModel = entityNormalModels.get(entityId);
        OModel damageModel = entityDamageModels.get(entityId);

        if (SPARK_VISIBILITY_SUPPORT && SPARK_DAMAGE_LEVEL_SUPPORT && normalModel != null) {
            OBone bone = normalModel.getBones().get(boneName);
            if (bone != null) {
                setBoneDamageLevelSafe(bone, level);
                setBoneVisibleSafe(bone, level == 0);
            }
        }

        if (SPARK_VISIBILITY_SUPPORT && SPARK_DAMAGE_LEVEL_SUPPORT && damageModel != null) {
            OBone bone = damageModel.getBones().get(boneName);
            if (bone != null) {
                setBoneDamageLevelSafe(bone, level);
                setBoneVisibleSafe(bone, level > 0);
            }
        }

        LogManager.clientDebug("BodyDestructionRenderer",
                "setDamageLevel: entityId={}, bone={}, level={} (visibilitySupport={}, damageLevelSupport={})",
                entityId, boneName, level, SPARK_VISIBILITY_SUPPORT, SPARK_DAMAGE_LEVEL_SUPPORT);
    }
}