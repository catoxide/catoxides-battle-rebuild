package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

/**
 * Structure set - groups multiple structures with shared placement rules.
 */
public class StructureSet {
    public String name;
    public List<SetStructure> structures = new ArrayList<>();
    public PlacementType placementType = PlacementType.RANDOM_SPREAD;

    public enum PlacementType {
        @SerializedName("random_spread") RANDOM_SPREAD,
        @SerializedName("concentric_rings") CONCENTRIC_RINGS
    }

    public StructureSet() {}
    public StructureSet(String name) { this.name = name; }

    public static class SetStructure {
        public String structure;
        public int weight = 1;

        public SetStructure() {}
        public SetStructure(String structure) { this.structure = structure; }
    }
}
