package com.catoxide.catoxidesbattlerebuild.server.bodypart.config;

import java.util.Collections;
import java.util.List;

/**
 * 标准身体部位配置实现
 * 提供常见的身体部位配置参数
 */
public class StandardBodyPartConfig implements IBodyPartConfig {
    
    private final String configName;
    private final float baseHealth;
    private final float damageMultiplier;
    private final float armorValue;
    private final float armorToughness;
    private final boolean criticalZone;
    private final boolean fatalZone;
    private final List<String> defaultAbilities;
    private final float defaultTransmissionCoefficient;
    
    public StandardBodyPartConfig(String configName,
                                  float baseHealth,
                                  float damageMultiplier,
                                  float armorValue,
                                  float armorToughness,
                                  boolean criticalZone,
                                  boolean fatalZone,
                                  List<String> defaultAbilities,
                                  float defaultTransmissionCoefficient) {
        this.configName = configName;
        this.baseHealth = baseHealth;
        this.damageMultiplier = damageMultiplier;
        this.armorValue = armorValue;
        this.armorToughness = armorToughness;
        this.criticalZone = criticalZone;
        this.fatalZone = fatalZone;
        this.defaultAbilities = defaultAbilities != null ? defaultAbilities : Collections.emptyList();
        this.defaultTransmissionCoefficient = defaultTransmissionCoefficient;
    }
    
    @Override
    public String getConfigName() {
        return configName;
    }
    
    @Override
    public float getBaseHealth() {
        return baseHealth;
    }
    
    @Override
    public float getDamageMultiplier() {
        return damageMultiplier;
    }
    
    @Override
    public float getArmorValue() {
        return armorValue;
    }
    
    @Override
    public float getArmorToughness() {
        return armorToughness;
    }
    
    @Override
    public boolean isCriticalZone() {
        return criticalZone;
    }
    
    @Override
    public boolean isFatalZone() {
        return fatalZone;
    }
    
    @Override
    public List<String> getDefaultAbilities() {
        return defaultAbilities;
    }
    
    @Override
    public float getDefaultTransmissionCoefficient() {
        return defaultTransmissionCoefficient;
    }
}
