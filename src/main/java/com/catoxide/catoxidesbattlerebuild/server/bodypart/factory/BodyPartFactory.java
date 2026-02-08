package com.catoxide.catoxidesbattlerebuild.server.bodypart.factory;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.IBodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyPartConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.StandardBodyPartConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * 身体部位工厂
 * 负责创建身体部位实例
 */
public class BodyPartFactory {
    
    private static final BodyPartFactory INSTANCE = new BodyPartFactory();
    
    // 配置模板缓存
    private final Map<String, IBodyPartConfig> configTemplates;
    
    private BodyPartFactory() {
        this.configTemplates = new HashMap<>();
    }
    
    public static BodyPartFactory getInstance() {
        return INSTANCE;
    }
    
    /**
     * 注册配置模板
     * @param templateName 模板名称
     * @param config 配置实例
     */
    public void registerConfigTemplate(String templateName, IBodyPartConfig config) {
        configTemplates.put(templateName, config);
    }
    
    /**
     * 获取配置模板
     * @param templateName 模板名称
     * @return 配置实例，如果不存在则返回null
     */
    public IBodyPartConfig getConfigTemplate(String templateName) {
        return configTemplates.get(templateName);
    }
    
    /**
     * 从配置创建身体部位
     * @param partName 部位名称
     * @param config 配置实例
     * @return 身体部位实例
     */
    public IBodyPart createBodyPart(String partName, IBodyPartConfig config) {
        return new BodyPart(partName, config);
    }
    
    /**
     * 从模板创建身体部位
     * @param partName 部位名称
     * @param templateName 模板名称
     * @return 身体部位实例，如果模板不存在则返回null
     */
    public IBodyPart createBodyPartFromTemplate(String partName, String templateName) {
        IBodyPartConfig config = configTemplates.get(templateName);
        if (config == null) {
            return null;
        }
        return createBodyPart(partName, config);
    }
    
    /**
     * 批量创建身体部位
     * @param partConfigs 部位配置映射（部位名称 -> 配置）
     * @return 身体部位映射
     */
    public Map<String, IBodyPart> createBodyParts(Map<String, IBodyPartConfig> partConfigs) {
        Map<String, IBodyPart> parts = new HashMap<>();
        for (Map.Entry<String, IBodyPartConfig> entry : partConfigs.entrySet()) {
            String partName = entry.getKey();
            IBodyPartConfig config = entry.getValue();
            parts.put(partName, createBodyPart(partName, config));
        }
        return parts;
    }
    
    /**
     * 从模板批量创建身体部位
     * @param partTemplateMappings 部位模板映射（部位名称 -> 模板名称）
     * @return 身体部位映射
     */
    public Map<String, IBodyPart> createBodyPartsFromTemplates(Map<String, String> partTemplateMappings) {
        Map<String, IBodyPart> parts = new HashMap<>();
        for (Map.Entry<String, String> entry : partTemplateMappings.entrySet()) {
            String partName = entry.getKey();
            String templateName = entry.getValue();
            IBodyPart part = createBodyPartFromTemplate(partName, templateName);
            if (part != null) {
                parts.put(partName, part);
            }
        }
        return parts;
    }
    
    /**
     * 创建标准配置
     * @param configName 配置名称
     * @param baseHealth 基础血量
     * @param damageMultiplier 伤害倍率
     * @param armorValue 护甲值
     * @return 标准配置实例
     */
    public IBodyPartConfig createStandardConfig(String configName,
                                                float baseHealth,
                                                float damageMultiplier,
                                                float armorValue) {
        return new StandardBodyPartConfig(
            configName,
            baseHealth,
            damageMultiplier,
            armorValue,
            0f,  // armorToughness
            false,  // criticalZone
            false,  // fatalZone
            null,  // defaultAbilities
            1.0f  // defaultTransmissionCoefficient
        );
    }
}
