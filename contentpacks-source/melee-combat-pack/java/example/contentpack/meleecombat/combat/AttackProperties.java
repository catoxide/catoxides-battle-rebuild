package example.contentpack.meleecombat.combat;

public record AttackProperties(
        float armorPenetration,
        float piercing,
        float baseDamage
) {
    public static AttackProperties of(float armorPenetration, float piercing, float baseDamage) {
        return new AttackProperties(armorPenetration, piercing, baseDamage);
    }
}