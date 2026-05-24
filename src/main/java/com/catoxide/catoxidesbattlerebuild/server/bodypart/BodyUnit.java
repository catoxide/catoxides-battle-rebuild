package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.List;

public class BodyUnit {
    private final String boneName;
    private final float transmissionCoeff;
    private final float armorValue;
    private final String collisionTag;
    private final List<String> specialEffects;
    private final String hitSound;
    private final BodyPart parentPart;

    public BodyUnit(String boneName, float transmissionCoeff, float armorValue,
                    String collisionTag, List<String> specialEffects, String hitSound, BodyPart parentPart) {
        this.boneName = boneName;
        this.transmissionCoeff = transmissionCoeff;
        this.armorValue = armorValue;
        this.collisionTag = collisionTag;
        this.specialEffects = specialEffects;
        this.hitSound = hitSound;
        this.parentPart = parentPart;
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
}
