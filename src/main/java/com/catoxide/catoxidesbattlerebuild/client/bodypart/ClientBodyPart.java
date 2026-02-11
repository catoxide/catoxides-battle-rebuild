package com.catoxide.catoxidesbattlerebuild.client.bodypart;

import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端身体部位类
 * 负责血量管理和伤害处理
 * 对齐服务端BodyPart
 * 注意：血量由BodyPart负责，BodyUnit只负责伤害传导
 */
public class ClientBodyPart {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientBodyPart.class);
    
    // 部位ID
    private final String partId;
    
    // 部位名称
    private final String partName;
    
    // 当前血量
    private double currentHealth;
    
    // 最大血量
    private final double maxHealth;
    
    // 关联的BodyUnit列表
    private final List<ClientBodyUnit> bodyUnits;
    
    // 是否致命部位
    private final boolean isCritical;

    public ClientBodyPart(String partId, String partName, double maxHealth, boolean isCritical) {
        this.partId = partId;
        this.partName = partName;
        this.maxHealth = maxHealth;
        this.currentHealth = maxHealth;
        this.isCritical = isCritical;
        this.bodyUnits = new ArrayList<>();
        
        LOGGER.info("[ClientBodyPart] Created body part: partId={}, partName={}, maxHealth={}, isCritical={}", 
                partId, partName, maxHealth, isCritical);
    }

    /**
     * 添加BodyUnit
     */
    public void addBodyUnit(ClientBodyUnit bodyUnit) {
        bodyUnits.add(bodyUnit);
        LOGGER.debug("[ClientBodyPart] Added body unit to part: partId={}, boneName={}", partId, bodyUnit.getBoneName());
    }

    /**
     * 接收伤害
     * 根据击中的骨骼找到对应的BodyUnit，计算传导后的伤害
     */
    public void receiveDamage(String boneName, double rawDamage, Vec3 hitPosition) {
        // 找到对应的BodyUnit
        ClientBodyUnit targetUnit = null;
        for (ClientBodyUnit unit : bodyUnits) {
            if (unit.getBoneName().equals(boneName)) {
                targetUnit = unit;
                break;
            }
        }
        
        if (targetUnit == null) {
            LOGGER.warn("[ClientBodyPart] No body unit found for bone: boneName={}, partId={}", boneName, partId);
            return;
        }
        
        // 通过BodyUnit计算传导后的伤害
        double conductedDamage = targetUnit.receiveDamage(rawDamage, hitPosition);
        
        // 应用伤害到BodyPart的血量
        applyDamage(conductedDamage);
        
        LOGGER.info("[ClientBodyPart] Damage received: partId={}, boneName={}, rawDamage={}, conductedDamage={}, remainingHealth={}", 
                partId, boneName, rawDamage, conductedDamage, currentHealth);
    }

    /**
     * 应用伤害
     */
    private void applyDamage(double damage) {
        currentHealth = Math.max(0, currentHealth - damage);
        LOGGER.debug("[ClientBodyPart] Applied damage: partId={}, damage={}, newHealth={}", partId, damage, currentHealth);
    }

    /**
     * 治疗身体部位
     */
    public void heal(double healAmount) {
        currentHealth = Math.min(maxHealth, currentHealth + healAmount);
        LOGGER.debug("[ClientBodyPart] Healed: partId={}, healAmount={}, newHealth={}", partId, healAmount, currentHealth);
    }

    /**
     * 检查部位是否存活
     */
    public boolean isAlive() {
        return currentHealth > 0;
    }

    /**
     * 检查部位是否被破坏
     */
    public boolean isDestroyed() {
        return currentHealth <= 0;
    }

    /**
     * 检查是否为致命部位
     */
    public boolean isCriticalPart() {
        return isCritical;
    }

    /**
     * 获取部位ID
     */
    public String getPartId() {
        return partId;
    }

    /**
     * 获取部位名称
     */
    public String getPartName() {
        return partName;
    }

    /**
     * 获取当前血量
     */
    public double getCurrentHealth() {
        return currentHealth;
    }

    /**
     * 获取最大血量
     */
    public double getMaxHealth() {
        return maxHealth;
    }

    /**
     * 获取血量百分比
     */
    public double getHealthPercentage() {
        return (currentHealth / maxHealth) * 100.0;
    }

    /**
     * 获取所有BodyUnit
     */
    public List<ClientBodyUnit> getBodyUnits() {
        return new ArrayList<>(bodyUnits);
    }

    /**
     * 根据骨骼名称获取BodyUnit
     */
    public ClientBodyUnit getBodyUnitByBone(String boneName) {
        for (ClientBodyUnit unit : bodyUnits) {
            if (unit.getBoneName().equals(boneName)) {
                return unit;
            }
        }
        LOGGER.warn("[ClientBodyPart] Body unit not found for bone: boneName={}, partId={}", boneName, partId);
        return null;
    }
}
