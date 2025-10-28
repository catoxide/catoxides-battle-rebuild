// HitboxSyncPacket.java
package com.catoxide.catoxidesbattlerebuild.mob.server;

import com.catoxide.catoxidesbattlerebuild.client.ClientHitboxManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class HitboxSyncPacket {
    private final int parentId;
    private final List<HitboxData> hitboxDataList;

    public HitboxSyncPacket(int parentId, List<HitboxData> hitboxDataList) {
        this.parentId = parentId;
        this.hitboxDataList = hitboxDataList;
    }

    public HitboxSyncPacket(FriendlyByteBuf buf) {
        this.parentId = buf.readInt();
        int size = buf.readInt();
        this.hitboxDataList = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            hitboxDataList.add(new HitboxData(buf));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(parentId);
        buf.writeInt(hitboxDataList.size());
        for (HitboxData data : hitboxDataList) {
            data.encode(buf);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            // 在客户端处理数据包
            ClientHitboxManager.handleHitboxSync(this);
        });
        context.get().setPacketHandled(true);
    }

    public int getParentId() { return parentId; }
    public List<HitboxData> getHitboxDataList() { return hitboxDataList; }

    public static class HitboxData {
        public final String partName;
        public final Vec3 position;
        public final Quaternionf rotation;  // 新增旋转信息
        public final double minX, minY, minZ, maxX, maxY, maxZ;

        public HitboxData(String partName, Vec3 position, Quaternionf rotation,
                          double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.partName = partName;
            this.position = position;
            this.rotation = rotation;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }

        // 为了向后兼容，保留没有旋转的构造函数
        public HitboxData(String partName, Vec3 position,
                          double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this(partName, position, new Quaternionf(), minX, minY, minZ, maxX, maxY, maxZ);
        }

        public HitboxData(FriendlyByteBuf buf) {
            this.partName = buf.readUtf();
            this.position = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());

            // 读取旋转（四元数）
            float x = buf.readFloat();
            float y = buf.readFloat();
            float z = buf.readFloat();
            float w = buf.readFloat();
            this.rotation = new Quaternionf(x, y, z, w);

            this.minX = buf.readDouble(); this.minY = buf.readDouble(); this.minZ = buf.readDouble();
            this.maxX = buf.readDouble(); this.maxY = buf.readDouble(); this.maxZ = buf.readDouble();
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(partName);
            buf.writeDouble(position.x); buf.writeDouble(position.y); buf.writeDouble(position.z);

            // 写入旋转（四元数）
            if (rotation != null) {
                buf.writeFloat(rotation.x);
                buf.writeFloat(rotation.y);
                buf.writeFloat(rotation.z);
                buf.writeFloat(rotation.w);
            } else {
                // 如果旋转为空，写入单位四元数
                buf.writeFloat(0.0f);
                buf.writeFloat(0.0f);
                buf.writeFloat(0.0f);
                buf.writeFloat(1.0f);
            }

            buf.writeDouble(minX); buf.writeDouble(minY); buf.writeDouble(minZ);
            buf.writeDouble(maxX); buf.writeDouble(maxY); buf.writeDouble(maxZ);
        }
    }
}