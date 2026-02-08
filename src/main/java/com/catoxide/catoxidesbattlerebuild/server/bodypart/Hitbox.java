package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Collections;
import net.minecraft.resources.ResourceLocation;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IHitboxConfig;

/**
 * Hitbox实现类
 * Hitbox作为血量单元，负责结算伤害比例和部位致命效果
 */
public class Hitbox implements IHitbox {
    
    // 唯一标识符
    private final UUID id;
    
    // Hitbox名称
    private final String name;
    
    // 关联的实体ID
    private final long entityId;
    
    // 血量
    private float currentHealth;
    private float maxHealth;
    
    // 护甲值
    private float armorValue;
    
    // 骨骼及其传导系数
    private final Map<String, Float> boneTransmissionCoefficients;
    
    // 默认传导系数
    private float defaultTransmissionCoefficient;
    
    // 实体传导系数（传导到实体血量的比例）
    private float entityTransmissionCoefficient;
    
    // 区域标记
    private boolean isCritical;
    private String collisionTag;
    
    // 特殊效果
    private List<String> specialEffects;
    
    // 击中音效
    private ResourceLocation hitSound;
    
    // 击中统计
    private int hitCount;
    private long lastHitTime;
    
    // 激活状态
    private boolean active;
    
    // 致命相关
    private boolean fatal;
    private float fatalThreshold;
    
    // 能力列表
    private final List<IHitboxAbility> abilities;
    
    // 配置
    private IHitboxConfig config;
    
    /**
     * 构造函数
     */
    public Hitbox(long entityId, String name) {
        this.id = UUID.randomUUID();
        this.entityId = entityId;
        this.name = name;
        this.currentHealth = 100.0f;
        this.maxHealth = 100.0f;
        this.armorValue = 0.0f;
        this.boneTransmissionCoefficients = new HashMap<>();
        this.defaultTransmissionCoefficient = 1.0f;
        this.entityTransmissionCoefficient = 0.5f;
        this.isCritical = false;
        this.collisionTag = "default";
        this.specialEffects = new ArrayList<>();
        this.hitSound = null;
        this.hitCount = 0;
        this.lastHitTime = 0L;
        this.active = true;
        this.fatal = false;
        this.fatalThreshold = 0.0f;
        this.abilities = new ArrayList<>();
    }
    
    /**
     * 从配置创建Hitbox
     */
    public Hitbox(long entityId, IHitboxConfig config) {
        this(entityId, config.getHitboxName());
        this.config = config;
        this.maxHealth = config.getMaxHealth();
        this.currentHealth = this.maxHealth;
        this.armorValue = config.getArmorValue();
        this.isCritical = config.isCritical();
        this.collisionTag = config.getCollisionTag();
        this.defaultTransmissionCoefficient = config.getDefaultTransmissionCoefficient();
        this.entityTransmissionCoefficient = config.getEntityTransmissionCoefficient();
        this.specialEffects = new ArrayList<>(config.getSpecialEffects());
        this.hitSound = config.getHitSound();
        this.fatal = config.isFatal();
        this.fatalThreshold = config.getFatalThreshold();
        
        // 添加默认骨骼传导系数
        this.boneTransmissionCoefficients.putAll(config.getDefaultBoneTransmissions());
        
        // 初始化能力
        // TODO: 从配置创建能力实例
        for (String abilityClassName : config.getAbilityClassNames()) {
            // abilityClassName = "com.example.abilities.FireAbility"
            // 通过反射创建能力实例
            try {
                Class<?> clazz = Class.forName(abilityClassName);
                IHitboxAbility ability = (IHitboxAbility) clazz.getDeclaredConstructor().newInstance();
                ability.initialize(this);
                this.abilities.add(ability);
            } catch (Exception e) {
                // TODO: 记录错误日志
                e.printStackTrace();
            }
        }
    }
    
    // ==================== ID和名称 ====================
    
