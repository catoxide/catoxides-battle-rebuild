package com.catoxide.catoxidesbattlerebuild.core.mob;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 数据驱动实体定义注册表
 * <p>EntityType 的 key（namespace:id）→ {@link MobDefinition}。
 * 由 ContentPackContext 在注册数据驱动实体时填充；
 * {@link DataDrivenMob} 构造时按自己的 EntityType key 查询定义。
 */
public final class MobDefinitionRegistry {

    private static final Map<ResourceLocation, MobDefinition> DEFINITIONS = new ConcurrentHashMap<>();

    private MobDefinitionRegistry() {}

    /** 注册实体定义（注册实体时调用） */
    public static void register(ResourceLocation entityTypeKey, MobDefinition definition) {
        DEFINITIONS.put(entityTypeKey, definition);
    }

    /** 查询实体定义（无则返回 null） */
    public static MobDefinition get(ResourceLocation entityTypeKey) {
        return DEFINITIONS.get(entityTypeKey);
    }

    /** 所有已注册定义 */
    public static Collection<MobDefinition> all() {
        return DEFINITIONS.values();
    }

    /** 清空（世界重载/测试用） */
    public static void clear() {
        DEFINITIONS.clear();
    }
}
