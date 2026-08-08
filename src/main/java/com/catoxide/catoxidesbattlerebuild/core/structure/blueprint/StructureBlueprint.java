package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified structure blueprint format.
 * Authors define structures in JSON using this format.
 * At runtime, BlueprintConverter converts to vanilla NBT + Pool JSON + Structure JSON.
 */
public class StructureBlueprint {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TAG = "StructureBlueprint";

    public String structureId;
    public String namespace = "catoxidesbattlerebuild";
    public String startPool;
    public int maxDistanceFromCenter = 112;
    public int structureSize = 2;
    public TerrainAdaptation terrainAdaptation = TerrainAdaptation.NONE;
    public List<StructurePiece> pieces = new ArrayList<>();
    public List<JigsawConnection> connections = new ArrayList<>();
    public List<String> processors = new ArrayList<>();
    public String fallbackPool = "minecraft:empty_pool_element";
    public PlacementConfig placement = new PlacementConfig();
    public String biomesResourceLocation = null;

    public StructureBlueprint() {}
    public StructureBlueprint(String structureId) { this.structureId = structureId; }

    public String toJson() { return GSON.toJson(this); }

    public void toJsonFile(Path path) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(this, writer);
        }
    }

    public static StructureBlueprint fromJson(String json) {
        return GSON.fromJson(json, StructureBlueprint.class);
    }

    public static StructureBlueprint fromJsonFile(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, StructureBlueprint.class);
        }
    }

    public StructurePiece getPiece(String name) {
        for (StructurePiece piece : pieces) {
            if (piece.name.equals(name)) return piece;
        }
        return null;
    }

    public StructurePiece getStartPiece() {
        return pieces.isEmpty() ? null : pieces.get(0);
    }

    public List<String> validate() {
        return BlueprintValidator.validate(this);
    }
}
