package com.catoxide.catoxidesbattlerebuild.damage;

import java.util.*;

public class DamageResult {
    private final CompositeDamage originalDamage;
    private final Map<DamageType, Float> componentResults = new HashMap<>();
    private final List<AppliedEffect> appliedEffects = new ArrayList<>();
    private float totalDamage = 0;

    public DamageResult(CompositeDamage originalDamage) {
        this.originalDamage = originalDamage;
    }

    public void setComponentResult(DamageType type, float amount) {
        componentResults.put(type, Math.max(0, amount));
        recalculateTotal();
    }

    public float getComponentAmount(DamageType type) {
        return componentResults.getOrDefault(type, 0f);
    }

    public Set<DamageType> getComponentTypes() {
        return componentResults.keySet();
    }

    public void addAppliedEffect(AppliedEffect effect) {
        appliedEffects.add(effect);
    }

    private void recalculateTotal() {
        totalDamage = componentResults.values().stream()
                .reduce(0f, Float::sum);
    }

    // Getter方法
    public float getTotalDamage() { return totalDamage; }
    public CompositeDamage getOriginalDamage() { return originalDamage; }
    public List<AppliedEffect> getAppliedEffects() { return appliedEffects; }
    public Map<DamageType, Float> getComponentResults() { return componentResults; }
}
