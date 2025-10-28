// NetworkHandler.java
package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.mob.server.HitboxSyncPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static SimpleChannel CHANNEL; // 移除了 final 和直接初始化

    private static int packetId = 0;

    public static void register() {
        // 延迟初始化网络通道
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation("catoxidesbattlerebuild", "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals
        );

        // 注册 HitboxSyncPacket
        CHANNEL.registerMessage(packetId++, HitboxSyncPacket.class,
                HitboxSyncPacket::encode, HitboxSyncPacket::new, HitboxSyncPacket::handle);

        // 注册 HitboxRemovePacket
        CHANNEL.registerMessage(packetId++, HitboxRemovePacket.class,
                HitboxRemovePacket::encode, HitboxRemovePacket::new, HitboxRemovePacket::handle);
    }

    // 添加安全检查方法
    private static void checkChannelInitialized() {
        if (CHANNEL == null) {
            throw new IllegalStateException("Network channel not initialized. Call NetworkHandler.register() during mod setup.");
        }
    }

    public static void sendToAllTracking(HitboxSyncPacket packet, Entity entity) {
        checkChannelInitialized();
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), packet);
    }

    public static void sendToAllTracking(HitboxRemovePacket packet, Entity entity) {
        checkChannelInitialized();
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), packet);
    }

    public static void sendToPlayer(HitboxSyncPacket packet, ServerPlayer player) {
        checkChannelInitialized();
        CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}