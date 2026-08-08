package com.catoxide.catoxidesbattlerebuild.core.structure.litelab;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.*;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Imports structures from LiteLab NBT export format.
 * <p>
 * LiteLab exports structures as JSON NBT with:
 * - DataVersion: int
 * - size: [width, height, depth]
 * - palette: list of {Name, Properties}
 * - blocks: list of {pos: [x, y, z], state: paletteIndex}
 *
 * This importer converts that format to our StructureBlueprint format.
 */
public final class LiteLabNBTImporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TAG = "LiteLabImporter";

    private LiteLabNBTImporter() {}

    /**
     * Import a structure from LiteLab's NBT JSON export format.
     *
     * @param nbtJsonPath Path to the .nbt JSON file exported from LiteLab
     * @param structureId ID to assign the imported structure
     * @param namespace   Namespace for the structure
     * @return The imported blueprint, or null on failure
     */
    public static StructureBlueprint importFromNBT(Path nbtJsonPath, String structureId, String namespace) {
        try {
            String content = Files.readString(nbtJsonPath);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();

            LogManager.serverInfo(TAG, "Importing structure from LiteLab export: %s", nbtJsonPath);

            // Parse size
            JsonArray sizeArray = root.getAsJsonArray("size");
            int width = sizeArray.get(0).getAsInt();
            int height = sizeArray.get(1).getAsInt();
            int depth = sizeArray.get(2).getAsInt();

            // Parse palette
            JsonArray paletteArray = root.getAsJsonArray("palette");
            Map<Integer, String> paletteMap = new LinkedHashMap<>();
            for (int i = 0; i < paletteArray.size(); i++) {
                JsonObject entry = paletteArray.get(i).getAsJsonObject();
                String blockName = entry.get("Name").getAsString();
                paletteMap.put(i, blockName);
            }

            // Parse blocks and create a 3D grid
            BlockGrid grid = parseBlocks(root, width, height, depth, paletteMap);

            // Create blueprint
            StructureBlueprint blueprint = new StructureBlueprint(structureId);
            blueprint.namespace = namespace;
            blueprint.startPool = "start";
            blueprint.maxDistanceFromCenter = 112;
            blueprint.structureSize = 2;
            blueprint.terrainAdaptation = TerrainAdaptation.BEARD_CUT;

            // Create a single piece from the import
            StructurePiece piece = new StructurePiece(structureId);
            piece.size = new int[]{width, height, depth};
            piece.projection = "ground";
            piece.isStart = true;
            blueprint.pieces.add(piece);

            // Generate template path
            piece.templatePath = namespace + "/" + structureId;

            // Store the block grid as metadata (will be used by NBT generator)
            // For now, we note that this is a LiteLab import
            LogManager.serverInfo(TAG, "Imported %d blocks from LiteLab: %dx%dx%d",
                grid.getBlockCount(), width, height, depth);

            // Create start pool
            TemplatePool startPool = new TemplatePool("start");
            startPool.fallbackPool = "minecraft:empty";
            TemplatePool.PoolElement element = new TemplatePool.PoolElement(structureId);
            element.weight = 1;
            element.projection = "ground";
            startPool.addElement(element);

            return blueprint;

        } catch (IOException e) {
            LogManager.serverError(TAG, "Failed to import LiteLab NBT from %s: %s",
                nbtJsonPath, e.getMessage(), e);
            return null;
        } catch (Exception e) {
            LogManager.serverError(TAG, "Failed to parse LiteLab NBT from %s: %s",
                nbtJsonPath, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Import from raw JSON string.
     */
    public static StructureBlueprint importFromNBTJson(String nbtJson, String structureId, String namespace) {
        try {
            JsonObject root = JsonParser.parseString(nbtJson).getAsJsonObject();

            // Parse size
            JsonArray sizeArray = root.getAsJsonArray("size");
            int width = sizeArray.get(0).getAsInt();
            int height = sizeArray.get(1).getAsInt();
            int depth = sizeArray.get(2).getAsInt();

            // Parse palette
            JsonArray paletteArray = root.getAsJsonArray("palette");
            Map<Integer, String> paletteMap = new LinkedHashMap<>();
            for (int i = 0; i < paletteArray.size(); i++) {
                JsonObject entry = paletteArray.get(i).getAsJsonObject();
                String blockName = entry.get("Name").getAsString();
                paletteMap.put(i, blockName);
            }

            // Parse blocks
            BlockGrid grid = parseBlocks(root, width, height, depth, paletteMap);

            // Create blueprint
            StructureBlueprint blueprint = new StructureBlueprint(structureId);
            blueprint.namespace = namespace;
            blueprint.startPool = "start";
            blueprint.maxDistanceFromCenter = 112;
            blueprint.structureSize = 2;
            blueprint.terrainAdaptation = TerrainAdaptation.BEARD_CUT;

            StructurePiece piece = new StructurePiece(structureId);
            piece.size = new int[]{width, height, depth};
            piece.projection = "ground";
            piece.isStart = true;
            blueprint.pieces.add(piece);
            piece.templatePath = namespace + "/" + structureId;

            LogManager.serverInfo(TAG, "Imported %d blocks from LiteLab JSON: %dx%dx%d",
                grid.getBlockCount(), width, height, depth);

            return blueprint;

        } catch (Exception e) {
            LogManager.serverError(TAG, "Failed to parse LiteLab NBT JSON: %s", e.getMessage(), e);
            return null;
        }
    }

    private static BlockGrid parseBlocks(JsonObject root, int width, int height, int depth,
                                          Map<Integer, String> paletteMap) {
        BlockGrid grid = new BlockGrid(width, height, depth);

        if (!root.has("blocks")) {
            return grid;
        }

        JsonArray blocksArray = root.getAsJsonArray("blocks");
        for (JsonElement blockEl : blocksArray) {
            JsonObject block = blockEl.getAsJsonObject();

            JsonArray posArray = block.getAsJsonArray("pos");
            int x = posArray.get(0).getAsInt();
            int y = posArray.get(1).getAsInt();
            int z = posArray.get(2).getAsInt();

            int state = block.get("state").getAsInt();
            String blockName = paletteMap.getOrDefault(state, "minecraft:air");

            grid.setBlock(x, y, z, blockName);
        }

        return grid;
    }

    /**
     * 3D block grid for imported structures.
     */
    public static class BlockGrid {
        private final String[][][] blocks;
        private final int width, height, depth;
        private int blockCount = 0;

        public BlockGrid(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.blocks = new String[width][height][depth];
        }

        public void setBlock(int x, int y, int z, String blockName) {
            if (x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < depth) {
                if (!"minecraft:air".equals(blocks[x][y][z]) || !"minecraft:air".equals(blockName)) {
                    blocks[x][y][z] = blockName;
                }
                if (!"minecraft:air".equals(blockName)) {
                    blockCount++;
                }
            }
        }

        public String getBlock(int x, int y, int z) {
            if (x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < depth) {
                return blocks[x][y][z] != null ? blocks[x][y][z] : "minecraft:air";
            }
            return "minecraft:air";
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getDepth() { return depth; }
        public int getBlockCount() { return blockCount; }

        public String[][][] getBlocks() { return blocks; }
    }
}
