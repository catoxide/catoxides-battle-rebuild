package com.catoxide.catoxidesbattlerebuild.network;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/**
 * 压缩的骨骼变换数据
 * 
 * 优化策略：
 * 1. 使用半精度浮点数压缩矩阵（16个short代替16个float）
 * 2. 使用变化标志位只同步变化的分量
 * 3. 根据骨骼重要性使用不同精度等级
 */
public class CompressedBoneTransform {
    
    // 骨骼名称（使用字符串池进一步压缩）
    public final String boneName;
    
    // 压缩的变换矩阵（16个short代替16个float）
    // 矩阵格式：[m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33]
    public final short[] compressedMatrix;
    
    // 变化标志位（哪些分量有变化）
    // bit 0-3: 平移分量变化
    // bit 4-6: 旋转分量变化
    // bit 7: 缩放变化
    public final byte changeFlags;
    
    // 精度等级（0=低精度, 1=中精度, 2=高精度）
    public final byte precisionLevel;
    
    /**
     * 从完整的4x4矩阵创建压缩变换
     */
    public CompressedBoneTransform(String boneName, Matrix4f matrix, byte precisionLevel) {
        this.boneName = boneName;
        this.precisionLevel = precisionLevel;
        this.compressedMatrix = compressMatrix(matrix, precisionLevel);
        this.changeFlags = calculateChangeFlags(matrix);
    }
    
    /**
     * 从压缩数据创建（用于解码）
     */
    public CompressedBoneTransform(String boneName, short[] compressedMatrix, 
                                   byte changeFlags, byte precisionLevel) {
        this.boneName = boneName;
        this.compressedMatrix = compressedMatrix;
        this.changeFlags = changeFlags;
        this.precisionLevel = precisionLevel;
    }
    
    /**
     * 压缩4x4矩阵为short数组
     * 
     * 压缩策略：
     * - 低精度：使用16位整数，范围[-32768, 32767]，精度约0.001
     * - 中精度：使用16位整数，范围[-32768, 32767]，精度约0.0001
     * - 高精度：使用16位整数，范围[-32768, 32767]，精度约0.00001
     */
    private static short[] compressMatrix(Matrix4f matrix, byte precisionLevel) {
        short[] result = new short[16];
        float[] matrixData = new float[16];
        matrix.get(matrixData);
        
        float scale = getScaleFactor(precisionLevel);
        
        for (int i = 0; i < 16; i++) {
            // 将float转换为short，使用适当的缩放因子
            result[i] = (short) Math.round(matrixData[i] * scale);
        }
        
        return result;
    }
    
    /**
     * 解压short数组为4x4矩阵
     */
    public Matrix4f decompressMatrix() {
        float[] matrixData = new float[16];
        float scale = getScaleFactor(precisionLevel);
        
        for (int i = 0; i < 16; i++) {
            matrixData[i] = compressedMatrix[i] / scale;
        }
        
        Matrix4f matrix = new Matrix4f();
        matrix.set(matrixData);
        return matrix;
    }
    
    /**
     * 获取缩放因子
     */
    private static float getScaleFactor(byte precisionLevel) {
        return switch (precisionLevel) {
            case 0 -> 1000.0f;  // 低精度：0.001
            case 1 -> 10000.0f; // 中精度：0.0001
            case 2 -> 100000.0f; // 高精度：0.00001
            default -> 1000.0f;
        };
    }
    
    /**
     * 计算变化标志位
     */
    private static byte calculateChangeFlags(Matrix4f matrix) {
        byte flags = 0;
        
        // 检查平移分量（m03, m13, m23）
        if (Math.abs(matrix.m03()) > 0.001f) flags |= 0x01;
        if (Math.abs(matrix.m13()) > 0.001f) flags |= 0x02;
        if (Math.abs(matrix.m23()) > 0.001f) flags |= 0x04;
        
        // 检查旋转分量（通过矩阵的旋转部分）
        float rotationChange = Math.abs(matrix.m00() - 1.0f) + Math.abs(matrix.m11() - 1.0f) + Math.abs(matrix.m22() - 1.0f);
        if (rotationChange > 0.01f) flags |= 0x08;
        
        // 检查缩放变化
        float scale = matrix.m00() + matrix.m11() + matrix.m22();
        if (Math.abs(scale - 3.0f) > 0.01f) flags |= 0x10;
        
        return flags;
    }
    
    /**
     * 检查是否有变化
     */
    public boolean hasChanges() {
        return changeFlags != 0;
    }
    
    /**
     * 获取平移向量
     */
    public Vector3f getTranslation() {
        Matrix4f matrix = decompressMatrix();
        return new Vector3f(matrix.m03(), matrix.m13(), matrix.m23());
    }
    
    /**
     * 获取旋转四元数
     */
    public Quaternionf getRotation() {
        Matrix4f matrix = decompressMatrix();
        return matrix.getUnnormalizedRotation(new Quaternionf());
    }
    
    /**
     * 获取缩放向量
     */
    public Vector3f getScale() {
        Matrix4f matrix = decompressMatrix();
        return new Vector3f(matrix.m00(), matrix.m11(), matrix.m22());
    }
    
    /**
     * 计算与另一个变换的差异
     */
    public float calculateDifference(CompressedBoneTransform other) {
        if (!boneName.equals(other.boneName)) {
            return Float.MAX_VALUE;
        }
        
        Matrix4f thisMatrix = decompressMatrix();
        Matrix4f otherMatrix = other.decompressMatrix();
        
        float[] thisData = new float[16];
        float[] otherData = new float[16];
        thisMatrix.get(thisData);
        otherMatrix.get(otherData);
        
        float diff = 0.0f;
        for (int i = 0; i < 16; i++) {
            diff += Math.abs(thisData[i] - otherData[i]);
        }
        
        return diff;
    }
    
    /**
     * 获取估算的数据大小（字节）
     */
    public int getEstimatedSize() {
        // 骨骼名称（假设平均10字节）+ 压缩矩阵（32字节）+ 标志位（2字节）
        return 10 + 32 + 2;
    }
}


