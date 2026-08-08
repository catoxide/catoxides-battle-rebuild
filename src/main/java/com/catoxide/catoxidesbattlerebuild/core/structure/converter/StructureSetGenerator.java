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

        placement.addProperty("spacing", config.spacing);
        placement.addProperty("separation", config.separation);
        placement.addProperty("salt", config.salt);
        placement.add("exclusion_zone", generateExclusionZone(config, namespace));

        return placement;
    }

    /**
     * Generate exclusion zone configuration.
     */
    private static JsonObject generateExclusionZone(PlacementConfig config, String namespace) {
        JsonObject zone = new JsonObject();

        // Default: exclude own structure type
        zone.addProperty("own_structures", 4);

        // Add custom exclusion zones
        if (!config.exclusionZones.isEmpty()) {
            JsonArray otherZones = new JsonArray();
            for (PlacementConfig.ExclusionZone ez : config.exclusionZones) {
                JsonObject other = new JsonObject();
                other.addProperty("structure", ez.structureType);
                other.addProperty("distance", ez.distance);
                otherZones.add(other);
            }
            zone.add("other_structures", otherZones);
        } else {
            zone.add("other_structures", new JsonArray());
        }

        return zone;
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
