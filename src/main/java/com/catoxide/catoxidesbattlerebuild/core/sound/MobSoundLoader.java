package com.catoxide.catoxidesbattlerebuild.core.sound;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Map;

/**
 * 生物声音资源加载器
 * <p>扫描资源包，自动发现 {@code .ogg} 文件并注册为 {@code SoundEvent}，
 * 同时构建 {@link MobSoundProfile} 供 ContentPack 注册时绑定到对应实体。
 *
 * <h3>资源约定</h3>
 * <ul>
 *   <li>声音文件路径：{@code assets/<modid>/sounds/<mob_id>/<event_name>.ogg}</li>
 *   <li>对应的 sounds.json 会自动生成（或允许手动提供覆盖）</li>
 *   <li>声音事件 ID：{@code <modid>:<mob_id>.<event_name>}</li>
 * </ul>
 *
 * <h3>加载流程</h3>
 * <ol>
 *   <li>在 {@code AddClientReloadListenerEvent} / {@code AddServerReloadListenerEvent}
 *       注册 {@code SimpleJsonResourceReloadListener}，扫描 {@code sounds/} 目录</li>
 *   <li>对每个 mob_id 子目录，遍历 .ogg 文件，记录为 SoundEvent 候选</li>
 *   <li>在注册阶段（{@code DeferredRegister<SoundEvent>}）为每个候选注册
 *       {@code SoundEvent.createVariableRangeEvent(id)}</li>
 *   <li>构建 {@link MobSoundProfile} 并缓存到 {@code MobSoundRegistry}</li>
 *   <li>ContentPack 的 mob 注册时，从 {@code MobSoundRegistry} 取出 profile 注入</li>
 * </ol>
 *
 * <h3>手动配置覆盖</h3>
 * <p>除了自动扫描 .ogg 外，也支持 mob 配置 JSON 显式指定声音事件 ID
 * （可以引用任意命名空间的 SoundEvent，不限于本 mod）。
 * 例如：{@code "ambient": "minecraft:zombie_ambient"} 可让自定义生物使用原版僵尸的环境音。
 *
 * <p><b>TODO 后续实现</b>：当前仅为接口定义，实际加载逻辑待 ContentPack 框架完善后实现。
 */
public final class MobSoundLoader {

    private MobSoundLoader() {}

    /**
     * 扫描资源管理器，发现所有 mob 声音资源
     *
     * @param rm        ResourceManager（客户端或服务端）
     * @param modId     限定扫描命名空间（null = 扫描所有命名空间）
     * @return mob_id → 该生物的所有 .ogg 文件相对路径集合
     *
     * <p><b>TODO</b>：实现 .ogg 扫描逻辑
     */
    public static Map<String, Map<String, ResourceLocation>> scanSounds(
            ResourceManager rm, String modId) {
        throw new UnsupportedOperationException("MobSoundLoader.scanSounds not yet implemented");
    }

    /**
     * 为发现的 SoundEvent ID 注册 DeferredHolder
     *
     * <p><b>TODO</b>：在 ModSounds 中实现自动注册逻辑
     */
    public static void registerSoundEvents(Map<String, Map<String, ResourceLocation>> sounds) {
        throw new UnsupportedOperationException("MobSoundLoader.registerSoundEvents not yet implemented");
    }

    /**
     * 根据 mob_id 构建对应的 MobSoundProfile
     *
     * <p><b>TODO</b>：合并 .ogg 自动发现 + mob JSON 显式配置
     */
    public static MobSoundProfile buildProfile(String mobId) {
        throw new UnsupportedOperationException("MobSoundLoader.buildProfile not yet implemented");
    }
}
