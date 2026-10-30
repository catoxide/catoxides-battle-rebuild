package example.contentpack.map.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 生物的脸：从实体贴图里裁出头部正面那一块（和玩家皮肤的脸一样，通常 8×8），
 * 平铺到 16×16 的图标框里，而不是渲染 3D 头。
 *
 * <p>UV 直接从模型读：找模型里名为 {@code head} 的部件，取朝 -Z（正面）的多边形，
 * 它的贴图坐标就是脸。这样不用给每个生物维护「脸在哪」的表，换贴图/换模型也跟着走。
 * 模型的几何字段（cubes / polygons / uv）是私有的，这里用缓存好的反射各读一次；
 * 读不到就返回 {@code null}，调用方退回渲染 3D 头。
 */
final class MarkerFaces {

    /** 归一化 UV（0..1）乘以它变成 blit 需要的整数 texel 坐标；不依赖贴图实际尺寸。 */
    static final int TEXEL_SPACE = 1_000_000;

    private static final Rect NONE = new Rect(0f, 0f, 0f, 0f);
    private static final Map<EntityType<?>, Rect> RECTS = new HashMap<>();
    private static final String[] HEAD_NAMES = {"head", "skull", "head2", "body", "cube"};
    private static final Object[] NO_POLYGONS = new Object[0];

    private static final Field CUBES = accessible(ModelPart.class, "cubes");
    private static final Field POLYGONS = accessible(ModelPart.Cube.class, "polygons");
    private static final Class<?> POLYGON_TYPE =
            POLYGONS == null ? null : POLYGONS.getType().getComponentType();
    private static final Field NORMAL = accessible(POLYGON_TYPE, "normal");
    private static final Field VERTICES = accessible(POLYGON_TYPE, "vertices");
    private static final Class<?> VERTEX_TYPE =
            VERTICES == null ? null : VERTICES.getType().getComponentType();
    private static final Field UV_U = accessible(VERTEX_TYPE, "u");
    private static final Field UV_V = accessible(VERTEX_TYPE, "v");

    private MarkerFaces() {
    }

    /** 贴图里的一块区域（UV 已归一化）。 */
    record Face(ResourceLocation texture, float u0, float v0, float u1, float v1) {
        float width() {
            return u1 - u0;
        }

        float height() {
            return v1 - v0;
        }
    }

    private record Rect(float u0, float v0, float u1, float v1) {
        float area() {
            return (u1 - u0) * (v1 - v0);
        }
    }

