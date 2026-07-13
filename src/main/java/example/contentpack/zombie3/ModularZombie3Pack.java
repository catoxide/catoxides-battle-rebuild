package example.contentpack.zombie3;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.sound.MobSoundProfile;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * ModularZombie3 ContentPack 实现
 * <p>这是第一个可插拔模块示例：将 ModularZombie2 的功能从主 mod 中完全剥离，
 * 作为一个独立的 ContentPack JAR 加载。
 */
public class ModularZombie3Pack implements ContentPack {

    DeferredHolder<EntityType<?>, EntityType<ModularZombie3>> MODULAR_ZOMBIE_3;

    @Override
    public String getId() {
        return "modular_zombie_3";
    }

    @Override
    public String getDisplayName() {
        return "Modular Zombie 3 Pack";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void register(ContentPackContext context) {
        // 注册生物实体 + 属性（延迟执行：在 EntityAttributeCreationEvent 中调用 createAttributes()）
        MODULAR_ZOMBIE_3 = context.registerEntity(
                "modular_zombie_3",
                EntityType.Builder.<ModularZombie3>of(ModularZombie3::new, MobCategory.MONSTER)
                        .sized(0.6f, 1.95f),
                ModularZombie3::createAttributes
        );

        // 注册声音事件
        ResourceLocation ambientId = context.id("zombie3.ambient");
        ResourceLocation hurtId = context.id("zombie3.hurt");
        ResourceLocation deathId = context.id("zombie3.death");
        ResourceLocation stepId = context.id("zombie3.step");
        ResourceLocation swingId = context.id("zombie3.swing");

        context.registerSound(ambientId);
        context.registerSound(hurtId);
        context.registerSound(deathId);
        context.registerSound(stepId);
        context.registerSound(swingId);

        // 注册声音配置
        context.registerMobSoundProfile("zombie3", new MobSoundProfile(
                ambientId, hurtId, deathId, stepId, swingId,
                MobSoundProfile.DEFAULT_VOLUME,
                0.85f
        ));
    }

    @Override
    public void init() {
        LogManager.serverInfo("Zombie3Pack", "Initializing ContentPack: {} v{}", getId(), getVersion());
        // Note: Renderer registration is now handled by ContentPackRendererRegistrar
        // via FMLClientSetupEvent. This avoids accessing DeferredHolder.get() before
        // the DeferredRegister is locked by NeoForge.
    }

    /**
     * 在 DeferredRegister 锁定后安全地注册渲染器。
     * 由 ContentPackRendererRegistrar 在 FMLClientSetupEvent 中调用。
     */
    public void registerRenderer() {
        net.minecraft.client.renderer.entity.EntityRenderers.register(
            MODULAR_ZOMBIE_3.get(),
            ModularZombie3Renderer::new
        );
        LogManager.clientInfo("Zombie3Pack", "ModularZombie3Renderer registered via deferred call");
    }

    public DeferredHolder<EntityType<?>, EntityType<ModularZombie3>> getModularZombie3() {
        return MODULAR_ZOMBIE_3;
    }
}
