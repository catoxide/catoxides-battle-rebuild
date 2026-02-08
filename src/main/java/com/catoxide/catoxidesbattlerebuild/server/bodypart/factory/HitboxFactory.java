package com.catoxide.catoxidesbattlerebuild.server.bodypart.factory;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.IHitbox;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.Hitbox;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IHitboxConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.StandardHitboxConfig;

/**
 * Hitbox工厂
 * 负责创建Hitbox实例和管理配置模板
 */
public class HitboxFactory {
    
    // 单例实例
    private static HitboxFactory instance;
    
    // 配置模板映射
    private final Map<String, IHitboxConfig> configTemplates;
    
    /**
     * 私有构造函数
     */
    private HitboxFactory() {
        this.configTemplates = new HashMap<>();
    }
    
    /**
     * 获取单例实例
     */
    public static HitboxFactory getInstance() {
        if (instance == null) {
            instance = new HitboxFactory();
        }
        return instance;
    }
    
    // ==================== 配置模板管理 ====================
    
    /**
     * 注册配置模板
     */
    public void registerConfigTemplate(IHitboxConfig config) {
        configTemplates.put(config.getConfigName(), config);
    }
    
    /**
     * 注册配置模板（使用Builder）
     */
    public void registerConfigTemplate(String configName, String hitboxName, 
                                        float maxHealth, float armorValue, 
                                        boolean critical, String collisionTag,
                                        float defaultTransmissionCoefficient,
                                        float entityTransmissionCoefficient) {
        StandardHitboxConfig.Builder builder = new StandardHitboxConfig.Builder(configName, hitboxName)
            .maxHealth(maxHealth)
            .armorValue(armorValue)
            .critical(critical)
            .collisionTag(collisionTag)
            .defaultTransmissionCoefficient(defaultTransmissionCoefficient)
            .entityTransmissionCoefficient(entityTransmissionCoefficient);
        
        registerConfigTemplate(builder.build());
    }
    
    /**
     * 获取配置模板
     */
    public IHitboxConfig getConfigTemplate(String templateName) {
        return configTemplates.get(templateName);
    }
    
    /**
     * 获取所有配置模板名称
     */
    public List<String> getConfigTemplateNames() {
        return new ArrayList<>(configTemplates.keySet());
    }
    
    /**
     * 检查配置模板是否存在
     */
    public boolean hasConfigTemplate(String templateName) {
        return configTemplates.containsKey(templateName);
    }
    
    /**
     * 移除配置模板
     */
    public void removeConfigTemplate(String templateName) {
        configTemplates.remove(templateName);
    }
    
    /**
     * 清空所有配置模板
     */
    public void clearConfigTemplates() {
        configTemplates.clear();
    }
    
    // ==================== Hitbox创建 ====================
    
    /**
     * 创建Hitbox（基本构造）
     */
    public IHitbox createHitbox(long entityId, String hitboxName) {
        return new Hitbox(entityId, hitboxName);
    }
    
    /**
     * 从配置模板创建Hitbox
     */
    public IHitbox createHitbox(long entityId,String hitboxName,String templateName) {
        IHitboxConfig config = configTemplates.get(templateName);
        if (config == null) {
            throw new IllegalArgumentException("Config template not found: " + templateName);
        }
        return new Hitbox(entityId, config);
    }
    
    /**
     * 从配置对象创建Hitbox
     */
    public IHitbox createHitbox(long entityId, IHitboxConfig config) {
        return new Hitbox(entityId, config);
    }
    
    /**
     * 批量创建Hitbox（从配置模板）
     */
    public List<IHitbox> createHitboxes(long entityId, List<String> templateNames) {
        List<IHitbox> hitboxes = new ArrayList<>();
        for (String templateName : templateNames) {
            hitboxes.add(createHitbox(entityId, templateName));
        }
        return hitboxes;
    }
    
    /**
     * 批量创建Hitbox（从配置对象）
     */
    public List<IHitbox> createHitboxes(long entityId, List<IHitboxConfig> configs) {
        List<IHitbox> hitboxes = new ArrayList<>();
        for (IHitboxConfig config : configs) {
            hitboxes.add(new Hitbox(entityId, config));
        }
        return hitboxes;
    }
    
    // ==================== 预设配置 ====================
    
    /**
     * 创建头部配置
     */
    public IHitboxConfig createHeadConfig(String configName) {
        return new StandardHitboxConfig.Builder(configName, "head")
            .maxHealth(50.0f)
            .armorValue(0.2f)
            .critical(true)
            .collisionTag("head")
            .defaultTransmissionCoefficient(1.0f)
            .entityTransmissionCoefficient(1.0f)
            .fatal(true)
            .fatalThreshold(0.0f)
            .build();
    }
    
    /**
     * 创建躯干配置
     */
    public IHitboxConfig createTorsoConfig(String configName) {
        return new StandardHitboxConfig.Builder(configName, "torso")
            .maxHealth(100.0f)
            .armorValue(0.3f)
            .critical(false)
            .collisionTag("torso")
            .defaultTransmissionCoefficient(1.0f)
            .entityTransmissionCoefficient(0.8f)
            .fatal(true)
            .fatalThreshold(0.0f)
            .build();
    }
    
    /**
     * 创建四肢配置
     */
    public IHitboxConfig createLimbConfig(String configName, String limbName) {
        return new StandardHitboxConfig.Builder(configName, limbName)
            .maxHealth(40.0f)
            .armorValue(0.1f)
            .critical(false)
            .collisionTag("limb")
            .defaultTransmissionCoefficient(0.8f)
            .entityTransmissionCoefficient(0.3f)
            .fatal(false)
            .build();
    }
    
    /**
     * 注册默认配置模板
     */
    public void registerDefaultTemplates() {
        // 头部
        registerConfigTemplate(createHeadConfig("default_head"));
        
        // 躯干
        registerConfigTemplate(createTorsoConfig("default_torso"));
        
        // 四肢
        registerConfigTemplate(createLimbConfig("default_left_arm", "left_arm"));
        registerConfigTemplate(createLimbConfig("default_right_arm", "right_arm"));
        registerConfigTemplate(createLimbConfig("default_left_leg", "left_leg"));
        registerConfigTemplate(createLimbConfig("default_right_leg", "right_leg"));
    }
}
