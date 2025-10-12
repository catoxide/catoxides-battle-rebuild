package com.catoxide.catoxidesbattlerebuild;


import com.catoxide.catoxidesbattlerebuild.damage.CompositeDamage;
import com.catoxide.catoxidesbattlerebuild.damage.DamageComponent;
import com.catoxide.catoxidesbattlerebuild.damage.DamageResult;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Set;

// 伤害组件工厂接口
public interface IDamageComponentFactory {
    /**
     * 为指定武器和架势创建伤害组件
     */
    List<DamageComponent> createDamageComponents(IWeapon weapon, String stanceId,
                                                 LivingEntity attacker, LivingEntity target);

    /**
     * 获取支持的伤害类型
     */
    Set<DamageType> getSupportedDamageTypes();
}

