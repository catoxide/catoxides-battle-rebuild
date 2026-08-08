package com.catoxide.catoxidesbattlerebuild.core.structure.placement;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.PlacementConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSources;
import net.minecraft.world.level.biome.Biomes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Biome filtering utility for structure placement.
 */
public final class BiomeFilter {

    private BiomeFilter() {}

    /**
     * Check if a biome is allowed for structure placement.
     */
    public static boolean isAllowed(Holder<Biome> biome, PlacementConfig config) {
        if (biome == null) return false;

        // No restrictions = all allowed
        if (config.biomes.isEmpty() && config.excludedBiomes.isEmpty()) {
            return true;
        }

        ResourceKey<Biome> biomeKey = biome.unwrapKey().orElse(null);
        if (biomeKey == null) return false;

        String biomePath = biomeKey.location().getPath();
        String biomeFull = biomeKey.location().toString();

        // Check excluded biomes first
        for (String excluded : config.excludedBiomes) {
            if (matchesBiome(biomePath, biomeFull, excluded)) {
                return false;
            }
        }

        // If no inclusion list, allowed
        if (config.biomes.isEmpty()) {
            return true;
        }

        // Check inclusion list
        for (String included : config.biomes) {
            if (matchesBiome(biomePath, biomeFull, included)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a biome matches the filter string.
     * Supports:
     * - Exact match: "plains"
     * - Tag match: "#minecraft:is_mountain"
     * - Partial match: "mountain" matches "snowy_mountains"
     */
    private static boolean matchesBiome(String path, String full, String filter) {
        // Tag match
        if (filter.startsWith("#")) {
            String tagName = filter.substring(1);
            return full.contains(tagName);
        }

        // Exact match
        if (path.equals(filter) || full.equals(filter)) {
            return true;
        }

        // Partial match
        return path.contains(filter) || full.contains(filter);
    }

    /**
     * Get common biome tag keys for reference.
     */
    public static Set<String> getCommonBiomeTags() {
        return Set.of(
            "#minecraft:is_mountain",
            "#minecraft:is_hill",
            "#minecraft:is_plains",
            "#minecraft:is_sandy",
            "#minecraft:is_deep_ocean",
            "#minecraft:is_ocean",
            "#minecraft:is_river",
            "#minecraft:is_badlands",
            "#minecraft:is_nether",
            "#minecraft:is_end",
            "#minecraft:is_overworld"
        );
    }

    /**
     * Get biome-friendly name for logging.
     */
    public static String getBiomeName(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> key.location().toString())
            .orElse("unknown");
    }
}
