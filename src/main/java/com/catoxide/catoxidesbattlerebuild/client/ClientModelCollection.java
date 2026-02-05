package com.catoxide.catoxidesbattlerebuild.client;

import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.model.data.EntityModelData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端模型集合
 * 管理GeckoLib的模型、动画和烘焙数据
 * 
 * 与服务端的ModelCollection类似，但针对客户端进行了优化
 */
public class ClientModelCollection {
    private final ResourceLocation modelLocation;
    private final CoreGeoModel<? extends GeoAnimatable> coreModel;
    private final BakedGeoModel bakedModel;
    private final BakedAnimations bakedAnimations;
    private final AnimationProcessor<? extends GeoAnimatable> animationProcessor;
    
    // 缓存的骨骼名称映射（bone名称 -> index）
    private final Map<String, Integer> boneIndexMap;
    
    // 缓存的cube数据（用于受击盒计算）
    private final Map<String, Map<String, float[]>> cubeDataCache;
    
    /**
     * 构造器
     */
    public ClientModelCollection(
            ResourceLocation modelLocation,
            CoreGeoModel<? extends GeoAnimatable> coreModel,
            BakedGeoModel bakedModel,
            BakedAnimations bakedAnimations,
            AnimationProcessor<? extends GeoAnimatable> animationProcessor
    ) {
        this.modelLocation = modelLocation;
        this.coreModel = coreModel;
        this.bakedModel = bakedModel;
        this.bakedAnimations = bakedAnimations;
        this.animationProcessor = animationProcessor;
        this.boneIndexMap = new ConcurrentHashMap<>();
        this.cubeDataCache = new ConcurrentHashMap<>();
        
        // 初始化时缓存骨骼索引
        initializeBoneIndexMap();
    }
    
    /**
     * 初始化骨骼索引映射
     */
    private void initializeBoneIndexMap() {
        // 从AnimationProcessor获取已注册的骨骼
        if (animationProcessor != null) {
            try {
                var bones = animationProcessor.getRegisteredBones();
                if (bones != null) {
                    int index = 0;
                    for (var bone : bones) {
                        String boneName = bone.getName();
                        boneIndexMap.put(boneName, index++);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error initializing bone index map: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取骨骼索引
     */
    public int getBoneIndex(String boneName) {
        return boneIndexMap.getOrDefault(boneName, -1);
    }
    
    /**
     * 获取骨骼数量
     */
    public int getBoneCount() {
        return boneIndexMap.size();
    }
    
    /**
     * 获取骨骼名称
     */
    public String getBoneName(int index) {
        // 反向查找：从index找到boneName
        for (Map.Entry<String, Integer> entry : boneIndexMap.entrySet()) {
            if (entry.getValue() == index) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * 获取动画处理器
     */
    public AnimationProcessor<? extends GeoAnimatable> getAnimationProcessor() {
        return animationProcessor;
    }
    
    /**
     * 获取烘焙模型
     */
    public BakedGeoModel getBakedModel() {
        return bakedModel;
    }
    
    /**
     * 获取核心模型
     */
    public CoreGeoModel<? extends GeoAnimatable> getCoreModel() {
        return coreModel;
    }
    
    /**
     * 获取烘焙动画
     */
    public BakedAnimations getBakedAnimations() {
        return bakedAnimations;
    }
    
    /**
     * 获取模型位置
     */
    public ResourceLocation getModelLocation() {
        return modelLocation;
    }
    
    /**
     * 获取cube数据（用于受击盒）
     */
    public Map<String, float[]> getCubeData(String boneName) {
        return cubeDataCache.get(boneName);
    }
    
    /**
     * 缓存cube数据
     */
    public void cacheCubeData(String boneName, Map<String, float[]> cubeData) {
        cubeDataCache.put(boneName, cubeData);
    }
    
    /**
     * 创建实体模型数据（用于动画解算）
     */
    public EntityModelData createEntityModelData(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        return new EntityModelData(false, false, 0.0f, 0.0f);
    }
    
    /**
     * 查找动画
     */
    public Animation findAnimation(ResourceLocation animationId) {
        if (bakedAnimations != null && bakedAnimations.animations() != null) {
            return bakedAnimations.animations().get(animationId);
        }
        return null;
    }
    
    /**
     * 检查模型是否有效
     */
    public boolean isValid() {
        return coreModel != null && bakedModel != null && animationProcessor != null;
    }
    
    @Override
    public String toString() {
        return String.format("ClientModelCollection[model=%s, bones=%d]", 
                modelLocation, getBoneCount());
    }
}



