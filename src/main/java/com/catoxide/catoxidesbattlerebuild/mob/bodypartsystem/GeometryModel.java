
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * 几何模型解析器 - 负责解析和存储geo.json模型数据
 */
public class GeometryModel {
    public final Map<String, Bone> bones = new HashMap<>();
    public Bone rootBone;
    public final List<Vertex> allVertices = new ArrayList<>();

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
            e.printStackTrace();
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

    /**
     * 获取指定骨骼的所有顶点（世界坐标）
     */
    public List<Vertex> getBoneVertices(String boneName) {
        Bone bone = bones.get(boneName);
        if (bone == null) return Collections.emptyList();

        List<Vertex> vertices = new ArrayList<>();
        for (Cube cube : bone.cubes) {
            vertices.addAll(applyBoneTransform(cube.getVertices(), bone.pivot));
        }
        return vertices;
    }

    /**
     * 获取骨骼的边界框（局部坐标）
     */
    public AABB getBoneBoundingBox(String boneName) {
        List<Vertex> vertices = getBoneVertices(boneName);
        if (vertices.isEmpty()) return null;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

        for (Vertex vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }

        float scale = 1.0f / 16.0f;
        return new AABB(
                minX * scale, minY * scale, minZ * scale,
                maxX * scale, maxY * scale, maxZ * scale
        );
    }

    /**
     * 获取骨骼信息
     */
    public Bone getBone(String boneName) {
        return bones.get(boneName);
    }

    /**
     * 检查骨骼是否存在
     */
    public boolean hasBone(String boneName) {
        return bones.containsKey(boneName);
    }

    /**
     * 获取所有骨骼名称
     */
    public Set<String> getBoneNames() {
        return bones.keySet();
    }

    // 骨骼类
    public static class Bone {
        public final String name;
        public final String parent;
        public final float[] pivot;
        public final List<Cube> cubes = new ArrayList<>();
        public final Map<String, Bone> children = new HashMap<>();

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

        /**
         * 获取骨骼的所有子骨骼
         */
        public Collection<Bone> getChildren() {
            return children.values();
        }

        /**
         * 检查是否有子骨骼
         */
        public boolean hasChildren() {
            return !children.isEmpty();
        }
    }

    // 立方体类
    public static class Cube {
        public final Vertex origin;
        public final float[] size;
        public Vertex[] vertices;
        public final String boneName;

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

        /**
         * 获取立方体的中心点
         */
        public Vertex getCenter() {
            return new Vertex(
                    origin.x + size[0] / 2,
                    origin.y + size[1] / 2,
                    origin.z + size[2] / 2
            );
        }
    }
}