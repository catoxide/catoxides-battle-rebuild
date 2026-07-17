package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPartConfig;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 JSON 文件加载实体部位血量配置
 * <p>JSON 格式：
 * <pre>{@code
 * {
 *   "entity_id": "catoxidesbattlerebuild:modular_zombie_2",
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
 *     },
 *     {
 *       "partName": "body",
 *       "maxModuleHealth": 20.0,
 *       "isFatal": false,
 *       "transmissionToBase": 1.0,
 *       "units": [
 *         {
 *           "boneName": "body",
 *           "transmissionCoeff": 1.0,
 *           "armorValue": 5.0,
 *           "antiPiercing": 5.0,
 *           "collisionTag": "body",
 *           "specialEffects": [],
 *           "hitSound": "entity.zombie.hurt"
 *         }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class HealthConfig {

    private final String entityId;
    private final List<BodyPartConfig> parts;

    private HealthConfig(String entityId, List<BodyPartConfig> parts) {
        this.entityId = entityId;
        this.parts = parts;
    }

    public String entityId() { return entityId; }
    public List<BodyPartConfig> parts() { return parts; }

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * 从 ResourceLocation 加载 JSON 配置
     */
    public static HealthConfig load(net.minecraft.server.packs.resources.ResourceManager rm, ResourceLocation location) {
        try {
            List<net.minecraft.server.packs.resources.Resource> resources = rm.getResourceStack(location);
            if (resources.isEmpty()) {
                LogManager.serverWarn("HealthConfig", "No resource found at {}", location);
                return null;
            }

            net.minecraft.server.packs.resources.Resource resource = resources.get(0);
            try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                String entityId = root.get("entity_id").getAsString();

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

                LogManager.serverInfo("HealthConfig", "Loaded config for entity {} with {} parts", entityId, parts.size());
                return new HealthConfig(entityId, parts);
            }
        } catch (Exception e) {
            LogManager.serverError("HealthConfig", "Failed to load health config from {}: {}", location, e.getMessage(), e);
            return null;
        }
    }
}
