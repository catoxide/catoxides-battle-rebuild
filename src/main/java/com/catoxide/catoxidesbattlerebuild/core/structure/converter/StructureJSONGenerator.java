package com.catoxide.catoxidesbattlerebuild.core.structure.converter;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.PlacementConfig;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructureBlueprint;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.TerrainAdaptation;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates structure JSON files from StructureBlueprint definitions.
 * Output: data/<namespace>/worldgen/structure/<name>.json
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

        // Biomes reference
        if (blueprint.biomesResourceLocation != null && !blueprint.biomesResourceLocation.isEmpty()) {
            root.addProperty("biomes", blueprint.biomesResourceLocation);
        } else {
            // Default: all biomes
            JsonObject biomesObj = new JsonObject();
            biomesObj.addProperty("type", "minecraft:weighted_list");
            JsonArray entries = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("biome", "#minecraft:is_overworld");
            entry.addProperty("weight", 1);
            entries.add(entry);
            biomesObj.add("entries", entries);
            root.add("biomes", biomesObj);
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

        // Size
        root.addProperty("size", blueprint.structureSize);

        // Max distance from center
        root.addProperty("max_distance_from_center", blueprint.maxDistanceFromCenter);

        // Start height
        PlacementConfig placement = blueprint.placement;
        JsonObject startHeight = new JsonObject();
        startHeight.addProperty("type", "minecraft:constant");
        startHeight.addProperty("value", placement.heightOffset);
        root.add("start_height", startHeight);

        // Height type
        JsonObject heightType = new JsonObject();
        heightType.addProperty("type", "minecraft:" + placement.heightType);
        root.add("height", heightType);

        // Spawn overrides (empty by default)
        JsonArray spawnOverrides = new JsonArray();
        root.add("spawn_overrides", buildSpawnOverrides(spawnOverrides));

        // Processors
        JsonArray processors = new JsonArray();
        for (String processor : blueprint.processors) {
            processors.add(processor);
        }
        root.add("processors", processors);

        LogManager.serverInfo(TAG, "Generated structure JSON for '%s'", blueprint.structureId);
        return root;
    }

    private static JsonObject buildSpawnOverrides(JsonArray overrides) {
        JsonObject result = new JsonObject();
        result.addProperty("bonus_chests", 0);
        result.add("spawns", overrides);
        return result;
    }

    private static String toVanillaTerrainAdaptation(TerrainAdaptation adaptation) {
        if (adaptation == null) return "none";
        switch (adaptation) {
            case NONE: return "none";
            case BEARD_CUT: return "beard_cut";
            case BEARD_THIN: return "beard_thin";
            case GROUND_HUG: return "ground_hug";
            default: return "none";
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
