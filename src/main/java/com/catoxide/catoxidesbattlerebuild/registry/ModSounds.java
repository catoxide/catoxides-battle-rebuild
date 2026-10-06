package com.catoxide.catoxidesbattlerebuild.registry;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组自定义 SoundEvent 注册器
 * <p>当前为空。主 mod 自定义声音事件可在此按需声明；内容包的音效应通过
 * {@code ContentPackContext#registerSound} 注册。
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
 * <p><b>注意</b>：此注册表仅供主 mod 使用；内容包的声音事件请走
 * {@code ContentPackContext#registerSound}。
 */
public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, CatoxidesBattleRebuildConstants.MODID);

    // 内容包的声音事件请走 ContentPackContext#registerSound；此注册表仅供主 mod 使用
}
