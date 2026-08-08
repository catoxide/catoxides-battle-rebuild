package com.catoxide.catoxidesbattlerebuild.core.structure.litelab;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.*;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Exports a StructureBlueprint to a format compatible with LiteLab import.
 * <p>
 * This generates:
 * 1. A blueprint.json (our format) that can be loaded by ContentPacks
 * 2. An NBT JSON file that can be imported into LiteLab for editing
 */
public final class BlueprintExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TAG = "BlueprintExporter";

    private BlueprintExporter() {}

    /**
     * Export a blueprint to JSON file.
     */
    public static Path exportBlueprint(StructureBlueprint blueprint, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path outputPath = outputDir.resolve("blueprint.json");

        String json = GSON.toJson(blueprint);
        Files.writeString(outputPath, json);

        LogManager.serverInfo(TAG, "Exported blueprint to: %s", outputPath);
        return outputPath;
    }

    /**
     * Export a blueprint to a LiteLab-compatible NBT JSON file.
     * This allows editing the structure in LiteLab, then re-importing.
     */
    public static Path exportForLiteLab(StructureBlueprint blueprint, Path outputDir) throws IOException {
        if (blueprint.pieces.isEmpty()) {
            throw new IllegalArgumentException("Blueprint has no pieces to export");
        }

        Files.createDirectories(outputDir);

        // Export each piece as a separate NBT JSON
        Map<String, Path> exported = new LinkedHashMap<>();

        for (StructurePiece piece : blueprint.pieces) {
            JsonObject nbtJson = convertPieceToNBT(piece, blueprint);

            Path nbtPath = outputDir.resolve(piece.name + ".nbt");
            Files.writeString(nbtPath, GSON.toJson(nbtJson));
            exported.put(piece.name, nbtPath);

            LogManager.serverInfo(TAG, "Exported piece '%s' for LiteLab: %s", piece.name, nbtPath);
        }

        // Also export the full blueprint JSON
        Path blueprintPath = outputDir.resolve("blueprint.json");
        Files.writeString(blueprintPath, GSON.toJson(blueprint));

        LogManager.serverInfo(TAG, "Exported %d pieces + blueprint to: %s",
            exported.size(), outputDir);

        return blueprintPath;
    }

    /**
     * Export a single piece to NBT JSON format compatible with LiteLab import.
     */
    public static JsonObject convertPieceToNBT(StructurePiece piece, StructureBlueprint blueprint) {
        JsonObject nbt = new JsonObject();

        // LiteLab NBT format
        nbt.addProperty("DataVersion", 3465); // MC 1.21.1

        JsonArray size = new JsonArray();
        size.add(piece.size[0]);
        size.add(piece.size[1]);
        size.add(piece.size[2]);
        nbt.add("size", size);

        // Palette: air + jigsaw blocks
        JsonArray palette = new JsonArray();
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();

        // Entry 0: air
        JsonObject airEntry = new JsonObject();
        airEntry.addProperty("Name", "minecraft:air");
        JsonObject airProps = new JsonObject();
        airEntry.add("Properties", airProps);
        palette.add(airEntry);
        paletteIndex.put("minecraft:air", 0);

        // Add jigsaw blocks to palette
        for (JigsawBlockDefinition jbd : piece.jigsawBlocks) {
            int idx = palette.size();
            JsonObject jigsawEntry = new JsonObject();
            jigsawEntry.addProperty("Name", "minecraft:jigsaw");
            JsonObject props = new JsonObject();
            props.addProperty("name", jbd.name);
            props.addProperty("pool", jbd.targetPool);
            props.addProperty("targetName", jbd.targetName);
            props.addProperty("finalState", jbd.finalState);
            props.addProperty("jointType", jbd.jointType.name().toLowerCase());
            props.addProperty("facing", jbd.facing);
            jigsawEntry.add("Properties", props);
            palette.add(jigsawEntry);
            paletteIndex.put("minecraft:jigsaw:" + jbd.name, idx);
        }

        nbt.add("palette", palette);

        // Blocks: only non-air blocks
        JsonArray blocks = new JsonArray();
        for (JigsawBlockDefinition jbd : piece.jigsawBlocks) {
            JsonObject block = new JsonObject();

            JsonArray pos = new JsonArray();
            pos.add(jbd.x);
            pos.add(jbd.y);
            pos.add(jbd.z);
            block.add("pos", pos);

            block.addProperty("state", paletteIndex.getOrDefault("minecraft:jigsaw:" + jbd.name, 0));
            blocks.add(block);
        }

        nbt.add("blocks", blocks);

        return nbt;
    }

    /**
     * Convert a blueprint to plain JSON string.
     */
    public static String toJson(StructureBlueprint blueprint) {
        return GSON.toJson(blueprint);
    }

    /**
     * Generate a complete datapack folder structure from a blueprint.
     */
    public static Path exportAsDatapack(StructureBlueprint blueprint, Path outputDir) throws IOException {
        String ns = blueprint.namespace;

        // Create pack.mcmeta
        Path packDir = outputDir.resolve(blueprint.structureId + "_datapack");
        Files.createDirectories(packDir);

        String packMcmeta = """
            {
              "pack": {
                "pack_format": 18,
                "description": "Datapack for %s structure"
              }
            }
            """.formatted(blueprint.structureId);
        Files.writeString(packDir.resolve("pack.mcmeta"), packMcmeta);

        // Export blueprint for server-side loading
        Path blueprintPath = exportBlueprint(blueprint, packDir);

        LogManager.serverInfo(TAG, "Exported datapack to: %s", packDir);
        return packDir;
    }
}
