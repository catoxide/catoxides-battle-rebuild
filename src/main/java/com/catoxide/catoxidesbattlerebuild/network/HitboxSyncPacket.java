package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.server.temp.BoneHitboxComponent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.*;
import java.util.function.Supplier;

/**
 * 骨骼受击盒同步数据包
 */
public class HitboxSyncPacket {

    // 版本控制
    private static final int PROTOCOL_VERSION = 1;

    // 数据
    private final Map<Integer, List<BoneHitboxData>> entityHitboxesMap;

    public HitboxSyncPacket(Map<Integer, List<BoneHitboxData>> entityHitboxesMap) {
        this.entityHitboxesMap = entityHitboxesMap;
    }

    /**
     * 单个骨骼受击盒的数据结构
     */
    public static class BoneHitboxData {
        public final String boneName;
        public final Vector3f worldCenter;
        public final Vector3f halfExtents;
        public final Quaternionf worldOrientation;
        public final float damageMultiplier;
        public final boolean isCritical;
        public final boolean isArmored;
        public final boolean isActive;
        
        // 动画状态字段（用于客户端解算）
        public final String animationName;
        public final double animationTime;
        public final double animationSpeed;
        public final boolean looping;

        public BoneHitboxData(BoneHitboxComponent hitbox) {
            this.boneName = hitbox.getBoneName();
            this.worldCenter = hitbox.getWorldCenter();
            this.halfExtents = hitbox.getHalfExtents();
            this.worldOrientation = hitbox.getWorldOrientation();
            this.damageMultiplier = hitbox.getDamageMultiplier();
            this.isCritical = hitbox.isCritical();
            this.isArmored = hitbox.isArmored();
            this.isActive = hitbox.isActive();
            
            // 动画状态（默认值，实际应从实体获取）
            this.animationName = "";
            this.animationTime = 0.0;
            this.animationSpeed = 1.0;
            this.looping = false;
        }

        public BoneHitboxData(String boneName, Vector3f worldCenter, Vector3f halfExtents,
                              Quaternionf worldOrientation, float damageMultiplier,
                              boolean isCritical, boolean isArmored, boolean isActive) {
            this.boneName = boneName;
            this.worldCenter = worldCenter;
            this.halfExtents = halfExtents;
            this.worldOrientation = worldOrientation;
            this.damageMultiplier = damageMultiplier;
            this.isCritical = isCritical;
            this.isArmored = isArmored;
            this.isActive = isActive;
            
            // 动画状态（默认值）
            this.animationName = "";
            this.animationTime = 0.0;
            this.animationSpeed = 1.0;
            this.looping = false;
        }
        
        /**
         * 带动画状态的构造器
         */
        public BoneHitboxData(String boneName, Vector3f worldCenter, Vector3f halfExtents,
                              Quaternionf worldOrientation, float damageMultiplier,
                              boolean isCritical, boolean isArmored, boolean isActive,
                              String animationName, double animationTime, 
                              double animationSpeed, boolean looping) {
            this.boneName = boneName;
            this.worldCenter = worldCenter;
            this.halfExtents = halfExtents;
            this.worldOrientation = worldOrientation;
            this.damageMultiplier = damageMultiplier;
            this.isCritical = isCritical;
            this.isArmored = isArmored;
            this.isActive = isActive;
            
            // 动画状态
            this.animationName = animationName;
            this.animationTime = animationTime;
            this.animationSpeed = animationSpeed;
            this.looping = looping;
        }

        /**
         * 创建客户端的 BoneHitboxComponent
         */
        public BoneHitboxComponent toClientComponent(UUID entityId) {
            return new BoneHitboxComponent(
                    entityId,
                    boneName,
                    worldCenter,
                    halfExtents,
                    worldOrientation,
                    damageMultiplier,
                    isCritical,
                    isArmored,
                    isActive
            );
        }
    }

    /**
     * 编码数据包
     */
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(PROTOCOL_VERSION);
        buffer.writeInt(entityHitboxesMap.size());

