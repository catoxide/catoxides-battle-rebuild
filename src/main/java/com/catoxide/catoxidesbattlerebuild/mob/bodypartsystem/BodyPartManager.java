package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.debug.EnhancedDebugManager;

import java.util.*;

public class BodyPartManager {
    private final ModularZombie parent;
    private final Map<String, BodyPart> bodyParts = new HashMap<>();

    // 新的模块化组件
    private final GeometryModel geometryModel;
    private final ModularTransformPipeline transformPipeline;
    private final HitboxFactory hitboxFactory;
    private final HitboxLifecycleManager hitboxLifecycleManager;
    private final SyncManager syncManager;

    private static final ModelManager modelManager = new ModelManager();
    private static final String MODEL_PATH = "/assets/catoxidesbattlerebuild/geo/modular_zombie.geo.json";

    public BodyPartManager(ModularZombie parent) {
        this.parent = parent;
        // 初始化调试系统
        ModularTransformPipeline.getInstance();

        // 先加载几何模型，再初始化身体部位
        this.geometryModel = loadGeometryModel(MODEL_PATH);
        this.hitboxFactory = new HitboxFactory(parent);
        this.hitboxLifecycleManager = new HitboxLifecycleManager(parent);
        this.syncManager = new SyncManager(parent, this);
        this.transformPipeline = ModularTransformPipeline.getInstance();

        initBodyPartsFromGeometry();
    }

    // 使用ModelManager加载几何模型
    private GeometryModel loadGeometryModel(String modelPath) {
        return modelManager.loadModel(modelPath);
    }

    // 从几何模型初始化身体部位
    private void initBodyPartsFromGeometry() {
        Map<String, String> boneMapping = new HashMap<>();
        boneMapping.put("head", "head");
        boneMapping.put("body", "torso");
        boneMapping.put("left_arm", "arm_left");
        boneMapping.put("right_arm", "arm_right");
        boneMapping.put("left_leg", "leg_left");
        boneMapping.put("right_leg", "leg_right");

        for (Map.Entry<String, String> entry : boneMapping.entrySet()) {
            String boneName = entry.getKey();
            String partName = entry.getValue();

            GeometryModel.Bone bone = geometryModel.bones.get(boneName);
            if (bone != null) {
                List<GeometryModel.Cube> cubes = bone.cubes;
                float health = getHealthForPart(partName);
                float damageMultiplier = getDamageMultiplierForPart(partName);

                bodyParts.put(partName, new BodyPart(this, partName, boneName, cubes, health, damageMultiplier, false, bone.pivot));
            }
        }
    }

    // 代理方法到各个管理器
    public void spawnHitboxEntities() {
        System.out.println("=== 开始生成碰撞箱实体 ===");

        for (BodyPart part : bodyParts.values()) {
            try {
                BoneTransform boneTransform = parent.getServerAnimationSystem().getBoneTransform(part.getBoneName());
                System.out.println("生成部位: " + part.getPartName() + " | 骨骼: " + part.getBoneName());

                // 使用工厂创建碰撞箱集群
                List<HitboxPart> cluster = hitboxFactory.createHitboxCluster(part, boneTransform);

                // 使用生命周期管理器注册
                hitboxLifecycleManager.registerHitboxCluster(part.getPartName(), cluster);

            } catch (Exception e) {
                System.err.println("生成部位 " + part.getPartName() + " 的碰撞箱失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void updateHitboxPositions() {
        for (BodyPart part : bodyParts.values()) {
            if (hitboxLifecycleManager.isPartDestroyed(part.getPartName())) {
                continue;
            }

            BoneTransform boneTransform = parent.getServerAnimationSystem().calculateBoneTransform(part.getBoneName());
            if (boneTransform != null) {
                // 使用工厂创建新的碰撞箱集群
                List<HitboxPart> newCluster = hitboxFactory.createHitboxCluster(part, boneTransform);

                // 更新生命周期管理器
                hitboxLifecycleManager.updateHitboxCluster(part.getPartName(), newCluster);
            }
        }
    }

    public void discardAllHitboxes() {
        hitboxLifecycleManager.cleanupAllHitboxes();
    }

    // 射线检测方法（TODO）
//    public String rayTracePreciseParts(net.minecraft.world.phys.Vec3 start, net.minecraft.world.phys.Vec3 end) {
//        return raycastService.rayTracePreciseParts(start, end);
//    }

    // 部位破坏处理方法(TODO)
    public void onPartDestroyed(String partName) {
        hitboxLifecycleManager.markPartAsDestroyed(partName);
        syncManager.syncAll(); // 同步状态变化
    }

    public void syncAllToClient() {
        syncManager.syncAll();
    }

    // 几何模型相关方法
    public GeometryModel getGeometryModel() {
        return geometryModel;
    }

    // 部位管理方法
    public BodyPart getBodyPart(String partName) {
        return bodyParts.get(partName);
    }

    public Map<String, BodyPart> getBodyParts() {
        return bodyParts;
    }

    // 修复：使用HitboxLifecycleManager替代不存在的hitboxManager
    public List<HitboxPart> getPreciseHitboxCluster(String partName) {
        return hitboxLifecycleManager.getHitboxCluster(partName);
    }

    // 提供变换管道的访问方法
    public ModularTransformPipeline getTransformPipeline() {
        return transformPipeline;
    }

    // 工具方法
    private float getHealthForPart(String partName) {
        switch (partName) {
            case "head": return 20.0f;
            case "torso": return 50.0f;
            case "arm_left":
            case "arm_right": return 15.0f;
            case "leg_left":
            case "leg_right": return 25.0f;
            default: return 10.0f;
        }
    }

    private float getDamageMultiplierForPart(String partName) {
        switch (partName) {
            case "head": return 2.0f;
            case "torso": return 1.0f;
            case "arm_left":
            case "arm_right": return 0.6f;
            case "leg_left":
            case "leg_right": return 0.7f;
            default: return 1.0f;
        }
    }

    // 新增调试方法
    public void enableDebugMode() {
        EnhancedDebugManager.enableAll();
    }

    public void skipPositionTransforms() {
        EnhancedDebugManager.skipPosition();
    }

    public void skipRotationTransforms() {
        EnhancedDebugManager.skipRotation();
    }

    public void skipPivotTransforms() {
        EnhancedDebugManager.skipPivot();
    }

    public void skipAllTransforms() {
        EnhancedDebugManager.skipAll();
    }

    public String getTransformDebugInfo() {
        return EnhancedDebugManager.getStatus();
    }

    // 在现有的调试信息中添加变换状态
    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Body Parts: ").append(bodyParts.size()).append("\n");
        sb.append("Geometry Bones: ").append(geometryModel.bones.size()).append("\n");
        sb.append("Transform Pipeline:\n").append(getTransformDebugInfo()).append("\n");

        for (Map.Entry<String, BodyPart> entry : bodyParts.entrySet()) {
            BodyPart part = entry.getValue();
            sb.append("  ").append(entry.getKey())
                    .append(": health=").append(part.getCurrentHealth())
                    .append(", destroyed=").append(part.isDestroyed())
                    .append(", hitboxes=").append(part.getHitboxCount())
                    .append("\n");
        }

        return sb.toString();
    }
}