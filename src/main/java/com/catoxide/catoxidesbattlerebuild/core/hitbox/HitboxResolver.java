package com.catoxide.catoxidesbattlerebuild.core.hitbox;

import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.origin.OBone;
import cn.solarmoon.spark_core.animation.model.origin.OCube;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hitbox 配置解析器（混合模式）
 * <p>解析流程：
 * <ol>
 *   <li>从 geo.json 自动提取每个骨骼的 cubes，计算 localOffset 和 halfExtents</li>
 *   <li>如果存在 hitbox.json 覆盖文件，用手动配置覆盖/补充</li>
 * </ol>
 *
 * <p><b>单位说明</b>：
 * Spark-Core 在加载模型时（见 {@code ModelModule.kt:47-67}）已将 geo.json 中的
 * px 值除以 16 转成米，并完成了 Bedrock→MC 的 X 轴翻转。因此
 * {@link OBone#getPivot()}、{@link OCube#getOriginPos()}、{@link OCube#getSize()}
 * 返回的值<b>已经是米单位且 X 轴方向已对齐 MC</b>，无需再做任何换算或翻转。
 */
public final class HitboxResolver {

    private HitboxResolver() {}

    /**
     * 从 ModelInstance（已加载的 geo.json）自动提取 hitbox 配置
     * <p>对每个骨骼的第一个 cube，计算：
     * <ul>
     *   <li>localOffset = cubeCenter - bonePivot（米单位）</li>
     *   <li>halfExtents = cubeSize / 2（米单位）</li>
     * </ul>
     *
     * @param model 已加载的 Spark-Core ModelInstance
     * @return 骨骼名 → HitboxConfig
     */
    public static Map<String, HitboxConfig> resolveFromModel(ModelInstance model) {
        Map<String, HitboxConfig> result = new LinkedHashMap<>();
        if (model == null || model.getOrigin() == null) return result;

        Map<String, OBone> bones = model.getOrigin().getBones();
        for (Map.Entry<String, OBone> entry : bones.entrySet()) {
            String boneName = entry.getKey();
            OBone bone = entry.getValue();
            List<OCube> cubes = bone.getCubes();
            if (cubes == null || cubes.isEmpty()) continue;

            // 只取第一个 cube 作为 hitbox
            OCube cube = cubes.get(0);

            // originPos / size / pivot 均为米单位（Spark-Core 加载时已 /16）
            Vector3f origin = toVec3f(cube.getOriginPos());
            Vector3f size = toVec3f(cube.getSize());
            Vector3f pivot = toVec3f(bone.getPivot());

            // cube 中心 = origin + size/2
            Vector3f cubeCenter = new Vector3f(
                    origin.x + size.x / 2f,
                    origin.y + size.y / 2f,
                    origin.z + size.z / 2f
            );

            // localOffset = cubeCenter - pivot（米单位，X 已对齐 MC）
            Vector3f offset = new Vector3f(
                    cubeCenter.x - pivot.x,
                    cubeCenter.y - pivot.y,
                    cubeCenter.z - pivot.z
            );

            Vector3f halfExtents = new Vector3f(
                    size.x / 2f,
                    size.y / 2f,
                    size.z / 2f
            );

            result.put(boneName, new HitboxConfig(boneName, offset, halfExtents));
        }
        return result;
    }

    /**
     * 从 hitbox.json 覆盖/补充配置
     * <p>JSON 格式（米单位，与 Spark-Core 加载后的模型数据一致）：
     * <pre>{@code
     * {
     *   "bone_name": {
     *     "offset": [x, y, z],     // 米，MC 坐标系
     *     "half_extents": [x, y, z], // 米
     *     "armor": 0.0              // 装甲厚度（可选）
     *   }
     * }
     * }</pre>
     *
     * @param rm       ResourceManager
     * @param location hitbox.json 的 ResourceLocation
     * @param base     从 geo.json 提取的基础配置（会被覆盖）
     * @return 合并后的配置
     */
    public static Map<String, HitboxConfig> overrideFromJson(
            ResourceManager rm, ResourceLocation location, Map<String, HitboxConfig> base) {
        Map<String, HitboxConfig> result = new LinkedHashMap<>(base);
        try {
            List<Resource> resources = rm.getResourceStack(location);
            for (Resource res : resources) {
                try (InputStreamReader reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (String boneName : root.keySet()) {
                        JsonObject cfg = root.getAsJsonObject(boneName);
                        Vector3f offset = readVec3(cfg, "offset", new Vector3f());
                        Vector3f halfExtents = readVec3(cfg, "half_extents", new Vector3f(0.1f, 0.1f, 0.1f));
                        float armor = cfg.has("armor") ? cfg.get("armor").getAsFloat() : 0f;

                        result.put(boneName, new HitboxConfig(boneName, offset, halfExtents, armor));
                    }
                }
            }
        } catch (Exception e) {
            LogManager.clientWarn("HitboxResolver", "Failed to load hitbox override {}: {}", location, e.getMessage());
        }
        return result;
    }

    private static Vector3f readVec3(JsonObject obj, String key, Vector3f def) {
        if (!obj.has(key)) return def;
        JsonArray arr = obj.getAsJsonArray(key);
        return new Vector3f(
                arr.get(0).getAsFloat(),
                arr.get(1).getAsFloat(),
                arr.get(2).getAsFloat()
        );
    }

    private static Vector3f toVec3f(Vec3 v) {
        if (v == null) return new Vector3f();
        return new Vector3f((float) v.x, (float) v.y, (float) v.z);
    }
}
