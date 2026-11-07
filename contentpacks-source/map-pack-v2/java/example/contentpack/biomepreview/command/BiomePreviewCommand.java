package example.contentpack.biomepreview.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import example.contentpack.biomepreview.network.BiomePreviewNetwork;
import example.contentpack.biomepreview.preview.PreviewAccuracy;
import example.contentpack.biomepreview.preview.PreviewBench;
import example.contentpack.biomepreview.preview.PreviewScheduler;
import example.contentpack.biomepreview.preview.PreviewSettings;
import example.contentpack.biomepreview.preview.SurfaceBiomeSampler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.List;

/**
 * /biomepreview biome [x] [z]                 预测该列地表群系（depth=0 采样，无区块加载）
 * /biomepreview job &lt;radius&gt;               后台生成一块预览图（档位决定步长，不占 tick）
 * /biomepreview cancel                        取消当前后台任务
 * /biomepreview bench &lt;radius&gt; [step]        网格吞吐基准（异步跑，默认 step=4）
 * /biomepreview compare &lt;chunks&gt; [step]      与已生成区块的实际群系对照（会强制加载区块）
 * <p>radius 单位方块、chunks 单位区块；biome/job/bench 不写世界，compare 只读已生成区块数据。
 */
public final class BiomePreviewCommand {

    private BiomePreviewCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("biomepreview")
                .requires(source -> source.hasPermission(2))
                .then(biome())
                .then(job())
                .then(cancel())
                .then(bench())
                .then(compare()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> biome() {
        return Commands.literal("biome")
                .executes(ctx -> biome(ctx.getSource(),
                        BlockPos.containing(ctx.getSource().getPosition())))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> biome(ctx.getSource(), new BlockPos(
                                        IntegerArgumentType.getInteger(ctx, "x"), 0,
                                        IntegerArgumentType.getInteger(ctx, "z"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> job() {
        return Commands.literal("job")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 8192))
                        .executes(ctx -> job(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "radius"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cancel() {
        return Commands.literal("cancel").executes(ctx -> cancel(ctx.getSource()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> bench() {
        return Commands.literal("bench")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 8192))
                        .executes(ctx -> bench(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "radius"),
                                SurfaceBiomeSampler.MIN_STEP_BLOCKS))
                        .then(Commands.argument("step", IntegerArgumentType.integer(
                                        SurfaceBiomeSampler.MIN_STEP_BLOCKS, 64))
                                .executes(ctx -> bench(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        IntegerArgumentType.getInteger(ctx, "step")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> compare() {
        return Commands.literal("compare")
                .then(Commands.argument("chunks", IntegerArgumentType.integer(0, 16))
                        .executes(ctx -> compare(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "chunks"),
                                SurfaceBiomeSampler.MIN_STEP_BLOCKS))
                        .then(Commands.argument("step", IntegerArgumentType.integer(
                                        SurfaceBiomeSampler.MIN_STEP_BLOCKS, 64))
                                .executes(ctx -> compare(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "chunks"),
                                        IntegerArgumentType.getInteger(ctx, "step")))));
    }

    private static int job(CommandSourceStack source, int radius) {
        SurfaceBiomeSampler sampler = samplerFor(source);
        if (sampler == null) {
            return 0;
        }
        int step = PreviewSettings.stepBlocks();
        if (step <= 0) {
            source.sendFailure(Component.literal(
                    "[biomepreview] preview is off (biome-preview-pack.toml -> performance.resolutionTier = 0)"));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        ServerLevel level = source.getLevel();
        PreviewScheduler.submit(sampler, pos.getX(), pos.getZ(), radius, step, result -> {
            source.sendSuccess(() -> Component.literal("[biomepreview] " + result.summary()), false);
            // 手动 job 的结果直接进执行者的地图缓存（和地图自动请求共用同一条链路）
            if (source.getEntity() instanceof ServerPlayer player) {
                BiomePreviewNetwork.sendResult(player, level,
                        level.dimension().location(), result);
            }
        });
        source.sendSuccess(() -> Component.literal(String.format(
                "[biomepreview] job queued: center=%d,%d radius=%d step=%d (background, tick-guarded)",
                pos.getX(), pos.getZ(), radius, step)), false);
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        boolean running = PreviewScheduler.isRunning();
        PreviewScheduler.cancelAll();
        source.sendSuccess(() -> Component.literal(
                running ? "[biomepreview] cancelled" : "[biomepreview] nothing running"), false);
        return running ? 1 : 0;
    }

    private static int biome(CommandSourceStack source, BlockPos pos) {
        SurfaceBiomeSampler sampler = samplerFor(source);
        if (sampler == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[biomepreview] " + pos.getX() + "," + pos.getZ()
                + " -> " + sampler.surfaceBiomeId(pos.getX(), pos.getZ())), false);
        return 1;
    }

    private static int bench(CommandSourceStack source, int radius, int step) {
        SurfaceBiomeSampler sampler = samplerFor(source);
        if (sampler == null) {
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        int threads = Math.min(16, Runtime.getRuntime().availableProcessors());
        source.sendSuccess(() -> Component.literal(String.format(
                "[biomepreview] bench radius=%d step=%d threads=%d running...", radius, step, threads)), false);

        Thread worker = new Thread(() -> {
            try {
                PreviewBench.Report report = PreviewBench.run(sampler, pos.getX(), pos.getZ(), radius, step, threads);
                List<String> lines = PreviewBench.describe(report);
                source.getServer().execute(() -> lines.forEach(line ->
                        source.sendSuccess(() -> Component.literal(line), false)));
            } catch (Throwable throwable) {
                source.getServer().execute(() -> source.sendFailure(
                        Component.literal("[biomepreview] bench failed: " + throwable)));
            }
        }, "biomepreview-bench");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static int compare(CommandSourceStack source, int chunkRadius, int step) {
        SurfaceBiomeSampler sampler = samplerFor(source);
        if (sampler == null) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        ChunkPos center = new ChunkPos(BlockPos.containing(source.getPosition()));
        PreviewAccuracy.Report report = PreviewAccuracy.run(
                level, sampler, center.x, center.z, chunkRadius, step);
        PreviewAccuracy.describe(report).forEach(line ->
                source.sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static SurfaceBiomeSampler samplerFor(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        if (level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.literal("[biomepreview] overworld only (dimension="
                    + level.dimension().location() + ")"));
            return null;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!SurfaceBiomeSampler.supports(generator.getBiomeSource())) {
            source.sendFailure(Component.literal("[biomepreview] unsupported biome source: "
                    + generator.getBiomeSource().getClass().getName()));
            return null;
        }
        return new SurfaceBiomeSampler(generator.getBiomeSource(), level.getChunkSource().randomState());
    }
}
