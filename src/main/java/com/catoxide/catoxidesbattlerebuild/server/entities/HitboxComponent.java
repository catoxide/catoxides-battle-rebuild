package com.catoxide.catoxidesbattlerebuild.server.entities;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * 受击盒组件
 * 提供游戏性相关的受击盒配置和状态管理
 * 补充BoneCollection和CubeCollection缺少的游戏性功能
 */
public class HitboxComponent {
    
    // 实体ID
    private final long entityId;
    
    // 骨骼名称
    private final String boneName;
    
    // 伤害倍率
    private float damageMultiplier;
    
    // 是否为暴击区域
    private boolean isCritical;
    
    // 护甲值
    private float armorValue;
    
    // 击中音效
    private ResourceLocation hitSound;
    
    // 是否激活
    private boolean active;
    
    // 最近击中时间戳
    private long lastHitTime;
    
    // 击中次数统计
    private int hitCount;
    
    // 碰撞标签（如头部、躯干、四肢等）
    private String collisionTag;
    
    // 特殊效果（如燃烧、冰冻等）
    private List<String> specialEffects;
    
    // 静态配置（不同实体类型共享）
    private static final ConcurrentHashMap<String, HitboxConfigTemplate> configTemplates = new ConcurrentHashMap<>();
    
    /**
     * 构造函数
     */
    public HitboxComponent(long entityId, String boneName) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.damageMultiplier = 1.0f;
        this.isCritical = false;
        this.armorValue = 0.0f;
        this.active = true;
        this.lastHitTime = 0L;
        this.hitCount = 0;
        this.collisionTag = "default";
    }
    
    /**
     * 从模板创建受击盒组件
     */
    public static HitboxComponent fromTemplate(long entityId, String boneName, String templateName) {
        HitboxComponent component = new HitboxComponent(entityId, boneName);
        HitboxConfigTemplate template = configTemplates.get(templateName);
        if (template != null) {
            component.setDamageMultiplier(template.getDefaultDamageMultiplier());
            component.setCritical(template.isDefaultCritical());
            component.setArmorValue(template.getDefaultArmorValue());
            component.setHitSound(template.getDefaultHitSound());
            component.setCollisionTag(template.getDefaultCollisionTag());
        }
        return component;
    }
    
    /**
     * 记录击中事件
     */
    public void recordHit(float damage) {
        this.hitCount++;
        this.lastHitTime = System.currentTimeMillis();
        // 触发击中效果
        triggerHitEffects(damage);
    }
    
    /**
     * 触发击中效果
     */
    private void triggerHitEffects(float damage) {
        // 在这里可以添加音效播放、粒子效果等
        if (hitSound != null) {
            // 播放音效的逻辑
            playHitSound(hitSound, damage);
        }
        
        // 应用特殊效果
        if (specialEffects != null) {
            for (String effect : specialEffects) {
                applySpecialEffect(effect, damage);
            }
        }
    }
    
    /**
     * 播放击中音效
     */
    private void playHitSound(ResourceLocation sound, float damage) {
        // 实际游戏中会调用Minecraft的音效系统
        // 这里只是占位符
    }
    
    /**
     * 应用特殊效果
     */
    private void applySpecialEffect(String effect, float damage) {
        // 根据效果类型应用不同的特殊效果
        // 这里是占位符
    }
    
    /**
     * 计算实际受到的伤害
     */
    public float calculateReceivedDamage(float incomingDamage) {
        float reducedDamage = incomingDamage * damageMultiplier;
        float armorReduction = Math.min(reducedDamage * armorValue, reducedDamage); // 防止护甲超过伤害
        return reducedDamage - armorReduction;
    }
    
    /**
     * 检查是否为有效击中（如果组件处于激活状态且未过期）
     */
    public boolean isValidHit() {
        return active;
    }
    
    /**
     * 注册受击盒配置模板
     */
    public static void registerConfigTemplate(String templateName, HitboxConfigTemplate template) {
        configTemplates.put(templateName, template);
    }
    
    /**
     * 获取配置模板
     */
    public static HitboxConfigTemplate getConfigTemplate(String templateName) {
        return configTemplates.get(templateName);
    }
    
    // Getter和Setter方法
    
    public long getEntityId() {
        return entityId;
    }
    
    public String getBoneName() {
        return boneName;
    }
    
    public float getDamageMultiplier() {
        return damageMultiplier;
    }
    
    public void setDamageMultiplier(float damageMultiplier) {
        this.damageMultiplier = damageMultiplier;
    }
    
    public boolean isCritical() {
        return isCritical;
    }
    
    public void setCritical(boolean critical) {
        isCritical = critical;
    }
    
    public float getArmorValue() {
        return armorValue;
    }
    
    public void setArmorValue(float armorValue) {
        this.armorValue = armorValue;
    }
    
    public ResourceLocation getHitSound() {
        return hitSound;
    }
    
    public void setHitSound(ResourceLocation hitSound) {
        this.hitSound = hitSound;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public void setActive(boolean active) {
        this.active = active;
    }
    
    public long getLastHitTime() {
        return lastHitTime;
    }
    
    public int getHitCount() {
        return hitCount;
    }
    
    public String getCollisionTag() {
        return collisionTag;
    }
    
    public void setCollisionTag(String collisionTag) {
        this.collisionTag = collisionTag;
    }
    
    public List<String> getSpecialEffects() {
        return specialEffects;
    }
    
    public void setSpecialEffects(List<String> specialEffects) {
        this.specialEffects = specialEffects;
    }
}

/**
 * 受击盒配置模板
 * 用于定义不同类型实体的受击盒配置
 */
class HitboxConfigTemplate {
    private final String templateName;
    private final float defaultDamageMultiplier;
    private final boolean defaultCritical;
    private final float defaultArmorValue;
    private final ResourceLocation defaultHitSound;
    private final String defaultCollisionTag;
    private final List<String> defaultSpecialEffects;
    
    public HitboxConfigTemplate(String templateName, 
                                float defaultDamageMultiplier,
                                boolean defaultCritical,
                                float defaultArmorValue,
                                ResourceLocation defaultHitSound,
                                String defaultCollisionTag,
                                List<String> defaultSpecialEffects) {
        this.templateName = templateName;
        this.defaultDamageMultiplier = defaultDamageMultiplier;
        this.defaultCritical = defaultCritical;
        this.defaultArmorValue = defaultArmorValue;
        this.defaultHitSound = defaultHitSound;
        this.defaultCollisionTag = defaultCollisionTag;
        this.defaultSpecialEffects = defaultSpecialEffects;
    }
    
    // Getter方法
    public String getTemplateName() { return templateName; }
    public float getDefaultDamageMultiplier() { return defaultDamageMultiplier; }
    public boolean isDefaultCritical() { return defaultCritical; }
    public float getDefaultArmorValue() { return defaultArmorValue; }
    public ResourceLocation getDefaultHitSound() { return defaultHitSound; }
    public String getDefaultCollisionTag() { return defaultCollisionTag; }
    public List<String> getDefaultSpecialEffects() { return defaultSpecialEffects; }
}