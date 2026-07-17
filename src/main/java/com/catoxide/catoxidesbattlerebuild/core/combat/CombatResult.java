package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;

import java.util.List;

public record CombatResult(
        List<HitResult> hitResults,
        float totalDamage,
        float remainingPiercing,
        boolean hasDestroyedParts
) {
    public record HitResult(
            BodyUnit bodyUnit,
            float actualDamage,
            float penetrationFactor,
            float piercingFactor,
            boolean penetrated,
            boolean destroyed
    ) {}
}