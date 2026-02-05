package com.catoxide.catoxidesbattlerebuild.client;

import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端动画解算器
 * 负责根据动画状态计算骨骼矩阵
 * 
 * 核心功能：
 * 1. 使用GeckoLib的AnimationProcessor计算骨骼变换
 * 2. 处理动画时间、速度、循环
 * 3. 计算每个骨骼的变换矩阵
 * 4. 应用实体变换（位置、旋转）
 */
public class ClientAnimationResolver {
    
    /**
     * 解算动画，返回骨骼矩阵
     * 
     * @param entityCollection 实体集合
     * @param partialTick 部分tick（用于插值）
     * @return 骨骼名称到矩阵的映射
     */
    public Map<String, Matrix4f> resolveAnimation(ClientEntityCollection entityCollection, float partialTick) {
        long startTime = System.currentTimeMillis();
        GeckoLib.LOGGER.info("[AnimationResolver] Starting animation resolution");
        GeckoLib.LOGGER.info("[AnimationResolver] Partial tick: {}", partialTick);
        
        if (entityCollection == null || !entityCollection.isValid()) {
            GeckoLib.LOGGER.warn("[AnimationResolver] Entity collection is null or invalid");
            return new ConcurrentHashMap<>();
        }
        
        GeckoLib.LOGGER.info("[AnimationResolver] Entity collection is valid");
        
        ClientModelCollection modelCollection = entityCollection.getModelCollection();
        if (modelCollection == null || !modelCollection.isValid()) {
            GeckoLib.LOGGER.warn("[AnimationResolver] Model collection is null or invalid");
            return new ConcurrentHashMap<>();
        }
        
        GeckoLib.LOGGER.info("[AnimationResolver] Model collection is valid: {}", modelCollection);
        
        AnimationProcessor<?> processor = modelCollection.getAnimationProcessor();
        if (processor == null) {
            GeckoLib.LOGGER.warn("[AnimationResolver] Animation processor is null");
            return new ConcurrentHashMap<>();
        }
        
        GeckoLib.LOGGER.info("[AnimationResolver] Animation processor obtained");
        
        // 获取当前动画
        Animation animation = entityCollection.getCurrentAnimation();
        if (animation == null) {
            GeckoLib.LOGGER.warn("[AnimationResolver] Current animation is null");
            return new ConcurrentHashMap<>();
        }
        
        GeckoLib.LOGGER.info("[AnimationResolver] Current animation: {}", animation.name());
        
        // 计算动画时间（考虑插值）
        double animationTime = calculateAnimationTime(entityCollection, partialTick);
        GeckoLib.LOGGER.info("[AnimationResolver] Calculated animation time: {}", animationTime);
        
        // 创建实体模型数据
        EntityModelData entityModelData = createEntityModelData(entityCollection);
        GeckoLib.LOGGER.info("[AnimationResolver] Entity model data created");
        
        // 更新动画处理器
        updateAnimationProcessor(processor, animation, animationTime, entityModelData);
        GeckoLib.LOGGER.info("[AnimationResolver] Animation processor updated");
        
        // 提取骨骼矩阵
        Map<String, Matrix4f> boneMatrices = extractBoneMatrices(processor);
        GeckoLib.LOGGER.info("[AnimationResolver] Extracted {} bone matrices", boneMatrices.size());
        
        // 应用实体变换
        Matrix4f entityTransform = entityCollection.getEntityTransform();
        boneMatrices = applyEntityTransform(boneMatrices, entityTransform);
        GeckoLib.LOGGER.info("[AnimationResolver] Applied entity transform to {} matrices", boneMatrices.size());
        
        // 更新实体集合的骨骼矩阵
        entityCollection.setAllBoneMatrices(boneMatrices);
        
        long endTime = System.currentTimeMillis();
        GeckoLib.LOGGER.info("[AnimationResolver] Animation resolution completed in {} ms", endTime - startTime);
        
        return boneMatrices;
    }
    
    /**
     * 更新动画处理器
     */
    private void updateAnimationProcessor(
            AnimationProcessor<?> processor,
            Animation animation,
            double animationTime,
            EntityModelData entityModelData
    ) {
        try {
            // 设置动画状态
            // 注意：这里需要根据GeckoLib的API调整
            // 伪代码：
            // processor.setAnimation(animation);
            // processor.setAnimationTime(animationTime);
            // processor.setEntityModelData(entityModelData);
            // processor.update();
            
            // 由于GeckoLib API可能变化，这里暂时不做处理
            // 实际使用时需要根据具体版本调整
            
        } catch (Exception e) {
            System.err.println("Error updating animation processor: " + e.getMessage());
        }
    }
    
