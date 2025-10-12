package com.catoxide.catoxidesbattlerebuild.damage;

import java.util.HashMap;
import java.util.Map;

/**
 * 穿透等级解析器 - 负责根据优先级解析最终的穿透等级
 */
public class PenetrationResolver {
    // 全局默认值（最低优先级）
    private static final PenetrationLevel GLOBAL_FALLBACK = PenetrationLevel.LIGHT;

    // 伤害类型到穿透等级的映射表（可配置）
    private static final Map<DamageType, PenetrationLevel> DAMAGE_TYPE_PENETRATIONS = new HashMap<>();

    static {
        // ✅ 硬编码的默认映射（后期可迁移到JSON）
        initializeDefaultMappings();
    }

    private static void initializeDefaultMappings() {
        // 物理伤害
        DAMAGE_TYPE_PENETRATIONS.put(DamageType.PHYSICS, PenetrationLevel.LIGHT);
        // 元素伤害
        DAMAGE_TYPE_PENETRATIONS.put(DamageType.FIRE, PenetrationLevel.LIGHT);
        DAMAGE_TYPE_PENETRATIONS.put(DamageType.COLD, PenetrationLevel.LIGHT);
        DAMAGE_TYPE_PENETRATIONS.put(DamageType.LIGHTNING, PenetrationLevel.ARMOR_PIERCING);
        // TODO 魔法伤害
        // 特殊伤害
        DAMAGE_TYPE_PENETRATIONS.put(DamageType.TRUE_DAMAGE, PenetrationLevel.IGNORE_ARMOR);
    }

    /**
     * 解析穿透等级（按优先级顺序）
     * 优先级：显式指定 > 武器覆盖 > 全局配置 > 全局默认
     */
    public static PenetrationLevel resolvePenetration(
            DamageType damageType,
            PenetrationLevel explicitPenetration,
            PenetrationLevel weaponOverride) {

        // 1. 显式指定（最高优先级）
        if (explicitPenetration != null) {
            return explicitPenetration;
        }

        // 2. 武器或来源覆盖
        if (weaponOverride != null) {
            return weaponOverride;
        }

        // 3. 全局配置映射
        if (damageType != null && DAMAGE_TYPE_PENETRATIONS.containsKey(damageType)) {
            return DAMAGE_TYPE_PENETRATIONS.get(damageType);
        }

        // 4. 全局默认（最低优先级）
        return GLOBAL_FALLBACK;
    }

    // ✅ 开发工具：运行时修改映射（方便测试）
    public static void setPenetrationMapping(DamageType damageType, PenetrationLevel penetration) {
        DAMAGE_TYPE_PENETRATIONS.put(damageType, penetration);
    }

    public static void resetToDefaults() {
        DAMAGE_TYPE_PENETRATIONS.clear();
        initializeDefaultMappings();
    }

    // ✅ 为JSON迁移做准备
    public static Map<DamageType, PenetrationLevel> getCurrentMappings() {
        return new HashMap<>(DAMAGE_TYPE_PENETRATIONS);
    }

    public static void loadFromMap(Map<DamageType, PenetrationLevel> mappings) {
        DAMAGE_TYPE_PENETRATIONS.clear();
        DAMAGE_TYPE_PENETRATIONS.putAll(mappings);
    }
}