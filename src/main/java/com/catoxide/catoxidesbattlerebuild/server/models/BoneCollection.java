package com.catoxide.catoxidesbattlerebuild.server.models;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;

import java.util.*;

/**
 * 骨骼集合 - 封装骨骼的所有数据
 * 包含原始骨骼数据、变换矩阵、受击盒配置
 */
public record BoneCollection(
        // 基础数据
        String name,
        GeoBone sourceBone,

        // 几何数据
        Vector3f localPosition,
        Vector3f worldPosition,
        Vector3f localScale,
        Matrix4f localRotation,
        Vector3f pivotPoint,
        Matrix4f worldRotation,

        // 层级关系
        String parentName,
        List<String> childrenNames,

        // 几何子元素
        List<CubeCollection> cubes,

        // 变换矩阵缓存
        Matrix4f localMatrix,      // 局部变换矩阵
        Matrix4f worldMatrix,      // 世界变换矩阵（相对于模型根）
        Matrix4f animationMatrix,  // 动画变换矩阵

        // 受击盒配置
        BoneHitboxConfig hitboxConfig,

        // 状态标记
        boolean hasHitbox
) {

    /**
     * 创建构建器
     * @param name 骨骼名称
     * @return 构建器实例
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * 骨骼集合构建器
     * 用于构建BoneCollection实例
     */
    public static class Builder {
        private final String name;
        private GeoBone sourceBone;
        private Vector3f localPosition = new Vector3f();
        private Vector3f worldPosition = new Vector3f();
        private Vector3f localScale = new Vector3f(1, 1, 1);
        private Matrix4f localRotation = new Matrix4f();
        private Matrix4f worldRotation = new Matrix4f();
        private Vector3f pivotPoint = new Vector3f();
        private String parentName;
        private final List<String> childrenNames = new ArrayList<>();
        private final List<CubeCollection> cubes = new ArrayList<>();
        private Matrix4f localMatrix = new Matrix4f().identity();
        private Matrix4f worldMatrix = new Matrix4f().identity();
        private Matrix4f animationMatrix = new Matrix4f().identity();
        private BoneHitboxConfig hitboxConfig;
        private boolean hasHitbox = false;

        /**
         * 构造函数
         * @param name 骨骼名称
         */
        public Builder(String name) {
            this.name = name;
        }

        /**
         * 从GeoBone复制数据
         * @param bone Geckolib骨骼对象
         * @return 构建器实例
         */
        public Builder fromGeoBone(GeoBone bone) {
            this.sourceBone = bone;
            this.localPosition = new Vector3f(
                (float) bone.getLocalPosition().x,
                (float) bone.getLocalPosition().y,
                (float) bone.getLocalPosition().z
            );
            this.worldPosition = new Vector3f(
                (float) bone.getWorldPosition().x,
                (float) bone.getWorldPosition().y,
                (float) bone.getWorldPosition().z
            );
            this.localScale = new Vector3f(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
            this.localRotation = bone.getLocalSpaceMatrix();
            this.worldRotation = bone.getWorldSpaceMatrix();
            this.pivotPoint = new Vector3f(bone.getPivotX(), bone.getPivotY(), bone.getPivotZ());

            // 收集cubes
            if (bone.getCubes() != null) {
                for (GeoCube cube : bone.getCubes()) {
                    cubes.add(CubeCollection.fromGeoCube(cube));
                }
            }

            return this;
        }

        /**
         * 设置父骨骼名称
         * @param parentName 父骨骼名称
         * @return 构建器实例
         */
        public Builder parent(String parentName) {
            this.parentName = parentName;
            return this;
        }

        /**
         * 设置源骨骼
         * @param sourceBone 源骨骼
         * @return 构建器实例
         */
        public Builder sourceBone(GeoBone sourceBone) {
            this.sourceBone = sourceBone;
            return this;
        }

        /**
         * 添加子骨骼名称
         * @param childName 子骨骼名称
         * @return 构建器实例
         */
        public Builder child(String childName) {
            this.childrenNames.add(childName);
            return this;
        }

        /**
         * 设置受击盒配置
         * @param config 受击盒配置
         * @return 构建器实例
         */
        public Builder hitboxConfig(BoneHitboxConfig config) {
            this.hitboxConfig = config;
            this.hasHitbox = config != null && config.enabled();
            return this;
        }

        /**
         * 设置局部位置
         * @param localPosition 局部位置
         * @return 构建器实例
         */
        public Builder localPosition(Vector3f localPosition) {
            this.localPosition = localPosition;
            return this;
        }

        /**
         * 设置世界位置
         * @param worldPosition 世界位置
         * @return 构建器实例
         */
        public Builder worldPosition(Vector3f worldPosition) {
            this.worldPosition = worldPosition;
            return this;
        }

        /**
         * 设置局部缩放
         * @param localScale 局部缩放
         * @return 构建器实例
         */
        public Builder localScale(Vector3f localScale) {
            this.localScale = localScale;
            return this;
        }

        /**
         * 设置局部旋转
         * @param localRotation 局部旋转矩阵
         * @return 构建器实例
         */
        public Builder localRotation(Matrix4f localRotation) {
            this.localRotation = localRotation;
            return this;
        }

        /**
         * 设置世界旋转
         * @param worldRotation 世界旋转矩阵
         * @return 构建器实例
         */
        public Builder worldRotation(Matrix4f worldRotation) {
            this.worldRotation = worldRotation;
            return this;
        }

        /**
         * 设置枢轴点
         * @param pivotPoint 枢轴点
         * @return 构建器实例
         */
        public Builder pivotPoint(Vector3f pivotPoint) {
            this.pivotPoint = pivotPoint;
            return this;
        }

        /**
         * 设置局部变换矩阵
         * @param localMatrix 局部变换矩阵
         * @return 构建器实例
         */
        public Builder localMatrix(Matrix4f localMatrix) {
            this.localMatrix = localMatrix;
            return this;
        }

        /**
         * 设置世界变换矩阵
         * @param worldMatrix 世界变换矩阵
         * @return 构建器实例
         */
        public Builder worldMatrix(Matrix4f worldMatrix) {
            this.worldMatrix = worldMatrix;
            return this;
        }

        /**
         * 设置动画变换矩阵
         * @param animationMatrix 动画变换矩阵
         * @return 构建器实例
         */
        public Builder animationMatrix(Matrix4f animationMatrix) {
            this.animationMatrix = animationMatrix;
            return this;
        }

        /**
         * 添加几何立方体
         * @param cube 立方体集合
         * @return 构建器实例
         */
        public Builder addCube(CubeCollection cube) {
            this.cubes.add(cube);
            return this;
        }

        /**
         * 设置几何立方体列表
         * @param cubes 立方体集合列表
         * @return 构建器实例
         */
        public Builder cubes(List<CubeCollection> cubes) {
            this.cubes.clear();
            this.cubes.addAll(cubes);
            return this;
        }

        /**
         * 构建BoneCollection实例
         * @return BoneCollection实例
         */
        public BoneCollection build() {
            return new BoneCollection(
                    name, sourceBone, localPosition, worldPosition, localScale,
                    localRotation, pivotPoint, worldRotation, parentName,
                    childrenNames, cubes, localMatrix, worldMatrix, animationMatrix,
                    hitboxConfig, hasHitbox
            );
        }
    }

    /**
     * 计算世界变换矩阵（相对于模型根）
     * 通过递归应用父级变换矩阵来计算
     * @param boneMap 所有骨骼的映射表
     * @return 世界变换矩阵
     */
    public Matrix4f calculateWorldMatrix(Map<String, BoneCollection> boneMap) {
        Matrix4f result = new Matrix4f(localMatrix);

        // 递归应用父级变换
        String currentParent = parentName;
        while (currentParent != null) {
            BoneCollection parent = boneMap.get(currentParent);
            if (parent != null) {
                result = parent.localMatrix.mul(result, new Matrix4f());
                currentParent = parent.parentName();
            } else {
                break;
            }
        }

        return result;
    }

    /**
     * 获取完整的变换矩阵（包括动画）
     * 将世界变换矩阵和动画变换矩阵相乘
     * @return 完整的变换矩阵
     */
    public Matrix4f getFullTransform() {
        Matrix4f result = new Matrix4f(worldMatrix);
        if (animationMatrix != null) {
            result.mul(animationMatrix);
        }
        return result;
    }

//    /**
//     * 获取受击盒的世界位置
//     * 将局部受击盒配置转换为世界坐标
//     * @return 世界坐标下的受击盒，如果没有配置则返回null
//     */
//    public BoneHitbox getWorldHitbox() {
//        this.worldPosition = worldPosition
//    }

    /**
     * 获取所有cube的世界顶点
     * 将局部坐标下的立方体顶点转换为世界坐标
     * @return 世界坐标下的顶点列表
     */
    public List<Vector3f> getWorldCubeVertices() {
        List<Vector3f> vertices = new ArrayList<>();
        Matrix4f transform = getFullTransform();

        for (CubeCollection cube : cubes) {
            for (Vector3f vertex : cube.localVertices()) {
                Vector3f worldVertex = transform.transformPosition(vertex, new Vector3f());
                vertices.add(worldVertex);
            }
        }

        return vertices;
    }
}