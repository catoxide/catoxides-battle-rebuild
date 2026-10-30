package com.catoxide.catoxidesbattlerebuild.core.structure.converter;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructurePiece;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.TemplatePool;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates template_pool JSON files from TemplatePool definitions.
 * Output: data/<namespace>/worldgen/template_pool/<pool_name>.json
 *
 * <p><b>The emitted shape must match {@code minecraft:template_pool}'s codec exactly</b>,
 * because a malformed file here makes every world unopenable. Modelled on a real vanilla file,
 * e.g. {@code data/minecraft/worldgen/template_pool/pillager_outpost/base_plates.json}:
 * <pre>
 * {
 *   "elements": [
 *     { "weight": 1,
 *       "element": {
 *         "element_type": "minecraft:single_pool_element",
 *         "location": "ns:piece",
 *         "processors": { "processors": [] },
 *         "projection": "rigid"
 *       } }
 *   ],
 *   "fallback": "minecraft:empty"
 * }
 * </pre>
 */
public final class TemplatePoolGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TAG = "TemplatePoolGenerator";

    private TemplatePoolGenerator() {}

    /**
     * Generate the template pool JSON object.
     */
    public static JsonObject generate(TemplatePool pool, String namespace, List<StructurePiece> pieces) {
        if (pool == null) {
            throw new IllegalArgumentException("Pool cannot be null");
        }

        JsonObject root = new JsonObject();

        // Fallback pool. The key is "fallback" (not "fallback_pool") and its value must be a
        // TEMPLATE POOL id - "minecraft:empty_pool_element" is an element *type*, not a pool.
        String fallback = pool.fallbackPool != null && !pool.fallbackPool.isEmpty()
            ? pool.fallbackPool : "minecraft:empty";
        if (fallback.contains("empty_pool_element")) {
            LogManager.serverWarn(TAG,
                "Pool '%s' fallback '%s' is a pool element type, not a template pool; using 'minecraft:empty'",
                pool.name, fallback);
            fallback = "minecraft:empty";
        }
        root.addProperty("fallback", fallback);

        // Elements array
        JsonArray elements = new JsonArray();

        for (TemplatePool.PoolElement element : pool.elements) {
            JsonObject elem = new JsonObject();
            elem.add("element", buildPoolElement(element, namespace, pieces));
            elem.addProperty("weight", Math.max(1, element.weight));
            elements.add(elem);
        }

        root.add("elements", elements);

        LogManager.serverInfo(TAG, "Generated template pool '%s': %d elements", pool.name, elements.size());
        return root;
    }

    /**
     * Build a single pool element JSON object.
     */
    private static JsonObject buildPoolElement(TemplatePool.PoolElement element, String namespace, List<StructurePiece> pieces) {
        JsonObject elem = new JsonObject();

        // Element type - the full registry name, not the bare word "single".
        elem.addProperty("element_type", "minecraft:single_pool_element");

        // Location - points at the NBT template. Key is "location", not "locatable_template".
        elem.addProperty("location", namespace + ":" + element.template);

        // Projection - only "rigid" and "terrain_matching" exist. A piece-level override wins.
        String projection = element.projection;
        for (StructurePiece piece : pieces) {
            if (piece.name != null && piece.name.equals(element.template) && piece.projection != null) {
                projection = piece.projection;
                break;
            }
        }
        elem.addProperty("projection", toVanillaProjection(projection));

        // Processors - a ProcessorList, i.e. {"processors":[...]} or a processor list id.
        // A bare array is not a valid input.
        JsonArray processorList = new JsonArray();
        if (element.processors != null) {
            for (String processor : element.processors) {
                processorList.add(processor);
            }
        }
        JsonObject processors = new JsonObject();
        processors.add("processors", processorList);
        elem.add("processors", processors);

        return elem;
    }

    /**
     * Map a blueprint projection string onto a StructureTemplatePool.Projection name.
     * "ground" (our blueprint default) means "follow the terrain" -> terrain_matching.
     */
    private static String toVanillaProjection(String projection) {
        if (projection == null) return "rigid";
        switch (projection.toLowerCase()) {
            case "rigid": return "rigid";
            case "terrain_matching":
            case "terrain":
            case "ground": return "terrain_matching";
            default:
                LogManager.serverWarn(TAG, "Unknown projection '%s'; using 'rigid'", projection);
                return "rigid";
        }
    }

    /**
     * Write the template pool JSON to a file.
     * Path: data/<namespace>/worldgen/template_pool/<pool_name>.json
     */
    public static Path writeJson(TemplatePool pool, String namespace, List<StructurePiece> pieces, Path dataDir) throws IOException {
        JsonObject json = generate(pool, namespace, pieces);

        // Create the output path
        Path outputPath = dataDir
            .resolve(namespace)
            .resolve("worldgen")
            .resolve("template_pool");

        Files.createDirectories(outputPath);
        outputPath = outputPath.resolve(pool.name + ".json");

        String jsonStr = GSON.toJson(json);
        Files.writeString(outputPath, jsonStr);

        LogManager.serverInfo(TAG, "Wrote template pool JSON: %s", outputPath);
        return outputPath;
    }

    /**
     * Generate a start pool from the first piece of a blueprint.
     */
    public static TemplatePool createStartPool(String name, String namespace, StructurePiece startPiece) {
        TemplatePool pool = new TemplatePool(name);
        pool.fallbackPool = "minecraft:empty";

        TemplatePool.PoolElement element = new TemplatePool.PoolElement(startPiece.name);
        element.weight = startPiece.weight;
        element.projection = startPiece.projection;

        pool.addElement(element);
        return pool;
    }

    /**
     * Generate pools for all pieces in a blueprint.
     * Each piece with jigsaw connections gets its own pool.
     */
    public static List<TemplatePool> generatePoolsForBlueprint(
            String namespace, String blueprintId, List<StructurePiece> pieces) {

        List<TemplatePool> pools = new ArrayList<>();

        // Start pool (contains the first piece)
        TemplatePool startPool = new TemplatePool("start");
        startPool.fallbackPool = "minecraft:empty";
        if (!pieces.isEmpty()) {
            StructurePiece first = pieces.get(0);
            TemplatePool.PoolElement startElement = new TemplatePool.PoolElement(first.name);
            startElement.weight = first.weight;
            startElement.projection = first.projection;
            startPool.addElement(startElement);
        }
        pools.add(startPool);

        // Pools for pieces referenced by jigsaw connections
        for (StructurePiece piece : pieces) {
            if (piece.isStart) continue; // Already in start pool

            // Check if any jigsaw references this piece
            boolean referenced = false;
            for (StructurePiece other : pieces) {
                for (var jbd : other.jigsawBlocks) {
                    if (jbd.targetPool != null && jbd.targetPool.contains(piece.name)) {
                        referenced = true;
                        break;
                    }
                }
                if (referenced) break;
            }

            if (referenced) {
                TemplatePool pool = new TemplatePool(piece.name);
                pool.fallbackPool = "minecraft:empty";
                TemplatePool.PoolElement elem = new TemplatePool.PoolElement(piece.name);
                elem.weight = piece.weight;
                elem.projection = piece.projection;
                pool.addElement(elem);
                pools.add(pool);
            }
        }

        LogManager.serverInfo(TAG, "Generated %d pools for blueprint", pools.size());
        return pools;
    }
}
