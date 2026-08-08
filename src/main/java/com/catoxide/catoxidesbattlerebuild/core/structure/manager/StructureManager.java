package com.catoxide.catoxidesbattlerebuild.core.structure.manager;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructureBlueprint;
import com.catoxide.catoxidesbattlerebuild.core.structure.converter.BlueprintConverter;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global manager for all registered structures.
 * Handles structure registration, conversion, and lifecycle.
 */
public final class StructureManager {

    private static final String TAG = "StructureManager";

    /** All registered blueprints, keyed by "namespace:structureId" */
    private static final Map<String, StructureBlueprint> REGISTERED = new ConcurrentHashMap<>();

    /** Track which structures have been converted */
    private static final Set<String> CONVERTED = ConcurrentHashMap.newKeySet();

    private static volatile Path outputBaseDir = null;

    private StructureManager() {}

    /**
     * Set the base output directory for converted structure data files.
     */
    public static void setOutputBaseDir(Path dir) {
        outputBaseDir = dir;
        LogManager.serverInfo(TAG, "Output directory set to: %s", dir);
    }

    /**
     * Get the current output base directory.
     */
    public static Path getOutputBaseDir() {
        return outputBaseDir;
    }

    /**
     * Register a structure blueprint.
     *
     * @param blueprint The blueprint to register
     * @return true if registered successfully
     */
    public static boolean registerStructure(StructureBlueprint blueprint) {
        if (blueprint == null) {
            LogManager.serverError(TAG, "Cannot register null structure");
            return false;
        }

        String key = blueprint.namespace + ":" + blueprint.structureId;

        if (REGISTERED.containsKey(key)) {
            LogManager.serverWarn(TAG, "Structure '%s' already registered, skipping", key);
            return false;
        }

        REGISTERED.put(key, blueprint);
        LogManager.serverInfo(TAG, "Registered structure: %s (%d pieces)",
            key, blueprint.pieces.size());
        return true;
    }

    /**
     * Get a registered blueprint by key.
     */
    public static StructureBlueprint getBlueprint(String key) {
        return REGISTERED.get(key);
    }

    /**
     * Get all registered blueprints.
     */
    public static Collection<StructureBlueprint> getAllBlueprints() {
        return Collections.unmodifiableCollection(REGISTERED.values());
    }

    /**
     * Get the number of registered structures.
     */
    public static int getStructureCount() {
        return REGISTERED.size();
    }

    /**
     * Convert all registered structures to vanilla data files.
     * This should be called during world load or mod initialization.
     */
    public static void convertAll() {
        if (outputBaseDir == null) {
            LogManager.serverWarn(TAG, "Cannot convert structures - output directory not set");
            return;
        }

        int successCount = 0;
        int failCount = 0;

        for (Map.Entry<String, StructureBlueprint> entry : REGISTERED.entrySet()) {
            String key = entry.getKey();
            StructureBlueprint blueprint = entry.getValue();

            if (CONVERTED.contains(key)) {
                continue; // Already converted
            }

            try {
                List<Path> files = BlueprintConverter.convert(blueprint, outputBaseDir.toString(), true);
                CONVERTED.add(key);
                successCount++;
                LogManager.serverInfo(TAG, "Converted structure '%s': %d files", key, files.size());
            } catch (Exception e) {
                failCount++;
                LogManager.serverError(TAG, "Failed to convert structure '%s': %s", key, e.getMessage(), e);
            }
        }

        LogManager.serverInfo(TAG, "Conversion complete: %d success, %d failed", successCount, failCount);
    }

    /**
     * Convert a single structure by key.
     */
    public static boolean convertStructure(String key) {
        StructureBlueprint blueprint = REGISTERED.get(key);
        if (blueprint == null) {
            LogManager.serverError(TAG, "Structure '%s' not found", key);
            return false;
        }

        if (outputBaseDir == null) {
            LogManager.serverError(TAG, "Cannot convert - output directory not set");
            return false;
        }

        try {
            List<Path> files = BlueprintConverter.convert(blueprint, outputBaseDir.toString(), true);
            CONVERTED.add(key);
            LogManager.serverInfo(TAG, "Converted structure '%s': %d files", key, files.size());
            return true;
        } catch (Exception e) {
            LogManager.serverError(TAG, "Failed to convert structure '%s': %s", key, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check if a structure has been converted.
     */
    public static boolean isConverted(String key) {
        return CONVERTED.contains(key);
    }

    /**
     * Clear all registered structures and conversion state.
     * Use for testing or world reload.
     */
    public static void clear() {
        REGISTERED.clear();
        CONVERTED.clear();
        LogManager.serverInfo(TAG, "Cleared all structures");
    }

    /**
     * Initialize the structure manager with the mod's resource directory.
     */
    public static void init(Path modDataDir) {
        outputBaseDir = modDataDir;
        LogManager.serverInfo(TAG, "StructureManager initialized with output dir: %s", modDataDir);
    }
}
