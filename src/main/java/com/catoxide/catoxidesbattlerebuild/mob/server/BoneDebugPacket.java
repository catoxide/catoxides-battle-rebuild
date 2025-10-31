// BoneDebugPacket.java
package com.catoxide.catoxidesbattlerebuild.mob.server;

import com.catoxide.catoxidesbattlerebuild.client.ClientBoneDebugManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class BoneDebugPacket {
    private final int parentId;
    private final List<BoneData> boneDataList;

    public BoneDebugPacket(int parentId, List<BoneData> boneDataList) {
        this.parentId = parentId;
        this.boneDataList = boneDataList;
    }

    public BoneDebugPacket(FriendlyByteBuf buf) {
        this.parentId = buf.readInt();
        int size = buf.readInt();
        this.boneDataList = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            boneDataList.add(new BoneData(buf));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(parentId);
        buf.writeInt(boneDataList.size());
        for (BoneData data : boneDataList) {
            data.encode(buf);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            // 在客户端处理数据包
            ClientBoneDebugManager.handleBoneDebugSync(this);
        });
        context.get().setPacketHandled(true);
    }

    public int getParentId() { return parentId; }
    public List<BoneData> getBoneDataList() { return boneDataList; }

    public static class BoneData {
        public final String boneName;
        public final Vec3 position;
        public final Quaternionf rotation;
        public final Vec3 parentPosition; // 父骨骼位置（用于绘制骨骼连线）

        public BoneData(String boneName, Vec3 position, Quaternionf rotation, Vec3 parentPosition) {
            this.boneName = boneName;
            this.position = position;
            this.rotation = rotation;
            this.parentPosition = parentPosition;
        }

        public BoneData(FriendlyByteBuf buf) {
            this.boneName = buf.readUtf();
            this.position = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());

            // 读取旋转
            float x = buf.readFloat();
            float y = buf.readFloat();
            float z = buf.readFloat();
            float w = buf.readFloat();
            this.rotation = new Quaternionf(x, y, z, w);

            // 读取父骨骼位置
            this.parentPosition = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(boneName);
            buf.writeDouble(position.x); buf.writeDouble(position.y); buf.writeDouble(position.z);

            // 写入旋转
            if (rotation != null) {
                buf.writeFloat(rotation.x);
                buf.writeFloat(rotation.y);
                buf.writeFloat(rotation.z);
                buf.writeFloat(rotation.w);
            } else {
                buf.writeFloat(0.0f);
                buf.writeFloat(0.0f);
                buf.writeFloat(0.0f);
                buf.writeFloat(1.0f);
            }

            // 写入父骨骼位置
            buf.writeDouble(parentPosition.x);
            buf.writeDouble(parentPosition.y);
            buf.writeDouble(parentPosition.z);
        }
    }
}