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

        // Fallback pool
        root.addProperty("fallback_pool", pool.fallbackPool != null && !pool.fallbackPool.isEmpty()
            ? pool.fallbackPool : "minecraft:empty");

        // Elements array
        JsonArray elements = new JsonArray();

        for (TemplatePool.PoolElement element : pool.elements) {
            JsonObject elem = new JsonObject();

            // Element name (unique identifier)
            String elemName = namespace + ":" + pool.name + "/" + element.template;
            elem.add("element", buildPoolElement(element, namespace, pieces));
            elem.addProperty("weight", element.weight);

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

        // Element type
        elem.addProperty("element_type", "single");

        // Locate reference - points to the NBT template
        String templatePath = namespace + ":" + element.template;
        elem.addProperty("locatable_template", templatePath);

        // Projection
        String projection = "ground";
        if (element.projection != null) {
            projection = element.projection;
        }
        elem.addProperty("projection", projection);

        // Try to find the piece for projection override
        for (StructurePiece piece : pieces) {
            if (piece.name.equals(element.template)) {
                if (piece.projection != null) {
                    projection = piece.projection;
                }
                break;
            }
        }
        elem.addProperty("projection", projection);

        // Processors
        JsonArray processors = new JsonArray();
        for (String processor : element.processors) {
            processors.add(processor);
        }
        elem.add("processors", processors);

        return elem;
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
