package com.catoxide.catoxidesbattlerebuild.core.structure.manager;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.nio.file.Path;

/**
 * Structure system initialization helper.
 * Call init() from your mod constructor to set up the structure system.
 */
public final class StructureSystemInit {

    private static final String TAG = "StructureSystemInit";

    private StructureSystemInit() {}

    /**
     * Initialize the structure system.
     * Call this from your mod constructor.
     */
    public static void init() {
        try {
            Path dataDir = Path.of("data");
            StructureManager.init(dataDir);
            LogManager.serverInfo(TAG, "Structure system initialized");
        } catch (Exception e) {
            LogManager.serverError(TAG, "Failed to initialize structure system: %s", e.getMessage(), e);
        }
    }

    /**
     * Convert all registered structures.
     * Call this during server startup.
     */
    public static void convertStructures() {
        try {
            StructureManager.convertAll();
        } catch (Exception e) {
            LogManager.serverError(TAG, "Failed to convert structures: %s", e.getMessage(), e);
        }
    }
}
