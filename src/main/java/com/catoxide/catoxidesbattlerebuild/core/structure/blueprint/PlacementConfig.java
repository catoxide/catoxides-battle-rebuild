package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Structure placement configuration.
 */
public class PlacementConfig {
    public int spacing = 24;
    public int separation = 8;
    public long salt = 0L;
    public double recommendedDensity = 1.0;
    public String type = "random_spread";
    public String heightType = "motion_blocking";
    public int heightOffset = 0;
    public List<String> biomes = new ArrayList<>();
    public List<String> excludedBiomes = new ArrayList<>();
    public List<ExclusionZone> exclusionZones = new ArrayList<>();

    public static class ExclusionZone {
        public String structureType;
        public int distance;

        public ExclusionZone() {}
        public ExclusionZone(String structureType, int distance) {
            this.structureType = structureType;
            this.distance = distance;
        }
    }
}
