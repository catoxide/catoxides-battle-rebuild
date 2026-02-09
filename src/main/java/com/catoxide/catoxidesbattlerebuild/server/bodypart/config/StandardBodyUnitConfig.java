package com.catoxide.catoxidesbattlerebuild.server.bodypart.config;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.resources.ResourceLocation;

/**
 * 标准BodyUnit配置实现
 * 提供基本的BodyUnit配置参数
 * 
 * 新架构：BodyUnit与Bone是1对1关系
 * 每个BodyUnit关联一个特定的骨骼
 */
public class StandardBodyUnitConfig implements IBodyUnitConfig {
    
    private final String configName;
    private final String bodyUnitName;
    private final String boneName;
    private final float maxHealth;
    private final float armorValue;
    private final boolean critical;
    private final String collisionTag;
    private final float transmissionCoefficient;
    private final List<String> specialEffects;
    private final ResourceLocation hitSound;
    private final List<String> abilityClassNames;
    private final float entityTransmissionCoefficient;
    private final boolean fatal;
    private final float fatalThreshold;
    
    private StandardBodyUnitConfig(Builder builder) {
        this.configName = builder.configName;
        this.bodyUnitName = builder.bodyUnitName;
        this.boneName = builder.boneName;
        this.maxHealth = builder.maxHealth;
        this.armorValue = builder.armorValue;
        this.critical = builder.critical;
        this.collisionTag = builder.collisionTag;
        this.transmissionCoefficient = builder.transmissionCoefficient;
        this.specialEffects = Collections.unmodifiableList(new ArrayList<>(builder.specialEffects));
        this.hitSound = builder.hitSound;
        this.abilityClassNames = Collections.unmodifiableList(new ArrayList<>(builder.abilityClassNames));
        this.entityTransmissionCoefficient = builder.entityTransmissionCoefficient;
        this.fatal = builder.fatal;
        this.fatalThreshold = builder.fatalThreshold;
    }
    
    @Override
    public String getConfigName() {
        return configName;
    }
    
    @Override
    public String getBodyUnitName() {
        return bodyUnitName;
    }
    
    @Override
    public String getBoneName() {
        return boneName;
    }
    
    @Override
    public float getMaxHealth() {
        return maxHealth;
    }
    
    @Override
    public float getArmorValue() {
        return armorValue;
    }
    
    @Override
    public boolean isCritical() {
        return critical;
    }
    
    @Override
    public String getCollisionTag() {
        return collisionTag;
    }
    
    @Override
    public float getTransmissionCoefficient() {
        return transmissionCoefficient;
    }
    
    @Override
    public List<String> getSpecialEffects() {
        return specialEffects;
    }
    
    @Override
    public ResourceLocation getHitSound() {
        return hitSound;
    }
    
    @Override
    public List<String> getAbilityClassNames() {
        return abilityClassNames;
    }
    
    @Override
    public float getEntityTransmissionCoefficient() {
        return entityTransmissionCoefficient;
    }
    
    @Override
    public boolean isFatal() {
        return fatal;
    }
    
    @Override
    public float getFatalThreshold() {
        return fatalThreshold;
    }
    
    /**
     * 构建器模式
     */
    public static class Builder {
        private String configName;
        private String bodyUnitName;
        private String boneName;
        private float maxHealth = 100.0f;
        private float armorValue = 0.0f;
        private boolean critical = false;
        private String collisionTag = "default";
        private float transmissionCoefficient = 1.0f;
        private List<String> specialEffects = new ArrayList<>();
        private ResourceLocation hitSound = null;
        private List<String> abilityClassNames = new ArrayList<>();
        private float entityTransmissionCoefficient = 0.5f;
        private boolean fatal = false;
        private float fatalThreshold = 0.0f;
        
        public Builder(String configName, String bodyUnitName, String boneName) {
            this.configName = configName;
            this.bodyUnitName = bodyUnitName;
            this.boneName = boneName;
        }
        
        public Builder maxHealth(float maxHealth) {
            this.maxHealth = maxHealth;
            return this;
        }
        
        public Builder armorValue(float armorValue) {
            this.armorValue = armorValue;
            return this;
        }
        
        public Builder critical(boolean critical) {
            this.critical = critical;
            return this;
        }
        
        public Builder collisionTag(String collisionTag) {
            this.collisionTag = collisionTag;
            return this;
        }
        
        public Builder transmissionCoefficient(float coefficient) {
            this.transmissionCoefficient = coefficient;
            return this;
        }
        
        public Builder addSpecialEffect(String effect) {
            this.specialEffects.add(effect);
            return this;
        }
        
        public Builder specialEffects(List<String> effects) {
            this.specialEffects = new ArrayList<>(effects);
            return this;
        }
        
        public Builder hitSound(ResourceLocation hitSound) {
            this.hitSound = hitSound;
            return this;
        }
        
        public Builder addAbility(String abilityClassName) {
            this.abilityClassNames.add(abilityClassName);
            return this;
        }
        
        public Builder abilities(List<String> abilityClassNames) {
            this.abilityClassNames = new ArrayList<>(abilityClassNames);
            return this;
        }
        
        public Builder entityTransmissionCoefficient(float coefficient) {
            this.entityTransmissionCoefficient = coefficient;
            return this;
        }
        
        public Builder fatal(boolean fatal) {
            this.fatal = fatal;
            return this;
        }
        
        public Builder fatalThreshold(float threshold) {
            this.fatalThreshold = threshold;
            return this;
        }
        
        public StandardBodyUnitConfig build() {
            return new StandardBodyUnitConfig(this);
        }
    }
}