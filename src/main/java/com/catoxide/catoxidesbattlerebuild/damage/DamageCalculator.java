package com.catoxide.catoxidesbattlerebuild.damage;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;

public class DamageCalculator {

    public DamageResult calculateDamage(CompositeDamage compositeDamage) {
        DamageResult result = new DamageResult(compositeDamage);

        // 阶段1: 预处理（计算基础值）
        preCalculateComponents(compositeDamage, result);

        // 阶段2: 应用目标抗性
        //applyTargetResistances(compositeDamage, result);

        // 阶段3: 应用自定义盔甲系统
        //applyArmorReduction(compositeDamage, result);

        // 阶段4: 应用特殊效果
        //applySpecialEffects(compositeDamage, result);

        // 阶段5: 最终修正
        //applyFinalModifiers(compositeDamage, result);

        return result;
    }

    private void preCalculateComponents(CompositeDamage damage, DamageResult result) {
        for (DamageComponent component : damage.getComponents()) {
            float baseAmount = component.getBaseAmount();

            // 应用组件特定修正
            for (Map.Entry<String, Float> modifier : component.getModifiers().entrySet()) {
                baseAmount *= (1 + modifier.getValue());
            }

            result.setComponentResult(component.getType(), baseAmount);
        }
    }

//    private void applyTargetResistances(CompositeDamage damage, DamageResult result) {
//        LivingEntity target = damage.getTarget();
//        ResistanceSystem resistanceSystem = ResistanceSystem.get(target);
//
//        for (DamageType type : result.getComponentTypes()) {
//            float currentAmount = result.getComponentAmount(type);
//            float resistance = resistanceSystem.getResistance(type);
//            float reducedAmount = currentAmount * (1 - resistance);
//            result.setComponentResult(type, reducedAmount);
//        }
//    }

//    private void applyArmorReduction(CompositeDamage damage, DamageResult result) {
//        CustomArmorSystem armorSystem = CustomArmorSystem.get(damage.getTarget());
//
//        // 处理物理伤害类型
//        float physicalDamage = result.getComponentAmount(DamageType.SLASHING) +
//                result.getComponentAmount(DamageType.PIERCING) +
//                result.getComponentAmount(DamageType.BLUDGEONING);
//
//        if (physicalDamage > 0) {
//            float armorReduction = armorSystem.calculatePhysicalReduction(physicalDamage);
//            // 按比例分配减免到各个物理伤害组件
//            distributeArmorReduction(result, armorReduction,
//                    DamageType.SLASHING, DamageType.PIERCING, DamageType.BLUDGEONING);
//        }
//
//        // 处理元素伤害（如果有元素抗性系统）
//        applyElementalResistances(damage, result, armorSystem);
//    }
}
