package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

public class BodyPartConfig {
    private final String partName;
    private final float maxModuleHealth;
    private final boolean isFatal;
    private final float transmissionToBase;
    private final List<BodyUnitConfig> units;

    public BodyPartConfig(String partName, float maxModuleHealth, boolean isFatal,
                           float transmissionToBase, List<BodyUnitConfig> units) {
        this.partName = partName;
        this.maxModuleHealth = maxModuleHealth;
        this.isFatal = isFatal;
        this.transmissionToBase = transmissionToBase;
        this.units = units;
    }

    public String partName() { return partName; }
    public float maxModuleHealth() { return maxModuleHealth; }
    public boolean isFatal() { return isFatal; }
    public float transmissionToBase() { return transmissionToBase; }
    public List<BodyUnitConfig> units() { return units; }

    public static class BodyUnitConfig {
        private final String boneName;
        private final float transmissionCoeff;
        private final float armorValue;
        private final float antiPiercing;
        private final String collisionTag;
        private final List<String> specialEffects;
        private final String hitSound;

        public BodyUnitConfig(String boneName, float transmissionCoeff, float armorValue,
                              String collisionTag, List<String> specialEffects, String hitSound) {
            this(boneName, transmissionCoeff, armorValue, 0f, collisionTag, specialEffects, hitSound);
        }

        public BodyUnitConfig(String boneName, float transmissionCoeff, float armorValue,
                              float antiPiercing, String collisionTag, List<String> specialEffects, String hitSound) {
            this.boneName = boneName;
            this.transmissionCoeff = transmissionCoeff;
            this.armorValue = armorValue;
            this.antiPiercing = antiPiercing;
            this.collisionTag = collisionTag;
            this.specialEffects = specialEffects;
            this.hitSound = hitSound;
        }

        public String boneName() { return boneName; }
        public float transmissionCoeff() { return transmissionCoeff; }
        public float armorValue() { return armorValue; }
        public float antiPiercing() { return antiPiercing; }
        public String collisionTag() { return collisionTag; }
        public List<String> specialEffects() { return specialEffects; }
        public String hitSound() { return hitSound; }
    }
}
