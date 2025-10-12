package com.catoxide.catoxidesbattlerebuild.damage;

public class AppliedEffect {
    private final String effectId;
    private final int duration;
    private final int amplifier;
    private final DamageComponent sourceComponent;

    public AppliedEffect(String effectId, int duration, int amplifier, DamageComponent source) {
        this.effectId = effectId;
        this.duration = duration;
        this.amplifier = amplifier;
        this.sourceComponent = source;
    }

    // Getter方法
    public String getEffectId() { return effectId; }
    public int getDuration() { return duration; }
    public int getAmplifier() { return amplifier; }
    public DamageComponent getSourceComponent() { return sourceComponent; }
}