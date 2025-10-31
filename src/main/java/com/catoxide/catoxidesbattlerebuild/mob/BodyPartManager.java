package com.catoxide.catoxidesbattlerebuild.mob;

import com.catoxide.catoxidesbattlerebuild.mob.server.HitboxSyncPacket;
import com.catoxide.catoxidesbattlerebuild.network.NetworkHandler;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;


public class BodyPartManager {
    private final ModularZombie parent;
    private final Map<String, BodyPart> bodyParts = new HashMap<>();
    // 移除单个碰撞箱映射，只保留精确碰撞箱集群
    private final Map<String, List<HitboxPart>> preciseHitboxClusters = new HashMap<>();
    private final GeometryModel geometryModel;

    public BodyPartManager(ModularZombie parent) {
        this.parent = parent;

        this.geometryModel = new GeometryModel(getGeoJsonContent());

        initBodyPartsFromGeometry();

    }

    // 从geo.json文件获取内容
    private String getGeoJsonContent() {
        // 优先尝试ClassLoader方式
        String classLoaderContent = readFromClassLoader();
        if (classLoaderContent != null) {
            return classLoaderContent;
        }

        // 然后尝试外部文件（开发环境）
        String externalFileContent = readFromExternalFile();
        if (externalFileContent != null) {
            return externalFileContent;
        }

        // 抛出错误
        throw new RuntimeException(        "无法读取geometry文件：所有读取方式都失败了。\n" +
                "请确保以下文件之一存在：\n" +
                "- classpath: /assets/catoxidesbattlerebuild/geometry/modular_zombie.geo.json\n" +
                "- 文件系统: src/main/resources/assets/catoxidesbattlerebuild/geometry/modular_zombie.geo.json\n" +
                "- 文件系统: assets/catoxidesbattlerebuild/geometry/modular_zombie.geo.json\n" +
                "- 文件系统: ./modular_zombie.geo.json\n" +
                "这个文件对于模块化僵尸的碰撞检测系统是必需的。");
    }

    // ClassLoader方式读取
    private String readFromClassLoader() {
        try {
            InputStream inputStream = getClass().getResourceAsStream("/assets/catoxidesbattlerebuild/geo/modular_zombie.geo.json");
            if (inputStream != null) {
                return readStream(inputStream);
            }
        } catch (Exception e) {
            System.err.println("ClassLoader读取失败: " + e.getMessage());
        }
        return null;
    }
    // 通用的流读取方法 - 需要添加这个方法
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

    // 外部文件方式读取
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

