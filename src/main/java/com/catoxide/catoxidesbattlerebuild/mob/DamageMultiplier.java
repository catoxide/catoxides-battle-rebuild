package com.catoxide.catoxidesbattlerebuild.mob;

// TODO：临时简化版，后续可以扩展
public class DamageMultiplier {
    private final float multiplier;

    public DamageMultiplier(float multiplier) {
        this.multiplier = multiplier;
    }

    public float getMultiplier() {
        return multiplier;
    }

    // 静态方法获取默认倍率
    public static DamageMultiplier getDefaultForPart(String bodyPart) {
        return new DamageMultiplier(1.0f); // 默认1.0
    }
}