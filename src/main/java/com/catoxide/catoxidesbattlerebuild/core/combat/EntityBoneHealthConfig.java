package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPartConfig;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 JSON 文件加载实体骨骼血量配置
 * <p>配置文件放置位置：
 * <pre>
 *   assets/&lt;modid&gt;/health_config/&lt;entity&gt;.json
 * </pre>
 * <p>JSON 格式示例：
 * <pre>{@code
 * {
 *   "parts": [
 *     {
 *       "partName": "head",
 *       "maxModuleHealth": 10.0,
 *       "isFatal": true,
 *       "transmissionToBase": 1.5,
 *       "units": [
 *         {
 *           "boneName": "head",
 *           "transmissionCoeff": 1.0,
 *           "armorValue": 2.0,
 *           "antiPiercing": 3.0,
 *           "collisionTag": "head",
 *           "specialEffects": [],
 *           "hitSound": "entity.zombie.hurt"
 *         }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code partName} - 部位名称（逻辑分组，如 head/body/arm）</li>
 *   <li>{@code maxModuleHealth} - 部位总血量（float，支持小数）</li>
 *   <li>{@code isFatal} - 该部位被破坏是否直接致死</li>
 *   <li>{@code transmissionToBase} - 伤害传递到基体血量的倍率</li>
 *   <li>{@code units} - 该部位包含的骨骼单元列表</li>
 *   <li>{@code boneName} - 骨骼名称（对应 geo.json 中的 bone name）</li>
 *   <li>{@code transmissionCoeff} - 骨骼到部位内部伤害传递系数</li>
 *   <li>{@code armorValue} - 装甲值/硬度（与攻击穿甲值对比）</li>
 *   <li>{@code antiPiercing} - 抗贯穿值（命中时消耗攻击贯穿值）</li>
 *   <li>{@code collisionTag} - 碰撞标签（用于调试渲染分类）</li>
 *   <li>{@code specialEffects} - 命中特效列表（暂不实现）</li>
 *   <li>{@code hitSound} - 命中音效（暂不实现）</li>
 * </ul>
 */
public final class EntityBoneHealthConfig {

    private EntityBoneHealthConfig() {}

    private static final String ASSET_PREFIX = "health_config/";

    /**
     * 从资源管理器加载指定实体的骨骼血量配置
     *
     * @param rm       资源管理器
     * @param modId    mod id（用于定位资源）
     * @param entityName 实体名称（对应文件名）
     * @return 部位配置列表，加载失败返回 null
     */
    public static List<BodyPartConfig> load(
            net.minecraft.server.packs.resources.ResourceManager rm,
            String modId, String entityName) {

        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(modId, ASSET_PREFIX + entityName + ".json");

        try {
            List<net.minecraft.server.packs.resources.Resource> resources = rm.getResourceStack(location);
            if (!resources.isEmpty()) {
                net.minecraft.server.packs.resources.Resource resource = resources.get(0);
                return parseFromReader(new InputStreamReader(resource.open(), StandardCharsets.UTF_8),
                        entityName, location.toString());
            }
        } catch (Exception e) {
            LogManager.serverDebug("HealthConfig", "ResourceManager lookup failed for %s: %s", location, e.getMessage());
        }

        // Fallback: try loading from classpath directly
        try {
            String classpathResource = "assets/" + modId + "/" + ASSET_PREFIX + entityName + ".json";
            URL resourceUrl = Thread.currentThread().getContextClassLoader().getResource(classpathResource);
            if (resourceUrl != null) {
                LogManager.serverInfo("HealthConfig", "Loaded %s parts for entity %s from classpath: %s",
                        "classpath", entityName, classpathResource);
                try (var reader = new InputStreamReader(resourceUrl.openStream(), StandardCharsets.UTF_8)) {
                    return parseFromReader(reader, entityName, classpathResource);
                }
            }
        } catch (Exception e) {
            LogManager.serverDebug("HealthConfig", "Classpath lookup failed for %s: %s",
                    "assets/" + modId + "/" + ASSET_PREFIX + entityName + ".json", e.getMessage());
        }

        LogManager.serverDebug("HealthConfig", "No health config found at %s", location);
        return null;
    }

    private static List<BodyPartConfig> parseFromReader(
            InputStreamReader reader, String entityName, String source) throws IOException {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        JsonArray partsArray = root.getAsJsonArray("parts");
        List<BodyPartConfig> parts = new ArrayList<>();

        for (JsonElement partElem : partsArray) {
            JsonObject partObj = partElem.getAsJsonObject();

            String partName = partObj.get("partName").getAsString();
            float maxModuleHealth = partObj.get("maxModuleHealth").getAsFloat();
            boolean isFatal = partObj.get("isFatal").getAsBoolean();
            float transmissionToBase = partObj.get("transmissionToBase").getAsFloat();

            JsonArray unitsArray = partObj.getAsJsonArray("units");
            List<BodyPartConfig.BodyUnitConfig> units = new ArrayList<>();

            for (JsonElement unitElem : unitsArray) {
                JsonObject unitObj = unitElem.getAsJsonObject();

                String boneName = unitObj.get("boneName").getAsString();
                float transmissionCoeff = unitObj.get("transmissionCoeff").getAsFloat();
                float armorValue = unitObj.get("armorValue").getAsFloat();
                float antiPiercing = unitObj.get("antiPiercing").getAsFloat();
                String collisionTag = unitObj.get("collisionTag").getAsString();

                List<String> specialEffects = new ArrayList<>();
                if (unitObj.has("specialEffects")) {
                    for (JsonElement eff : unitObj.getAsJsonArray("specialEffects")) {
                        specialEffects.add(eff.getAsString());
                    }
                }

                String hitSound = unitObj.has("hitSound") ? unitObj.get("hitSound").getAsString() : null;

                units.add(new BodyPartConfig.BodyUnitConfig(
                        boneName, transmissionCoeff, armorValue, antiPiercing,
                        collisionTag, specialEffects, hitSound
                ));
            }

            parts.add(new BodyPartConfig(partName, maxModuleHealth, isFatal, transmissionToBase, units));
        }

        LogManager.serverInfo("HealthConfig", "Loaded %d parts for entity %s from %s",
                parts.size(), entityName, source);
        return parts;
    }
}