    // 从几何模型初始化身体部位
    private void initBodyPartsFromGeometry() {
        // 骨骼名称映射：几何模型骨骼名 -> 部位名
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
                // 直接存储立方体，而不是转换为AABB
                List<GeometryModel.Cube> cubes = bone.cubes;

                // 设置部位属性
                float health = getHealthForPart(partName);
                float damageMultiplier = getDamageMultiplierForPart(partName);

                // 修改：传入立方体而不是AABB列表
                bodyParts.put(partName, new BodyPart(partName, boneName, cubes, health, damageMultiplier, false));
            }
        }
    }

    // 从立方体创建碰撞箱
    private List<AABB> createHitboxesFromCubes(List<GeometryModel.Cube> cubes) {
        List<AABB> hitboxes = new ArrayList<>();
        float scale = 1.0f / 16.0f;

        System.out.println("转换 " + cubes.size() + " 个立方体为碰撞箱");

        for (int i = 0; i < cubes.size(); i++) {
            GeometryModel.Cube cube = cubes.get(i);

            // 调试输出原始数据
            System.out.println("立方体 " + i + ": 原点(" + cube.origin.x + "," + cube.origin.y + "," + cube.origin.z +
                    ") 大小(" + cube.size[0] + "," + cube.size[1] + "," + cube.size[2] + ")");

            double minX = cube.origin.x * scale;
            double minY = cube.origin.y * scale;
            double minZ = cube.origin.z * scale;
            double maxX = (cube.origin.x + cube.size[0]) * scale;
            double maxY = (cube.origin.y + cube.size[1]) * scale;
            double maxZ = (cube.origin.z + cube.size[2]) * scale;

            AABB hitbox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            hitboxes.add(hitbox);

            System.out.println("  转换后AABB: (" + minX + "," + minY + "," + minZ + ") -> (" + maxX + "," + maxY + "," + maxZ + ")");
            System.out.println("  尺寸: " + (maxX-minX) + " x " + (maxY-minY) + " x " + (maxZ-minZ));
        }

        return hitboxes;
    }

    // 获取部位生命值
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

    // 获取部位伤害倍率
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

    // 几何模型解析器（内部类）
    public static class GeometryModel {
        public Map<String, Bone> bones = new HashMap<>();
        public Bone rootBone;
        public List<Vertex> allVertices = new ArrayList<>();

        public GeometryModel(String geoJsonContent) {
            parseGeometry(geoJsonContent);
            buildBoneHierarchy();
            collectAllVertices();
        }

        private void parseGeometry(String content) {
            try {
                JsonObject geoJson = JsonParser.parseString(content).getAsJsonObject();
                JsonArray geometryArray = geoJson.getAsJsonArray("minecraft:geometry");
                JsonObject geometry = geometryArray.get(0).getAsJsonObject();
                JsonArray bonesArray = geometry.getAsJsonArray("bones");

                for (int i = 0; i < bonesArray.size(); i++) {
                    Bone bone = new Bone(bonesArray.get(i).getAsJsonObject());
                    bones.put(bone.name, bone);

                    if (bone.parent == null) {
                        rootBone = bone;
                    }
                }
            } catch (Exception e) {
                System.err.println("解析几何模型失败: " + e.getMessage());
            }
        }

        private void buildBoneHierarchy() {
            for (Bone bone : bones.values()) {
                if (bone.parent != null && bones.containsKey(bone.parent)) {
                    bones.get(bone.parent).children.put(bone.name, bone);
                }
            }
        }

        private void collectAllVertices() {
            for (Bone bone : bones.values()) {
                for (Cube cube : bone.cubes) {
                    List<Vertex> transformedVertices = applyBoneTransform(
                            cube.getVertices(), bone.pivot
                    );
                    allVertices.addAll(transformedVertices);
                }
            }
        }

        private List<Vertex> applyBoneTransform(List<Vertex> vertices, float[] pivot) {
            List<Vertex> transformed = new ArrayList<>();
            for (Vertex v : vertices) {
                transformed.add(new Vertex(
                        v.x + pivot[0],
                        v.y + pivot[1],
                        v.z + pivot[2]
                ));
            }
            return transformed;
        }

        // 骨骼类
        public static class Bone {
            public String name;
            public String parent;
            public float[] pivot;
            public List<Cube> cubes = new ArrayList<>();
            public Map<String, Bone> children = new HashMap<>();

            public Bone(JsonObject boneObj) {
                this.name = boneObj.get("name").getAsString();
                this.parent = boneObj.has("parent") ? boneObj.get("parent").getAsString() : null;

                JsonArray pivotArr = boneObj.getAsJsonArray("pivot");
                this.pivot = new float[]{
                        pivotArr.get(0).getAsFloat(),
                        pivotArr.get(1).getAsFloat(),
                        pivotArr.get(2).getAsFloat()
                };

                if (boneObj.has("cubes")) {
                    JsonArray cubesArr = boneObj.getAsJsonArray("cubes");
                    for (int i = 0; i < cubesArr.size(); i++) {
                        cubes.add(new Cube(cubesArr.get(i).getAsJsonObject(), this.name));
                    }
                }
            }
        }

        // 立方体类
        public static class Cube {
            public Vertex origin;
            public float[] size;
            public Vertex[] vertices;
            public String boneName;

            public Cube(JsonObject cubeObj, String boneName) {
                this.boneName = boneName;

                JsonArray originArr = cubeObj.getAsJsonArray("origin");
                this.origin = new Vertex(
                        originArr.get(0).getAsFloat(),
                        originArr.get(1).getAsFloat(),
                        originArr.get(2).getAsFloat()
                );

                JsonArray sizeArr = cubeObj.getAsJsonArray("size");
                this.size = new float[]{
                        sizeArr.get(0).getAsFloat(),
                        sizeArr.get(1).getAsFloat(),
                        sizeArr.get(2).getAsFloat()
                };

                calculateVertices();
            }

            private void calculateVertices() {
                vertices = new Vertex[8];

                vertices[0] = new Vertex(origin.x, origin.y, origin.z);
                vertices[1] = new Vertex(origin.x + size[0], origin.y, origin.z);
                vertices[2] = new Vertex(origin.x, origin.y + size[1], origin.z);
                vertices[3] = new Vertex(origin.x + size[0], origin.y + size[1], origin.z);
                vertices[4] = new Vertex(origin.x, origin.y, origin.z + size[2]);
                vertices[5] = new Vertex(origin.x + size[0], origin.y, origin.z + size[2]);
                vertices[6] = new Vertex(origin.x, origin.y + size[1], origin.z + size[2]);
                vertices[7] = new Vertex(origin.x + size[0], origin.y + size[1], origin.z + size[2]);
            }

            public List<Vertex> getVertices() {
                return Arrays.asList(vertices);
            }
        }
    }

    // 顶点类
    public static class Vertex {
        public float x, y, z;

        public Vertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return String.format("(%.2f, %.2f, %.2f)", x, y, z);
        }
    }



    public void spawnHitboxEntities() {
        System.out.println("=== 开始生成碰撞箱实体 ===");

        for (BodyPart part : bodyParts.values()) {
            try {
                // 使用 ServerAnimationSystem 获取完整的骨骼变换
                BoneTransform boneTransform = parent.getServerAnimationSystem().getBoneTransform(part.getBoneName());

                System.out.println("生成部位: " + part.getPartName() + " | 骨骼: " + part.getBoneName());
                System.out.println("  骨骼变换 - 位置: " + boneTransform.position + ", 旋转: " + boneTransform.rotation + ", 缩放: " + boneTransform.scale);

                // 创建精确碰撞箱集群
                List<HitboxPart> cluster = new ArrayList<>();
                List<GeometryModel.Cube> cubes = part.getCubes();

                for (int i = 0; i < cubes.size(); i++) {
                    GeometryModel.Cube cube = cubes.get(i);

                    // 变换立方体的所有顶点到世界坐标
                    List<Vec3> worldVertices = new ArrayList<>();
                    for (Vertex vertex : cube.getVertices()) {
                        Vec3 worldVertex = transformVertex(vertex, boneTransform);
                        worldVertices.add(worldVertex);
                    }

                    // 从变换后的顶点创建OBB
                    AABB worldOBB = createOBBFromTransformedVertices(worldVertices);
                    Vec3 center = getAABBCenter(worldOBB);

                    HitboxPart preciseHitbox = new HitboxPart(ModEntities.HITBOX_PART.get(), parent.level());
                    preciseHitbox.initialize(parent, part.getPartName() + "_precise_" + i);

                    preciseHitbox.setPos(center.x, center.y, center.z);
                    preciseHitbox.setBoundingBox(worldOBB);

                    parent.level().addFreshEntity(preciseHitbox);
                    cluster.add(preciseHitbox);

                    System.out.println("  立方体 " + i + " 世界OBB: " + worldOBB);
                }

                preciseHitboxClusters.put(part.getPartName(), cluster);

            } catch (Exception e) {
                System.err.println("生成部位 " + part.getPartName() + " 的碰撞箱失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // 更新碰撞箱位置
    public void updateHitboxPositions() {
        try {
            for (Map.Entry<String, BodyPart> entry : bodyParts.entrySet()) {
                String partName = entry.getKey();  // 获取部位名称
                BodyPart part = entry.getValue();  // 获取部位对象 - 这里定义了 part 变量

                if (part.isDestroyed()) continue;
                // 使用 ServerAnimationSystem 获取完整的骨骼变换
                BoneTransform boneTransform = parent.getServerAnimationSystem().getBoneTransform(part.getBoneName());

                // 更新精确碰撞箱集群
                List<HitboxPart> cluster = preciseHitboxClusters.get(partName);
                if (cluster != null) {
                    List<GeometryModel.Cube> cubes = part.getCubes();
                    for (int i = 0; i < cubes.size() && i < cluster.size(); i++) {
                        GeometryModel.Cube cube = cubes.get(i);
                        HitboxPart preciseHitbox = cluster.get(i);

                        // 变换立方体的所有顶点到世界坐标
                        List<Vec3> worldVertices = new ArrayList<>();
                        for (Vertex vertex : cube.getVertices()) {
                            Vec3 worldVertex = transformVertex(vertex, boneTransform);
                            worldVertices.add(worldVertex);
                        }

                        // 从变换后的顶点创建OBB
                        AABB worldOBB = createOBBFromTransformedVertices(worldVertices);
                        Vec3 center = getAABBCenter(worldOBB);
                        preciseHitbox.setPos(center.x, center.y, center.z);
                        preciseHitbox.setBoundingBox(worldOBB);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("更新部位 " + " 的碰撞箱失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 简化的变换方法
    private AABB transformHitboxSimple(AABB localHitbox, BoneTransform transform) {
        return localHitbox.move(transform.position);
    }

    // 获取AABB中心点
    private Vec3 getAABBCenter(AABB aabb) {
        return new Vec3(
                (aabb.minX + aabb.maxX) / 2,
                (aabb.minY + aabb.maxY) / 2,
                (aabb.minZ + aabb.maxZ) / 2
        );
    }
    // 变换顶点到世界坐标（考虑旋转和缩放）
    private Vec3 transformVertex(Vertex vertex, BoneTransform transform) {
        // 将模型坐标转换为世界坐标（除以16，因为Minecraft中1格=16像素）
        float scaleFactor = 1.0f / 16.0f;
        Vector3f localPos = new Vector3f(vertex.x * scaleFactor, vertex.y * scaleFactor, vertex.z * scaleFactor);

        // 应用缩放
        localPos.mul(transform.scale);

        // 应用旋转
        Vector3f rotatedPos = transform.rotation.transform(localPos);

        // 应用位置偏移
        return new Vec3(
                transform.position.x + rotatedPos.x,
                transform.position.y + rotatedPos.y,
                transform.position.z + rotatedPos.z
        );
    }
    private AABB createOBBFromTransformedVertices(List<Vec3> worldVertices) {
        if (worldVertices.isEmpty()) {
            return new AABB(0, 0, 0, 0, 0, 0);
        }

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (Vec3 vertex : worldVertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // 获取部位数据
    public BodyPart getBodyPart(String partName) {
        return bodyParts.get(partName);
    }

    // 获取所有部位
    public Map<String, BodyPart> getBodyParts() {
        return bodyParts;
    }

    // 获取精确碰撞箱集群
    public List<HitboxPart> getPreciseHitboxCluster(String partName) {
        return preciseHitboxClusters.get(partName);
    }

    public String rayTracePreciseParts(Vec3 start, Vec3 end) {
        double closestDistance = Double.MAX_VALUE;
        String hitPart = null;

        for (Map.Entry<String, BodyPart> entry : bodyParts.entrySet()) {
            String partName = entry.getKey();
            BodyPart part = entry.getValue();

            if (part.isDestroyed()) continue;

            try {
                // 改为使用 ServerAnimationSystem
                BoneTransform boneTransform = parent.getServerAnimationSystem().getBoneTransform(part.getBoneName());
                List<AABB> hitboxes = part.getPreciseHitboxes();

                for (AABB hitbox : hitboxes) {
                    AABB worldHitbox = transformHitboxSimple(hitbox, boneTransform);
                    Optional<Vec3> hitPos = worldHitbox.clip(start, end);
                    if (hitPos.isPresent()) {
                        double distance = start.distanceTo(hitPos.get());
                        if (distance < closestDistance) {
                            closestDistance = distance;
                            hitPart = partName;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("射线检测失败，跳过部位 " + partName + ": " + e.getMessage());
            }
        }

        return hitPart;
    }


    // 清理所有碰撞箱
    public void discardAllHitboxes() {
        // 只清理精确碰撞箱集群
        preciseHitboxClusters.values().forEach(cluster -> {
            if (cluster != null) {
                cluster.forEach(hitbox -> {
                    if (hitbox != null) {
                        hitbox.discard();
                    }
                });
            }
        });
        preciseHitboxClusters.clear();
    }

    // 调试信息
    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Body Parts: ").append(bodyParts.size()).append("\n");
        sb.append("Geometry Bones: ").append(geometryModel.bones.size()).append("\n");
        sb.append("Precise Hitbox Clusters: ").append(preciseHitboxClusters.size()).append("\n");

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

    // 客户端状态跟踪
    private final Map<String, Long> recentlyHitParts = new HashMap<>();
    private final Map<String, Boolean> destroyedParts = new HashMap<>();

    // 标记部位为最近被击中（客户端调用）
    @OnlyIn(Dist.CLIENT)
    public void markPartAsRecentlyHit(String partName) {
        recentlyHitParts.put(partName, System.currentTimeMillis());
    }

    // 检查部位是否最近被击中（客户端调用）
    @OnlyIn(Dist.CLIENT)
    public boolean isPartRecentlyHit(String partName) {
        Long hitTime = recentlyHitParts.get(partName);
        if (hitTime == null) return false;

        // 1秒内算作"最近"
        return System.currentTimeMillis() - hitTime < 1000;
    }

    // 标记部位被破坏（从服务端同步）
    public void markPartAsDestroyed(String partName) {
        destroyedParts.put(partName, true);
    }

    // 检查部位是否被破坏
    public boolean isPartDestroyed(String partName) {
        return destroyedParts.getOrDefault(partName, false);
    }

    // 客户端清理过期的击中状态
    @OnlyIn(Dist.CLIENT)
    public void cleanupHitStates() {
        long currentTime = System.currentTimeMillis();
        recentlyHitParts.entrySet().removeIf(entry ->
                currentTime - entry.getValue() > 1000);
    }

    public void syncHitboxesToClient() {
        if (parent.level().isClientSide) return;

        List<HitboxSyncPacket.HitboxData> hitboxDataList = new ArrayList<>();

        for (BodyPart part : bodyParts.values()) {
            if (part.isDestroyed()) continue;

            // 获取完整的骨骼变换（包括旋转）
            BoneTransform boneTransform = parent.getServerAnimationSystem().getBoneTransform(part.getBoneName());
            if (boneTransform != null) {
                Vec3 boneWorldPos = boneTransform.position;
                AABB baseHitbox = part.getBaseHitbox();
                AABB worldHitbox = baseHitbox.move(boneWorldPos);

                hitboxDataList.add(new HitboxSyncPacket.HitboxData(
                        part.getPartName(),
                        boneWorldPos,
                        boneTransform.rotation,  // 传递旋转信息
                        worldHitbox.minX, worldHitbox.minY, worldHitbox.minZ,
                        worldHitbox.maxX, worldHitbox.maxY, worldHitbox.maxZ
                ));

                System.out.println("同步部位: " + part.getPartName());
                System.out.println("  位置: " + boneWorldPos);
                System.out.println("  旋转: " + boneTransform.rotation);
                System.out.println("  AABB: " + worldHitbox);
            }
        }

        // 发送网络数据包
        if (!hitboxDataList.isEmpty()) {
            HitboxSyncPacket packet = new HitboxSyncPacket(parent.getId(), hitboxDataList);
            NetworkHandler.sendToAllTracking(packet, parent);
        }
    }
}