    /**
     * 提取骨骼矩阵（从AnimationProcessor）
     * 与服务端的extractBoneMatrices方法类似
     */
    private Map<String, Matrix4f> extractBoneMatrices(AnimationProcessor<?> animationProcessor) {
        Map<String, Matrix4f> boneMatrices = new ConcurrentHashMap<>();

        try {
            // 获取所有已注册的骨骼
            Collection<CoreGeoBone> bones = animationProcessor.getRegisteredBones();
            
            // Debug：输出骨骼数量
            System.out.println("[ClientAnimationResolver] Extracting bone matrices. Registered bones count: " + 
                    (bones != null ? bones.size() : "null"));

            // 遍历所有骨骼并获取它们的矩阵
            for (CoreGeoBone coreBone : bones) {
                // 将 CoreGeoBone 转换为 GeoBone
                if (coreBone instanceof GeoBone bone) {
                    String boneName = bone.getName();

                    // 使用getLocalSpaceMatrix()获取模型空间的变换矩阵
                    Matrix4f matrix = bone.getLocalSpaceMatrix();
                    if (matrix != null) {
                        // 创建矩阵的副本
                        boneMatrices.put(boneName, new Matrix4f(matrix));
                        
                        // Debug：输出矩阵的平移部分
                        Vector3f translation = new Vector3f();
                        matrix.getTranslation(translation);
                        System.out.println("[ClientAnimationResolver] Extracted matrix for bone: " + boneName + 
                                ", Translation: (" + translation.x + ", " + translation.y + ", " + translation.z + ")");
                    } else {
                        System.out.println("[ClientAnimationResolver] Warning: matrix is null for bone: " + boneName);
                    }
                }
            }

            System.out.println("[ClientAnimationResolver] Total matrices extracted: " + boneMatrices.size());

        } catch (Exception e) {
            System.out.println("[ClientAnimationResolver] Error extracting bone matrices: " + e.getMessage());
            e.printStackTrace();
        }

        return boneMatrices.isEmpty() ? new ConcurrentHashMap<>() : boneMatrices;
    }
    
    /**
     * 应用实体变换到骨骼矩阵
     */
    private Map<String, Matrix4f> applyEntityTransform(
            Map<String, Matrix4f> boneMatrices,
            Matrix4f entityTransform
    ) {
        Map<String, Matrix4f> transformedMatrices = new ConcurrentHashMap<>();
        
        for (Map.Entry<String, Matrix4f> entry : boneMatrices.entrySet()) {
            String boneName = entry.getKey();
            Matrix4f boneMatrix = entry.getValue();
            
            // 应用实体变换
            Matrix4f finalMatrix = new Matrix4f();
            finalMatrix.mul(entityTransform, boneMatrix);
            
            transformedMatrices.put(boneName, finalMatrix);
        }
        
        return transformedMatrices;
    }
    
    /**
     * 计算动画时间（考虑插值）
     */
    private double calculateAnimationTime(ClientEntityCollection entityCollection, float partialTick) {
        double baseTime = entityCollection.getAnimationTime();
        double speed = entityCollection.getAnimationSpeed();
        
        // 应用动画速度
        double adjustedTime = baseTime * speed;
        
        // 考虑插值
        if (partialTick > 0.0f && partialTick < 1.0f) {
            // 在两个动画帧之间插值
            // 这里简化处理，实际可能需要更复杂的插值逻辑
            adjustedTime += partialTick * speed * 0.05; // 假设每tick 0.05秒
        }
        
        return adjustedTime;
    }
    
    /**
     * 创建实体模型数据
     */
    private EntityModelData createEntityModelData(ClientEntityCollection entityCollection) {
        if (entityCollection.getEntity() == null) {
            // EntityModelData是记录类型，需要4个参数：limbSwing, limbSwingAmount, ageInTicks, netHeadYaw
            return new EntityModelData(false, false, 0.0f, 0.0f);
        }
        
        // EntityModelData是记录类型，需要4个参数：limbSwing, limbSwingAmount, ageInTicks, netHeadYaw
        EntityModelData data = new EntityModelData(false, false, 0.0f, 0.0f);
        
        return data;
    }
    
    /**
     * 解算单个骨骼的矩阵（简化版）
     * 用于快速获取单个骨骼的变换
     */
    public Matrix4f resolveBoneMatrix(
            ClientEntityCollection entityCollection,
            String boneName,
            float partialTick
    ) {
        Map<String, Matrix4f> matrices = resolveAnimation(entityCollection, partialTick);
        return matrices.get(boneName);
    }
    
    /**
     * 预热解算器（初始化缓存）
     */
    public void warmUp(ClientModelCollection modelCollection) {
        if (modelCollection == null) {
            return;
        }
        
        // 预加载骨骼索引
        int boneCount = modelCollection.getBoneCount();
        
        // 预加载动画数据
        // 这里可以添加预加载逻辑
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        // 清理缓存和资源
    }
}




