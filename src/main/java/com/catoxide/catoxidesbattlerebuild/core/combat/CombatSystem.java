package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.List;

public class CombatSystem {
    private static final CombatSystem INSTANCE = new CombatSystem();
    private static final String TAG = "CombatSystem";

    private CombatSystem() {
        LogManager.serverStartup(TAG, "CombatSystem initialized");
    }

    public static CombatSystem getInstance() {
        return INSTANCE;
    }

    public CombatResult processAttack(AttackProperties attack, List<BodyUnit> hitUnits, float[] remainingHealths) {
        LogManager.serverDebug(TAG, "========== processAttack START ==========");
        LogManager.serverDebug(TAG, "  Attack props: armorPen={}, piercing={}, baseDamage={}",
                attack.armorPenetration(), attack.piercing(), attack.baseDamage());
        LogManager.serverDebug(TAG, "  Hit units count: {}", hitUnits.size());

        for (int i = 0; i < hitUnits.size(); i++) {
            BodyUnit u = hitUnits.get(i);
            float h = i < remainingHealths.length ? remainingHealths[i] : -1;
            LogManager.serverDebug(TAG, "    [{}] bone={}, armor={}, antiPiercing={}, health={}, destroyed={}",
                    i, u.getBoneName(), u.getArmorValue(), u.getAntiPiercing(), h, u.isDestroyed());
        }

        CombatResult result = DamagePipeline.process(attack, hitUnits, remainingHealths);

        LogManager.serverInfo(TAG, "Attack result: totalDamage={}, destroyed={}, remainingPiercing={}, hitCount={}",
                result.totalDamage(), result.hasDestroyedParts(), result.remainingPiercing(), result.hitResults().size());

        for (int i = 0; i < result.hitResults().size(); i++) {
            CombatResult.HitResult hit = result.hitResults().get(i);
            LogManager.serverDebug(TAG, "  Hit[{}]: bone={}, damage={}, penFactor={}, pierceFactor={}, destroyed={}",
                    i, hit.bodyUnit().getBoneName(), hit.actualDamage(),
                    hit.penetrationFactor(), hit.piercingFactor(), hit.destroyed());
        }

        for (CombatResult.HitResult hit : result.hitResults()) {
            if (hit.destroyed()) {
                hit.bodyUnit().setDestroyed(true);
                LogManager.serverInfo(TAG, "  -> Body part DESTROYED: boneName={}", hit.bodyUnit().getBoneName());
            }
        }

        LogManager.serverDebug(TAG, "========== processAttack END ==========");

        return result;
    }
}