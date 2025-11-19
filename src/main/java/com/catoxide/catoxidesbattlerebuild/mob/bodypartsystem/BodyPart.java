package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.debug.EnhancedDebugManager;
import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.debug.SkipSystem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;


public class BodyPart {
    private final String partName;
    private final String boneName;
    private final List<GeometryModel.Cube> cubes;
    private AABB baseHitbox;

    // 模块类型属性
    private final PartType partType;
    private boolean lethalWhenDestroyed;

    // 可破坏模块属性
    private float currentHealth;
    private final float maxHealth;
    private final float[] pivot;

    // 伤害倍率属性
    private final float damageMultiplierToMain;

    // 新增：bodyPartManager 字段声明
    private BodyPartManager bodyPartManager;

    public enum PartType {
        DESTRUCTIBLE,
        INDESTRUCTIBLE
    }

    // 移除静态初始化块，因为它使用了实例字段
    // static {
    //     // 初始化调试管理器
    //     EnhancedDebugManager.initialize(transformPipeline);
    // }

    // 可破坏模块构造器
    public BodyPart(BodyPartManager manager, String partName, String boneName, List<GeometryModel.Cube> cubes,
                    float maxHealth, float damageMultiplierToMain, boolean lethalWhenDestroyed, float[] pivot) {
        this.bodyPartManager = manager;  // 初始化 bodyPartManager
        this.partName = partName;
        this.boneName = boneName;
        this.cubes = cubes;
        this.baseHitbox = calculateBoundingBoxFromCubes(cubes);
        this.partType = PartType.DESTRUCTIBLE;
        this.maxHealth = maxHealth;
        this.currentHealth = maxHealth;
        this.damageMultiplierToMain = damageMultiplierToMain;
        this.lethalWhenDestroyed = lethalWhenDestroyed;
        this.pivot = pivot;
    }

    // 不可破坏模块构造器 - 也需要 BodyPartManager 参数
    public BodyPart(BodyPartManager manager, String partName, String boneName, List<GeometryModel.Cube> cubes,
                    float damageMultiplierToMain, float[] pivot) {
        this.bodyPartManager = manager;  // 初始化 bodyPartManager
        this.partName = partName;
        this.boneName = boneName;
        this.cubes = cubes;
        this.baseHitbox = calculateBoundingBoxFromCubes(cubes);
        this.partType = PartType.INDESTRUCTIBLE;
        this.maxHealth = 0;
        this.currentHealth = 0;
        this.damageMultiplierToMain = damageMultiplierToMain;
        this.lethalWhenDestroyed = false;
        this.pivot = pivot;
    }

    // 修改变换方法 - 使用单例模式简化
    private List<Vec3> transformCubeVertices(GeometryModel.Cube cube, BoneTransform transform) {
        List<Vec3> worldVertices = new ArrayList<>();

        // 直接使用单例模式，避免复杂的依赖关系
        ModularTransformPipeline pipeline = ModularTransformPipeline.getInstance();

        for (Vertex vertex : cube.getVertices()) {
            Vec3 worldVertex = pipeline.transformVertex(vertex, transform, this.pivot);
            worldVertices.add(worldVertex);
        }

        return worldVertices;
    }


    // 设置致命性
    public void setLethalWhenDestroyed(boolean lethalWhenDestroyed) {
        this.lethalWhenDestroyed = lethalWhenDestroyed;
    }

    // 处理伤害，返回对主体的伤害值
    public float takeDamage(float damage) {
        float damageToMain = damage * damageMultiplierToMain;

        if (partType == PartType.DESTRUCTIBLE && !isDestroyed()) {
            // 可破坏模块：先扣除模块血量
            currentHealth = Math.max(0, currentHealth - damage);

            // 如果模块被破坏，应用额外的对主体伤害
            if (isDestroyed()) {
                damageToMain += calculateDestructionDamage();
            }
        }

        return damageToMain;
    }