    @Override
    public UUID getId() {
        return id;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public long getEntityId() {
        return entityId;
    }
    
    // ==================== 血量管理 ====================
    
    @Override
    public float getCurrentHealth() {
        return currentHealth;
    }
    
    @Override
    public float getMaxHealth() {
        return maxHealth;
    }
    
    @Override
    public void setCurrentHealth(float health) {
        this.currentHealth = Math.max(0, Math.min(health, maxHealth));
    }
    
    @Override
    public void setMaxHealth(float maxHealth) {
        this.maxHealth = maxHealth;
        if (this.currentHealth > maxHealth) {
            this.currentHealth = maxHealth;
        }
    }
    
    @Override
    public boolean isAlive() {
        return currentHealth > 0;
    }
    
    // ==================== 骨骼管理 ====================
    
    @Override
    public void addBone(String boneName, float transmissionCoefficient) {
        this.boneTransmissionCoefficients.put(boneName, 
            Math.max(0.0f, Math.min(1.0f, transmissionCoefficient)));
    }
    
    @Override
    public void removeBone(String boneName) {
        this.boneTransmissionCoefficients.remove(boneName);
    }
    
    @Override
    public Map<String, Float> getBones() {
        return Collections.unmodifiableMap(boneTransmissionCoefficients);
    }
    
    @Override
    public float getTransmissionCoefficient(String boneName) {
        return boneTransmissionCoefficients.getOrDefault(boneName, defaultTransmissionCoefficient);
    }
    
    @Override
    public boolean hasBone(String boneName) {
        return boneTransmissionCoefficients.containsKey(boneName);
    }
    
    // ==================== 伤害处理 ====================
    
    @Override
    public float receiveDamage(String boneName, float rawDamage, String damageType) {
        if (!active) {
            return 0.0f;
        }
        
        // 获取传导系数
        float transmission = getTransmissionCoefficient(boneName);
        
        // 计算传导后的伤害
        float transmittedDamage = rawDamage * transmission;
        
        // 触发能力的前置钩子
        for (IHitboxAbility ability : abilities) {
            if (ability.isActive()) {
                float modifiedDamage = ability.onDamagePre(boneName, transmittedDamage, damageType);
                if (modifiedDamage < 0) {
                    // 取消伤害
                    return 0.0f;
                }
                transmittedDamage = modifiedDamage;
            }
        }
        
        // 计算护甲减免
        float armorReduction = calculateArmorReduction(transmittedDamage);
        float actualDamage = transmittedDamage - armorReduction;
        
        // 扣除血量
        float oldHealth = currentHealth;
        currentHealth = Math.max(0, currentHealth - actualDamage);
        
        // 触发能力的后置钩子
        for (IHitboxAbility ability : abilities) {
            if (ability.isActive()) {
                ability.onDamagePost(boneName, transmittedDamage, actualDamage, damageType);
            }
        }
        
        // 记录击中
        recordHit(actualDamage);
        
        // 检查致命
        if (isFatal()) {
            triggerFatalEffect();
        }
        
        return actualDamage;
    }
    
    @Override
    public float calculateEntityTransmission(float damage) {
        return damage * entityTransmissionCoefficient;
    }
    
    @Override
    public boolean isFatal() {
        if (!fatal) {
            return false;
        }
        
        // 检查血量是否低于致命阈值
        if (currentHealth <= fatalThreshold) {
            // 触发能力的致命检查钩子
            for (IHitboxAbility ability : abilities) {
                if (ability.isActive()) {
                    if (!ability.onFatalCheck(currentHealth, maxHealth - currentHealth)) {
                        return false;
                    }
                }
            }
            return true;
        }
        
        return false;
    }
    
    @Override
    public void triggerFatalEffect() {
        // 触发能力的致命效果钩子
        for (IHitboxAbility ability : abilities) {
            if (ability.isActive()) {
                ability.onFatalEffect();
            }
        }
        
        // TODO: 实现致命效果，如头部致命导致死亡，腿部致命导致减速等
        // 可以根据collisionTag实现不同的致命效果
    }
    
    // ==================== 防护系统 ====================
    
    @Override
    public float getArmorValue() {
        return armorValue;
    }
    
    @Override
    public void setArmorValue(float armorValue) {
        this.armorValue = Math.max(0.0f, armorValue);
    }
    
    @Override
    public float calculateArmorReduction(float damage) {
        // 护甲减免公式：伤害 * 护甲值，但不超过伤害本身
        return Math.min(damage * armorValue, damage);
    }
    
    // ==================== 区域标记 ====================
    
    @Override
    public boolean isCritical() {
        return isCritical;
    }
    
    @Override
    public void setCritical(boolean critical) {
        this.isCritical = critical;
    }
    
    @Override
    public String getCollisionTag() {
        return collisionTag;
    }
    
    @Override
    public void setCollisionTag(String tag) {
        this.collisionTag = tag;
    }
    
    // ==================== 特殊效果 ====================
    
    @Override
    public List<String> getSpecialEffects() {
        return Collections.unmodifiableList(specialEffects);
    }
    
    @Override
    public void addSpecialEffect(String effect) {
        if (!specialEffects.contains(effect)) {
            specialEffects.add(effect);
        }
    }
    
    @Override
    public void removeSpecialEffect(String effect) {
        specialEffects.remove(effect);
    }
    
    // ==================== 击中事件 ====================
    
    @Override
    public void recordHit(float damage) {
        hitCount++;
        lastHitTime = System.currentTimeMillis();
        
        // 触发能力的击中钩子
        for (IHitboxAbility ability : abilities) {
            if (ability.isActive()) {
                ability.onHit(damage);
            }
        }
        
        // 播放击中音效
        if (hitSound != null) {
            // TODO: 播放音效
            playHitSound(hitSound, damage);
        }
        
        // 应用特殊效果
        for (String effect : specialEffects) {
            applySpecialEffect(effect, damage);
        }
    }
    
    @Override
    public int getHitCount() {
        return hitCount;
    }
    
    @Override
    public long getLastHitTime() {
        return lastHitTime;
    }
    
    /**
     * 播放击中音效
     */
    private void playHitSound(ResourceLocation sound, float damage) {
        // TODO: 调用Minecraft音效系统
    }
    
    /**
     * 应用特殊效果
     */
    private void applySpecialEffect(String effect, float damage) {
        // TODO: 根据效果类型应用特殊效果
        // 例如：燃烧、冰冻、中毒等
    }
    
    // ==================== 状态管理 ====================
    
    @Override
    public boolean isActive() {
        return active;
    }
    
    @Override
    public void setActive(boolean active) {
        this.active = active;
    }
    
    @Override
    public void reset() {
        this.currentHealth = maxHealth;
        this.hitCount = 0;
        this.lastHitTime = 0L;
        
        // 重置能力
        for (IHitboxAbility ability : abilities) {
            ability.reset();
        }
    }
    
    // ==================== 能力系统 ====================
    
    @Override
    public List<IHitboxAbility> getAbilities() {
        return Collections.unmodifiableList(abilities);
    }
    
    @Override
    public void addAbility(IHitboxAbility ability) {
        if (!abilities.contains(ability)) {
            ability.initialize(this);
            abilities.add(ability);
        }
    }
    
    @Override
    public void removeAbility(IHitboxAbility ability) {
        abilities.remove(ability);
    }
    
    /**
     * Tick更新
     */
    public void tick(int deltaTick) {
        // 更新能力
        for (IHitboxAbility ability : abilities) {
            if (ability.isActive()) {
                ability.onTick(deltaTick);
            }
        }
    }
}
