package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;

public class PiercingCalculator {
    public static float calculateRemainingPiercing(AttackProperties attack, float currentPiercing, BodyUnit bodyUnit) {
        return currentPiercing - bodyUnit.getAntiPiercing();
    }

    public static float calculateModifiedAntiPiercing(
            AttackProperties attack,
            float penetrationFactor,
            float remainingHealth
    ) {
        if (attack.piercing() <= 0 || penetrationFactor <= 0 || attack.baseDamage() <= 0) {
            return Float.MAX_VALUE;
        }

        return remainingHealth * attack.piercing() / (penetrationFactor * attack.baseDamage());
    }

    public static float calculatePiercingFactor(AttackProperties attack, float remainingPiercing) {
        if (attack.piercing() <= 0) {
            return 1.0f;
        }
        return Math.max(0, remainingPiercing / attack.piercing());
    }

    public static boolean canContinuePiercing(float remainingPiercing) {
        return remainingPiercing >= 0;
    }
}