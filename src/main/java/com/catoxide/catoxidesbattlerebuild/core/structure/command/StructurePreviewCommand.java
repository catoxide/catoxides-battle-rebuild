package com.catoxide.catoxidesbattlerebuild.core.structure.command;

import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructureBlueprint;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructurePiece;
import com.catoxide.catoxidesbattlerebuild.core.structure.manager.StructureManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Structure preview command handler.
 * Usage: /catoxides preview_structure <structure_id> [x] [y] [z]
 */
public final class StructurePreviewCommand {

    private static final String COMMAND_NAME = "preview_structure";

    private StructurePreviewCommand() {}

    /**
     * Register the structure preview command.
     */
    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("catoxides")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal(COMMAND_NAME)
                    .then(Commands.argument("structure_id", StringArgumentType.string())
                        .suggests(StructurePreviewCommand::suggestStructures)
                        .executes(StructurePreviewCommand::executeAtFeet)
                        .then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                            .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(StructurePreviewCommand::executeWithXZ))
                            .then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                    .executes(StructurePreviewCommand::executeWithPosition)))))));
    }

    private static int executeAtFeet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = context.getSource().getPlayerOrException();
        String structureId = StringArgumentType.getString(context, "structure_id");
        BlockPos pos = player.blockPosition();
        return placeStructurePreview(context.getSource(), structureId, pos);
    }

    private static int executeWithXZ(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = context.getSource().getPlayerOrException();
        String structureId = StringArgumentType.getString(context, "structure_id");
        int x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x");
        int z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z");
        BlockPos pos = new BlockPos(x, (int) player.getY(), z);
        return placeStructurePreview(context.getSource(), structureId, pos);
    }

    private static int executeWithPosition(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String structureId = StringArgumentType.getString(context, "structure_id");
        int x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x");
        int y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "y");
        int z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z");
        BlockPos pos = new BlockPos(x, y, z);
        return placeStructurePreview(context.getSource(), structureId, pos);
    }

    private static int placeStructurePreview(CommandSourceStack source, String structureId, BlockPos origin) {
        StructureBlueprint blueprint = findBlueprint(structureId);
        if (blueprint == null) {
            source.sendFailure(Component.literal("Structure not found: " + structureId));
            return 0;
        }

        StructurePiece piece = blueprint.getStartPiece();
        if (piece == null) {
            source.sendFailure(Component.literal("Structure has no start piece"));
            return 0;
        }

        int placed = 0;
        int width = piece.size[0];
        int height = piece.size[1];
        int depth = piece.size[2];

        net.minecraft.world.level.Level level = source.getLevel();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    BlockPos blockPos = origin.offset(x, y, z);

                    // For preview: place glass to show the structure bounds
                    if (x == 0 || x == width - 1 || y == 0 || y == height - 1 || z == 0 || z == depth - 1) {
                        BlockState glass = Blocks.GLASS.defaultBlockState();
                        level.setBlock(blockPos, glass, 3);
                        placed++;
                    }
                }
            }
        }

        final int finalPlaced = placed;
        source.sendSuccess(() -> Component.literal(
            String.format("Preview placed for structure '%s' at %s (%dx%dx%d, %d blocks)",
                structureId, origin, width, height, depth, finalPlaced)), false);

        return placed;
    }

    private static StructureBlueprint findBlueprint(String query) {
        for (StructureBlueprint bp : StructureManager.getAllBlueprints()) {
            if (bp.structureId.equals(query) ||
                (bp.namespace + ":" + bp.structureId).equals(query)) {
                return bp;
            }
        }
        for (StructureBlueprint bp : StructureManager.getAllBlueprints()) {
            if (bp.structureId.contains(query) ||
                (bp.namespace + ":" + bp.structureId).contains(query)) {
                return bp;
            }
        }
        return null;
    }

    private static CompletableFuture<Suggestions> suggestStructures(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        for (StructureBlueprint bp : StructureManager.getAllBlueprints()) {
            builder.suggest(bp.structureId);
            builder.suggest(bp.namespace + ":" + bp.structureId);
        }
        return builder.buildFuture();
    }
}
