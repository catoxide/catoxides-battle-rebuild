package com.catoxide.catoxidesbattlerebuild.armor;



import com.catoxide.catoxidesbattlerebuild.damage.PenetrationLevel;
import net.minecraft.world.entity.LivingEntity;
import java.util.HashMap;
import java.util.Map;

public class EntityArmorSystem {
    private final LivingEntity entity;
    private final Map<String, ArmorClass> armorSlots;
    private ArmorClass overallArmorClass;

    public EntityArmorSystem(LivingEntity entity) {
        this.entity = entity;
        this.armorSlots = new HashMap<>();
        this.overallArmorClass = ArmorClass.NONE;
        initializeDefaultSlots();
    }

    private void initializeDefaultSlots() {
        // 定义默认的护甲槽位
        armorSlots.put("head", ArmorClass.NONE);
        armorSlots.put("chest", ArmorClass.NONE);
        armorSlots.put("legs", ArmorClass.NONE);
        armorSlots.put("feet", ArmorClass.NONE);
        updateOverallArmorClass();
    }

    public static EntityArmorSystem get(LivingEntity target) {
        // TODO: 从实体数据中获取或创建护甲系统
        return new EntityArmorSystem(target);
    }

    // 设置特定槽位的护甲等级
    public void setArmorClass(String slot, ArmorClass armorClass) {
        armorSlots.put(slot, armorClass);
        updateOverallArmorClass();
    }

    // 获取实体整体护甲等级（取最高值）
    public ArmorClass getOverallArmorClass() {
        return overallArmorClass;
    }

    // 获取特定部位的护甲等级
    public ArmorClass getArmorClass(String slot) {
        return armorSlots.getOrDefault(slot, ArmorClass.NONE);
    }

    // 检查实体是否有护甲
    public boolean hasArmor() {
        return overallArmorClass != ArmorClass.NONE;
    }

    // 更新整体护甲等级（基于所有槽位）
    private void updateOverallArmorClass() {
        ArmorClass highest = ArmorClass.NONE;
        for (ArmorClass armorClass : armorSlots.values()) {
            if (armorClass.getLevel() > highest.getLevel()) {
                highest = armorClass;
            }
        }
        overallArmorClass = highest;
    }

    // 计算对特定穿透等级的伤害减免
    public float calculateDamageReduction(PenetrationLevel penetration) {
        return overallArmorClass.getDamageReduction() *
                (1 - penetration.getPenetrationEffectiveness(overallArmorClass));
    }
}
