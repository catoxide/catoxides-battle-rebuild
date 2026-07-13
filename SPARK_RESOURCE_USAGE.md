# Spark-Core 资源使用指南

## 清理记录

以下空目录因 Spark 兼容迁移已删除（无任何代码引用）：

- `assets/catoxidesbattlerebuild/blockstates/` — 项目无自定义方块
- `assets/catoxidesbattlerebuild/models/block/` — 同上
- `sparkcore/` — Spark-Core 脚本目录（本项目不使用脚本系统）
- `spark_modules/` — Spark 模块打包目录（空文件夹，无内容）

---

## 当前有效资源文件一览

```
src/main/resources/
├── META-INF/neoforge.mods.toml           # 模组描述文件
├── catoxidesbattlerebuild.mixins.json    # Mixin 配置
│
├── spark_models/                          # ★ Spark 模型（Bedrock 格式）
│   ├── entity/catoxidesbattlerebuild/
│   │   └── modular_zombie_2.json          # ModularZombie2 的骨骼模型
│   └── item/catoxidesbattlerebuild/
│       └── iron_sword.json                # 铁剑武器模型
│
├── spark_animations/                      # ★ Spark 动画（Bedrock 格式）
│   ├── entity/catoxidesbattlerebuild/modular_zombie_2/
│   │   ├── still.json                     # 空闲站立
│   │   ├── walking.json                   # 慢走游荡
│   │   ├── running.json                   # 奔跑追击
│   │   ├── attack.json                    # 攻击挥拳
│   │   ├── alert.json                     # 警觉过渡
│   │   ├── aggressive.json                # 攻击预备（Winding）
│   │   ├── hit_front.json                 # 正面受击
│   │   └── hit_back.json                  # 背面受击
│   │
│   └── item/catoxidesbattlerebuild/iron_sword/
│       ├── idle.json                      # 铁剑待机
│       └── attack.json                    # 铁剑挥砍
│
└── assets/catoxidesbattlerebuild/
    ├── textures/
    │   ├── entity/
    │   │   ├── modular_zombie.png         # ModularZombie（GeckoLib，旧版）贴图
    │   │   └── modular_zombie_2.png       # ModularZombie2（Spark）贴图
    │   └── item/
    │       └── iron_sword.png             # 铁剑贴图
    │
    ├── geo/                               # GeckoLib 模型（旧格式，与 spark_models 并存）
    │   ├── modular_zombie.geo.json         # ModularZombie 模型（GeckoLib 用）
    │   └── modular_zombie_2.geo.json      # ModularZombie2 模型（带 locators）
    │
    ├── animations/                        # GeckoLib 动画（旧格式）
    │   ├── modular_zombie.animation.json   # ModularZombie 全部动画（GeckoLib 格式）
    │   └── modular_zombie_2.animation.json # ModularZombie2 全部动画（GeckoLib 格式）
    │
    └── models/item/
        ├── iron_sword_weapon.json          # 铁剑物品模型（第一人称）
        └── iron_sword_weapon_in_hand.json  # 铁剑物品模型（第三人称）
```

---

## 两套动画系统对比

| | GeckoLib（旧） | Spark-Core（新） |
|---|---|---|
| **模型格式** | `geo/*.geo.json` | `spark_models/` Bedrock JSON |
| **动画格式** | `animations/*.animation.json` | `spark_animations/` 拆分 JSON |
| **模型路径** | `assets/modid/geo/name.geo.json` | `spark_models/type/modid/name.json` |
| **动画路径** | 全部在单个 `animations/` 文件中 | `spark_animations/entity/item/modid/name/anim.json` |
| **代码入口** | `GeoEntityRenderer` + `GeoModel` | `ModelIndex` + `IEntityAnimatable` |
| **使用实体** | ModularZombie（zombie1） | ModularZombie2（zombie2） |

---

## 如何为新生物添加 Spark 资源

### 1. 创建模型文件

在 `spark_models/entity/<modid>/<mob_name>.json` 创建 Bedrock 格式模型：

```json
{
  "format_version": "1.12.0",
  "minecraft:geometry": [
    {
      "description": {
        "identifier": "geometry.my_mob",
        "texture_width": 64,
        "texture_height": 64,
        "visible_bounds_width": 2,
        "visible_bounds_height": 3.5,
        "visible_bounds_offset": [0, 1.25, 0]
      },
      "bones": [
        {
          "name": "body",
          "pivot": [0, 12, 0],
          "cubes": [
            {"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16]}
          ]
        },
        {
          "name": "head",
          "parent": "body",
          "pivot": [0, 24, 0],
          "cubes": [
            {"origin": [-4, 24, -4], "size": [8, 8, 8], "uv": [0, 0]}
          ]
        }
      ]
    }
  ]
}
```

### 2. 创建动画文件

在 `spark_animations/entity/<modid>/<mob_name>/` 为每个动画状态创建一个 `.json` 文件：

```json
{
  "animations": {
    "still": {
      "loop": "loop",
      "animation_length": 5.0,
      "bones": {
        "head": {
          "rotation": {
            "0.0": { "post": [0, 0, 0], "lerp_mode": "ease_in_out" },
            "2.5": { "post": [-5, 0, 0], "lerp_mode": "ease_in_out" },
            "5.0": { "post": [0, 0, 0], "lerp_mode": "ease_in_out" }
          }
        }
      }
    }
  }
}
```

### 3. 创建贴图

放置纹理到 `assets/<modid>/textures/entity/<mob_name>.png`

