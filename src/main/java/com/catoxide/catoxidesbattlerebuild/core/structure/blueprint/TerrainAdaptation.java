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

    public String toVanillaString() {
        return this.name().toLowerCase();
    }
}
