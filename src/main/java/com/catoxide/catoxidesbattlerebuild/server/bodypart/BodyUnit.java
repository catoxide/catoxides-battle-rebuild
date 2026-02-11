package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Collections;
import net.minecraft.resources.ResourceLocation;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyUnitConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.IBodyUnitAbility;

/**
 * BodyUnit实现类
 * BodyUnit作为血量单元，负责结算伤害比例和部位致命效果
 * 每个BodyUnit关联一个Bone（1对1关系）
 */
public class BodyUnit implements IBodyUnit {
    
    // 唯一标识符
    private final UUID id;
    
    // BodyUnit名称
    private final String name;
    
    // 关联的实体ID
    private final long entityId;
    
    // 关联的骨骼名称（1对1关系）
    private final String boneName;
    
    // 传导系数（伤害从骨骼传导到BodyUnit的比例）
    private float transmissionCoefficient;
    
    // 护甲值
    private float armorValue;
    
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
    private final List<IBodyUnitAbility> abilities;
    
    // 配置
    private IBodyUnitConfig config;
    
    // 关联的BodyPart
    private IBodyPart bodyPart;
    
    /**
     * 设置关联的BodyPart
     */
    public void setBodyPart(IBodyPart bodyPart) {
        this.bodyPart = bodyPart;
    }
    
    /**
     * 获取关联的BodyPart
     */
    public IBodyPart getBodyPart() {
        return bodyPart;
    }
    
    /**
     * 构造函数（基础构造）
     */
    public BodyUnit(long entityId, String name, String boneName) {
        this.id = UUID.randomUUID();
        this.entityId = entityId;
        this.name = name;
        this.boneName = boneName;
        this.transmissionCoefficient = 1.0f;
        this.armorValue = 0.0f;
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
     * 构造函数（指定传导系数）
     */
    public BodyUnit(long entityId, String name, String boneName, float transmissionCoefficient) {
        this(entityId, name, boneName);
        this.transmissionCoefficient = transmissionCoefficient;
    }
    
    /**
     * 从配置创建BodyUnit
     */
    public BodyUnit(long entityId, String boneName, IBodyUnitConfig config) {
        this(entityId, config.getBodyUnitName(), boneName);
        this.config = config;
        this.transmissionCoefficient = config.getTransmissionCoefficient();
        this.armorValue = config.getArmorValue();
        this.isCritical = config.isCritical();
        this.collisionTag = config.getCollisionTag();
        this.entityTransmissionCoefficient = config.getEntityTransmissionCoefficient();
        this.specialEffects = new ArrayList<>(config.getSpecialEffects());
        this.hitSound = config.getHitSound();
        this.fatal = config.isFatal();
        this.fatalThreshold = config.getFatalThreshold();
        
        // 初始化能力
        // TODO: 从配置创建能力实例
        for (String abilityClassName : config.getAbilityClassNames()) {
            // abilityClassName = "com.example.abilities.FireAbility"
            // 通过反射创建能力实例
            try {
                Class<?> clazz = Class.forName(abilityClassName);
                IBodyUnitAbility ability = (IBodyUnitAbility) clazz.getDeclaredConstructor().newInstance();
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
    
    // ==================== 骨骼管理（1对1关系） ====================
    
    /**
     * 获取关联的骨骼名称
     */
    public String getBoneName() {
        return boneName;
    }
    
    /**
     * 获取传导系数
     */
    public float getTransmissionCoefficient() {
        return transmissionCoefficient;
    }

    @Override
    public boolean matchesBone(String boneName) {
        return this.boneName.equals(boneName);
    }

    /**
     * 接收伤害（从关联的骨骼）
     * @param boneName 来源骨骼
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 实际受到的伤害
     */
    @Override
    public float receiveDamage(String boneName, float rawDamage, String damageType) {
        if (!active) {
            return 0.0f;
        }
        
        // 验证骨骼名称是否匹配
        if (!this.boneName.equals(boneName)) {
            // 骨骼不匹配，不处理伤害
            return 0.0f;
        }
        
        // 计算传导后的伤害
        float transmittedDamage = rawDamage * transmissionCoefficient;
        
        // 触发能力的前置钩子
        for (IBodyUnitAbility ability : abilities) {
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
        
        // TODO: 实现致命检查逻辑
        // 由于血量管理移至BodyPart，这里需要调整致命检查逻辑
        return false;
    }
    
    @Override
    public void triggerFatalEffect() {
        // 触发能力的致命效果钩子
        for (IBodyUnitAbility ability : abilities) {
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
        for (IBodyUnitAbility ability : abilities) {
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
    public boolean isAlive() {
        // 调用BodyPart的isAlive方法
        if (bodyPart != null) {
            return bodyPart.isAlive();
        }
        // 如果BodyPart未设置，默认返回true
        return true;
    }
    
    @Override
    public void reset() {
        this.hitCount = 0;
        this.lastHitTime = 0L;
        
        // 重置能力
        for (IBodyUnitAbility ability : abilities) {
            ability.reset();
        }
    }
    
    // ==================== 能力系统 ====================
    
    @Override
    public List<IBodyUnitAbility> getAbilities() {
        return Collections.unmodifiableList(abilities);
    }
    
    @Override
    public void addAbility(IBodyUnitAbility ability) {
        if (!abilities.contains(ability)) {
            ability.initialize(this);
            abilities.add(ability);
        }
    }
    
    @Override
    public void removeAbility(IBodyUnitAbility ability) {
        abilities.remove(ability);
    }
    
    /**
     * Tick更新
     */
    public void tick(int deltaTick) {
        // 更新能力
        for (IBodyUnitAbility ability : abilities) {
            if (ability.isActive()) {
                ability.onTick(deltaTick);
            }
        }
    }
}