### 4. Java 代码对接

继承 `AnimatedMob<T>`，实现抽象方法：

```java
public class MyMob extends AnimatedMob<MyMob> {

    public static final int STATE_IDLE = 0;
    public static final int STATE_WALKING = 1;

    @Override
    public ModelIndex getDefaultModelIndex() {
        return new ModelIndex(
            "entity",                                    // 类型：entity 或 item
            ResourceLocation.fromNamespaceAndPath(       // 对应 spark_models/entity/modid/name.json
                "mymod", "my_mob")
        );
    }

    @Override
    public ResourceLocation getTextureLocation() {
        return ResourceLocation.fromNamespaceAndPath(
            "mymod", "textures/entity/my_mob.png");
    }

    @Override
    public String getStateAnimationName(int state) {
        switch (state) {
            case STATE_IDLE:    return "still";
            case STATE_WALKING: return "walking";
            default:            return "still";
        }
    }

    // ... 实现其他抽象方法
}
```

### 5. 在 Renderer 中注册

```java
public class MyMobRenderer extends SparkEntityRenderer<MyMob> {
    public MyMobRenderer(GeoRendererProvider rendererProvider) {
        super(rendererProvider, MyMob.class);
    }
}
```

---

## 如何为武器添加 Spark 资源

### 1. 创建武器模型

在 `spark_models/item/<modid>/<weapon_name>.json` 创建模型：

```json
{
  "format_version": "1.12.0",
  "minecraft:geometry": [
    {
      "description": {
        "identifier": "geometry.my_sword",
        "texture_width": 32,
        "texture_height": 32,
        "visible_bounds_width": 2,
        "visible_bounds_height": 2.5,
        "visible_bounds_offset": [0, 0.75, 0]
      },
      "bones": [
        {
          "name": "blade",
          "pivot": [0, 0, 0],
          "cubes": [
            {"origin": [-1, 0, -0.5], "size": [2, 8, 0.5], "uv": [0, 0]}
          ]
        }
      ]
    }
  ]
}
```

### 2. 创建武器动画

在 `spark_animations/item/<modid>/<weapon_name>/` 创建动画文件：

```json
{
  "animations": {
    "attack": {
      "loop": "hold_on_last_frame",
      "animation_length": 0.6,
      "bones": {
        "blade": {
          "rotation": {
            "0.0":  { "post": [0, 0, 0],       "lerp_mode": "linear" },
            "0.15": { "post": [-60, 0, 15],     "lerp_mode": "ease_in" },
            "0.35": { "post": [-10, 0, -5],     "lerp_mode": "ease_out" },
            "0.6":  { "post": [0, 0, 0],        "lerp_mode": "linear" }
          }
        }
      }
    }
  }
}
```

### 3. Java 代码对接

```java
public class MySwordAnimatable extends ItemInHandAnimatable {
    public MySwordAnimatable(ItemStack stack, Level level) {
        super(stack, level);
        setAttackRange(2.5f);
        setHitboxBoneName("blade");
    }

    @Override
    public ModelIndex getDefaultModelIndex() {
        return new ModelIndex(
            "item",
            ResourceLocation.fromNamespaceAndPath("mymod", "my_sword")
        );
    }
}
```

---

## 路径映射规则

Spark-Core 使用 `ModelIndex` 将代码中的引用映射到资源文件：

```
ModelIndex("entity", "modid", "name")
  ↓ 模型
  spark_models/entity/modid/name.json
  ↓ 动画
  spark_animations/entity/modid/name/<animation_name>.json

ModelIndex("item", "modid", "name")
  ↓ 模型
  spark_models/item/modid/name.json
  ↓ 动画
  spark_animations/item/modid/name/<animation_name>.json
```

---

## GeckoLib 与 Spark 共存说明

当前项目中 ModularZombie（zombie1）使用 GeckoLib，ModularZombie2（zombie2）使用 Spark-Core。

- `assets/.../geo/` 和 `assets/.../animations/` 是 **GeckoLib** 格式，由 `ModularZombieModel` 通过 `ResourceLocation` 加载
- `spark_models/` 和 `spark_animations/` 是 **Spark-Core** 格式，由 `ModelIndex` 加载
- 两套系统互不干扰，可以共存

ModularZombie2 同时拥有两种格式的模型/动画（`geo/modular_zombie_2.geo.json` 和 `spark_models/.../modular_zombie_2.json`），这是因为 Spark-Core 在某些情况下会回退到 GeckoLib 的 BakedGeoModel 缓存。建议保留两套格式以确保兼容性。

---

## 注意事项

1. **Bedrock JSON 格式**：Spark 模型/动画使用 Minecraft Bedrock 的 `format_version: 1.12.0` 格式，与 GeckoLib 的自定义 JSON 格式不同
2. **动画拆分**：Spark 将每个动画放在独立文件中，GeckoLib 将所有动画放在一个大的 `animations/*.animation.json` 中
3. **骨骼命名**：Java 代码中的 `getStateAnimationName()` 返回值必须与 `spark_animations/` 目录下的文件名一致（不含 `.json` 后缀）
4. **Locators**：`geo/*.geo.json` 中的 `locators` 用于定义 hitbox 位置（如 `hitbox_head`），Spark 模型中目前没有使用此机制，hitbox 通过 `HitboxConfig` 在代码中定义
5. **贴图路径**：Spark-Core 的贴图通过 `getTextureLocation()` 指定，使用 `assets/` 下的标准 Minecraft 资源路径
