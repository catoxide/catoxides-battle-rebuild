package com.catoxide.catoxidesbattlerebuild.core.structure.converter;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.PlacementConfig;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructureBlueprint;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.TerrainAdaptation;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates structure JSON files from StructureBlueprint definitions.
 * Output: data/<namespace>/worldgen/structure/<name>.json
 *
 * <p><b>The emitted shape must match {@code minecraft:jigsaw}'s codec exactly.</b>
 * RegistryDataLoader treats a missing key, an unknown key or a bad enum name as a hard
 * failure, and one such file makes the whole world unopenable ("Failed to load registries
 * due to above errors"). Every field below is modelled on a real vanilla file, e.g.
 * {@code data/minecraft/worldgen/structure/pillager_outpost.json}.
 */
public final class StructureJSONGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TAG = "StructureJSONGenerator";

    private StructureJSONGenerator() {}

    /**
     * Generate the structure JSON object.
     */
    public static JsonObject generate(StructureBlueprint blueprint, String namespace) {
        if (blueprint == null) {
            throw new IllegalArgumentException("Blueprint cannot be null");
        }

        JsonObject root = new JsonObject();

        // Type - jigsaw for custom structures
        root.addProperty("type", "minecraft:jigsaw");

        // Biomes: a biome TAG string (or a list of biome ids). The codec field is
        // homogeneousList(BIOME), so a weighted-list object is not a valid input.
        if (blueprint.biomesResourceLocation != null && !blueprint.biomesResourceLocation.isEmpty()) {
            root.addProperty("biomes", blueprint.biomesResourceLocation);
        } else {
            root.addProperty("biomes", "#minecraft:is_overworld");
        }

        // Step - surface_structures for most custom structures
        root.addProperty("step", "surface_structures");

        // Terrain adaptation
        root.addProperty("terrain_adaptation", toVanillaTerrainAdaptation(blueprint.terrainAdaptation));

        // Start pool
        String startPoolPath = namespace + ":";
        if (blueprint.startPool != null) {
            startPoolPath += blueprint.startPool;
        } else {
            startPoolPath += "start";
        }
        root.addProperty("start_pool", startPoolPath);

        // Size - Codec.intRange(1, 128); an out-of-range value is a hard failure.
        root.addProperty("size", clamp(blueprint.structureSize, 1, 128));

        // Max distance from center - Codec.intRange(1, 128)
        root.addProperty("max_distance_from_center", clamp(blueprint.maxDistanceFromCenter, 1, 128));

        PlacementConfig placement = blueprint.placement != null ? blueprint.placement : new PlacementConfig();

        // Start height - a HeightProvider, i.e. {"absolute": n}. NOT {"type":...,"value":...}.
        JsonObject startHeight = new JsonObject();
        startHeight.addProperty("absolute", placement.heightOffset);
        root.add("start_height", startHeight);

        // Where to project the start onto terrain. Replaces the old invented "height" object,
        // which the codec rejected outright ("missed input").
        String heightmap = toVanillaHeightmap(placement.heightType);
        if (heightmap != null) {
            root.addProperty("project_start_to_heightmap", heightmap);
        } else {
            LogManager.serverWarn(TAG, "Unknown heightType '%s' for '%s'; omitting project_start_to_heightmap",
                placement.heightType, blueprint.structureId);
        }

        // Spawn overrides - a map keyed by mob category; {} means "no extra spawns".
        // The old {"bonus_chests":0,"spawns":[]} shape was never a valid codec input.
        root.add("spawn_overrides", new JsonObject());

        // Required by minecraft:jigsaw and previously missing entirely.
        root.addProperty("use_expansion_hack", true);

        LogManager.serverInfo(TAG, "Generated structure JSON for '%s'", blueprint.structureId);
        return root;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Map our blueprint enum onto the values minecraft:jigsaw actually accepts:
     * none | beard_thin | beard_box | bury | encapsulate.
     */
    private static String toVanillaTerrainAdaptation(TerrainAdaptation adaptation) {
        if (adaptation == null) return "none";
        switch (adaptation) {
            case NONE: return "none";
            case BEARD_THIN: return "beard_thin";
            case BEARD_CUT: return "beard_box";
            case GROUND_HUG: return "bury";
            default: return "none";
        }
    }

    /**
     * Map a PlacementConfig.heightType onto a Heightmap.Types enum name.
     * Returns null when the value is unknown, so the caller omits the optional field
     * rather than emitting an invalid enum name.
     */
    private static String toVanillaHeightmap(String heightType) {
        if (heightType == null) return "MOTION_BLOCKING";
        switch (heightType.toLowerCase()) {
            case "world_surface": return "WORLD_SURFACE";
            case "world_surface_wg": return "WORLD_SURFACE_WG";
            case "ocean_floor": return "OCEAN_FLOOR";
            case "ocean_floor_wg": return "OCEAN_FLOOR_WG";
            case "motion_blocking": return "MOTION_BLOCKING";
            case "motion_blocking_no_leaves": return "MOTION_BLOCKING_NO_LEAVES";
            default: return null;
        }
    }

    /**
     * Write the structure JSON to a file.
     * Path: data/<namespace>/worldgen/structure/<name>.json
     */
    public static Path writeJson(StructureBlueprint blueprint, String namespace, Path dataDir) throws IOException {
        JsonObject json = generate(blueprint, namespace);

        Path outputPath = dataDir
            .resolve(namespace)
            .resolve("worldgen")
            .resolve("structure");

        Files.createDirectories(outputPath);
        outputPath = outputPath.resolve(blueprint.structureId + ".json");

        String jsonStr = GSON.toJson(json);
        Files.writeString(outputPath, jsonStr);

        LogManager.serverInfo(TAG, "Wrote structure JSON: %s", outputPath);
        return outputPath;
    }
}
