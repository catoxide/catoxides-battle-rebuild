package com.catoxide.catoxidesbattlerebuild.core.structure.converter;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.JigsawBlockDefinition;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructurePiece;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Converts a StructurePiece to vanilla NBT format.
 * Output is a gzip-compressed NBT file compatible with vanilla structure templates.
 */
public final class StructureNBTGenerator {

    private static final String TAG = "StructureNBTGenerator";

    private StructureNBTGenerator() {}

    /**
     * Generate an NBT compound for a structure piece.
     * The NBT follows the vanilla structure template format:
     * - author: "Minecraft"
     * - DataVersion: current data version
     * - palette: list of block states
     * - blocks: compacted block data
     * - size: [width, height, depth]
     * - nbtData: optional entity/tile entity data
     * - ui_data: optional
     */
    public static CompoundTag generate(StructurePiece piece, String namespace) {
        if (piece == null) {
            throw new IllegalArgumentException("Piece cannot be null");
        }

        int width = piece.size[0];
        int height = piece.size[1];
        int depth = piece.size[2];

        CompoundTag root = new CompoundTag();

        // Author
        root.putString("author", "Minecraft");

        // Data version (MC 1.21.1 = 3465)
        root.putInt("DataVersion", 3465);

        // Size
        ListTag sizeList = new ListTag();
        sizeList.add(IntTag.valueOf(width));
        sizeList.add(IntTag.valueOf(height));
        sizeList.add(IntTag.valueOf(depth));
        root.put("size", sizeList);

        // Build palette from jigsaw blocks (air + jigsaw blocks)
        Map<String, Integer> paletteMap = new LinkedHashMap<>();
        List<BlockState> palette = new ArrayList<>();

        // Entry 0 is always air
        addPaletteEntry(paletteMap, palette, Blocks.AIR.defaultBlockState());

        // Build block states array (compact format)
        // Each block is stored as [paletteIndex, x, y, z]
        ListTag blocks = new ListTag();

        // Initialize all blocks to air (palette index 0)
        int[][][] blockData = new int[width][height][depth];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    blockData[x][y][z] = 0; // air
                }
            }
        }

        // Place jigsaw blocks at their positions
        for (JigsawBlockDefinition jbd : piece.jigsawBlocks) {
            if (jbd.x >= 0 && jbd.x < width &&
                jbd.y >= 0 && jbd.y < height &&
                jbd.z >= 0 && jbd.z < depth) {

                // Find or create palette entry for jigsaw block
                String jigsawState = buildJigsawBlockState(jbd);
                int paletteIndex = getOrCreatePaletteIndex(paletteMap, palette, jigsawState);

                blockData[jbd.x][jbd.y][jbd.z] = paletteIndex;
            }
        }

        // Write non-air blocks to the blocks list
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    int paletteIdx = blockData[x][y][z];
                    if (paletteIdx != 0) { // skip air
                        ListTag blockEntry = new ListTag();
                        blockEntry.add(IntTag.valueOf(paletteIdx));
                        blockEntry.add(IntTag.valueOf(x));
                        blockEntry.add(IntTag.valueOf(y));
                        blockEntry.add(IntTag.valueOf(z));
                        blocks.add(blockEntry);
                    }
                }
            }
        }
        root.put("blocks", blocks);

        // Palette
        ListTag paletteNbt = new ListTag();
        for (BlockState state : palette) {
            CompoundTag stateTag = new CompoundTag();
            stateTag.putString("Name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            if (!state.getProperties().isEmpty()) {
                CompoundTag properties = new CompoundTag();
                for (var prop : state.getProperties()) {
                    properties.putString(prop.getName(), String.valueOf(state.getValue(prop)));
                }
                stateTag.put("Properties", properties);
            }
            paletteNbt.add(stateTag);
        }
        root.put("palette", paletteNbt);

        // Empty optional tags
        root.put("nbtData", new ListTag());
        root.put("nbtTables", new ListTag());
        root.put("entities", new ListTag());
        root.put("liquidBlocks", new ListTag());

        LogManager.serverInfo(TAG, "Generated NBT for piece '%s': %d palette entries, %d blocks",
            piece.name, palette.size(), blocks.size());

        return root;
    }

    /**
     * Write the NBT to a gzip-compressed .nbt file.
     */
    public static Path writeNbt(StructurePiece piece, String namespace, Path outputDir) throws IOException {
        CompoundTag nbt = generate(piece, namespace);

        Path outputPath = outputDir.resolve(piece.name + ".nbt");

        // Serialize NBT to bytes
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        try (DataOutputStream dataOut = new DataOutputStream(byteOut)) {
            NbtIo.write(nbt, dataOut);
        }

        // Compress with gzip
        byte[] compressed = compress(byteOut.toByteArray());

        // Write to file
        Files.createDirectories(outputDir.getParent());
        Files.write(outputPath, compressed);

        LogManager.serverInfo(TAG, "Wrote NBT file: %s (%d bytes compressed)",
            outputPath.getFileName(), compressed.length);

        return outputPath;
    }

    /**
     * Build a block state string for a jigsaw block.
     * Format: minecraft:jigsaw[name=NAME;pool=POOL;targetName=TARGET;finalState=STATE;jointType=TYPE;facing=DIRECTION]
     */
    private static String buildJigsawBlockState(JigsawBlockDefinition jbd) {
        StringBuilder sb = new StringBuilder("minecraft:jigsaw[");
        List<String> props = new ArrayList<>();

        if (jbd.name != null && !jbd.name.isEmpty()) {
            props.add("name=" + jbd.name);
        }
        if (jbd.targetPool != null && !jbd.targetPool.isEmpty()) {
            props.add("pool=" + jbd.targetPool);
        }
        if (jbd.targetName != null && !jbd.targetName.isEmpty()) {
            props.add("targetName=" + jbd.targetName);
        }
        if (jbd.finalState != null && !jbd.finalState.isEmpty()) {
            props.add("finalState=" + jbd.finalState);
        }
        if (jbd.jointType != null) {
            props.add("jointType=" + jbd.jointType.name().toLowerCase());
        }
        if (jbd.facing != null) {
            props.add("facing=" + jbd.facing);
        }

        sb.append(String.join(";", props));
        sb.append("]");
        return sb.toString();
    }

    private static void addPaletteEntry(Map<String, Integer> paletteMap, List<BlockState> palette, BlockState state) {
        String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!paletteMap.containsKey(key)) {
            paletteMap.put(key, palette.size());
            palette.add(state);
        }
    }

    private static int getOrCreatePaletteIndex(Map<String, Integer> paletteMap, List<BlockState> palette, String stateStr) {
        if (paletteMap.containsKey(stateStr)) {
            return paletteMap.get(stateStr);
        }
        // Parse the block state string
        int bracketStart = stateStr.indexOf('[');
        String blockName = bracketStart > 0 ? stateStr.substring(0, bracketStart) : stateStr;

        // Try to resolve to a BlockState
        BlockState state;
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.parse(blockName);
            net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.get(rl);
            state = block.defaultBlockState();
        } catch (Exception e) {
            // Fallback to air if block not found
            LogManager.serverWarn(TAG, "Unknown block: %s, using air", blockName);
            state = Blocks.AIR.defaultBlockState();
        }

        int index = palette.size();
        paletteMap.put(stateStr, index);
        palette.add(state);
        return index;
    }

    private static byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }
}
