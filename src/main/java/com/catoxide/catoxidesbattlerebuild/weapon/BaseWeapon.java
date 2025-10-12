package com.catoxide.catoxidesbattlerebuild.weapon;

import com.catoxide.catoxidesbattlerebuild.IWeapon;
import com.catoxide.catoxidesbattlerebuild.IWeaponHitbox;
import com.catoxide.catoxidesbattlerebuild.ICombatStance;
import com.catoxide.catoxidesbattlerebuild.damage.DamageComponent;
import com.catoxide.catoxidesbattlerebuild.damage.DamageType;
import com.catoxide.catoxidesbattlerebuild.damage.PenetrationLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.LivingEntity;

import java.util.*;

public abstract class BaseWeapon implements IWeapon {
    protected final String weaponId;
    protected final Map<DamageType, Float> baseDamages;
    protected final Map<String, ICombatStance> availableStances;
    protected final IWeaponHitbox hitbox;

    protected PenetrationLevel weaponPenetrationOverride = null;

    public BaseWeapon(String weaponId, IWeaponHitbox hitbox) {
        this.weaponId = weaponId;
        this.hitbox = hitbox;
        this.baseDamages = new HashMap<>();
        this.availableStances = new HashMap<>();
        setupWeapon();
    }

    public BaseWeapon(String weaponId, IWeaponHitbox hitbox, PenetrationLevel weaponPenetration) {
        this.weaponId = weaponId;
        this.hitbox = hitbox;
        this.weaponPenetrationOverride = weaponPenetration;
        this.baseDamages = new HashMap<>();
        this.availableStances = new HashMap<>();
        setupWeapon();
    }

    protected abstract void setupWeapon();

    // ✅ 修复：确保这些方法与IWeapon接口完全匹配
    @Override
    public String getWeaponId() {
        return weaponId;
    }

    @Override
    public IWeaponHitbox getHitbox() {
        return hitbox;
    }

    @Override
    public Map<DamageType, Float> getBaseDamages() {
        return new HashMap<>(baseDamages);
    }

    @Override
    public List<String> getAvailableStances() {
        return new ArrayList<>(availableStances.keySet());
    }

    @Override
    public ICombatStance getStance(String stanceId) {
        return availableStances.get(stanceId);
    }

    protected void addDamageType(DamageType type, float amount) {
        baseDamages.put(type, amount);
    }

    protected void addStance(String stanceId, ICombatStance stance) {
        availableStances.put(stanceId, stance);
    }

    // 伤害组件创建方法
    public DamageComponent createDamageComponent(DamageType type, float baseAmount) {
        return new DamageComponent(type, baseAmount, null, weaponPenetrationOverride);
    }

    public DamageComponent createDamageComponent(DamageType type, float baseAmount, PenetrationLevel explicitPenetration) {
        return new DamageComponent(type, baseAmount, explicitPenetration, weaponPenetrationOverride);
    }

    public void setWeaponPenetrationOverride(PenetrationLevel penetration) {
        this.weaponPenetrationOverride = penetration;
    }
}