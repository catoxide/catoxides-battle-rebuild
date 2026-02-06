package com.catoxide.catoxidesbattlerebuild.server.models;

import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoCube;

import java.util.Arrays;
import java.util.List;

/**
 * Cube几何数据集合
 */
public record CubeCollection(
        String id,
        Vector3f pivot,        // 枢轴点/原点 - 相当于JSON中的origin
        Vector3f size,
        Vector3f rotation,
        Vector3f originOffset, // 原点偏移 - 相对于pivot的额外偏移量
        List<Vector3f> localVertices,
        float inflate // 膨胀系数，用于受击盒
) {

    private static final Vector3f[] CUBE_CORNERS = {
            new Vector3f(-0.5f, -0.5f, -0.5f),
            new Vector3f(0.5f, -0.5f, -0.5f),
            new Vector3f(0.5f, -0.5f, 0.5f),
            new Vector3f(-0.5f, -0.5f, 0.5f),
            new Vector3f(-0.5f, 0.5f, -0.5f),
            new Vector3f(0.5f, 0.5f, -0.5f),
            new Vector3f(0.5f, 0.5f, 0.5f),
            new Vector3f(-0.5f, 0.5f, 0.5f)
    };

    // 根据原始JSON数据创建CubeCollection
    public static CubeCollection fromJsonData(String id, Vector3f pivot, Vector3f size, Vector3f rotation, Vector3f originOffset) {
        List<Vector3f> vertices = calculateLocalVertices(pivot, size, rotation, originOffset);
        return new CubeCollection(id, pivot, size, rotation, originOffset, vertices, 0.0f);
    }

    private static List<Vector3f> calculateLocalVertices(
            Vector3f pivot, Vector3f size, Vector3f rotation, Vector3f originOffset) {

        Vector3f[] vertices = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            // 应用尺寸
            Vector3f vertex = new Vector3f(
                    CUBE_CORNERS[i].x * size.x,
                    CUBE_CORNERS[i].y * size.y,
                    CUBE_CORNERS[i].z * size.z
            );

            // 应用旋转（简化的欧拉角旋转）
            if (rotation.x != 0 || rotation.y != 0 || rotation.z != 0) {
                // 这里需要实现完整的旋转矩阵，简化为绕轴旋转
                // 实际项目中应使用四元数或旋转矩阵
            }

            // 应用偏移
            vertex.add(pivot).add(originOffset);
            vertices[i] = vertex;
        }

        return Arrays.asList(vertices);
    }

//    // 获取包围盒
//    public CubeBoundingBox getBoundingBox() {
//        if (localVertices.isEmpty()) return null;
//
//        Vector3f min = new Vector3f(Float.MAX_VALUE);
//        Vector3f max = new Vector3f(Float.MIN_VALUE);
//
//        for (Vector3f vertex : localVertices) {
//            min.x = Math.min(min.x, vertex.x);
//            min.y = Math.min(min.y, vertex.y);
//            min.z = Math.min(min.z, vertex.z);
//            max.x = Math.max(max.x, vertex.x);
//            max.y = Math.max(max.y, vertex.y);
//            max.z = Math.max(max.z, vertex.z);
//        }
//
//        // 应用膨胀
//        Vector3f inflateVec = new Vector3f(inflate, inflate, inflate);
//        min.sub(inflateVec);
//        max.add(inflateVec);
//
//        return new CubeBoundingBox(min, max);
//    }
}