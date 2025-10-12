package com.catoxide.catoxidesbattlerebuild.damage;

import com.catoxide.catoxidesbattlerebuild.armor.ArmorClass;

import java.util.HashMap;
import java.util.Map;

public class DamageComponent {
    private final DamageType type;
    private final float baseAmount;
    private final Map<String, Float> modifiers;
    private final PenetrationLevel penetration;

    // ✅ 基础构造函数（使用默认解析逻辑）
    public DamageComponent(DamageType type, float baseAmount) {
        this(type, baseAmount, null, null);
    }

    // ✅ 显式指定穿透等级
    public DamageComponent(DamageType type, float baseAmount, PenetrationLevel explicitPenetration) {
        this(type, baseAmount, explicitPenetration, null);
    }

    // ✅ 完整构造函数（支持所有覆盖选项）
    public DamageComponent(DamageType type, float baseAmount,
                           PenetrationLevel explicitPenetration,
                           PenetrationLevel weaponOverride) {
        this.type = type;
        this.baseAmount = baseAmount;
        this.penetration = PenetrationResolver.resolvePenetration(type, explicitPenetration, weaponOverride);
        this.modifiers = new HashMap<>();
    }

    // 添加特殊修正
    public void addModifier(String key, float value) {
        modifiers.put(key, value);
    }

    public float getModifier(String key) {
        return modifiers.getOrDefault(key, 0f);
    }

    // Getter方法
    public DamageType getType() { return type; }
    public float getBaseAmount() { return baseAmount; }
    public Map<String, Float> getModifiers() { return modifiers; }
    public PenetrationLevel getPenetrationLevel() { return penetration; }

    // 伤害计算方法（带空值检查）
    public float calculateEffectiveDamage(ArmorClass targetArmor) {
        if (targetArmor == null) {
            targetArmor = ArmorClass.NONE;
        }

        float modifiedAmount = baseAmount;

        // 应用组件修正
        for (float modifier : modifiers.values()) {
            modifiedAmount *= (1 + modifier);
        }

        // 应用穿透效果
        float penetrationEffect = penetration.getPenetrationEffectiveness(targetArmor);
        float armorReduction = targetArmor.getDamageReduction();

        // 计算最终伤害：基础伤害 × 穿透效果 × (1 - 护甲减免)
        return modifiedAmount * penetrationEffect * (1 - armorReduction * (1 - penetrationEffect));
    }
}