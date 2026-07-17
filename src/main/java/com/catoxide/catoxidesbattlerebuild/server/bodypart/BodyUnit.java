package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.List;

public class BodyUnit {
    private final String boneName;
    private final float transmissionCoeff;
    private final float armorValue;
    private final float antiPiercing;
    private final String collisionTag;
    private final List<String> specialEffects;
    private final String hitSound;
    private final BodyPart parentPart;
    private float currentHealth;
    private float maxHealth;
    private boolean destroyed;

    public BodyUnit(String boneName, float transmissionCoeff, float armorValue,
                    String collisionTag, List<String> specialEffects, String hitSound, BodyPart parentPart) {
        this(boneName, transmissionCoeff, armorValue, 0f, collisionTag, specialEffects, hitSound, parentPart);
    }

    public BodyUnit(String boneName, float transmissionCoeff, float armorValue,
                    float antiPiercing, String collisionTag, List<String> specialEffects,
                    String hitSound, BodyPart parentPart) {
        this.boneName = boneName;
        this.transmissionCoeff = transmissionCoeff;
        this.armorValue = armorValue;
        this.antiPiercing = antiPiercing;
        this.collisionTag = collisionTag;
        this.specialEffects = specialEffects;
        this.hitSound = hitSound;
        this.parentPart = parentPart;
        this.currentHealth = 100f;
        this.maxHealth = 100f;
        this.destroyed = false;
    }

    public float applyDamage(float rawDamage) {
        float damage = rawDamage * transmissionCoeff;
        damage = Math.max(0, damage - armorValue);
        return damage;
    }

    public String getBoneName() { return boneName; }
    public float getTransmissionCoeff() { return transmissionCoeff; }
    public float getArmorValue() { return armorValue; }
    public String getCollisionTag() { return collisionTag; }
    public List<String> getSpecialEffects() { return specialEffects; }
    public String getHitSound() { return hitSound; }
    public BodyPart getParentPart() { return parentPart; }
    public float getAntiPiercing() { return antiPiercing; }
    public boolean isDestroyed() { return destroyed; }
    public void setDestroyed(boolean value) { this.destroyed = value; }
    public float getCurrentHealth() { return currentHealth; }
    public float getMaxHealth() { return maxHealth; }
    public void setCurrentHealth(float health) { this.currentHealth = Math.max(0, Math.min(maxHealth, health)); }
    public void setMaxHealth(float health) { this.maxHealth = health; this.currentHealth = Math.min(currentHealth, maxHealth); }
}
