package com.catoxide.catoxidesbattlerebuild.server.bodypart.factory;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.IBodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyUnitConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.StandardBodyUnitConfig;

/**
 * BodyUnit工厂
 * 负责创建BodyUnit实例和管理配置模板
 */
public class BodyUnitFactory {
    
    // 单例实例
    private static BodyUnitFactory instance;
    
    // 配置模板映射
    private final Map<String, IBodyUnitConfig> configTemplates;
    
    /**
     * 私有构造函数
     */
    private BodyUnitFactory() {
        this.configTemplates = new HashMap<>();
    }
    
    /**
     * 获取单例实例
     */
    public static BodyUnitFactory getInstance() {
        if (instance == null) {
            instance = new BodyUnitFactory();
        }
        return instance;
    }
    
    // ==================== 配置模板管理 ====================
    
    /**
     * 注册配置模板
     */
    public void registerConfigTemplate(IBodyUnitConfig config) {
        configTemplates.put(config.getConfigName(), config);
    }
    
    /**
     * 注册配置模板（使用Builder）
     */
    public void registerConfigTemplate(String configName, String bodyUnitName, String boneName,
                                        float maxHealth, float armorValue, 
                                        boolean critical, String collisionTag,
                                        float defaultTransmissionCoefficient,
                                        float entityTransmissionCoefficient) {
        StandardBodyUnitConfig.Builder builder = new StandardBodyUnitConfig.Builder(configName, bodyUnitName, boneName)
            .maxHealth(maxHealth)
            .armorValue(armorValue)
            .critical(critical)
            .collisionTag(collisionTag)
            .transmissionCoefficient(defaultTransmissionCoefficient)
            .entityTransmissionCoefficient(entityTransmissionCoefficient);
        
        registerConfigTemplate(builder.build());
    }
    
    /**
     * 获取配置模板
     */
    public IBodyUnitConfig getConfigTemplate(String templateName) {
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
    
    // ==================== BodyUnit创建 ====================
    
    /**
     * 创建BodyUnit（基本构造，使用默认配置）
     */
    public IBodyUnit createBodyUnit(long entityId, String bodyUnitName, String boneName) {
        return new BodyUnit(entityId, bodyUnitName, boneName);
    }
    
    /**
     * 批量创建BodyUnit（使用默认配置）
     * @param entityId 实体ID
     * @param bodyUnitNames BodyUnit名称列表
     * @return 创建的BodyUnit列表
     */
    public List<IBodyUnit> createBodyUnitsFromNames(long entityId, List<String> bodyUnitNames) {
        List<IBodyUnit> bodyUnits = new ArrayList<>();
        for (String bodyUnitName : bodyUnitNames) {
            // 使用bodyUnitName作为boneName
            bodyUnits.add(new BodyUnit(entityId, bodyUnitName, bodyUnitName));
        }
        return bodyUnits;
    }
    
    /**
     * 从配置模板创建BodyUnit
     */
    public IBodyUnit createFromTemplate(long entityId, String templateName, String boneName) {
        IBodyUnitConfig config = configTemplates.get(templateName);
        if (config == null) {
            throw new IllegalArgumentException("Config template not found: " + templateName);
        }
        return new BodyUnit(entityId, boneName, config);
    }
    
    /**
     * 从配置对象创建BodyUnit
     */
    public IBodyUnit createFromConfig(long entityId, String boneName, IBodyUnitConfig config) {
        return new BodyUnit(entityId, boneName, config);
    }
    
    /**
     * 批量创建BodyUnit（从配置对象）
     * @param entityId 实体ID
     * @param configMap 骨骼名称到配置的映射
     * @return 创建的BodyUnit列表
     */
    public List<IBodyUnit> createBodyUnits(long entityId, Map<String, IBodyUnitConfig> configMap) {
        List<IBodyUnit> bodyUnits = new ArrayList<>();
        for (Map.Entry<String, IBodyUnitConfig> entry : configMap.entrySet()) {
            String boneName = entry.getKey();
            IBodyUnitConfig config = entry.getValue();
            bodyUnits.add(new BodyUnit(entityId, boneName, config));
        }
        return bodyUnits;
    }
    
    // ==================== 预设配置 ====================
    
    /**
     * 创建头部配置
     */
    public IBodyUnitConfig createHeadConfig(String configName) {
        return new StandardBodyUnitConfig.Builder(configName, "head", "head")
            .maxHealth(50.0f)
            .armorValue(0.2f)
            .critical(true)
            .collisionTag("head")
            .transmissionCoefficient(1.0f)
            .entityTransmissionCoefficient(1.0f)
            .fatal(true)
            .fatalThreshold(0.0f)
            .build();
    }
    
    /**
     * 创建躯干配置
     */
    public IBodyUnitConfig createTorsoConfig(String configName) {
        return new StandardBodyUnitConfig.Builder(configName, "torso", "torso")
            .maxHealth(100.0f)
            .armorValue(0.3f)
            .critical(false)
            .collisionTag("torso")
            .transmissionCoefficient(1.0f)
            .entityTransmissionCoefficient(0.8f)
            .fatal(true)
            .fatalThreshold(0.0f)
            .build();
    }
    
    /**
     * 创建四肢配置
     */
    public IBodyUnitConfig createLimbConfig(String configName, String limbName) {
        return new StandardBodyUnitConfig.Builder(configName, limbName, limbName)
            .maxHealth(40.0f)
            .armorValue(0.1f)
            .critical(false)
            .collisionTag("limb")
            .transmissionCoefficient(0.8f)
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