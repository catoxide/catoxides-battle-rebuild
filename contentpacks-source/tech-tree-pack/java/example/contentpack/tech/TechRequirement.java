package example.contentpack.tech;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条数据驱动条件（{@code requirements}/{@code visibleWhen} 的数组元素）。
 * <p>格式：{@code { "type": "...", ...参数 }}；内置类型见 {@link TechRequirementEngine}。
 * 未知类型交给其它内容包通过 capability 注册的 {@code RequirementExtension}。
 */
public record TechRequirement(String type, JsonObject params) {

    public TechRequirement {
        params = params == null ? new JsonObject() : params.deepCopy();
    }

    public static TechRequirement of(String type, JsonObject params) {
        return new TechRequirement(type, params);
    }

    public String string(String key, String fallback) {
        JsonElement el = params.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsString();
    }

    public int integer(String key, int fallback) {
        JsonElement el = params.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsInt();
    }

    public boolean bool(String key, boolean fallback) {
        JsonElement el = params.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsBoolean();
    }

    public List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        JsonElement el = params.get(key);
        if (el != null && el.isJsonArray()) {
            for (JsonElement entry : el.getAsJsonArray()) {
                out.add(entry.getAsString());
            }
        }
        return out;
    }

    /** 嵌套条件（all/any）。 */
    public List<TechRequirement> children() {
        List<TechRequirement> out = new ArrayList<>();
        JsonElement el = params.get("requirements");
        if (el != null && el.isJsonArray()) {
            for (JsonElement entry : el.getAsJsonArray()) {
                JsonObject object = entry.getAsJsonObject();
                out.add(new TechRequirement(object.get("type").getAsString(), object));
            }
        }
        return out;
    }

    /** 物品条件是否要求解锁时扣除。 */
    public boolean consumesItem() {
        return bool("consume", false);
    }
}
