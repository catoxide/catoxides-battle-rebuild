package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class BodyPartManager {
    private final ModularZombie parent;
    private final Map<String, BodyPart> bodyParts = new HashMap<>();

    // 模块化组件
    private final GeometryModel geometryModel;
    private final HitboxManager hitboxManager;
    private final ModelRotationManager rotationManager;
    private final WorldCoordinateTransformer coordinateTransformer;

    // 客户端状态跟踪
    private final Map<String, Long> recentlyHitParts = new HashMap<>();
    private final Map<String, Boolean> destroyedParts = new HashMap<>();

    public BodyPartManager(ModularZombie parent) {
        this.parent = parent;

        // 初始化模块化组件
        this.geometryModel = new GeometryModel(getGeoJsonContent());
        this.hitboxManager = new HitboxManager(parent, this);
        this.rotationManager = new ModelRotationManager();
        this.coordinateTransformer = new WorldCoordinateTransformer(parent);

        initBodyPartsFromGeometry();
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

                bodyParts.put(partName, new BodyPart(partName, boneName, cubes, health, damageMultiplier, false, bone.pivot));
            }
        }
    }

    // 代理方法到各个管理器
    public void spawnHitboxEntities() {
        hitboxManager.spawnHitboxEntities();
    }

    public void updateHitboxPositions() {
        hitboxManager.updateHitboxPositions();
    }

    public void discardAllHitboxes() {
       hitboxManager.discardAllHitboxes();
    }

    public String rayTracePreciseParts(net.minecraft.world.phys.Vec3 start, net.minecraft.world.phys.Vec3 end) {
        return hitboxManager.rayTracePreciseParts(start, end);
    }

    public void syncHitboxesToClient() {
        hitboxManager.syncHitboxesToClient();
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

    public List<HitboxPart> getPreciseHitboxCluster(String partName) {
        return hitboxManager.getPreciseHitboxCluster(partName);
    }

    // 旋转管理
    public ModelRotationManager getRotationManager() {
        return rotationManager;
    }

    // 坐标变换
    public WorldCoordinateTransformer getCoordinateTransformer() {
        return coordinateTransformer;
    }

    // 客户端状态管理
    public void markPartAsRecentlyHit(String partName) {
        recentlyHitParts.put(partName, System.currentTimeMillis());
    }

    public boolean isPartRecentlyHit(String partName) {
        Long hitTime = recentlyHitParts.get(partName);
        if (hitTime == null) return false;
        return System.currentTimeMillis() - hitTime < 1000;
    }

    public void markPartAsDestroyed(String partName) {
        destroyedParts.put(partName, true);
        ////hitboxManager.onPartDestroyed(partName);
    }

    public boolean isPartDestroyed(String partName) {
        return destroyedParts.getOrDefault(partName, false);
    }

    public void cleanupHitStates() {
        long currentTime = System.currentTimeMillis();
        recentlyHitParts.entrySet().removeIf(entry ->
                currentTime - entry.getValue() > 1000);
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

    private String getGeoJsonContent() {
        // 原有的文件读取逻辑
        String classLoaderContent = readFromClassLoader();
        if (classLoaderContent != null) return classLoaderContent;

        String externalFileContent = readFromExternalFile();
        if (externalFileContent != null) return externalFileContent;

        throw new RuntimeException("无法读取geometry文件");
    }

    private String readFromClassLoader() {
        try {
            InputStream inputStream = getClass().getResourceAsStream("/assets/catoxidesbattlerebuild/geo/modular_zombie.geo.json");
            if (inputStream != null) return readStream(inputStream);
        } catch (Exception e) {
            System.err.println("ClassLoader读取失败: " + e.getMessage());
        }
        return null;
    }

    private String readFromExternalFile() {
        try {
            String[] possiblePaths = {
                    "src/main/resources/assets/catoxidesbattlerebuild/geometry/modular_zombie.geo.json",
                    "assets/catoxidesbattlerebuild/geometry/modular_zombie.geo.json",
                    "./modular_zombie.geo.json"
            };
            for (String path : possiblePaths) {
                File file = new File(path);
                if (file.exists()) {
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            System.err.println("外部文件读取失败: " + e.getMessage());
        }
        return null;
    }

    private String readStream(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        }
    }

    // 调试信息
    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Body Parts: ").append(bodyParts.size()).append("\n");
        sb.append("Geometry Bones: ").append(geometryModel.bones.size()).append("\n");
        ////sb.append("Hitbox Manager: ").append(hitboxManager.getDebugInfo()).append("\n");

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