package com.catoxide.catoxidesbattlerebuild.server.bodypart.factory;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.IBodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyPartConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyUnitConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.StandardBodyPartConfig;
import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelData;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 身体部位工厂
 * 负责创建身体部位实例
 * 新架构：每个BodyUnit关联一个Bone，BodyPart包含多个BodyUnit
 */
public class BodyPartFactory {
    
    private static final BodyPartFactory INSTANCE = new BodyPartFactory();
    
    // 配置模板缓存
    private final Map<String, IBodyPartConfig> configTemplates;
    
    // BodyUnit配置模板缓存
    private final Map<String, IBodyUnitConfig> unitConfigTemplates;
    
    // 默认部位名称
    private static final String DEFAULT_PART_NAME = "main";
    
    // 默认传导系数
    private static final float DEFAULT_TRANSMISSION_COEFFICIENT = 1.0f;
    
    private BodyPartFactory() {
        this.configTemplates = new HashMap<>();
        this.unitConfigTemplates = new HashMap<>();
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
     * 注册BodyUnit配置模板
     * @param templateName 模板名称
     * @param config 配置实例
     */
    public void registerUnitConfigTemplate(String templateName, IBodyUnitConfig config) {
        unitConfigTemplates.put(templateName, config);
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
     * 获取BodyUnit配置模板
     * @param templateName 模板名称
     * @return 配置实例，如果不存在则返回null
     */
    public IBodyUnitConfig getUnitConfigTemplate(String templateName) {
        return unitConfigTemplates.get(templateName);
    }
    
    /**
     * 从配置创建身体部位（新架构）
     * @param entityId 实体ID
     * @param partName 部位名称
     * @param config 配置实例
     * @param boneToUnitConfigMapping 骨骼到BodyUnit配置的映射
     * @return 身体部位实例
     */
    public IBodyPart createBodyPart(long entityId, String partName, IBodyPartConfig config, 
                                     Map<String, IBodyUnitConfig> boneToUnitConfigMapping) {
        // 为每个骨骼创建对应的BodyUnit
        List<BodyUnit> bodyUnits = new ArrayList<>();
        for (Map.Entry<String, IBodyUnitConfig> entry : boneToUnitConfigMapping.entrySet()) {
            String boneName = entry.getKey();
            IBodyUnitConfig unitConfig = entry.getValue();
            BodyUnit unit = new BodyUnit(entityId, boneName, unitConfig);
            bodyUnits.add(unit);
        }
        
        return new BodyPart(partName, config, bodyUnits);
    }
    
    /**
     * 从模板创建身体部位
     * @param entityId 实体ID
     * @param partName 部位名称
     * @param templateName 模板名称
     * @param boneToUnitTemplateMapping 骨骼到BodyUnit模板的映射
     * @return 身体部位实例,如果模板不存在则返回null
     */
    public IBodyPart createBodyPartFromTemplate(long entityId, String partName, String templateName,
                                                  Map<String, String> boneToUnitTemplateMapping) {
        IBodyPartConfig config = configTemplates.get(templateName);
        if (config == null) {
            return null;
        }
        
        // 转换BodyUnit模板映射为配置映射
        Map<String, IBodyUnitConfig> boneToUnitConfigMapping = new HashMap<>();
        for (Map.Entry<String, String> entry : boneToUnitTemplateMapping.entrySet()) {
            String boneName = entry.getKey();
            String unitTemplateName = entry.getValue();
            IBodyUnitConfig unitConfig = unitConfigTemplates.get(unitTemplateName);
            if (unitConfig != null) {
                boneToUnitConfigMapping.put(boneName, unitConfig);
            }
        }
        
        return createBodyPart(entityId, partName, config, boneToUnitConfigMapping);
    }
    
    /**
     * 批量创建身体部位(新架构)
     * @param entityId 实体ID
     * @param partConfigs 部位配置映射(部位名称 -> 配置)
     * @param partToBoneUnitMapping 部位到骨骼BodyUnit配置的映射(部位名称 -> 骨骼到配置的映射)
     * @return 身体部位映射
     */
    public Map<String, IBodyPart> createBodyParts(long entityId, 
                                                     Map<String, IBodyPartConfig> partConfigs,
                                                     Map<String, Map<String, IBodyUnitConfig>> partToBoneUnitMapping) {
        Map<String, IBodyPart> parts = new HashMap<>();
        for (Map.Entry<String, IBodyPartConfig> entry : partConfigs.entrySet()) {
            String partName = entry.getKey();
            IBodyPartConfig config = entry.getValue();
            Map<String, IBodyUnitConfig> boneToUnitConfigMapping = 
                partToBoneUnitMapping.getOrDefault(partName, new HashMap<>());
            parts.put(partName, createBodyPart(entityId, partName, config, boneToUnitConfigMapping));
        }
        return parts;
    }
    
    /**
     * 从模板批量创建身体部位(新架构)
     * @param entityId 实体ID
     * @param partTemplateMappings 部位模板映射(部位名称 -> 模板名称)
     * @param partToBoneUnitTemplateMapping 部位到骨骼BodyUnit模板的映射(部位名称 -> 骨骼到模板名称的映射)
     * @return 身体部位映射
     */
    public Map<String, IBodyPart> createBodyPartsFromTemplates(long entityId,
                                                                 Map<String, String> partTemplateMappings,
                                                                 Map<String, Map<String, String>> partToBoneUnitTemplateMapping) {
        Map<String, IBodyPart> parts = new HashMap<>();
        for (Map.Entry<String, String> entry : partTemplateMappings.entrySet()) {
            String partName = entry.getKey();
            String templateName = entry.getValue();
            Map<String, String> boneToUnitTemplateMapping = 
                partToBoneUnitTemplateMapping.getOrDefault(partName, new HashMap<>());
            IBodyPart part = createBodyPartFromTemplate(entityId, partName, templateName, boneToUnitTemplateMapping);
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
    
    /**
     * 创建身体部位并自动分配骨骼（新架构）
     * 将未分配的骨骼默认分配到"main"部位，并为每个骨骼创建对应的BodyUnit
     * 
     * @param entityId 实体ID
     * @param partConfigs 部位配置映射（部位名称 -> 配置）
     * @param boneToPartMapping 骨骼到部位的映射（骨骼名称 -> 部位名称）
     * @param boneToUnitConfigMapping 骨骼到BodyUnit配置的映射（骨骼名称 -> 配置）
     * @param boneModelData 骨骼模型数据（用于获取所有骨骼）
     * @return 身体部位映射
     */
    public Map<String, IBodyPart> createBodyPartsWithBoneAssignment(
            long entityId,
            Map<String, IBodyPartConfig> partConfigs,
            Map<String, String> boneToPartMapping,
            Map<String, IBodyUnitConfig> boneToUnitConfigMapping,
            BoneModelData boneModelData) {
        
        // 1. 收集所有骨骼到部位的映射
        Map<String, String> fullBoneToPartMapping = new HashMap<>(boneToPartMapping);
        
        // 2. 获取模型中的所有骨骼
        Set<String> allBones = new HashSet<>(boneModelData.boneStaticDataMap().keySet());
        
        // 3. 找出未分配的骨骼，分配到"main"部位
        Set<String> assignedBones = new HashSet<>(boneToPartMapping.keySet());
        Set<String> unassignedBones = new HashSet<>(allBones);
        unassignedBones.removeAll(assignedBones);
        
        for (String boneName : unassignedBones) {
            fullBoneToPartMapping.put(boneName, DEFAULT_PART_NAME);
        }
        
        // 4. 确保存在"main"部位
        if (!partConfigs.containsKey(DEFAULT_PART_NAME)) {
            throw new IllegalArgumentException("必须存在名为 '" + DEFAULT_PART_NAME + "' 的默认部位");
        }
        
        // 5. 按部位分组骨骼
        Map<String, List<String>> partToBonesMapping = new HashMap<>();
        for (Map.Entry<String, String> entry : fullBoneToPartMapping.entrySet()) {
            String boneName = entry.getKey();
            String partName = entry.getValue();
            partToBonesMapping.computeIfAbsent(partName, k -> new ArrayList<>()).add(boneName);
        }
        
        // 6. 为每个部位创建BodyUnit配置映射
        Map<String, Map<String, IBodyUnitConfig>> partToBoneUnitConfigMapping = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : partToBonesMapping.entrySet()) {
            String partName = entry.getKey();
            List<String> bones = entry.getValue();
            Map<String, IBodyUnitConfig> boneUnitConfigMap = new HashMap<>();
            
            for (String boneName : bones) {
                IBodyUnitConfig unitConfig = boneToUnitConfigMapping.get(boneName);
                if (unitConfig == null) {
                    // 如果没有指定配置，使用默认配置
                    // TODO: 创建默认BodyUnit配置
                    unitConfig = null; // 暂时设为null，需要实现默认配置
                }
                if (unitConfig != null) {
                    boneUnitConfigMap.put(boneName, unitConfig);
                }
            }
            
            partToBoneUnitConfigMapping.put(partName, boneUnitConfigMap);
        }
        
        // 7. 创建所有身体部位
        return createBodyParts(entityId, partConfigs, partToBoneUnitConfigMapping);
    }
}