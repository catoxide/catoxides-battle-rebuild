package com.catoxide.catoxidesbattlerebuild.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import static com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild.MODID;

/**
 * 网络处理器
 */
public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1.0.0";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "hitbox_sync"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    /**
     * 注册所有数据包
     */
    public static void register() {
        // 注册主同步包
        CHANNEL.registerMessage(
                packetId++,
                HitboxSyncPacket.class,
                HitboxSyncPacket::encode,
                HitboxSyncPacket::decode,
                HitboxSyncPacket::handle
        );
    }
}