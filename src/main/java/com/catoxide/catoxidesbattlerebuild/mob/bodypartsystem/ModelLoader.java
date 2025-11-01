package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型加载器 - 负责从文件系统或资源包加载模型文件
 */
public class ModelLoader {

    /**
     * 加载几何模型
     */
    public GeometryModel loadGeometryModel(String modelPath) {
        String content = getModelContent(modelPath);
        if (content == null) {
            throw new RuntimeException("无法加载模型文件: " + modelPath);
        }
        return new GeometryModel(content);
    }

    private String getModelContent(String modelPath) {
        // 优先尝试ClassLoader方式（打包后）
        String classLoaderContent = readFromClassLoader(modelPath);
        if (classLoaderContent != null) {
            return classLoaderContent;
        }

        // 然后尝试外部文件（开发环境）
        String externalFileContent = readFromExternalFile(modelPath);
        if (externalFileContent != null) {
            return externalFileContent;
        }

        // 最后尝试调整路径（兼容性）
        String adjustedContent = tryAdjustedPaths(modelPath);
        if (adjustedContent != null) {
            return adjustedContent;
        }

        throw new RuntimeException(
                "无法读取模型文件：" + modelPath + "\n" +
                        "请确保以下文件之一存在：\n" +
                        "- classpath: " + modelPath + "\n" +
                        "- 文件系统: src/main/resources" + modelPath + "\n" +
                        "- 文件系统: ." + modelPath + "\n" +
                        "这个文件对于模块化僵尸的碰撞检测系统是必需的。"
        );
    }

    private String readFromClassLoader(String path) {
        try {
            // 确保路径以斜杠开头
            String normalizedPath = path.startsWith("/") ? path : "/" + path;
            InputStream inputStream = getClass().getResourceAsStream(normalizedPath);
            if (inputStream != null) {
                return readStream(inputStream);
            }
        } catch (Exception e) {
            System.err.println("ClassLoader读取失败: " + e.getMessage());
        }
        return null;
    }

    private String readFromExternalFile(String path) {
        try {
            // 移除开头的斜杠（如果存在）
            String normalizedPath = path.startsWith("/") ? path.substring(1) : path;

            String[] possiblePaths = {
                    "src/main/resources/" + normalizedPath,
                    "resources/" + normalizedPath,
                    normalizedPath,
                    "./" + normalizedPath
            };

            for (String filePath : possiblePaths) {
                File file = new File(filePath);
                if (file.exists() && file.isFile()) {
                    System.out.println("从外部文件加载: " + file.getAbsolutePath());
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            System.err.println("外部文件读取失败: " + e.getMessage());
        }
        return null;
    }

    private String tryAdjustedPaths(String originalPath) {
        // 尝试常见的路径变体
        String[] variants = {
                originalPath,
                originalPath.replace("geo/", "geometry/"),
                originalPath.replace("geometry/", "geo/"),
                "/assets" + (originalPath.startsWith("/") ? originalPath : "/" + originalPath),
                originalPath.replace(".geo.json", ".geometry.json"),
                originalPath.replace(".geometry.json", ".geo.json")
        };

        for (String variant : variants) {
            String content = readFromClassLoader(variant);
            if (content != null) {
                System.out.println("使用调整后的路径: " + variant);
                return content;
            }

            content = readFromExternalFile(variant);
            if (content != null) {
                System.out.println("使用调整后的路径: " + variant);
                return content;
            }
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

    /**
     * 检查模型文件是否存在
     */
    public boolean modelExists(String modelPath) {
        return readFromClassLoader(modelPath) != null || readFromExternalFile(modelPath) != null;
    }

    /**
     * 获取可用的模型路径列表
     */
    public List<String> findAvailableModels() {
        List<String> available = new ArrayList<>();
        String[] commonPaths = {
                "/assets/catoxidesbattlerebuild/geo/modular_zombie.geo.json",
                "/assets/catoxidesbattlerebuild/geometry/modular_zombie.geo.json",
                "assets/catoxidesbattlerebuild/geo/modular_zombie.geo.json"
        };

        for (String path : commonPaths) {
            if (modelExists(path)) {
                available.add(path);
            }
        }

        return available;
    }
}