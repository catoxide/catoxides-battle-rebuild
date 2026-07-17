package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;

public class ArmorPenetrationCalculator {
    private static final float MIN_FACTOR = 0.25f;
    private static final float MAX_FACTOR = 1.0f;
    private static final float PENETRATION_THRESHOLD = 10.0f;

    public static boolean canPenetrate(AttackProperties attack, BodyUnit bodyUnit) {
        return attack.armorPenetration() >= bodyUnit.getArmorValue();
    }

    public static float calculatePenetrationFactor(AttackProperties attack, BodyUnit bodyUnit) {
        float armor = bodyUnit.getArmorValue();
        float penetration = attack.armorPenetration();

        if (penetration >= armor + PENETRATION_THRESHOLD) {
            return MAX_FACTOR;
        }

        if (penetration <= armor) {
            return MIN_FACTOR;
        }

        float normalized = (penetration - armor) / PENETRATION_THRESHOLD;
        return MIN_FACTOR + (MAX_FACTOR - MIN_FACTOR) * normalized;
    }
}