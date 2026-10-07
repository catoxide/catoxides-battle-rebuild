package example.contentpack.meleecombat;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.combat.ArrowCombatHandlers;
import example.contentpack.meleecombat.combat.CombatConfig;
import com.catoxide.catoxidesbattlerebuild.core.combat.HurtInterceptors;
import com.catoxide.catoxidesbattlerebuild.core.combat.SwordAttackHandlers;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ClientRenderRegistrar;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.durability.DurabilitySupport;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.meleecombat.client.SwordPlayerModelHandler;
import example.contentpack.meleecombat.client.renderer.CustomProjectileRenderer;
import example.contentpack.meleecombat.client.renderer.SwordHitboxDebugRenderer;
import example.contentpack.meleecombat.handler.ArrowCombatHandler;
import example.contentpack.meleecombat.handler.MeleeHurtInterceptor;
import example.contentpack.meleecombat.handler.SwordAttackHandler;
import example.contentpack.meleecombat.network.HitAttemptPacket;
import example.contentpack.meleecombat.network.SyncBodyPartConfigPacket;
import example.contentpack.meleecombat.network.SyncDamageResultPacket;
import example.contentpack.meleecombat.projectile.CustomProjectileEntity;
import example.contentpack.meleecombat.weapon.CustomBowWeapon;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 近战战斗内容包（M6：伤害模块自 core 整体迁入）。
 *
 * <h3>内容</h3>
 * <ul>
 *   <li>三个战斗 hook（F4 单一注册点，自 core 主类迁入）：近战伤害拦截 / 剑攻击 / 弓矢命中</li>
 *   <li>战斗（穿深）配置 catoxides-combat.toml（文件名不变，既有用户配置继续生效）</li>
 *   <li>自定义弓链：CustomProjectileEntity + CustomBowWeapon（主 mod 命名空间下注册）</li>
 *   <li>三个战斗 payload：hit_attempt / sync_damage_result / sync_body_part_config</li>
 *   <li>客户端：F6 剑碰撞盒调试可视化 + 剑叠加层（SwordPlayerModelHandler）+ 投射物渲染器</li>
 * </ul>
 *
 * <h3>A7</h3>
 * 包不能用 @EventBusSubscriber（FML 只扫主 mod jar），全部事件显式 addListener；
 * 客户端监听器仅在 Dist.CLIENT 下注册（dedicated server 安全）。
 *
 * <h3>R3</h3>
 * 不装本 jar 时零注册：剑 = 原版武器、弓 = 原版、无部位伤害 / 无叠加层 / 无 F6。
 */
public class MeleeCombatPack implements ContentPack {

    private static final String TAG = "MeleeCombatPack";

    /** 自定义弓链注册表（主 mod 命名空间；register() 中创建） */
    public static DeferredRegister<EntityType<?>> ENTITIES;
    public static DeferredHolder<EntityType<?>, EntityType<CustomProjectileEntity>> CUSTOM_PROJECTILE;
    public static DeferredRegister<Item> ITEMS;
    public static DeferredHolder<Item, Item> CUSTOM_BOW;

    @Override
    public String getId() {
        return "melee_combat_pack";
    }

