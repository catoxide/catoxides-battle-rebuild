// GeoModelLoader.java
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class GeoModelLoader {
    private static final Map<String, GeometryModel> MODEL_CACHE = new HashMap<>();

    public static GeometryModel loadModel(String modelPath) {
        return MODEL_CACHE.computeIfAbsent(modelPath, path -> {
            String content = getGeoJsonContent(path);
            return new GeometryModel(content);
        });
    }

    private static String getGeoJsonContent(String modelPath) {
        // 优先尝试ClassLoader方式
        String classLoaderContent = readFromClassLoader(modelPath);
        if (classLoaderContent != null) {
            return classLoaderContent;
        }

        // 然后尝试外部文件（开发环境）
        String externalFileContent = readFromExternalFile(modelPath);
        if (externalFileContent != null) {
            return externalFileContent;
        }

        throw new RuntimeException("无法读取geometry文件：" + modelPath);
    }

    private static String readFromClassLoader(String path) {
        try {
            InputStream inputStream = GeoModelLoader.class.getResourceAsStream(path);
            if (inputStream != null) {
                return readStream(inputStream);
            }
        } catch (Exception e) {
            System.err.println("ClassLoader读取失败: " + e.getMessage());
        }
        return null;
    }

    private static String readStream(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        }
    }

    private static String readFromExternalFile(String path) {
        try {
            // 移除路径开头的斜杠（如果存在），因为外部文件路径不需要
            String cleanPath = path.startsWith("/") ? path.substring(1) : path;

            String[] possiblePaths = {
                    "src/main/resources/" + cleanPath,
                    "./" + cleanPath,
                    cleanPath
            };

            for (String filePath : possiblePaths) {
                File file = new File(filePath);
                if (file.exists() && file.isFile()) {
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            System.err.println("外部文件读取失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 清除缓存（用于开发时重新加载模型）
     */
    public static void clearCache() {
        MODEL_CACHE.clear();
    }

    /**
     * 清除指定路径的缓存
     */
    public static void clearCache(String modelPath) {
        MODEL_CACHE.remove(modelPath);
    }
}