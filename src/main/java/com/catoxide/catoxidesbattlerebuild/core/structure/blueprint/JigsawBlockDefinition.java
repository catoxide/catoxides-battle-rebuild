package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import com.google.gson.annotations.SerializedName;

/**
 * Defines a jigsaw block within a structure piece.
 * Maps to vanilla JigsawBlock block states.
 */
public class JigsawBlockDefinition {
    public int x;
    public int y;
    public int z;
    public String name;
    public String targetPool;
    public String targetName = "";
    public String finalState = "minecraft:air";
    public JointType jointType = JointType.PLAIN;
    public int maxDepth = 4;
    public String facing = "up";

    public enum JointType {
        @SerializedName("plain") PLAIN,
        @SerializedName("rolling") ROLLING
    }

    public JigsawBlockDefinition() {}

    public JigsawBlockDefinition(int x, int y, int z, String name, String targetPool) {
        this.x = x; this.y = y; this.z = z;
        this.name = name; this.targetPool = targetPool;
    }
}
