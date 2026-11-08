package example.contentpack.tech.network;

import example.contentpack.tech.TechService;
import example.contentpack.tech.client.TechClientState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 科技树网络包（双端）。
 * <p>payload id 用主 mod 命名空间；{@code optional()} 让未装本包的客户端不被服务端数据包拒绝。
 */
public final class TechNetwork {

    private TechNetwork() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("1").optional();
            registrar.playToClient(TechSyncPayload.TYPE, TechSyncPayload.CODEC,
                    (payload, context) -> context.enqueueWork(() -> TechClientState.receive(
                            payload.unlocked(), payload.points(), payload.visible(),
                            payload.met(), payload.research())));
            registrar.playToClient(TechOpenPayload.TYPE, TechOpenPayload.CODEC,
                    (payload, context) -> context.enqueueWork(
                            () -> TechClientState.open(payload.pos(), payload.tier())));
            registrar.playToClient(TechTableSyncPayload.TYPE, TechTableSyncPayload.CODEC,
                    (payload, context) -> context.enqueueWork(
                            () -> TechClientState.updateTable(payload.pos(), payload.tier())));
            registrar.playToServer(TechStateRequestPayload.TYPE, TechStateRequestPayload.CODEC,
                    TechNetwork::handleStateRequest);
            registrar.playToServer(TechUnlockPayload.TYPE, TechUnlockPayload.CODEC,
                    TechNetwork::handleUnlock);
            registrar.playToServer(TechSubmitPayload.TYPE, TechSubmitPayload.CODEC,
                    TechNetwork::handleSubmit);
            registrar.playToServer(TechUpgradePayload.TYPE, TechUpgradePayload.CODEC,
                    TechNetwork::handleUpgrade);
        });
    }

    private static void handleStateRequest(TechStateRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                TechService.sync(player);
            }
        });
    }

    private static void handleUnlock(TechUnlockPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            TechService.UnlockResult result = TechService.unlock(player, payload.nodeId(), payload.pos());
            switch (result) {
                case OK -> message(player, "tech_tree_pack.msg.unlocked");
                case INSUFFICIENT_RESOURCES -> message(player, "tech_tree_pack.msg.insufficient");
                case MISSING_REQUIREMENT -> message(player, "tech_tree_pack.msg.missing_requirement");
                case NOT_AT_TABLE -> message(player, "tech_tree_pack.msg.not_table");
                case TOO_FAR -> message(player, "tech_tree_pack.msg.too_far");
                case ALREADY_UNLOCKED -> message(player, "tech_tree_pack.msg.already");
                case UNKNOWN_NODE -> message(player, "tech_tree_pack.msg.unknown");
                case NO_TREE -> message(player, "tech_tree_pack.msg.no_tree");
            }
        });
    }

    private static void handleSubmit(TechSubmitPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            TechService.SubmitResult result =
                    TechService.submitResearch(player, payload.researchId(), payload.pos());
            switch (result) {
                case OK -> message(player, "tech_tree_pack.msg.submitted");
                case UNKNOWN -> message(player, "tech_tree_pack.msg.unknown_research");
                case MISSING_REQUIREMENT -> message(player, "tech_tree_pack.msg.missing_requirement");
                case COOLDOWN -> message(player, "tech_tree_pack.msg.cooldown");
                case INSUFFICIENT_RESOURCES -> message(player, "tech_tree_pack.msg.insufficient");
                case NOT_AT_TABLE -> message(player, "tech_tree_pack.msg.not_table");
                case TOO_FAR -> message(player, "tech_tree_pack.msg.too_far");
            }
        });
    }

    private static void handleUpgrade(TechUpgradePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            TechService.UpgradeResult result = TechService.upgradeTable(player, payload.pos());
            switch (result) {
                case OK -> message(player, "tech_tree_pack.msg.upgraded");
                case INSUFFICIENT_RESOURCES -> message(player, "tech_tree_pack.msg.upgrade_insufficient");
                case MAX_TIER -> message(player, "tech_tree_pack.msg.upgrade_max");
                case NOT_AT_TABLE -> message(player, "tech_tree_pack.msg.not_table");
                case TOO_FAR -> message(player, "tech_tree_pack.msg.too_far");
                case NO_TREE -> message(player, "tech_tree_pack.msg.no_tree");
            }
        });
    }

    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
