package com.catoxide.catoxidesbattlerebuild;

import com.catoxide.catoxidesbattlerebuild.damage.DamageType;
import com.catoxide.catoxidesbattlerebuild.damage.PenetrationLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

// 武器基础接口
public interface IWeapon {
    String getWeaponId();
    IWeaponHitbox getHitbox();
    Map<DamageType, Float> getBaseDamages();
    List<String> getAvailableStances();
    ICombatStance getStance(String stanceId);
    PenetrationLevel weaponPenetration = PenetrationLevel.NONE;
}

