package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import com.google.gson.annotations.SerializedName;

/**
 * Defines a connection between two jigsaw blocks in different pieces.
 */
public class JigsawConnection {
    @SerializedName("fromPiece") public String fromPiece;
    @SerializedName("fromJigsaw") public String fromJigsaw;
    @SerializedName("toPiece") public String toPiece;
    @SerializedName("toJigsaw") public String toJigsaw;
    public int weight = 1;

    public JigsawConnection() {}
    public JigsawConnection(String fromPiece, String fromJigsaw, String toPiece, String toJigsaw) {
        this.fromPiece = fromPiece; this.fromJigsaw = fromJigsaw;
        this.toPiece = toPiece; this.toJigsaw = toJigsaw;
    }
}
