package example.contentpack.meleecombat.combat;

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
        // 从 CombatConfig 读取可调参数（默认值与硬编码一致）
        float threshold = (float) (double) CombatConfig.PENETRATION_THRESHOLD.get();
        float min = (float) (double) CombatConfig.MIN_PENETRATION_FACTOR.get();
        float max = (float) (double) CombatConfig.MAX_PENETRATION_FACTOR.get();

        if (penetration >= armor + threshold) {
            return max;
        }

        if (penetration <= armor) {
            return min;
        }

        float normalized = (penetration - armor) / threshold;
        return min + (max - min) * normalized;
    }
}