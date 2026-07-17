package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.ArrayList;
import java.util.List;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

public class BodyPart {
    private final String partName;
    private float moduleHealth;
    private final float maxModuleHealth;
    private final boolean isFatal;
    private final float transmissionToBase;
    private final List<BodyUnit> units = new ArrayList<>();

    public BodyPart(String partName, float maxModuleHealth, boolean isFatal, float transmissionToBase) {
        this.partName = partName;
        this.moduleHealth = maxModuleHealth;
        this.maxModuleHealth = maxModuleHealth;
        this.isFatal = isFatal;
        this.transmissionToBase = transmissionToBase;
    }

    public void addUnit(BodyUnit unit) {
        units.add(unit);
    }

    public float applyDamage(float damage) {
        float before = moduleHealth;
        moduleHealth = Math.max(0, moduleHealth - damage);
        LogManager.serverInfo("TEST-BodyPart", "  [{}] applyDamage: {} → {} (damage={}, transmissionToBase={})",
            partName, before, moduleHealth, damage, transmissionToBase);
        return damage * transmissionToBase;
    }

    public boolean isDestroyed() {
        return moduleHealth <= 0;
    }

    public String getPartName() { return partName; }
    public float getModuleHealth() { return moduleHealth; }
    public float getMaxModuleHealth() { return maxModuleHealth; }
    public boolean isFatal() { return isFatal; }
    public float getTransmissionToBase() { return transmissionToBase; }
    public List<BodyUnit> getUnits() { return units; }
}
