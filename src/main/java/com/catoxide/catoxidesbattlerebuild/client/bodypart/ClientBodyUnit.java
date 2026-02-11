package com.catoxide.catoxidesbattlerebuild.client.bodypart;

import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端血量单元类
 * 负责伤害结算和部位致命效果
 * 对齐服务端BodyUnit
 */
public class ClientBodyUnit {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientBodyUnit.class);
    
    // 关联的骨骼名称
    private final String boneName;
    
    // 护甲值
    private double armor;
    
    // 传导系数（伤害传导到BodyPart的比例）
    private double conductionFactor;
    
    // 是否致命部位
    private final boolean isCritical;

    public ClientBodyUnit(String boneName, double armor, double conductionFactor, boolean isCritical) {
        this.boneName = boneName;
        this.armor = armor;
        this.conductionFactor = conductionFactor;
        this.isCritical = isCritical;
        
        LOGGER.debug("[ClientBodyUnit] Created body unit: boneName={}, armor={}, conductionFactor={}, isCritical={}", 
                boneName, armor, conductionFactor, isCritical);
    }

    /**
     * 接收伤害并计算传导到BodyPart的伤害
     */
    public double receiveDamage(double rawDamage, Vec3 hitPosition) {
        // 计算护甲减免
        double armorReduction = calculateArmorReduction(rawDamage);
        double damageAfterArmor = rawDamage - armorReduction;
        
        // 计算传导到BodyPart的伤害
        double conductedDamage = damageAfterArmor * conductionFactor;
        
        LOGGER.debug("[ClientBodyUnit] Received damage: boneName={}, rawDamage={}, armorReduction={}, conductedDamage={}, hitPosition={}", 
                boneName, rawDamage, armorReduction, conductedDamage, hitPosition);
        
        return conductedDamage;
    }

    /**
     * 计算护甲减免
     */
    private double calculateArmorReduction(double rawDamage) {
        // 简化的护甲计算公式
        double reduction = rawDamage * (armor / (armor + 100.0));
        LOGGER.debug("[ClientBodyUnit] Calculated armor reduction: boneName={}, armor={}, rawDamage={}, reduction={}", 
                boneName, armor, rawDamage, reduction);
        return reduction;
    }

    /**
     * 检查是否为致命部位
     */
    public boolean isCriticalPart() {
        return isCritical;
    }

    /**
     * 获取骨骼名称
     */
    public String getBoneName() {
        return boneName;
    }

    /**
     * 获取护甲值
     */
    public double getArmor() {
        return armor;
    }

    /**
     * 设置护甲值
     */
    public void setArmor(double armor) {
        this.armor = armor;
        LOGGER.debug("[ClientBodyUnit] Set armor: boneName={}, newArmor={}", boneName, armor);
    }

    /**
     * 获取传导系数
     */
    public double getConductionFactor() {
        return conductionFactor;
    }

    /**
     * 设置传导系数
     */
    public void setConductionFactor(double conductionFactor) {
        this.conductionFactor = conductionFactor;
        LOGGER.debug("[ClientBodyUnit] Set conduction factor: boneName={}, newFactor={}", boneName, conductionFactor);
    }
}
