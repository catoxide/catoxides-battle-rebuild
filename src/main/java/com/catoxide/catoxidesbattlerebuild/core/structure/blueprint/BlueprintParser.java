package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import com.google.gson.*;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses structure blueprint JSON files into StructureBlueprint objects.
 */
public final class BlueprintParser {
    private static final String TAG = "BlueprintParser";
    private BlueprintParser() {}

    public static StructureBlueprint parse(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            JsonObject root = element.getAsJsonObject();
            StructureBlueprint blueprint = new StructureBlueprint();

            if (root.has("structureId")) {
                blueprint.structureId = root.get("structureId").getAsString();
            } else {
                throw new BlueprintParseException("Missing required field: structureId");
            }

            if (root.has("namespace")) blueprint.namespace = root.get("namespace").getAsString();
            if (root.has("startPool")) blueprint.startPool = root.get("startPool").getAsString();
            if (root.has("maxDistanceFromCenter")) blueprint.maxDistanceFromCenter = root.get("maxDistanceFromCenter").getAsInt();
            if (root.has("structureSize")) blueprint.structureSize = root.get("structureSize").getAsInt();
            if (root.has("terrainAdaptation")) {
                String ta = root.get("terrainAdaptation").getAsString().toUpperCase().replace('-', '_');
                blueprint.terrainAdaptation = TerrainAdaptation.valueOf(ta);
            }
            if (root.has("fallbackPool")) blueprint.fallbackPool = root.get("fallbackPool").getAsString();
            if (root.has("processors")) {
                for (JsonElement el : root.get("processors").getAsJsonArray()) {
                    blueprint.processors.add(el.getAsString());
                }
            }
            if (root.has("biomesResourceLocation")) blueprint.biomesResourceLocation = root.get("biomesResourceLocation").getAsString();

            // Parse pieces
            if (root.has("pieces")) {
                for (JsonElement el : root.get("pieces").getAsJsonArray()) {
                    blueprint.pieces.add(parsePiece(el.getAsJsonObject()));
                }
            }

            // Parse connections
            if (root.has("connections")) {
                for (JsonElement el : root.get("connections").getAsJsonArray()) {
                    blueprint.connections.add(parseConnection(el.getAsJsonObject()));
                }
            }

            // Parse placement
            if (root.has("placement")) {
                blueprint.placement = parsePlacementConfig(root.get("placement").getAsJsonObject());
            }

            LogManager.serverInfo(TAG, "Parsed blueprint: %s (%d pieces, %d connections)",
                blueprint.structureId, blueprint.pieces.size(), blueprint.connections.size());
            return blueprint;

        } catch (BlueprintParseException e) { throw e; }
        catch (Exception e) { throw new BlueprintParseException("Failed to parse blueprint: " + e.getMessage(), e); }
    }

    public static StructureBlueprint parseFile(Path path) throws IOException {
        String content = Files.readString(path);
        StructureBlueprint result = parse(content);
        LogManager.serverInfo(TAG, "Loaded blueprint from: %s", path);
        return result;
    }

    public static StructureBlueprint parseResource(String resourcePath) {
        URL url = BlueprintParser.class.getClassLoader().getResource(resourcePath);
        if (url == null) throw new BlueprintParseException("Resource not found: " + resourcePath);
        try (java.io.Reader reader = new java.io.InputStreamReader(url.openStream())) {
            char[] chars = new char[(int) url.openConnection().getContentLengthLong()];
            reader.read(chars);
            return parse(new String(chars));
        } catch (IOException e) {
            throw new BlueprintParseException("Failed to read resource: " + resourcePath, e);
        }
    }

    private static StructurePiece parsePiece(JsonObject obj) {
        StructurePiece piece = new StructurePiece();
        piece.name = obj.get("name").getAsString();
        if (obj.has("templatePath")) piece.templatePath = obj.get("templatePath").getAsString();
        if (obj.has("size")) {
            JsonArray sa = obj.get("size").getAsJsonArray();
            piece.size = new int[sa.size()];
            for (int i = 0; i < sa.size(); i++) piece.size[i] = sa.get(i).getAsInt();
        }
        if (obj.has("weight")) piece.weight = obj.get("weight").getAsInt();
        if (obj.has("projection")) piece.projection = obj.get("projection").getAsString();
        if (obj.has("isStart")) piece.isStart = obj.get("isStart").getAsBoolean();
        if (obj.has("jigsawBlocks")) {
            for (JsonElement el : obj.get("jigsawBlocks").getAsJsonArray()) {
                piece.jigsawBlocks.add(parseJigsawBlock(el.getAsJsonObject()));
            }
        }
        return piece;
    }

    private static JigsawBlockDefinition parseJigsawBlock(JsonObject obj) {
        JigsawBlockDefinition jbd = new JigsawBlockDefinition();
        jbd.x = obj.has("x") ? obj.get("x").getAsInt() : 0;
        jbd.y = obj.has("y") ? obj.get("y").getAsInt() : 0;
        jbd.z = obj.has("z") ? obj.get("z").getAsInt() : 0;
        jbd.name = obj.get("name").getAsString();
        jbd.targetPool = obj.has("targetPool") ? obj.get("targetPool").getAsString() : "";
        jbd.targetName = obj.has("targetName") ? obj.get("targetName").getAsString() : "";
        jbd.finalState = obj.has("finalState") ? obj.get("finalState").getAsString() : "minecraft:air";
        if (obj.has("jointType")) jbd.jointType = JigsawBlockDefinition.JointType.valueOf(obj.get("jointType").getAsString().toUpperCase());
        jbd.maxDepth = obj.has("maxDepth") ? obj.get("maxDepth").getAsInt() : 4;
        jbd.facing = obj.has("facing") ? obj.get("facing").getAsString() : "up";
        return jbd;
    }

    private static JigsawConnection parseConnection(JsonObject obj) {
        JigsawConnection conn = new JigsawConnection();
        conn.fromPiece = obj.has("fromPiece") ? obj.get("fromPiece").getAsString() : "";
        conn.fromJigsaw = obj.has("fromJigsaw") ? obj.get("fromJigsaw").getAsString() : "";
        conn.toPiece = obj.has("toPiece") ? obj.get("toPiece").getAsString() : "";
        conn.toJigsaw = obj.has("toJigsaw") ? obj.get("toJigsaw").getAsString() : "";
        conn.weight = obj.has("weight") ? obj.get("weight").getAsInt() : 1;
        return conn;
    }

    private static PlacementConfig parsePlacementConfig(JsonObject obj) {
        PlacementConfig config = new PlacementConfig();
        config.spacing = obj.has("spacing") ? obj.get("spacing").getAsInt() : 24;
        config.separation = obj.has("separation") ? obj.get("separation").getAsInt() : 8;
        config.salt = obj.has("salt") ? obj.get("salt").getAsLong() : 0L;
        config.recommendedDensity = obj.has("recommendedDensity") ? obj.get("recommendedDensity").getAsDouble() : 1.0;
        config.type = obj.has("type") ? obj.get("type").getAsString() : "random_spread";
        config.heightType = obj.has("heightType") ? obj.get("heightType").getAsString() : "motion_blocking";
        config.heightOffset = obj.has("heightOffset") ? obj.get("heightOffset").getAsInt() : 0;
        if (obj.has("biomes")) for (JsonElement el : obj.get("biomes").getAsJsonArray()) config.biomes.add(el.getAsString());
        if (obj.has("excludedBiomes")) for (JsonElement el : obj.get("excludedBiomes").getAsJsonArray()) config.excludedBiomes.add(el.getAsString());
        if (obj.has("exclusionZones")) {
            for (JsonElement el : obj.get("exclusionZones").getAsJsonArray()) {
                JsonObject zone = el.getAsJsonObject();
                config.exclusionZones.add(new PlacementConfig.ExclusionZone(
                    zone.has("structureType") ? zone.get("structureType").getAsString() : "",
                    zone.has("distance") ? zone.get("distance").getAsInt() : 0));
            }
        }
        return config;
    }

    public static class BlueprintParseException extends RuntimeException {
        public BlueprintParseException(String message) { super(message); }
        public BlueprintParseException(String message, Throwable cause) { super(message, cause); }
    }
}
