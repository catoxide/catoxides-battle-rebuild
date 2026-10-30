package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import com.google.gson.annotations.SerializedName;

/**
 * Terrain adaptation modes for structure placement.
 */
public enum TerrainAdaptation {
    @SerializedName("none") NONE,
    @SerializedName("beard_cut") BEARD_CUT,
    @SerializedName("beard_thin") BEARD_THIN,
    @SerializedName("ground_hug") GROUND_HUG;

    /**
     * Map onto the values minecraft:jigsaw actually accepts:
     * none | beard_thin | beard_box | bury | encapsulate.
     * (This used to return name().toLowerCase(), which produced invalid values such as
     * "beard_cut" and "ground_hug".)
     */
    public String toVanillaString() {
        switch (this) {
            case BEARD_CUT: return "beard_box";
            case GROUND_HUG: return "bury";
            case BEARD_THIN: return "beard_thin";
            case NONE:
            default: return "none";
        }
    }
}
