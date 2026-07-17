package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPartConfig;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体部位血量配置管理器
 * <p>管理所有已加载的 HealthConfig，提供按 entity_id 查询部位配置。
 */
public final class HealthConfigManager {

    private static final HealthConfigManager INSTANCE = new HealthConfigManager();

    private final Map<String, HealthConfig> configs = new ConcurrentHashMap<>();

    private HealthConfigManager() {}

    public static HealthConfigManager getInstance() {
        return INSTANCE;
    }

    /**
     * 注册一个实体的部位血量配置
     */
    public void register(HealthConfig config) {
        if (config == null) return;
        configs.put(config.entityId(), config);
        LogManager.serverInfo("HealthConfigManager", "Registered health config for entity {}", config.entityId());
    }

    /**
     * 获取指定实体的部位配置列表，用于初始化 EntityBoneSystem
     * @return 部位配置列表，如果未找到则返回 null
     */
    public List<BodyPartConfig> getConfig(String entityId) {
        HealthConfig config = configs.get(entityId);
        if (config == null) {
            LogManager.serverDebug("HealthConfigManager", "No health config found for entity {}", entityId);
            return null;
        }
        return config.parts();
    }

    /**
     * 检查某个实体是否有配置
     */
    public boolean hasConfig(String entityId) {
        return configs.containsKey(entityId);
    }

    /**
     * 移除配置
     */
    public void unregister(String entityId) {
        configs.remove(entityId);
    }

    public int size() {
        return configs.size();
    }

    public void clear() {
        configs.clear();
        LogManager.serverInfo("HealthConfigManager", "All health configs cleared");
    }
}
