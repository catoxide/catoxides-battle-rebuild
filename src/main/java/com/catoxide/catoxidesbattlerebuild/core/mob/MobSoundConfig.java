package com.catoxide.catoxidesbattlerebuild.core.mob;

import net.minecraft.resources.ResourceLocation;

/**
 * 实体音效配置（数据驱动）
 * <p>对应实体定义 JSON 中的 {@code sounds} 字段。所有字段可缺省（null = 无该音效）。
 */
public record MobSoundConfig(
        ResourceLocation ambient,
        ResourceLocation hurt,
        ResourceLocation death,
        ResourceLocation step,
        ResourceLocation swing
) {
    public static final MobSoundConfig NONE = new MobSoundConfig(null, null, null, null, null);

    public static MobSoundConfig of(ResourceLocation ambient, ResourceLocation hurt,
                                    ResourceLocation death, ResourceLocation step, ResourceLocation swing) {
        return new MobSoundConfig(ambient, hurt, death, step, swing);
    }
}