    @Nullable
    static Face of(LivingEntity living) {
        EntityType<?> type = living.getType();
        if (type == EntityType.SLIME || type == EntityType.MAGMA_CUBE) {
            // 这两种的「脸」是内层模型里一堆积木（眼睛/嘴各是独立小块），外层贴图只是色块；
            // 裁出来会是个没有五官的方块，退回渲染 3D 头才看得见表情。
            return null;
        }
        EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher()
                .getRenderer(living);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> modelRenderer)) {
            return null;
        }
        Rect rect = RECTS.get(type);
        if (rect == null) {
            rect = resolve(modelRenderer.getModel());
            RECTS.put(type, rect == null ? NONE : rect);
        }
        if (rect == NONE) {
            return null;
        }
        return new Face(texture(modelRenderer, living), rect.u0(), rect.v0(), rect.u1(), rect.v1());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResourceLocation texture(EntityRenderer renderer, LivingEntity living) {
        return renderer.getTextureLocation(living);
    }

    @Nullable
    private static Rect resolve(EntityModel<?> model) {
        for (String name : HEAD_NAMES) {
            ModelPart part = part(model, name);
            if (part == null) {
                continue;
            }
            Rect rect = frontRect(part);
            if (rect != null) {
                return rect;
            }
        }
        return bestPart(model);
    }

    /** 按字段名找部件（head / skull / body …）；找不到名字的模型走几何兜底。 */
    @Nullable
    private static ModelPart part(EntityModel<?> model, String name) {
        for (Class<?> type = model.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType() != ModelPart.class
                        || !field.getName().equalsIgnoreCase(name)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return (ModelPart) field.get(model);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }
        // 层级模型（史莱姆/恶魂/鱿鱼…）的部件不存字段，原版就给了按名字找的后代接口
        if (model instanceof HierarchicalModel<?> hierarchical) {
            return hierarchical.getAnyDescendantWithName(name).orElse(null);
        }
        return null;
    }

    /** 名字都对不上时，取「正面最大的一块」；跳过帽子等叠加层。 */
    @Nullable
    private static Rect bestPart(EntityModel<?> model) {
        Rect best = null;
        for (Class<?> type = model.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != ModelPart.class) {
                    continue;
                }
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.contains("hat") || name.contains("inner")) {
                    continue;
                }
                ModelPart part;
                try {
                    field.setAccessible(true);
                    part = (ModelPart) field.get(model);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    continue;
                }
                if (part == null) {
                    continue;
                }
                Rect rect = frontRect(part);
                if (rect != null && (best == null || rect.area() > best.area())) {
                    best = rect;
                }
            }
        }
        return best;
    }

    /**
     * 正面（-Z）那一块。先只看部件自己的立方体：人形模型的帽子和耳朵是兄弟部件，
     * 不能混进来。自身没有立方体时才往子部件里找（少数模型把头拆开了）。
     */
    @Nullable
    private static Rect frontRect(ModelPart part) {
        Rect own = largestFront(part);
        if (own != null) {
            return own;
        }
        Rect best = null;
        for (ModelPart child : part.getAllParts().toList()) {
            if (child == part) {
                continue;
            }
            Rect rect = largestFront(child);
            if (rect != null && (best == null || rect.area() > best.area())) {
                best = rect;
            }
        }
        return best;
    }

    @Nullable
    private static Rect largestFront(ModelPart part) {
        Rect best = null;
        for (ModelPart.Cube cube : cubes(part)) {
            for (Object polygon : polygons(cube)) {
                if (!frontFacing(polygon)) {
                    continue;
                }
                Rect rect = rect(polygon);
                if (rect != null && (best == null || rect.area() > best.area())) {
                    best = rect;
                }
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private static List<ModelPart.Cube> cubes(ModelPart part) {
        if (CUBES == null) {
            return List.of();
        }
        try {
            return (List<ModelPart.Cube>) CUBES.get(part);
        } catch (IllegalAccessException e) {
            return List.of();
        }
    }

    private static Object[] polygons(ModelPart.Cube cube) {
        if (POLYGONS == null) {
            return NO_POLYGONS;
        }
        try {
            return (Object[]) POLYGONS.get(cube);
        } catch (IllegalAccessException e) {
            return NO_POLYGONS;
        }
    }

    private static boolean frontFacing(Object polygon) {
        if (NORMAL == null) {
            return false;
        }
        try {
            Vector3f normal = (Vector3f) NORMAL.get(polygon);
            return normal != null && normal.z() < -0.5f;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    @Nullable
    private static Rect rect(Object polygon) {
        if (VERTICES == null || UV_U == null || UV_V == null) {
            return null;
        }
        try {
            Object[] vertices = (Object[]) VERTICES.get(polygon);
            if (vertices == null || vertices.length == 0) {
                return null;
            }
            float u0 = Float.MAX_VALUE;
            float v0 = Float.MAX_VALUE;
            float u1 = -Float.MAX_VALUE;
            float v1 = -Float.MAX_VALUE;
            for (Object vertex : vertices) {
                float u = (Float) UV_U.get(vertex);
                float v = (Float) UV_V.get(vertex);
                u0 = Math.min(u0, u);
                u1 = Math.max(u1, u);
                v0 = Math.min(v0, v);
                v1 = Math.max(v1, v);
            }
            if (u1 <= u0 || v1 <= v0) {
                return null;
            }
            return new Rect(u0, v0, u1, v1);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    @Nullable
    private static Field accessible(@Nullable Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }
}
