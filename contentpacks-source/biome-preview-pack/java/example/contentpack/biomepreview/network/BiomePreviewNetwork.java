package example.contentpack.biomepreview.network;

import com.mojang.logging.LogUtils;
import example.contentpack.biomepreview.client.MapBiomeClientSource;
import example.contentpack.biomepreview.preview.PreviewScheduler;
import example.contentpack.biomepreview.preview.SurfaceBiomeSampler;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * 群系预测的网络桥：客户端请求区域 → 服务端用 {@link PreviewScheduler} 采样 → 回传图。
 *
 * <p>服务端只在收到请求时算，不做后台预生成；多个玩家同时请求会互相替换调度任务
 * （{@link PreviewScheduler} 是单任务，新替旧），自用服务器够用。
 */
public final class BiomePreviewNetwork {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FALLBACK_GRASS = 0x79C05A;

    private BiomePreviewNetwork() {
    }

    public static void register(IEventBus modBus) {
        LOGGER.info("[BiomePreview] registering payload handlers");
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            // 不用 optional：地图请求必须真正到达服务端；双方都装包才启用该功能
            var registrar = event.registrar("1");
            registrar.playToServer(BiomePreviewRequestPayload.TYPE,
                    BiomePreviewRequestPayload.CODEC, BiomePreviewNetwork::handleRequest);
            registrar.playToClient(BiomePreviewResultPayload.TYPE,
                    BiomePreviewResultPayload.CODEC, BiomePreviewNetwork::handleResult);
            LOGGER.info("[BiomePreview] payload handlers registered (request C2S, result S2C)");
        });
    }

    private static void handleRequest(BiomePreviewRequestPayload payload, IPayloadContext context) {
        LOGGER.info("[BiomePreview] handler invoked player={} clientSide={} dim={} center=({},{})",
                context.player() == null ? "null" : context.player().getName().getString(),
                context.player() == null || context.player().level().isClientSide(),
                payload.dimension(), payload.centerX(), payload.centerZ());
        context.enqueueWork(() -> {
            LOGGER.info("[BiomePreview] work started");
            if (!(context.player() instanceof ServerPlayer player)) {
                LOGGER.warn("[BiomePreview] request ignored: player is not ServerPlayer ({})",
                        context.player() == null ? "null" : context.player().getClass().getName());
                return;
            }
            ServerLevel level = player.serverLevel();
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            LOGGER.info("[BiomePreview] work player={} dim={} payloadDim={} biomeSource={} supports={}",
                    player.getGameProfile().getName(), level.dimension().location(), payload.dimension(),
                    generator.getBiomeSource().getClass().getName(),
                    SurfaceBiomeSampler.supports(generator.getBiomeSource()));
            if (!level.dimension().location().equals(payload.dimension())) {
                LOGGER.warn("[BiomePreview] request ignored: dimension mismatch");
                return;
            }
            if (!SurfaceBiomeSampler.supports(generator.getBiomeSource())) {
                LOGGER.warn("[BiomePreview] request ignored: biome source is not MultiNoise");
                return;
            }

            int step = Math.max(SurfaceBiomeSampler.MIN_STEP_BLOCKS, payload.step());
            LOGGER.info("[BiomePreview] request received player={} dim={} center=({},{}) radius={} step={}",
                    player.getGameProfile().getName(), payload.dimension(),
                    payload.centerX(), payload.centerZ(), payload.radius(), step);
            var key = new PreviewCache.Key(payload.dimension(), payload.centerX(),
                    payload.centerZ(), payload.radius(), step);
            PreviewScheduler.Result cached = PreviewCache.get(key);
            if (cached != null) {
                LOGGER.info("[BiomePreview] cache hit center=({},{})",
                        payload.centerX(), payload.centerZ());
                sendResult(player, level, payload.dimension(), cached);
                return;
            }

            SurfaceBiomeSampler sampler = new SurfaceBiomeSampler(
                    generator.getBiomeSource(), level.getChunkSource().randomState());
            PreviewScheduler.submit(sampler, payload.centerX(), payload.centerZ(),
                    payload.radius(), step, result -> {
                        PreviewCache.put(key, result);
                        LOGGER.info("[BiomePreview] sampling done center=({},{}) size={} palette={}",
                                result.centerX(), result.centerZ(), result.size(),
                                result.palette().size());
                        sendResult(player, level, payload.dimension(), result);
                    });
        });
    }

    /** 把一块预测图发给玩家（地图请求和 {@code /biomepreview job} 共用）。 */
    public static void sendResult(ServerPlayer player, ServerLevel level,
                                  ResourceLocation dimension, PreviewScheduler.Result result) {
        int[] colors = new int[result.palette().size()];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = colorOf(level, result.palette().get(i));
        }
        PacketDistributor.sendToPlayer(player, new BiomePreviewResultPayload(
                dimension, result.centerX(), result.centerZ(),
                result.radius(), result.step(), result.size(),
                colors, result.palette().toArray(new String[0]), result.cells()));
        LOGGER.info("[BiomePreview] result sent to {} size={}", player.getGameProfile().getName(),
                result.size());
    }

    private static void handleResult(BiomePreviewResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> MapBiomeClientSource.receive(payload));
    }

    /** 群系 → 地图色：优先固定草色，没有则用默认草绿。 */
    private static int colorOf(ServerLevel level, String biomeId) {
        ResourceLocation id = ResourceLocation.tryParse(biomeId);
        if (id == null) {
            return FALLBACK_GRASS;
        }
        try {
            var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
            var holder = registry.get(ResourceKey.create(Registries.BIOME, id));
            if (holder.isEmpty()) {
                return FALLBACK_GRASS;
            }
            Biome biome = holder.get().value();
            return biome.getSpecialEffects().getGrassColorOverride().orElse(FALLBACK_GRASS);
        } catch (RuntimeException ex) {
            LOGGER.warn("[BiomePreview] color lookup failed for {}: {}", biomeId, ex.getMessage());
            return FALLBACK_GRASS;
        }
    }
}
