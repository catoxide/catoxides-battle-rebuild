package com.catoxide.catoxidesbattlerebuild.core.structure.placement;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.TerrainAdaptation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Terrain adaptation utilities for structure placement.
 */
public final class TerrainAdaptationHelper {

    private TerrainAdaptationHelper() {}

    /**
     * Find the appropriate placement height for a structure based on terrain adaptation settings.
     */
    public static BlockPos adaptPosition(BlockPos origin, TerrainAdaptation adaptation,
                                          ChunkAccess chunk, int heightOffset) {
        if (chunk == null || origin == null) {
            return origin;
        }

        switch (adaptation) {
            case NONE:
                // Fixed height - just apply offset
                return origin.above(heightOffset);

            case BEARD_CUT:
            case BEARD_THIN:
                // Find surface and place structure on it
                return findSurfacePosition(origin, chunk, heightOffset);

            case GROUND_HUG:
                // Find surface and conform to terrain shape
                return findGroundHugPosition(origin, chunk, heightOffset);

            default:
                return origin.above(heightOffset);
        }
    }

    /**
     * Find the surface position using motion blocking heightmap.
     */
    private static BlockPos findSurfacePosition(BlockPos origin, ChunkAccess chunk, int offset) {
        try {
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
            if (y > 0) {
                return new BlockPos(origin.getX(), y + offset, origin.getZ());
            }
        } catch (Exception ignored) {
            // Fall through to default
        }

        return origin.above(offset);
    }

    /**
     * Find position that hugs the ground (for ground_hug adaptation).
     * This is more aggressive about finding a valid ground position.
     */
    private static BlockPos findGroundHugPosition(BlockPos origin, ChunkAccess chunk, int offset) {
        int[] sampleOffsets = {-3, -1, 0, 1, 3};
        int totalY = 0;
        int count = 0;

        for (int dx : sampleOffsets) {
            for (int dz : sampleOffsets) {
                try {
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    if (y > 0) {
                        totalY += y;
                        count++;
                    }
                } catch (Exception ignored) {
                    // Skip invalid samples
                }
            }
        }

        if (count > 0) {
            int avgY = totalY / count;
            return new BlockPos(origin.getX(), avgY + offset, origin.getZ());
        }

        return origin.above(offset);
    }

    /**
     * Get the heightmap type for a given height provider string.
     */
    public static Heightmap.Types getHeightmapType(String heightType) {
        return switch (heightType != null ? heightType.toLowerCase() : "motion_blocking") {
            case "world_surface" -> Heightmap.Types.WORLD_SURFACE;
            case "ocean_floor" -> Heightmap.Types.OCEAN_FLOOR;
            case "ocean_floor_wg" -> Heightmap.Types.OCEAN_FLOOR_WG;
            case "motion_blocking_no_leaves" -> Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
            default -> Heightmap.Types.MOTION_BLOCKING;
        };
    }

    /**
     * Check if the position is in valid terrain (not in deep ocean or void).
     */
    public static boolean isValidTerrain(int y) {
        // Simple bounds check
        return y >= -64 && y <= 320;
    }
}