        for (Map.Entry<Integer, List<BoneHitboxData>> entry : entityHitboxesMap.entrySet()) {
            buffer.writeInt(entry.getKey()); // 实体ID
            List<BoneHitboxData> hitboxList = entry.getValue();
            buffer.writeInt(hitboxList.size());

            for (BoneHitboxData data : hitboxList) {
                buffer.writeUtf(data.boneName, 32767);

                // 写入 Vector3f (世界中心)
                buffer.writeFloat(data.worldCenter.x());
                buffer.writeFloat(data.worldCenter.y());
                buffer.writeFloat(data.worldCenter.z());

                // 写入 Vector3f (半边长)
                buffer.writeFloat(data.halfExtents.x());
                buffer.writeFloat(data.halfExtents.y());
                buffer.writeFloat(data.halfExtents.z());

                // 写入 Quaternionf (旋转)
                buffer.writeFloat(data.worldOrientation.x());
                buffer.writeFloat(data.worldOrientation.y());
                buffer.writeFloat(data.worldOrientation.z());
                buffer.writeFloat(data.worldOrientation.w());

                // 写入数值和标记
                buffer.writeFloat(data.damageMultiplier);

                byte flags = 0;
                if (data.isCritical) flags |= 0x01;
                if (data.isArmored) flags |= 0x02;
                if (data.isActive) flags |= 0x04;
                buffer.writeByte(flags);
                
                // 写入动画状态
                buffer.writeUtf(data.animationName, 32767);
                buffer.writeDouble(data.animationTime);
                buffer.writeDouble(data.animationSpeed);
                buffer.writeBoolean(data.looping);
            }
        }
    }

    /**
     * 解码数据包
     */
    public static HitboxSyncPacket decode(FriendlyByteBuf buffer) {
        int version = buffer.readInt();
        if (version != PROTOCOL_VERSION) {
            throw new IllegalStateException("Protocol version mismatch: " + version);
        }

        Map<Integer, List<BoneHitboxData>> map = new HashMap<>();
        int entityCount = buffer.readInt();

        for (int i = 0; i < entityCount; i++) {
            int entityId = buffer.readInt();
            int hitboxCount = buffer.readInt();
            List<BoneHitboxData> hitboxList = new ArrayList<>(hitboxCount);

            for (int j = 0; j < hitboxCount; j++) {
                String boneName = buffer.readUtf(32767);

                Vector3f worldCenter = new Vector3f(
                        buffer.readFloat(),
                        buffer.readFloat(),
                        buffer.readFloat()
                );

                Vector3f halfExtents = new Vector3f(
                        buffer.readFloat(),
                        buffer.readFloat(),
                        buffer.readFloat()
                );

                Quaternionf orientation = new Quaternionf(
                        buffer.readFloat(),
                        buffer.readFloat(),
                        buffer.readFloat(),
                        buffer.readFloat()
                );

                float damageMultiplier = buffer.readFloat();
                byte flags = buffer.readByte();

                boolean isCritical = (flags & 0x01) != 0;
                boolean isArmored = (flags & 0x02) != 0;
                boolean isActive = (flags & 0x04) != 0;
                
                // 读取动画状态
                String animationName = buffer.readUtf(32767);
                double animationTime = buffer.readDouble();
                double animationSpeed = buffer.readDouble();
                boolean looping = buffer.readBoolean();

                hitboxList.add(new BoneHitboxData(
                        boneName, worldCenter, halfExtents, orientation,
                        damageMultiplier, isCritical, isArmored, isActive,
                        animationName, animationTime, animationSpeed, looping
                ));
            }

            map.put(entityId, hitboxList);
        }

        return new HitboxSyncPacket(map);
    }

    /**
     * 处理数据包（客户端执行）
     */
    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 确保在客户端执行
            if (context.getDirection().getReceptionSide().isClient()) {
                com.catoxide.catoxidesbattlerebuild.client.ClientHitboxHandler.handleHitboxSync(this);
            }
        });
        context.setPacketHandled(true);
    }

    public Map<Integer, List<BoneHitboxData>> getEntityHitboxesMap() {
        return entityHitboxesMap;
    }
}