    @Override
    public String getDisplayName() {
        return "Melee Combat Pack";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void register(ContentPackContext context) {
        IEventBus modBus = context.getModEventBus();

        // ===== 1. F4 单一注册点：三个战斗 hook（M6：自 core CatoxidesBattleRebuild 构造器迁入）=====
        HurtInterceptors.register(new MeleeHurtInterceptor());
        SwordAttackHandlers.register(new SwordAttackHandler());
        ArrowCombatHandlers.register(new ArrowCombatHandler());

        // ===== 2. 战斗（穿深）配置：文件名保持 catoxides-combat.toml → 既有用户配置继续生效 =====
        context.registerServerConfig(CombatConfig.SPEC, "catoxides-combat");

        // ===== 3. 自定义弓链：实体 + 物品（主 mod 命名空间；core ModEntities/ModWeapons 已不再持有）=====
        // ctx.registerEntity 仅支持 LivingEntity，非 Living 实体自建 DeferredRegister（与物品同款模式）
        ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, context.getModId());
        CUSTOM_PROJECTILE = ENTITIES.register("custom_projectile",
                () -> EntityType.Builder.<CustomProjectileEntity>of(CustomProjectileEntity::new, MobCategory.MISC)
                        .sized(0.05f, 0.05f)
                        .build("custom_projectile"));
        ENTITIES.register(modBus);

        ITEMS = DeferredRegister.create(Registries.ITEM, context.getModId());
        CUSTOM_BOW = ITEMS.register("custom_bow",
                () -> new CustomBowWeapon(new Item.Properties().stacksTo(1)));
        ITEMS.register(modBus);

        // 有限耐久：弓接入 core 耐久端口（原主类与铁剑一起注册；剑留 core，主类仍注册剑）
        modBus.addListener((RegisterCapabilitiesEvent e) ->
                DurabilitySupport.registerVariableDurability(e, CUSTOM_BOW.get()));

        // ===== 4. 网络：三个战斗 payload（V1 先例：包自注册 payload，registrar("1") → 主 mod 协议 "1"）=====
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> {
            PayloadRegistrar reg = e.registrar("1");
            reg.playBidirectional(HitAttemptPacket.TYPE, HitAttemptPacket.STREAM_CODEC,
                    new DirectionalPayloadHandler<>(
                            MeleeCombatPack::handleHitAttemptOnClient,
                            MeleeCombatPack::handleHitAttemptOnServer));
            reg.playBidirectional(SyncDamageResultPacket.TYPE, SyncDamageResultPacket.STREAM_CODEC,
                    new DirectionalPayloadHandler<>(
                            MeleeCombatPack::handleSyncDamageResultOnClient,
                            MeleeCombatPack::handleSyncDamageResultOnServer));
            reg.playBidirectional(SyncBodyPartConfigPacket.TYPE, SyncBodyPartConfigPacket.STREAM_CODEC,
                    new DirectionalPayloadHandler<>(
                            MeleeCombatPack::handleSyncBodyPartConfigOnClient,
                            MeleeCombatPack::handleSyncBodyPartConfigOnServer));
        });

        // ===== 5. 客户端（A7：显式监听器、包自注册；dedicated server 跳过）=====
        if (CatoxidesBattleRebuildConstants.CLIENT) {
            // F6 剑碰撞盒调试（原 core ClientEvents + SwordHitboxDebugRenderer 的 @EventBusSubscriber）
            SwordHitboxDebugRenderer.init(modBus);
            NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent e) -> {
                if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                    return;
                }
                SwordHitboxDebugRenderer.render(e.getPoseStack(),
                        e.getCamera().getPosition(),
                        e.getPartialTick().getGameTimeDeltaPartialTick(false));
            });
            NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post e) ->
                    SwordHitboxDebugRenderer.renderHud(e.getGuiGraphics()));
            // 剑叠加层（SwordPlayerModelHandler 的 3 个 @SubscribeEvent → 显式注册）
            SwordPlayerModelHandler.init();
        }

        LogManager.serverInfo(TAG, "MeleeCombatPack registered (hooks=3, config, bow chain, payloads=3, client=%s)",
                CatoxidesBattleRebuildConstants.CLIENT);
    }

    @Override
    public void registerClientRenderers(ClientRenderRegistrar registrar) {
        // 主 mod 在 Dist.CLIENT 的 EntityRenderersEvent.RegisterRenderers 中调用
        registrar.register(CUSTOM_PROJECTILE, CustomProjectileRenderer::new);
    }

    // ===== payload handlers（自 core ModNetworkHandler 迁入，逻辑不变）=====

    private static void handleHitAttemptOnClient(HitAttemptPacket packet, IPayloadContext context) {
    }

    private static void handleHitAttemptOnServer(HitAttemptPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                CatoxidesBattleRebuildConstants.LOGGER.debug("Received hit attempt: entityId={}, boneName={}",
                        packet.entityId(), packet.boneName()));
    }

    private static void handleSyncDamageResultOnClient(SyncDamageResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                CatoxidesBattleRebuildConstants.LOGGER.debug("Received damage result: entityId={}, damage={}",
                        packet.entityId(), packet.damage()));
    }

    private static void handleSyncDamageResultOnServer(SyncDamageResultPacket packet, IPayloadContext context) {
    }

    private static void handleSyncBodyPartConfigOnClient(SyncBodyPartConfigPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                CatoxidesBattleRebuildConstants.LOGGER.debug("Received body part config: entityId={}",
                        packet.entityId()));
    }

    private static void handleSyncBodyPartConfigOnServer(SyncBodyPartConfigPacket packet, IPayloadContext context) {
    }
}
