package com.catoxide.catoxidesbattlerebuild.core.player;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * 玩家属性统合系统 —— AttachmentType 注册中心。
 * <p>通过 NeoForge 数据附件（Attachment）把 {@link PlayerStateData} 挂到玩家实体：
 * 自动持久化（Codec → NBT）、死亡保留（copyOnDeath）、同步（StreamCodec，暂用全量定时推送）。
 */
public final class PlayerStateAttachments {

    private PlayerStateAttachments() {
    }

    /** AttachmentType 注册表（NeoForge 数据附件注册表） */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, CatoxidesBattleRebuildConstants.MODID);

    /** 玩家状态数据附件 */
    public static final Supplier<AttachmentType<PlayerStateData>> PLAYER_STATE =
            ATTACHMENT_TYPES.register("player_state",
                    () -> AttachmentType.builder(PlayerStateData::new)
                            .serialize(PlayerStateData.CODEC)
                            .copyOnDeath()
                            .build());
}
