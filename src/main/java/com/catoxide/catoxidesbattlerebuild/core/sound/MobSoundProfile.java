package com.catoxide.catoxidesbattlerebuild.core.sound;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.Optional;

/**
 * 生物声音配置（数据驱动）
 * <p>定义一个 {@code AnimatedMob} 子类所需的所有声音事件 ID。
 * 用于 ContentPack 的 mob 配置 JSON 中，通过 {@link MobSoundLoader} 加载
 * 对应的 .ogg 资源并注册为 {@link SoundEvent}。
 *
 * <h3>JSON 示例</h3>
 * <pre>{@code
 * {
 *   "ambient": "catoxidesbattlerebuild:zombie2.ambient",
 *   "hurt":    "catoxidesbattlerebuild:zombie2.hurt",
 *   "death":   "catoxidesbattlerebuild:zombie2.death",
 *   "step":    "catoxidesbattlerebuild:zombie2.step",
 *   "swing":   "catoxidesbattlerebuild:zombie2.swing",
 *   "volume":  1.0,
 *   "pitch":   0.85
 * }
 * }</pre>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>所有字段均可选（{@code null} 表示该生物没有此声音）</li>
 *   <li>仅定义声音事件 ID（ResourceLocation），实际 {@link SoundEvent} 对象
 *       由 {@link MobSoundLoader} 在 mod 加载阶段注册</li>
 *   <li>{@code volume} / {@code pitch} 提供合理默认值，可被 AnimatedMob
 *       的 {@code getSoundVolume()} / {@code getVoicePitch()} override 进一步调整</li>
 * </ul>
 *
 * <p><b>资源约定</b>：MC 仅支持 Ogg Vorbis 格式（{@code .ogg}），不支持 mp3。
 * 对应的 .ogg 文件需放在
 * {@code assets/<modid>/sounds/<mob_id>/<event_name>.ogg}。
 */
public record MobSoundProfile(
        ResourceLocation ambient,
        ResourceLocation hurt,
        ResourceLocation death,
        ResourceLocation step,
        ResourceLocation swing,
        float volume,
        float pitch
) {

    public static final float DEFAULT_VOLUME = 1.0f;
    public static final float DEFAULT_PITCH = 1.0f;

    public static final Codec<MobSoundProfile> CODEC = RecordCodecBuilder.create(
            it -> it.group(
                    ResourceLocation.CODEC.optionalFieldOf("ambient")
                            .xmap(o -> o.orElse(null), Optional::ofNullable)
                            .forGetter(MobSoundProfile::ambient),
                    ResourceLocation.CODEC.optionalFieldOf("hurt")
                            .xmap(o -> o.orElse(null), Optional::ofNullable)
                            .forGetter(MobSoundProfile::hurt),
                    ResourceLocation.CODEC.optionalFieldOf("death")
                            .xmap(o -> o.orElse(null), Optional::ofNullable)
                            .forGetter(MobSoundProfile::death),
                    ResourceLocation.CODEC.optionalFieldOf("step")
                            .xmap(o -> o.orElse(null), Optional::ofNullable)
                            .forGetter(MobSoundProfile::step),
                    ResourceLocation.CODEC.optionalFieldOf("swing")
                            .xmap(o -> o.orElse(null), Optional::ofNullable)
                            .forGetter(MobSoundProfile::swing),
                    Codec.FLOAT.optionalFieldOf("volume", DEFAULT_VOLUME)
                            .forGetter(MobSoundProfile::volume),
                    Codec.FLOAT.optionalFieldOf("pitch", DEFAULT_PITCH)
                            .forGetter(MobSoundProfile::pitch)
            ).apply(it, MobSoundProfile::new)
    );

    /**
     * 空配置：所有声音均为 null，使用默认音量/音调。
     * 用于无自定义声音的生物（如直接 override 返回 vanilla SoundEvent 的子类）。
     */
    public static MobSoundProfile empty() {
        return new MobSoundProfile(null, null, null, null, null, DEFAULT_VOLUME, DEFAULT_PITCH);
    }

    public boolean hasAmbient() { return ambient != null; }
    public boolean hasHurt()    { return hurt    != null; }
    public boolean hasDeath()   { return death   != null; }
    public boolean hasStep()    { return step    != null; }
    public boolean hasSwing()   { return swing   != null; }
}
