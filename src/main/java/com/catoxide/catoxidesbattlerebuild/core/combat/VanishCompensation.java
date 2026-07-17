package com.catoxide.catoxidesbattlerebuild.core.combat;

public class VanishCompensation {
    private static final float K_FACTOR = 0.1f;

    public static float calculateVanishMultiplier(AttackProperties attack, float remainingPiercing) {
        if (attack.piercing() <= 0 || remainingPiercing <= 0) {
            return 1.0f;
        }
        float ratio = remainingPiercing / attack.piercing();
        return 1.0f + ratio * K_FACTOR;
    }
}