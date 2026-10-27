package com.catoxide.catoxidesbattlerebuild.core.map;

import com.mojang.authlib.GameProfile;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 玩家皮肤来源扩展点（地图标记头像用）。
 *
 * <p>返回带 textures 的 {@link GameProfile}；返回 {@code null} 表示“不知道，交给下一个来源”。
 * map-pack 默认从在线玩家连接取皮肤，离线玩家按名字异步拉取；皮肤缓存插件/内容包可以注册
 * 更高优先级的来源（例如本地皮肤库、代理服专用接口）。
 */
public interface IMapPlayerSkinSource {

    @Nullable
    GameProfile profile(UUID uuid, @Nullable String name);
}