    private float calculateDestructionDamage() {
        // 模块被破坏时对主体造成的额外伤害
        // 可以是固定值或基于最大血量的百分比
        return maxHealth * 0.1f; // 示例：最大血量的10%
    }

    // Getter/Setter
    public String getPartName() { return partName; }
    public String getBoneName() { return boneName; }
    public AABB getBaseHitbox() { return baseHitbox; }
    public List<GeometryModel.Cube> getCubes() { return cubes; } // 新增：获取立方体
    public PartType getPartType() { return partType; }
    public boolean isLethalWhenDestroyed() { return lethalWhenDestroyed; }
    public float getCurrentHealth() { return currentHealth; }
    public float getMaxHealth() { return maxHealth; }
    public float getDamageMultiplierToMain() { return damageMultiplierToMain; }
    public boolean isDestroyed() {
        return partType == PartType.DESTRUCTIBLE && currentHealth <= 0;
    }
    public int getHitboxCount() { return cubes.size(); }

    // 获取变换后的世界OBB（新增方法）
    public AABB getTransformedHitbox(BoneTransform transform, int cubeIndex) {
        if (cubeIndex < 0 || cubeIndex >= cubes.size()) {
            return new AABB(0, 0, 0, 0, 0, 0);
        }

        GeometryModel.Cube cube = cubes.get(cubeIndex);
        List<Vec3> worldVertices = transformCubeVertices(cube, transform);
        return createOBBFromVertices(worldVertices);
    }

    // 获取所有变换后的世界OBB（新增方法）
    public List<AABB> getAllTransformedHitboxes(BoneTransform transform) {
        List<AABB> transformedHitboxes = new ArrayList<>();
        for (GeometryModel.Cube cube : cubes) {
            List<Vec3> worldVertices = transformCubeVertices(cube, transform);
            transformedHitboxes.add(createOBBFromVertices(worldVertices));
        }
        return transformedHitboxes;
    }

    // 从顶点创建OBB（新增方法）
    private AABB createOBBFromVertices(List<Vec3> vertices) {
        if (vertices.isEmpty()) {
            return new AABB(0, 0, 0, 0, 0, 0);
        }

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (Vec3 vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // 从立方体计算基础碰撞箱（修改方法）
    private AABB calculateBoundingBoxFromCubes(List<GeometryModel.Cube> cubes) {
        if (cubes.isEmpty()) {
            return new AABB(0, 0, 0, 0, 0, 0);
        }

        // 计算所有立方体的合并AABB
        AABB result = cubeToLocalAABB(cubes.get(0));
        for (int i = 1; i < cubes.size(); i++) {
            result = result.minmax(cubeToLocalAABB(cubes.get(i)));
        }
        return result;
    }

    // 将单个立方体转换为局部AABB（新增方法）
    private AABB cubeToLocalAABB(GeometryModel.Cube cube) {
        float scale = 1.0f / 16.0f;
        return new AABB(
                cube.origin.x * scale,
                cube.origin.y * scale,
                cube.origin.z * scale,
                (cube.origin.x + cube.size[0]) * scale,
                (cube.origin.y + cube.size[1]) * scale,
                (cube.origin.z + cube.size[2]) * scale
        );
    }

    // 兼容性方法 - 返回空的精确碰撞箱列表（修改方法）
    @Deprecated
    public List<AABB> getPreciseHitboxes() {
        // 返回空列表，因为我们现在使用立方体
        return new ArrayList<>();
    }

    public float[] getPivot() {
        return pivot;
    }

    // 治疗模块（新增方法）
    public void heal(float amount) {
        if (partType == PartType.DESTRUCTIBLE) {
            currentHealth = Math.min(currentHealth + amount, maxHealth);
        }
    }

    // 重置模块（新增方法）
    public void reset() {
        if (partType == PartType.DESTRUCTIBLE) {
            currentHealth = maxHealth;
        }
    }
}