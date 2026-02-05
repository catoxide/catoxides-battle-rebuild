package com.catoxide.catoxidesbattlerebuild.server.temp;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;

/**
 * 受击盒能力系统
 */
@Mod.EventBusSubscriber(modid = CatoxidesBattleRebuild.MODID)
public class HitboxCapability {
    public static final Capability<IHitboxCapability> HITBOX_CAPABILITY = CapabilityManager.get(new CapabilityToken<>() {});
    public static final ResourceLocation CAPABILITY_ID = new ResourceLocation(CatoxidesBattleRebuild.MODID, "hitbox_capability");

    /**
     * 受击盒能力接口
     */
    public interface IHitboxCapability extends INBTSerializable<CompoundTag> {
        /**
         * 添加受击盒组件
         */
        void addHitbox(BoneHitboxComponent hitbox);

        /**
         * 移除受击盒组件
         */
        void removeHitbox(String boneName);

        /**
         * 获取所有受击盒组件
         */
        List<BoneHitboxComponent> getHitboxes();

        /**
         * 通过骨骼名称获取受击盒组件
         */
        BoneHitboxComponent getHitbox(String boneName);

        /**
         * 检查是否有受击盒组件
         */
        boolean hasHitboxes();

        /**
         * 清除所有受击盒组件
         */
        void clearHitboxes();
    }

    /**
     * 受击盒能力实现
     */
    public static class HitboxCapabilityImpl implements IHitboxCapability {
        private final List<BoneHitboxComponent> hitboxes = new ArrayList<>();

        @Override
        public void addHitbox(BoneHitboxComponent hitbox) {
            // 移除同名骨骼的受击盒（如果存在）
            hitboxes.removeIf(existing -> existing.getBoneName().equals(hitbox.getBoneName()));
            hitboxes.add(hitbox);
        }

        @Override
        public void removeHitbox(String boneName) {
            hitboxes.removeIf(hitbox -> hitbox.getBoneName().equals(boneName));
        }

        @Override
        public List<BoneHitboxComponent> getHitboxes() {
            return hitboxes;
        }

        @Override
        public BoneHitboxComponent getHitbox(String boneName) {
            return hitboxes.stream()
                    .filter(hitbox -> hitbox.getBoneName().equals(boneName))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public boolean hasHitboxes() {
            return !hitboxes.isEmpty();
        }

        @Override
        public void clearHitboxes() {
            hitboxes.clear();
        }

        @Override
        public CompoundTag serializeNBT() {
            CompoundTag tag = new CompoundTag();
            // 这里可以实现序列化逻辑
            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            // 这里可以实现反序列化逻辑
        }
    }

    /**
     * 受击盒能力提供者
     */
    public static class HitboxCapabilityProvider implements ICapabilityProvider {
        private final LazyOptional<IHitboxCapability> instance = LazyOptional.of(HitboxCapabilityImpl::new);

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
            return HITBOX_CAPABILITY.orEmpty(cap, instance);
        }
    }

    /**
     * 为实体附加受击盒能力
     */
    @SubscribeEvent
    public static void attachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        event.addCapability(CAPABILITY_ID, new HitboxCapabilityProvider());
    }

    /**
     * 从实体获取受击盒能力
     */
    public static LazyOptional<IHitboxCapability> getHitboxCapability(Entity entity) {
        return entity.getCapability(HITBOX_CAPABILITY);
    }
}
