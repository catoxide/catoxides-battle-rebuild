package com.catoxide.catoxidesbattlerebuild.core.structure.converter;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.*;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Main converter pipeline that orchestrates all generators.
 * Converts a StructureBlueprint into vanilla-compatible data files:
 * - NBT templates for each piece
 * - Template pool JSON files
 * - Structure JSON
 * - Structure set JSON
 */
public final class BlueprintConverter {

    private static final String TAG = "BlueprintConverter";

    private final StructureBlueprint blueprint;
    private final String outputBaseDir;

    private BlueprintConverter(StructureBlueprint blueprint, String outputBaseDir) {
        this.blueprint = blueprint;
        this.outputBaseDir = outputBaseDir;
    }

    /**
     * Convert a blueprint and write all output files to the specified directory.
     * Returns a list of all generated file paths.
     */
    public static List<Path> convert(StructureBlueprint blueprint, String outputBaseDir) {
        return convert(blueprint, outputBaseDir, false);
    }

    /**
     * Convert a blueprint with optional validation.
     */
    public static List<Path> convert(StructureBlueprint blueprint, String outputBaseDir, boolean validate) {
        if (validate) {
            BlueprintValidator.validateOrThrow(blueprint);
        }

        List<Path> generatedFiles = new ArrayList<>();
        String namespace = blueprint.namespace;
        String structureId = blueprint.structureId;

        LogManager.serverInfo(TAG, "Starting conversion: %s -> %s", structureId, outputBaseDir);

        try {
            // Create base directories
            Path dataDir = Path.of(outputBaseDir, "data");
            Path nbtDir = dataDir.resolve(namespace).resolve("structures").resolve(structureId);
            Files.createDirectories(nbtDir);

            // 1. Generate NBT files for each piece
            for (StructurePiece piece : blueprint.pieces) {
                try {
                    Path nbtFile = StructureNBTGenerator.writeNbt(piece, namespace, nbtDir);
                    generatedFiles.add(nbtFile);
                } catch (IOException e) {
                    LogManager.serverError(TAG, "Failed to generate NBT for piece '%s': %s",
                        piece.name, e.getMessage());
                }
            }

            // 2. Generate template pools
            List<TemplatePool> pools = TemplatePoolGenerator.generatePoolsForBlueprint(
                namespace, structureId, blueprint.pieces);
            for (TemplatePool pool : pools) {
                try {
                    Path poolFile = TemplatePoolGenerator.writeJson(pool, namespace, blueprint.pieces, dataDir);
                    generatedFiles.add(poolFile);
                } catch (IOException e) {
                    LogManager.serverError(TAG, "Failed to generate pool '%s': %s",
                        pool.name, e.getMessage());
                }
            }

            // 3. Generate structure JSON
            try {
                Path structFile = StructureJSONGenerator.writeJson(blueprint, namespace, dataDir);
                generatedFiles.add(structFile);
            } catch (IOException e) {
                LogManager.serverError(TAG, "Failed to generate structure JSON: %s", e.getMessage());
            }

            // 4. Generate structure set JSON
            try {
                String setName = structureId + "_set";
                Path setFile = StructureSetGenerator.writeJson(setName, namespace, blueprint, dataDir);
                generatedFiles.add(setFile);
            } catch (IOException e) {
                LogManager.serverError(TAG, "Failed to generate structure set JSON: %s", e.getMessage());
            }

            LogManager.serverInfo(TAG, "Conversion complete: %d files generated for '%s'",
                generatedFiles.size(), structureId);

        } catch (IOException e) {
            LogManager.serverError(TAG, "Failed to create output directories: %s", e.getMessage());
        }

        return generatedFiles;
    }

    /**
     * Generate a datapack zip file from a blueprint.
     * The zip contains all necessary data files for the structure.
     */
    public static Path generateDatapack(StructureBlueprint blueprint, String outputDir) throws IOException {
        // First convert to a temp directory
        Path tempDir = Files.createTempDirectory("structure_datapack_");
        List<Path> files = convert(blueprint, tempDir.toString());

        // Create pack.mcmeta
        Path packMcmeta = tempDir.resolve("pack.mcmeta");
        String packMcmetaContent = """
            {
              "pack": {
                "pack_format": 18,
                "description": "Auto-generated datapack for structure: %s"
              }
            }
            """.formatted(blueprint.structureId);
        Files.writeString(packMcmeta, packMcmetaContent);

        // Create pack icon (placeholder - user should add their own)
        // pack.png would go here

        // Zip the directory
        Path zipPath = Path.of(outputDir, blueprint.structureId + "_datapack.zip");
        zipDirectory(tempDir, zipPath);

        // Cleanup temp
        deleteDirectory(tempDir);

        LogManager.serverInfo(TAG, "Generated datapack: %s", zipPath);
        return zipPath;
    }

    private static void zipDirectory(Path source, Path outputZip) throws IOException {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(outputZip))) {

            Files.walk(source).filter(Files::isRegularFile).forEach(file -> {
                try {
                    Path relative = source.relativize(file);
                    java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(relative.toString());
                    zos.putNextEntry(entry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    LogManager.serverError(TAG, "Failed to zip file %s: %s", file, e.getMessage());
                }
            });
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(file -> {
            try {
                Files.delete(file);
            } catch (IOException e) {
                // Ignore
            }
        });
    }
}
