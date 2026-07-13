package com.catoxide.catoxidesbattlerebuild.registry;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组自定义 SoundEvent 注册器
 * <p>当前为空，留待 ContentPack 的 MobSoundLoader 自动注册自定义声音事件。
 *
 * <h3>使用示例（未来）</h3>
 * <pre>{@code
 * public static final DeferredHolder<SoundEvent, SoundEvent> ZOMBIE2_AMBIENT =
 *     SOUNDS.register("zombie2.ambient",
 *         () -> SoundEvent.createVariableRangeEvent(
 *             CatoxidesBattleRebuildConstants.id("zombie2.ambient")));
 * }</pre>
 *
 * <p>对应资源文件需放在 {@code assets/catoxidesbattlerebuild/sounds/zombie2/ambient.ogg}，
 * 并在 {@code assets/catoxidesbattlerebuild/sounds.json} 中声明。
 *
 * <p><b>TODO</b>：实现 {@code MobSoundLoader} 后，由其自动调用 {@link #SOUNDS} 注册
 * 扫描到的 .ogg 文件，无需手动声明每个 DeferredHolder。
 */
public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, CatoxidesBattleRebuildConstants.MODID);

    // TODO: 由 MobSoundLoader 自动注册自定义声音事件
}
