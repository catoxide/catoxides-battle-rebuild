package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

/**
 * A single structure piece - one NBT template with jigsaw blocks.
 */
public class StructurePiece {
    public String name;
    @SerializedName("templatePath")
    public String templatePath;
    public int[] size = {8, 8, 8};
    public int weight = 1;
    @SerializedName("jigsawBlocks")
    public List<JigsawBlockDefinition> jigsawBlocks = new ArrayList<>();
    public String projection = "ground";
    public boolean isStart = false;

    public StructurePiece() {}
    public StructurePiece(String name) { this.name = name; }

    public void addJigsawBlock(JigsawBlockDefinition def) {
        this.jigsawBlocks.add(def);
    }

    public JigsawBlockDefinition getJigsawBlock(String name) {
        for (JigsawBlockDefinition jbd : jigsawBlocks) {
            if (jbd.name.equals(name)) return jbd;
        }
        return null;
    }
}
