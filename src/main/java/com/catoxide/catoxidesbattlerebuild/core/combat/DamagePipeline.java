package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.ArrayList;
import java.util.List;

public class DamagePipeline {
    private static final String TAG = "DamagePipeline";

    public static CombatResult process(AttackProperties attack, List<BodyUnit> hitUnits, float[] remainingHealths) {
        LogManager.serverDebug(TAG, "=== Pipeline Start ===");
        LogManager.serverDebug(TAG, "  Attack: armorPen={}, piercing={}, baseDamage={}",
                attack.armorPenetration(), attack.piercing(), attack.baseDamage());
        LogManager.serverDebug(TAG, "  Hit units: {}, health values: {}", hitUnits.size(), remainingHealths.length);

        List<CombatResult.HitResult> hitResults = new ArrayList<>();
        float remainingPiercing = attack.piercing();
        float totalDamage = 0;
        boolean hasDestroyedParts = false;

        for (int i = 0; i < hitUnits.size(); i++) {
            BodyUnit unit = hitUnits.get(i);
            float remainingHealth = remainingHealths[i];

            LogManager.serverDebug(TAG, "--- Unit [{}]: bone={}, armor={}, antiPiercing={}, health={} ---",
                    i, unit.getBoneName(), unit.getArmorValue(), unit.getAntiPiercing(), remainingHealth);

            if (unit.isDestroyed()) {
                LogManager.serverDebug(TAG, "  SKIP: unit already destroyed");
                continue;
            }

            if (!ArmorPenetrationCalculator.canPenetrate(attack, unit)) {
                float consumedPiercing = unit.getAntiPiercing();
                remainingPiercing -= consumedPiercing;

                LogManager.serverDebug(TAG, "  BLOCKED: armor penetration failed (armor={} > pen={})",
                        unit.getArmorValue(), attack.armorPenetration());
                LogManager.serverDebug(TAG, "  Consumed piercing: {}, remaining: {}",
                        consumedPiercing, remainingPiercing);

                hitResults.add(new CombatResult.HitResult(
                        unit,
                        0,
                        0,
                        0,
                        PiercingCalculator.canContinuePiercing(remainingPiercing),
                        false
                ));

                if (!PiercingCalculator.canContinuePiercing(remainingPiercing)) {
                    LogManager.serverDebug(TAG, "  Piercing exhausted - stopping pipeline");
                    break;
                }
                continue;
            }

            float penetrationFactor = ArmorPenetrationCalculator.calculatePenetrationFactor(attack, unit);
            float piercingFactor = PiercingCalculator.calculatePiercingFactor(attack, remainingPiercing);

            LogManager.serverDebug(TAG, "  Penetration: factor={} (canPenetrate=true)", penetrationFactor);
            LogManager.serverDebug(TAG, "  Piercing factor: {} (remainingPiercing={})",
                    piercingFactor, remainingPiercing);

            float actualDamage = attack.baseDamage() * penetrationFactor * piercingFactor;

            LogManager.serverDebug(TAG, "  Actual damage: {} * {} * {} = {}",
                    attack.baseDamage(), penetrationFactor, piercingFactor, actualDamage);

            boolean destroyed = actualDamage >= remainingHealth;

            if (destroyed) {
                float modifiedAntiPiercing = PiercingCalculator.calculateModifiedAntiPiercing(
                        attack, penetrationFactor, remainingHealth
                );
                remainingPiercing -= modifiedAntiPiercing;
                hasDestroyedParts = true;

                LogManager.serverDebug(TAG, "  DESTROYED! modifiedAntiPiercing={}, remainingPiercing={}",
                        modifiedAntiPiercing, remainingPiercing);
            } else {
                remainingPiercing -= unit.getAntiPiercing();
                LogManager.serverDebug(TAG, "  Not destroyed. consumedAntiPiercing={}, remainingPiercing={}",
                        unit.getAntiPiercing(), remainingPiercing);
            }

            totalDamage += actualDamage;

            hitResults.add(new CombatResult.HitResult(
                    unit,
                    actualDamage,
                    penetrationFactor,
                    piercingFactor,
                    PiercingCalculator.canContinuePiercing(remainingPiercing),
                    destroyed
            ));

            if (!PiercingCalculator.canContinuePiercing(remainingPiercing)) {
                LogManager.serverDebug(TAG, "  Piercing exhausted - stopping pipeline");
                break;
            }
        }

        float vanishMultiplier = VanishCompensation.calculateVanishMultiplier(attack, remainingPiercing);
        if (vanishMultiplier > 1.0f) {
            totalDamage *= vanishMultiplier;

            LogManager.serverDebug(TAG, "Vanish compensation: multiplier={} (remainingPiercing={})",
                    vanishMultiplier, remainingPiercing);

            for (int i = 0; i < hitResults.size(); i++) {
                CombatResult.HitResult old = hitResults.get(i);
                hitResults.set(i, new CombatResult.HitResult(
                        old.bodyUnit(),
                        old.actualDamage() * vanishMultiplier,
                        old.penetrationFactor(),
                        old.piercingFactor(),
                        old.penetrated(),
                        old.destroyed()
                ));
            }
        }

        LogManager.serverDebug(TAG, "=== Pipeline End ===");
        LogManager.serverDebug(TAG, "  Total damage: {}, destroyed parts: {}, remaining piercing: {}",
                totalDamage, hasDestroyedParts, remainingPiercing);

        return new CombatResult(hitResults, totalDamage, remainingPiercing, hasDestroyedParts);
    }
}