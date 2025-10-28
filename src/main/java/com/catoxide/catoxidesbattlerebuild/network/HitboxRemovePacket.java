// HitboxRemovePacket.java
package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.client.ClientHitboxManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class HitboxRemovePacket {
    private final int parentId;

    public HitboxRemovePacket(int parentId) {
        this.parentId = parentId;
    }

    public HitboxRemovePacket(FriendlyByteBuf buf) {
        this.parentId = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(parentId);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            // 在客户端移除该父实体的碰撞箱数据
            ClientHitboxManager.removeHitboxData(parentId);
        });
        context.get().setPacketHandled(true);
    }

    public int getParentId() {
        return parentId;
    }
}