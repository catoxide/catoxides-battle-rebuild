package com.catoxide.catoxidesbattlerebuild.core.structure.placement;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.PlacementConfig;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Custom structure placement algorithm.
 * Implements random spread placement with biome filtering and terrain adaptation.
 */
public final class CustomStructurePlacer {

    private static final String TAG = "StructurePlacer";

    private CustomStructurePlacer() {}

    /**
     * Calculate if a structure should spawn at the given chunk coordinates.
     * Uses the vanilla random spread algorithm.
     */
    public static boolean shouldSpawn(int chunkX, int chunkZ, PlacementConfig config, long seed) {
        if (config.spacing <= 0) return false;
        if (config.separation >= config.spacing) return false;

        Random random = new Random(spreadingSeed(seed, chunkX, chunkZ));

        int offsetX = random.nextInt(config.spacing) - random.nextInt(config.spacing);
        int offsetZ = random.nextInt(config.spacing) - random.nextInt(config.spacing);

        int adjustedX = chunkX + offsetX;
        int adjustedZ = chunkZ + offsetZ;

        // Check if this adjusted position is within the separation distance
        int distance = Math.abs(adjustedX - chunkX) * Math.abs(adjustedX - chunkX) +
                       Math.abs(adjustedZ - chunkZ) * Math.abs(adjustedZ - chunkZ);

        return distance < config.separation * config.separation;
    }

    /**
     * Calculate the spreading seed for a given position.
     * Matches the vanilla algorithm.
     */
    private static long spreadingSeed(long seed, int chunkX, int chunkZ) {
        long xSeed = spreadSeed(chunkX, seed);
        long zSeed = spreadSeed(chunkZ, seed);
        return xSeed ^ zSeed;
    }

    private static long spreadSeed(int value, long seed) {
        Random random = new Random(seed ^ value);
        return random.nextLong();
    }

    /**
     * Check if the given chunk position is compatible with the biome filter.
     */
    public static boolean isBiomeCompatible(int chunkX, int chunkZ, int y,
                                             Level level,
                                             PlacementConfig config) {
        // If no biome filter, allow all
        if (config.biomes.isEmpty() && config.excludedBiomes.isEmpty()) {
            return true;
        }

        try {
            // Get biome at the world position
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(chunkX << 4, y, chunkZ << 4);
            Holder<Biome> biome = level.getBiome(pos);

            ResourceKey<Biome> biomeKey = biome.unwrapKey().orElse(Biomes.PLAINS);
            String biomePath = biomeKey.location().toString();

            // Check excluded biomes
            for (String excluded : config.excludedBiomes) {
                if (biomePath.contains(excluded)) {
                    return false;
                }
            }

            // Check included biomes (if specified)
            if (!config.biomes.isEmpty()) {
                for (String included : config.biomes) {
                    if (biomePath.contains(included)) {
                        return true;
                    }
                }
                return false; // Not in any included biome
            }

            return true;
        } catch (Exception e) {
            LogManager.serverWarn(TAG, "Error checking biome compatibility at (%d, %d): %s",
                chunkX, chunkZ, e.getMessage());
            return true; // Default to allowing on error
        }
    }

    /**
     * Find the surface height at the given position using the heightmap.
     */
    @Nullable
    public static BlockPos findSurfacePos(int chunkX, int chunkZ,
                                           net.minecraft.world.level.chunk.ChunkAccess chunk,
                                           PlacementConfig config) {
        try {
            Heightmap.Types heightmapType = switch (config.heightType) {
                case "motion_blocking" -> Heightmap.Types.MOTION_BLOCKING;
                case "world_surface" -> Heightmap.Types.WORLD_SURFACE;
                case "ocean_floor" -> Heightmap.Types.OCEAN_FLOOR;
                case "ocean_floor_wg" -> Heightmap.Types.OCEAN_FLOOR_WG;
                default -> Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
            };

            // Sample center of chunk
            int blockX = chunkX << 4;
            int blockZ = chunkZ << 4;
            int sampleX = blockX + 7;
            int sampleZ = blockZ + 7;

            int y = chunk.getHeight(heightmapType, sampleX, sampleZ);
            if (y > 0) {
                // Apply height offset
                BlockPos pos = new BlockPos(sampleX, y + config.heightOffset, sampleZ);
                return pos;
            }
        } catch (Exception e) {
            LogManager.serverWarn(TAG, "Error finding surface at (%d, %d): %s",
                chunkX, chunkZ, e.getMessage());
        }

        return null;
    }

    /**
     * Generate candidate spawn positions in a region.
     */
    public static List<StructureSpawnCandidate> findSpawnPositions(
            int originChunkX, int originChunkZ, int radiusChunks,
            PlacementConfig config, long seed,
            Level level) {

        List<StructureSpawnCandidate> candidates = new java.util.ArrayList<>();
        int startChunkX = originChunkX - radiusChunks;
        int startChunkZ = originChunkZ - radiusChunks;
        int endChunkX = originChunkX + radiusChunks;
        int endChunkZ = originChunkZ + radiusChunks;

        for (int cx = startChunkX; cx <= endChunkX; cx++) {
            for (int cz = startChunkZ; cz <= endChunkZ; cz++) {
                if (shouldSpawn(cx, cz, config, seed)) {
                    // Biome check would happen here with actual level access
                    candidates.add(new StructureSpawnCandidate(cx, cz, 0));
                }
            }
        }

        LogManager.serverInfo(TAG, "Found %d potential spawn positions in radius %d",
            candidates.size(), radiusChunks);

        return candidates;
    }

    /**
     * A candidate position for structure spawning.
     */
    public record StructureSpawnCandidate(int chunkX, int chunkZ, int y) {}
}
