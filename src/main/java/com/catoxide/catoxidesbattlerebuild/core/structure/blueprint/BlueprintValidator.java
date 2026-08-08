package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates StructureBlueprint objects for correctness.
 * Returns a list of error messages (empty = valid).
 */
public final class BlueprintValidator {

    private static final String TAG = "BlueprintValidator";

    private BlueprintValidator() {}

    /**
     * Validate a blueprint and return error messages. Empty list = valid.
     */
    public static List<String> validate(StructureBlueprint blueprint) {
        List<String> errors = new ArrayList<>();

        if (blueprint == null) {
            errors.add("Blueprint is null");
            return errors;
        }

        // Required fields
        if (blueprint.structureId == null || blueprint.structureId.isBlank()) {
            errors.add("structureId is required");
        }
        if (blueprint.startPool == null || blueprint.startPool.isBlank()) {
            errors.add("startPool is required");
        }

        // Pieces validation
        if (blueprint.pieces.isEmpty()) {
            errors.add("At least one piece is required");
        } else {
            Set<String> pieceNames = new HashSet<>();
            for (StructurePiece piece : blueprint.pieces) {
                if (piece.name == null || piece.name.isBlank()) {
                    errors.add("Piece has no name");
                    continue;
                }
                if (pieceNames.contains(piece.name)) {
                    errors.add("Duplicate piece name: " + piece.name);
                }
                pieceNames.add(piece.name);

                // Validate jigsaw blocks
                for (JigsawBlockDefinition jbd : piece.jigsawBlocks) {
                    if (jbd.name == null || jbd.name.isBlank()) {
                        errors.add("Jigsaw block in piece '" + piece.name + "' has no name");
                    }
                    if (jbd.targetPool == null || jbd.targetPool.isBlank()) {
                        errors.add("Jigsaw '" + jbd.name + "' in piece '" + piece.name + "' has no targetPool");
                    }
                    // Bounds check
                    if (jbd.x < 0 || jbd.x >= piece.size[0] ||
                        jbd.y < 0 || jbd.y >= piece.size[1] ||
                        jbd.z < 0 || jbd.z >= piece.size[2]) {
                        errors.add("Jigsaw '" + jbd.name + "' in piece '" + piece.name +
                            "' is outside piece bounds (" + piece.size[0] + "x" +
                            piece.size[1] + "x" + piece.size[2] + ")");
                    }
                }
            }
        }

        // Connection validation
        for (JigsawConnection conn : blueprint.connections) {
            if (conn.fromPiece == null || conn.fromPiece.isBlank()) {
                errors.add("Connection has no fromPiece");
                continue;
            }
            if (conn.toPiece == null || conn.toPiece.isBlank()) {
                errors.add("Connection from '" + conn.fromPiece + "' has no toPiece");
                continue;
            }
            // Check pieces exist
            if (blueprint.getPiece(conn.fromPiece) == null) {
                errors.add("Connection references unknown piece: " + conn.fromPiece);
            }
            if (blueprint.getPiece(conn.toPiece) == null) {
                errors.add("Connection references unknown piece: " + conn.toPiece);
            }
            // Check jigsaws exist on pieces
            StructurePiece fromPiece = blueprint.getPiece(conn.fromPiece);
            if (fromPiece != null && conn.fromJigsaw != null && !conn.fromJigsaw.isBlank() &&
                fromPiece.getJigsawBlock(conn.fromJigsaw) == null) {
                errors.add("Connection references unknown jigsaw '" + conn.fromJigsaw +
                    "' in piece '" + conn.fromPiece + "'");
            }
            StructurePiece toPiece = blueprint.getPiece(conn.toPiece);
            if (toPiece != null && conn.toJigsaw != null && !conn.toJigsaw.isBlank() &&
                toPiece.getJigsawBlock(conn.toJigsaw) == null) {
                errors.add("Connection references unknown jigsaw '" + conn.toJigsaw +
                    "' in piece '" + conn.toPiece + "'");
            }
        }

        // Placement validation
        if (blueprint.placement.spacing <= 0) {
            errors.add("Placement spacing must be positive");
        }
        if (blueprint.placement.separation >= blueprint.placement.spacing) {
            errors.add("Placement separation must be less than spacing");
        }

        // Range validations
        if (blueprint.maxDistanceFromCenter < 1 || blueprint.maxDistanceFromCenter > 256) {
            errors.add("maxDistanceFromCenter must be between 1 and 256");
        }
        if (blueprint.structureSize < 1 || blueprint.structureSize > 5) {
            errors.add("structureSize must be between 1 and 5");
        }

        // Logging
        if (errors.isEmpty()) {
            LogManager.serverInfo(TAG, "Blueprint '%s' validated successfully", blueprint.structureId);
        } else {
            LogManager.serverWarn(TAG, "Blueprint '%s' has %d validation error(s)",
                blueprint.structureId, errors.size());
        }

        return errors;
    }

    /**
     * Validate and throw an exception if invalid.
     */
    public static void validateOrThrow(StructureBlueprint blueprint) {
        List<String> errors = validate(blueprint);
        if (!errors.isEmpty()) {
            throw new BlueprintParser.BlueprintParseException(
                "Blueprint '" + blueprint.structureId + "' validation failed:\n" +
                String.join("\n", errors));
        }
    }
}
