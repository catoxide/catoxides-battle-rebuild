package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import java.util.Objects;

/**
 * 3D顶点类
 */
public class Vertex {
    public final float x, y, z;

    public Vertex(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * 顶点加法
     */
    public Vertex add(Vertex other) {
        return new Vertex(x + other.x, y + other.y, z + other.z);
    }

    /**
     * 顶点减法
     */
    public Vertex subtract(Vertex other) {
        return new Vertex(x - other.x, y - other.y, z - other.z);
    }

    /**
     * 标量乘法
     */
    public Vertex multiply(float scalar) {
        return new Vertex(x * scalar, y * scalar, z * scalar);
    }

    /**
     * 计算两点间距离
     */
    public float distanceTo(Vertex other) {
        float dx = x - other.x;
        float dy = y - other.y;
        float dz = z - other.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 转换为Minecraft世界坐标（除以16）
     */
    public Vertex toWorldCoordinates() {
        float scale = 1.0f / 16.0f;
        return new Vertex(x * scale, y * scale, z * scale);
    }

    /**
     * 转换为模型坐标（乘以16）
     */
    public Vertex toModelCoordinates() {
        return new Vertex(x * 16, y * 16, z * 16);
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f, %.2f)", x, y, z);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Vertex vertex = (Vertex) obj;
        return Float.compare(vertex.x, x) == 0 &&
                Float.compare(vertex.y, y) == 0 &&
                Float.compare(vertex.z, z) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }
}