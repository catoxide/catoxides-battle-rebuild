package com.catoxide.catoxidesbattlerebuild.core.structure.converter;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.PlacementConfig;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructureBlueprint;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructureSet;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates structure_set JSON files.
 * Output: data/<namespace>/worldgen/structure_set/<name>.json
 */
public final class StructureSetGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TAG = "StructureSetGenerator";

    private StructureSetGenerator() {}

    /**
     * Generate structure set JSON from a blueprint's placement config.
     */
    public static JsonObject generate(String setName, String namespace, StructureBlueprint blueprint) {
        JsonObject root = new JsonObject();

        // Structures array
        JsonArray structures = new JsonArray();

        // Add the main structure
        JsonObject structEntry = new JsonObject();
        structEntry.addProperty("structure", namespace + ":" + blueprint.structureId);
        structEntry.addProperty("weight", 1);
        structures.add(structEntry);

        root.add("structures", structures);

        // Placement
        root.add("placement", generatePlacement(blueprint.placement, namespace));

        LogManager.serverInfo(TAG, "Generated structure set '%s': 1 structure", setName);
        return root;
    }

    /**
     * Generate placement JSON for random_spread algorithm.
     */
    private static JsonObject generatePlacement(PlacementConfig config, String namespace) {
        JsonObject placement = new JsonObject();
        placement.addProperty("type", "minecraft:random_spread");

        // Vanilla requires separation < spacing; the codec rejects the file otherwise.
        int spacing = Math.max(2, config.spacing);
        int separation = Math.max(0, Math.min(config.separation, spacing - 1));
        placement.addProperty("spacing", spacing);
        placement.addProperty("separation", separation);
        placement.addProperty("salt", config.salt);

        // exclusion_zone is optional and has exactly two fields:
        //   { "other_set": <structure_set id>, "chunk_count": <n> }
        // The previous { "own_structures": 4, "other_structures": [...] } shape was invented
        // and is rejected outright by the codec. Vanilla only excludes ONE other set.
        if (config.exclusionZones != null && !config.exclusionZones.isEmpty()) {
            if (config.exclusionZones.size() > 1) {
                LogManager.serverWarn(TAG,
                    "exclusion_zone holds a single other_set; using the first of %d",
                    config.exclusionZones.size());
            }
            PlacementConfig.ExclusionZone ez = config.exclusionZones.get(0);
            JsonObject zone = new JsonObject();
            zone.addProperty("other_set", ez.structureType);
            zone.addProperty("chunk_count", Math.max(1, ez.distance));
            placement.add("exclusion_zone", zone);
        }

        return placement;
    }

    /**
     * Generate from an explicit StructureSet object.
     */
    public static JsonObject generate(StructureSet structSet, String namespace) {
        JsonObject root = new JsonObject();

        // Structures
        JsonArray structures = new JsonArray();
        for (StructureSet.SetStructure ss : structSet.structures) {
            JsonObject entry = new JsonObject();
            entry.addProperty("structure", ss.structure);
            entry.addProperty("weight", ss.weight);
            structures.add(entry);
        }
        root.add("structures", structures);

        // placement is REQUIRED by minecraft:structure_set - the old code omitted it entirely,
        // which alone made the file unparseable. StructureSet carries no spacing/separation,
        // so emit a conservative random_spread.
        if (structSet.placementType == StructureSet.PlacementType.CONCENTRIC_RINGS) {
            LogManager.serverWarn(TAG,
                "concentric_rings placement is not supported yet; using random_spread for '%s'",
                structSet.name);
        }
        JsonObject placement = new JsonObject();
        placement.addProperty("type", "minecraft:random_spread");
        placement.addProperty("spacing", 34);
        placement.addProperty("separation", 8);
        placement.addProperty("salt", 0);
        root.add("placement", placement);

        LogManager.serverInfo(TAG, "Generated structure set '%s': %d structures",
            structSet.name, structures.size());
        return root;
    }

    /**
     * Write the structure set JSON to a file.
     * Path: data/<namespace>/worldgen/structure_set/<name>.json
     */
    public static Path writeJson(String setName, String namespace, StructureBlueprint blueprint, Path dataDir) throws IOException {
        JsonObject json = generate(setName, namespace, blueprint);

        Path outputPath = dataDir
            .resolve(namespace)
            .resolve("worldgen")
            .resolve("structure_set");

        Files.createDirectories(outputPath);
        outputPath = outputPath.resolve(setName + ".json");

        String jsonStr = GSON.toJson(json);
        Files.writeString(outputPath, jsonStr);

        LogManager.serverInfo(TAG, "Wrote structure set JSON: %s", outputPath);
        return outputPath;
    }

    /**
     * Write from explicit StructureSet.
     */
    public static Path writeJson(StructureSet structSet, String namespace, Path dataDir) throws IOException {
        JsonObject json = generate(structSet, namespace);

        Path outputPath = dataDir
            .resolve(namespace)
            .resolve("worldgen")
            .resolve("structure_set");

        Files.createDirectories(outputPath);
        outputPath = outputPath.resolve(structSet.name + ".json");

        String jsonStr = GSON.toJson(json);
        Files.writeString(outputPath, jsonStr);

        LogManager.serverInfo(TAG, "Wrote structure set JSON: %s", outputPath);
        return outputPath;
    }
}
