# Catoxide's Battle Rebuild - 项目架构指南

## 1. 整体架构概览

### 三层蛋糕结构拆解

```
┌─────────────────────────────────────────────────────────────┐
│ 客户端层 (纯渲染/几何计算)                                    │
│ ├─ geometry/    - ClientBoneCollection, ClientCubeCollection│
│ ├─ models/      - ClientBoneModelData, 数据提取/管理        │
│ ├─ resolver/    - BoneMatrixResolver, 矩阵解析              │
│ ├─ renderer/    - HitboxDebugRenderer, OBBRenderer          │
│ └─ 目的: 获取骨骼实时坐标 + 调试渲染                        │
└─────────────────────────────────────────────────────────────┘
                          ↕ (Networking - EntityID同步)
┌─────────────────────────────────────────────────────────────┐
│ 网络传输层 (可选，按需同步)                                    │
│ ├─ CustomPayloadPackets                                      │
│ └─ 内容: 骨骼Hitbox配置, 部位定义                            │
└─────────────────────────────────────────────────────────────┘
                          ↕
┌─────────────────────────────────────────────────────────────┐
│ 服务端层 (纯游戏逻辑)                                          │
│ ├─ bodypart/    - BodyPart, BodyUnit, EntityBoneSystem      │
│ ├─ config/      - 部位配置                                   │
│ ├─ damage/      - 伤害处理, 部位致命检测                     │
│ └─ 目的: 处理伤害, 管理血量                                  │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 各层详细说明

### 2.1 客户端层 (Client Side)

#### 目录结构
```
client/
├── geometry/
│   ├── ClientBoneCollection.java    # 骨骼动态集合 (Entity特定)
│   ├── ClientCubeCollection.java    # Cube碰撞盒 (Entity特定)
│   └── DoubleBufferedBoneData.java  # 双重缓冲 (新增 - 解决线程安全)
├── models/
│   ├── ClientBoneModelData.java     # 骨骼静态数据 (可复用)
│   ├── BoneModelDataExtractor.java  # 从GeoModel提取数据 (新增)
│   └── ModelDataManager.java        # 静态数据缓存管理器 (新增)
├── resolver/
│   ├── BoneMatrixResolver.java      # 矩阵解析 (已有)
│   └── EntityBoneManager.java       # Entity→骨骼集合管理 (新增)
├── tick/
│   └── ClientBoneTickManager.java   # 逻辑线程骨骼同步 (新增 - 解决partialTick问题)
└── renderer/
    ├── HitboxDebugRenderer.java     # 调试渲染 (已有)
    └── OBBRenderer.java             # OBB渲染 (已有)
```

#### 核心API设计

```java
// --------------------------
// 模型静态数据 (从GeoModel提取)
// --------------------------
public record ClientBoneModelData(...) {
    // 获取特定骨骼的静态数据
    public BoneStaticData getBoneStaticData(String boneName);
    
    // 检查骨骼是否存在
    public boolean containsBone(String boneName);
    
    // 获取子骨骼
    public List<String> getChildBones(String boneName);
}

// --------------------------
// 实体动态数据
// --------------------------
public class ClientBoneCollection {
    // 从静态数据 + EntityID创建
    public static ClientBoneCollection fromStaticData(
        long entityId, ClientBoneModelData.BoneStaticData data);
    
    // 更新变换矩阵 (从GeoBone同步)
    public void updateFromGeoBone(GeoBone geoBone);
    
    // 获取Cube列表
    public List<ClientCubeCollection> getCubeCollections();
    
    // 点碰撞检测
    public boolean containsPoint(Vec3 worldPoint);
}

// --------------------------
// 与GeckoLib的桥接
// --------------------------
public class BoneModelDataExtractor {
    // 从GeckoLib的GeoModel提取静态数据
    public static ClientBoneModelData extractFromGeoModel(
        GeoModel<?> geoModel, ResourceLocation modelLocation);
    
    // 从BakedGeoModel提取
    public static ClientBoneModelData extractFromBakedGeoModel(
        BakedGeoModel bakedModel, ResourceLocation modelLocation);
}
```

#### 关键实现: 从GeckoLib获取实时骨骼坐标

```java
// 在GeoRenderer的render方法中获取
public class ModularZombieRenderer extends GeoEntityRenderer<ModularZombie> {
    
    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, 
                     int packedLight, ModularZombie entity, float partialTick) {
        
        // 1. 获取GeoModel的BakedGeoModel
        BakedGeoModel bakedModel = this.getGeoModel().getBakedModel(
            this.getGeoModel().getModelResource(entity, this));
        
        // 2. 获取骨骼集合
        for (GeoBone geoBone : bakedModel.topLevelBones()) {
            // 3. 启用矩阵追踪
            geoBone.setTrackingMatrices(true);
            
            // 4. 获取实时坐标
            Vector3d worldPos = geoBone.getWorldPosition();
            Matrix4f worldMatrix = geoBone.getWorldSpaceMatrix();
            
            // 5. 同步到ClientBoneCollection
            ClientBoneCollection bone = boneManager.getBone(
                entity.getId(), geoBone.getName());
            if (bone != null) {
                bone.updateFromGeoBone(geoBone);
            }
        }
        
        // 调用原渲染逻辑
        super.render(poseStack, bufferSource, packedLight, entity, partialTick);
    }
}
```

---

### 2.2 服务端层 (Server Side)

#### 目录结构
```
server/
├── bodypart/
│   ├── EntityBoneSystem.java    # 实体骨骼系统管理器 (单例)
│   ├── BodyPart.java            # 身体部位 (核心，含模块血量)
│   ├── BodyUnit.java            # 骨骼属性单元 (无血量，仅属性映射)
│   └── config/
│       ├── IBodyPartConfig.java # 部位配置接口
│       └── BodyPartConfig.java  # 部位配置
└── damage/
    └── DamageProcessor.java     # 伤害处理
```

#### 核心设计: 血量管理与层级

```
Entity (实体)
  ├── 基础血量 (baseHealth / maxBaseHealth)
  └── List<BodyPart> (身体部位列表)
       ↓
    BodyPart (核心：模块血量池)
      ├── partName (部位名，如 "head", "torso")
      ├── moduleHealth (模块当前血量)
      ├── maxModuleHealth (模块最大血量)
      ├── isFatal (是否致命部位)
      ├── transmissionToBase (模块→基础血量传导系数)
      └── List<BodyUnit> (骨骼属性单元列表)
           ↓
        BodyUnit (骨骼→Part的属性映射，无血量)
          ├── boneName (关联骨骼名)
          ├── transmissionCoeff (骨骼→Part传导系数)
          ├── armorValue (护甲值)
          ├── collisionTag (碰撞标签)
          ├── specialEffects (特殊效果列表)
          └── hitSound (击中音效)
```

#### 伤害流程

```
1. 客户端报告击中 (entityId, boneName, rawDamage)
   ↓
2. EntityBoneSystem 查找 BodyUnit
   ↓
3. BodyUnit 应用属性:
   damage = rawDamage * BodyUnit.transmissionCoeff
   damage = max(0, damage - BodyUnit.armorValue)
   ↓
4. 找到所属 BodyPart
   ↓
5. BodyPart 扣除模块血量
   moduleHealth = max(0, moduleHealth - damage)
   ↓
6. 传导到基础血量:
   baseHealth = max(0, baseHealth - damage * Part.transmissionToBase)
   ↓
7. 检查致死条件:
   - 条件1: 某个 isFatal 的 Part 模块血量 <= 0
   - 条件2: 实体基础血量 <= 0
   ↓
8. 应用特殊效果 (特效、音效等)
```

#### 暴露给Jade等的API

```java
// EntityBoneSystem 提供的查询接口
public interface IBoneSystemApi {
    // 获取实体的所有BodyPart
    List<BodyPart> getBodyParts(long entityId);
    
    // 获取实体基础血量
    float getBaseHealth(long entityId);
    float getMaxBaseHealth(long entityId);
    
    // 通过骨骼查找所属Part
    BodyPart getPartByBone(long entityId, String boneName);
    
    // 获取骨骼对应的Unit
    BodyUnit getUnitByBone(long entityId, String boneName);
}

// BodyPart 提供的查询接口
public interface IBodyPart {
    String getPartName();
    float getModuleHealth();
    float getMaxModuleHealth();
    boolean isFatal();
    List<BodyUnit> getBodyUnits();
    boolean isDestroyed(); // 模块血量 <= 0
}

// BodyUnit 提供的查询接口
public interface IBodyUnit {
    String getBoneName();
    float getArmorValue();
    String getCollisionTag();
    List<String> getSpecialEffects();
}
```

---

### 2.3 碰撞策略

**碰撞检测先使用 Geckolib 原版机制，不重写**
- 客户端：使用 Geckolib 的 GeoBone 世界坐标 + 调试渲染
- 服务端碰撞验证：Tier4+ 再考虑，先信任客户端

---

### 2.4 双端同步策略

#### 同步原则

| 数据类型 | 同步方式 | 时机 |
|---------|---------|------|
| **骨骼实时坐标** | ❌ 不同步 | 客户端本地获取 |
| **骨骼静态数据** | ❌ 不同步 | 双端各自加载模型 |
| **部位配置** | ✅ 同步 | Entity spawn时 |
| **伤害/血量** | ✅ 同步 | 伤害发生时 |
| **EntityID** | ✅ 同步 | 固有属性 |

#### 同步实现 (Networking)

```java
// 部位配置同步包
public class SyncBodyPartConfigPacket {
    private final int entityId;
    private final Map<String, BodyPartConfig> configs;
    
    // 编码/解码
    public void encode(FriendlyByteBuf buffer) { ... }
    public static SyncBodyPartConfigPacket decode(FriendlyByteBuf buffer) { ... }
}

// 伤害结果同步包
public class SyncDamageResultPacket {
    private final int entityId;
    private final String boneName;
    private final String partName;
    private final float damage;
    private final boolean isFatal;
}
```

---

## 3. 三层架构之间的关系

```
GeckoLib层 (第三方库)
    ↓ provides
    GeoModel, GeoBone, BakedGeoModel
    ↓ extract static data
┌─────────────────────────────────┐
│ ClientBoneModelData (静态数据)  │ ← 双端各自加载模型，无需同步
│ └─ 骨骼层级, Cube尺寸, pivot    │
└─────────────────────────────────┘
    ↓ create + GeoBone动态数据
┌─────────────────────────────────┐
│ ClientBoneCollection (动态)     │ ← 客户端独有
│ └─ EntityID, 实时变换矩阵        │
└─────────────────────────────────┘
    ↓ 点检测 → boneName
    ↓ (EntityID + boneName) 通过网络
┌─────────────────────────────────┐
│ BodyPart/BodyUnit (服务端)      │ ← 服务端独有
│ └─ 伤害处理, 血量管理           │
└─────────────────────────────────┘
```

---

## 4. 完整工作流示例

### 攻击检测流程

```
1. 客户端: 玩家点击/射线检测
   ↓
2. 客户端: BoneMatrixResolver获取所有骨骼坐标
   ↓
3. 客户端: ClientCubeCollection.containsPoint()检测命中
   ↓
4. 客户端: 得到 (entityId, boneName)
   ↓
5. 网络: 发送 HitAttemptPacket(entityId, boneName, damage)
   ↓
6. 服务端: EntityBoneSystem.processEntityHit()
   ↓
7. 服务端: BodyUnit应用属性 → BodyPart扣除模块血量 → 传导到基础血量
   ↓
8. 服务端: 检查致死条件
   ↓
9. 网络: 发送 SyncDamageResultPacket()
   ↓
10.客户端: 播放受击动画/粒子效果
```

---

## 5. 各层职责总结

### 客户端职责
| 职责 | 实现方式 | 状态 |
|------|---------|------|
| 从GeckoLibCache提取骨骼静态数据 | `ClientModelManager.loadModelsFromGeckoLibCache()` | ✅ 已完成 |
| 缓存模型静态数据 | `ClientModelManager` | ✅ 已完成 |
| 同步GeoBone实时变换 | `ModularZombieRenderer.syncBoneData()` | ✅ 已完成 |
| 骨骼Hitbox调试渲染 | `HitboxDebugRenderer` | 🔄 进行中 |
| 射线/点碰撞检测 | `ClientCubeCollection` | ✅ 已完成 |
| **不做**: 伤害计算/血量管理 | ❌ 交给服务端 | - |

### 服务端职责
| 职责 | 实现方式 |
|------|---------|
| EntityBoneSystem | 管理所有实体的BodyPart/BodyUnit |
| 伤害计算/传导 | DamageProcessor + BodyPart/BodyUnit |
| 血量管理 | BodyPart (模块血量) + Entity基础血量 |
| 致命部位检测 | BodyPart.isFatal() && isDestroyed() |
| 部位配置同步 | SyncBodyPartConfigPacket |
| **不做**: 渲染/几何计算 | ❌ 交给客户端 |
| **暂不做**: 服务端碰撞验证 | Tier4+ 考虑 |

---

## 6. 关键文件清单

### 6.1 客户端核心文件 (已实现)

| 状态 | 文件 | 说明 |
|------|------|------|
| ✅ 已修改 | `ModularZombieRenderer.java` | 添加骨骼同步逻辑（syncBoneData） |
| ✅ 已修改 | `ClientModelManager.java` | 优先使用 GeckoLibCache 加载模型 |
| ✅ 已修改 | `ClientHitboxManager.java` | 添加 updateBoneTransform() 方法 |
| ✅ 已修改 | `ClientBoneCollection.java` | 添加 updateFromGeoBone() 方法 |
| ✅ 已修改 | `ClientCubeCollection.java` | 添加 updateFromBoneTransform() 方法 |
| ✅ 已有 | `ClientBoneModelData.java` | 静态数据record |
| ✅ 已有 | `HitboxDebugRenderer.java` | 调试渲染 |
| ✅ 已有 | `OBBRenderer.java` | OBB渲染 |
| ✅ 已有 | `BoneMatrixResolver.java` | 矩阵解析 |
| ✅ 已有 | `ClientInitializer.java` | 客户端初始化 |
| ✅ 已有 | `ClientEntityBodySystem.java` | 实体身体部位系统 |

### 6.2 客户端扩展文件 (按需实现)

| 状态 | 文件 | 说明 |
|------|------|------|
| ⬜ 可选 | `DoubleBufferedBoneData.java` | 双重缓冲（解决线程安全） |
| ⬜ 可选 | `ClientBoneTickManager.java` | 逻辑线程骨骼同步（解决partialTick问题） |
| ⬜ 可选 | `EntityBoneManager.java` | Entity→骨骼集合管理 |

### 6.3 服务端需要的 (从旧代码迁移重构)

| 文件 | 说明 |
|------|------|
| `EntityBoneSystem.java` | 单例管理器，从旧EntityBoneBodyUnitSystem重构 |
| `BodyPart.java` | 核心类，管理模块血量，从旧BodyPart重构 |
| `BodyUnit.java` | 简化为属性映射类，移除血量相关逻辑 |
| `BodyPartConfig.java` | 配置类 |
| `DamageProcessor.java` | 伤害处理 |

---

## 7. 下一步行动

### Phase 1: GeckoLibCache 集成骨骼追踪 (✅ 已完成)
1. ✅ **ClientModelManager** - 优先使用 `GeckoLibCache.getBakedModels()` 加载模型
2. ✅ **processGeoBone()** - 递归提取 GeoBone 静态数据到 ClientBoneModelData
3. ✅ **ModularZombieRenderer** - 在渲染时同步 GeoBone 实时数据
4. ✅ **ClientHitboxManager** - 添加 `updateBoneTransform()` 方法
5. ✅ **ClientBoneCollection** - 添加 `updateFromGeoBone()` 方法
6. ✅ **ClientCubeCollection** - 添加 `updateFromBoneTransform()` 方法

### Phase 2: 调试渲染验证 (🔄 进行中)
1. 🔄 让 `HitboxDebugRenderer` 从 `ClientBoneCollection` 读取数据
2. 🔄 在游戏中验证骨骼Hitbox是否正确
3. 🔄 测试实体注册和渲染是否正常工作

### Phase 3: 服务端部位系统 (⬜ 待开始)
1. ⬜ 重构 `EntityBoneSystem` (从旧代码迁移)
2. ⬜ 重构 `BodyPart` (模块血量管理)
3. ⬜ 简化 `BodyUnit` (仅属性映射)
4. ⬜ 实现 `BodyPartConfig` 配置
5. ⬜ 实现双端同步Packet

### Phase 4: 服务端安全验证 (⬜ 未来考虑)
1. ⬜ 服务端加载模型静态数据
2. ⬜ 服务端轻量级碰撞验证
3. ⬜ 距离/视线合理性检查

---

## 8. 旧代码迁移指南

### 可以直接迁移的 (geometry/models)
| 旧文件 (Deprecated) | 新文件 (client/) | 修改内容 |
|-------------------|-----------------|---------|
| `BoneCollection.java` | `ClientBoneCollection.java` | 改包名 + 移除服务端引用 |
| `CubeCollection.java` | `ClientCubeCollection.java` | 同上 |
| `BoneModelData.java` | `ClientBoneModelData.java` | 同上 |
| `BoneModelDataExtractor.java` | `BoneModelDataExtractor.java` | 同上 |
| `MatrixTransformer.java` | `BoneMatrixResolver.java` | 重构为新API |

### 需重构的 (bodypart，服务端)
| 旧文件 (Deprecated) | 新文件 (server/) | 修改内容 |
|-------------------|-----------------|---------|
| `EntityBoneBodyUnitSystem.java` | `EntityBoneSystem.java` | 简化，移除冗余逻辑 |
| `BodyPart.java` | `BodyPart.java` | 添加模块血量管理，重新设计伤害流程 |
| `BodyUnit.java` | `BodyUnit.java` | 移除血量管理逻辑，简化为属性映射 |

### 完全删除的 (服务端特有，客户端不需要)
| 文件 | 删除原因 |
|------|---------|
| `ServerEntityManager.java` | 客户端不需要 |
| `ServerBoneModelManager.java` | 客户端不需要 |
| `ServerCoreGeoModel.java` | 客户端不需要 |
| `ServerGeoModelManager.java` | 客户端不需要 |
| `ServerConfig.java` | 服务端配置 |
| `ServerInitializer.java` | 服务端初始化 |
| `ServerSideExecutor.java` | 服务端执行器 |
| `BoneBodyUnitIntegration.java` | 服务端集成类 |

---

## 9. 关键代码示例

### 9.1 使用 GeckoLibCache 集成骨骼追踪 (✅ 已实现)

这是我们最终采用的方案，直接使用 GeckoLib 内置的缓存系统，无需重新解析模型文件。

#### 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           初始化流程 (ClientModelManager)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ClientModelManager.initialize()                                            │
│      ↓                                                                     │
│  方案1: loadModelsFromGeckoLibCache()  ← 优先                               │
│      - GeckoLibCache.getBakedModels()  获取已烘焙的模型                      │
│      - processGeoBone() 递归提取骨骼数据到 ClientBoneModelData               │
│      ↓                                                                     │
│  方案2: loadModelsFromGeoJson()  ← 备用（当 GeckoLibCache 为空时）          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                           渲染流程 (ModularZombieRenderer)                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ModularZombieRenderer.render()                                              │
│      ↓                                                                     │
│  1. syncBoneData(zombie)                                                    │
│      ↓                                                                     │
│  2. getGeoModel().getBakedModel(modelLocation)  ← 从 GeckoLibCache 获取     │
│      ↓                                                                     │
│  3. 遍历 bakedModel.topLevelBones()                                         │
│      ↓                                                                     │
│  4. 递归遍历 GeoBone:                                                       │
│      - geoBone.setTrackingMatrices(true)  启用矩阵追踪                      │
│      - 获取 worldMatrix, worldPosition  获取实时变换                        │
│      ↓                                                                     │
│  5. ClientHitboxManager.updateBoneTransform()                               │
│      ↓                                                                     │
│  6. ClientBoneCollection.updateFromGeoBone()                                │
│      ↓                                                                     │
│  7. ClientCubeCollection.updateFromBoneTransform()                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 核心代码实现

##### 9.1.1 ClientModelManager - 从 GeckoLibCache 加载模型

```java
public class ClientModelManager {
    
    /**
     * 初始化模型管理器
     * 优先使用 GeckoLibCache 获取已烘焙的模型数据
     */
    public void initialize() {
        // 方案1：直接从 GeckoLibCache 获取已烘焙的模型数据（优先）
        loadModelsFromGeckoLibCache();
        
        // 方案2：如果 GeckoLibCache 为空，尝试从 geo JSON 文件加载（备用）
        if (modelCollections.isEmpty()) {
            loadModelsFromGeoJson(mc);
        }
    }
    
    /**
     * 从 GeckoLibCache 加载模型数据
     * 这是最直接、最可靠的方式，因为 GeckoLib 已经完成了所有解析工作
     */
    private void loadModelsFromGeckoLibCache() {
        Map<ResourceLocation, BakedGeoModel> bakedModels = GeckoLibCache.getBakedModels();
        
        for (Map.Entry<ResourceLocation, BakedGeoModel> entry : bakedModels.entrySet()) {
            ResourceLocation modelLocation = entry.getKey();
            BakedGeoModel bakedModel = entry.getValue();
            
            // 只处理我们 mod 的模型
            if (!modelLocation.getNamespace().equals("catoxidesbattlerebuild")) {
                continue;
            }
            
            // 从 BakedGeoModel 提取骨骼数据
            Map<String, ClientBoneModelData.BoneStaticData> boneStaticDataMap = new ConcurrentHashMap<>();
            List<String> boneHierarchy = new ArrayList<>();
            Map<String, List<String>> childBoneMap = new HashMap<>();
            
            // 递归处理顶层骨骼
            for (GeoBone rootBone : bakedModel.topLevelBones()) {
                processGeoBone(rootBone, boneStaticDataMap, boneHierarchy, childBoneMap, null);
            }
            
            // 创建 ClientBoneModelData
            ClientBoneModelData boneModelData = new ClientBoneModelData(
                modelLocation, boneStaticDataMap, boneHierarchy, childBoneMap);
            
            // 创建并存储模型集合
            ClientModelCollection collection = ClientModelCollection.create(
                modelLocation, modelLocation.getPath(), boneModelData);
            modelCollections.put(modelLocation, collection);
        }
    }
    
    /**
     * 递归处理 GeoBone 并提取静态数据
     */
    private void processGeoBone(GeoBone geoBone, 
                               Map<String, ClientBoneModelData.BoneStaticData> boneStaticDataMap,
                               List<String> boneHierarchy,
                               Map<String, List<String>> childBoneMap,
                               String parentName) {
        String boneName = geoBone.getName();
        boneHierarchy.add(boneName);
        
        // 记录父子关系
        if (parentName != null) {
            childBoneMap.computeIfAbsent(parentName, k -> new ArrayList<>()).add(boneName);
        }
        
        // 提取 Cube 数据
        List<ClientBoneModelData.CubeStaticData> cubeStaticDataList = new ArrayList<>();
        int cubeIndex = 0;
        for (GeoCube geoCube : geoBone.getCubes()) {
            String cubeId = boneName + "_cube_" + cubeIndex;
            
            // GeoCube 的数据已经是方块坐标（除以16）
            Vec3 pivot = new Vec3(geoCube.pivot().x, geoCube.pivot().y, geoCube.pivot().z);
            Vec3 size = new Vec3(geoCube.size().x, geoCube.size().y, geoCube.size().z);
            Vec3 rotation = new Vec3(geoCube.rotation().x, geoCube.rotation().y, geoCube.rotation().z);
            
            ClientBoneModelData.CubeStaticData cubeStaticData = new ClientBoneModelData.CubeStaticData(
                cubeId, pivot, size, rotation, new Vec3(0, 0, 0));
            cubeStaticDataList.add(cubeStaticData);
            cubeIndex++;
        }
        
        // 创建骨骼静态数据
        ClientBoneModelData.BoneStaticData boneStaticData = new ClientBoneModelData.BoneStaticData(
            boneName, parentName, cubeStaticDataList,
            new Vec3(geoBone.getPivotX(), geoBone.getPivotY(), geoBone.getPivotZ()),
            new Vec3(geoBone.getRotX(), geoBone.getRotY(), geoBone.getRotZ()),
            new Vec3(geoBone.getScaleX(), geoBone.getScaleY(), geoBone.getScaleZ())
        );
        boneStaticDataMap.put(boneName, boneStaticData);
        
        // 递归处理子骨骼
        for (GeoBone childBone : geoBone.getChildBones()) {
            processGeoBone(childBone, boneStaticDataMap, boneHierarchy, childBoneMap, boneName);
        }
    }
}
```

##### 9.1.2 ModularZombieRenderer - 渲染时同步骨骼数据

```java
public class ModularZombieRenderer extends GeoEntityRenderer<ModularZombie> {
    
    @Override
    public void render(Entity entity, float entityYaw, float partialTick, 
                       GuiGraphics guiGraphics,
                       MultiBufferSource bufferSource, 
                       Frustum frustum) {
        // 在渲染前同步骨骼数据
        if (entity instanceof ModularZombie zombie) {
            syncBoneData(zombie, partialTick);
        }
        
        super.render(entity, entityYaw, partialTick, guiGraphics, bufferSource, frustum);
    }
    
    /**
     * 同步骨骼数据到 ClientHitboxManager
     * 从 GeckoLib 的 GeoBone 获取实时变换数据
     */
    private void syncBoneData(ModularZombie zombie, float partialTick) {
        // 1. 获取 BakedGeoModel
        ResourceLocation modelLocation = getGeoModel().getModelResource(zombie, this);
        BakedGeoModel bakedModel = getGeoModel().getBakedModel(modelLocation);
        
        if (bakedModel == null) {
            return;
        }
        
        // 2. 同步顶层骨骼数据
        for (GeoBone geoBone : bakedModel.topLevelBones()) {
            syncGeoBone(geoBone, zombie);
        }
    }
    
    /**
     * 递归同步 GeoBone 数据到 ClientHitboxManager
     */
    private void syncGeoBone(GeoBone geoBone, ModularZombie zombie) {
        // 1. 启用矩阵追踪
        geoBone.setTrackingMatrices(true);
        
        // 2. 获取实时数据
        Vector3d worldPos = geoBone.getWorldPosition();
        Matrix4f worldMatrix = geoBone.getWorldSpaceMatrix();
        
        // 3. 同步到 ClientHitboxManager
        ClientHitboxManager.getInstance().updateBoneTransform(
            zombie.getUUID(),
            geoBone.getName(),
            worldMatrix,
            worldPos
        );
        
        // 4. 递归处理子骨骼
        for (GeoBone childBone : geoBone.getChildBones()) {
            syncGeoBone(childBone, zombie);
        }
    }
}
```

##### 9.1.3 ClientHitboxManager - 添加骨骼变换同步方法

```java
public class ClientHitboxManager {
    
    /**
     * 更新骨骼变换数据（从 GeckoLib GeoBone 同步）
     * 直接接收 GeoBone 的世界矩阵和位置数据
     */
    public void updateBoneTransform(UUID entityUuid, String boneName, 
                                   Matrix4f worldMatrix, Vector3d worldPosition) {
        Map<String, ClientBoneCollection> boneCollections = boneCollectionsMap.get(entityUuid);
        if (boneCollections == null) return;
        
        ClientBoneCollection boneCollection = boneCollections.get(boneName);
        if (boneCollection == null) return;
        
        // 直接更新世界矩阵和位置
        boneCollection.updateFromGeoBone(worldMatrix, worldPosition);
        
        // 同时更新该骨骼下所有立方体的顶点
        List<ClientCubeCollection> cubes = cubeCollectionsMap.get(entityUuid).get(boneName);
        if (cubes != null) {
            for (ClientCubeCollection cube : cubes) {
                cube.updateFromBoneTransform(worldMatrix, worldPosition);
            }
        }
    }
}
```

##### 9.1.4 ClientBoneCollection - 添加 GeoBone 同步方法

```java
public class ClientBoneCollection {
    
    /**
     * 从 GeckoLib GeoBone 更新骨骼变换数据
     * 直接使用 GeoBone 提供的世界矩阵和位置
     */
    public void updateFromGeoBone(Matrix4f worldMatrix, Vector3d worldPosition) {
        // 直接使用传入的世界矩阵
        this.worldTransform = new Matrix4f(worldMatrix);
    }
}
```

##### 9.1.5 ClientCubeCollection - 添加骨骼变换同步方法

```java
public class ClientCubeCollection {
    
    /**
     * 从骨骼变换更新立方体的世界变换
     * 直接使用骨骼的世界矩阵（包含所有变换）
     */
    public void updateFromBoneTransform(Matrix4f boneWorldMatrix, Vector3d boneWorldPosition) {
        // GeoBone 的世界矩阵已经包含了所有变换（位置、旋转、缩放）
        // 直接使用这个矩阵作为立方体的世界变换
        this.worldTransform = new Matrix4f(boneWorldMatrix);
    }
}
```

#### 关键优势

| 优势 | 说明 |
|------|------|
| **无需重新解析** | 直接使用 GeckoLib 烘焙好的模型数据 |
| **实时数据准确** | GeoBone 的世界矩阵已包含所有动画变换 |
| **兼容性好** | 保留 geo.json 解析作为备用方案 |
| **性能高效** | 无需为每个模型编写单独逻辑 |
| **数据一致性** | 与 GeckoLib 渲染使用相同的数据源 |

---

### 9.2 BoneModelDataExtractor (旧方案，保留参考)

```java
public class BoneModelDataExtractor {
    
    public static ClientBoneModelData extractFromGeoModel(
            GeoModel<?> geoModel, 
            ResourceLocation modelLocation,
            Object animatable) {
        
        BakedGeoModel bakedModel = geoModel.getBakedModel(
            geoModel.getModelResource(animatable, null));
        
        return extractFromBakedGeoModel(bakedModel, modelLocation);
    }
    
    public static ClientBoneModelData extractFromBakedGeoModel(
            BakedGeoModel bakedModel,
            ResourceLocation modelLocation) {
        
        Map<String, ClientBoneModelData.BoneStaticData> boneDataMap = new HashMap<>();
        List<String> boneHierarchy = new ArrayList<>();
        Map<String, List<String>> childBoneMap = new HashMap<>();
        
        // 递归处理骨骼
        for (GeoBone rootBone : bakedModel.topLevelBones()) {
            processBone(rootBone, boneDataMap, boneHierarchy, childBoneMap, null);
        }
        
        return new ClientBoneModelData(
            modelLocation, boneDataMap, boneHierarchy, childBoneMap);
    }
    
    private static void processBone(GeoBone bone, 
            Map<String, ClientBoneModelData.BoneStaticData> boneDataMap,
            List<String> boneHierarchy,
            Map<String, List<String>> childBoneMap,
            String parentBoneName) {
        
        boneHierarchy.add(bone.getName());
        
        // 提取Cube数据
        List<ClientBoneModelData.CubeStaticData> cubes = new ArrayList<>();
        for (GeoCube geoCube : bone.getCubes()) {
            cubes.add(extractCubeData(bone.getName(), geoCube));
        }
        
        // 创建骨骼静态数据
        ClientBoneModelData.BoneStaticData boneData = 
            new ClientBoneModelData.BoneStaticData(
                bone.getName(),
                parentBoneName,
                cubes,
                new Vec3(bone.getPivotX() / 16.0, bone.getPivotY() / 16.0, bone.getPivotZ() / 16.0),
                new Vec3(0, 0, 0), // 默认旋转
                new Vec3(1, 1, 1)  // 默认缩放
            );
        
        boneDataMap.put(bone.getName(), boneData);
        
        // 记录父子关系
        if (parentBoneName != null) {
            childBoneMap.computeIfAbsent(parentBoneName, k -> new ArrayList<>())
                .add(bone.getName());
        }
        
        // 递归子骨骼
        for (GeoBone child : bone.getChildBones()) {
            processBone(child, boneDataMap, boneHierarchy, childBoneMap, bone.getName());
        }
    }
    
    private static ClientBoneModelData.CubeStaticData extractCubeData(
            String boneName, GeoCube cube) {
        
        // GeoCube的坐标单位是像素，需要除以16转为世界单位
        Vec3 pivot = new Vec3(
            cube.getPivotX() / 16.0,
            cube.getPivotY() / 16.0,
            cube.getPivotZ() / 16.0
        );
        
        Vec3 size = new Vec3(
            (cube.getSizeX()) / 16.0,
            (cube.getSizeY()) / 16.0,
            (cube.getSizeZ()) / 16.0
        );
        
        Vec3 rotation = new Vec3(
            Math.toRadians(cube.getRotX()),
            Math.toRadians(cube.getRotY()),
            Math.toRadians(cube.getRotZ())
        );
        
        Vec3 originOffset = new Vec3(
            cube.getOriginX() / 16.0,
            cube.getOriginY() / 16.0,
            cube.getOriginZ() / 16.0
        );
        
        return new ClientBoneModelData.CubeStaticData(
            boneName + "_cube_" + cube.hashCode(),
            pivot,
            size,
            rotation,
            originOffset
        );
    }
}
```

---

## 10. 架构风险与优化方案

### 10.1 已知风险与解决方案

| 风险 | 严重程度 | 解决方案 | 实现状态 |
|------|---------|---------|---------|
| 1. 安全与反作弊 | ⚠️ 中 (未来解决) | 先信任客户端，后续加入服务端碰撞验证 | Phase 4 |
| 2. 攻击延迟与状态不一致 | ⚠️ 中 | Packet中加入时间戳 + 实体状态序列号 | Phase 3 |
| 3. 渲染线程与逻辑线程数据竞争 | 🔴 高 | 双重缓冲机制 (DoubleBufferedBoneData) | Phase 1 |
| 4. 性能开销: 每帧遍历所有骨骼 | 🟡 低 | 按需启用追踪，降低更新频率 | Phase 2 |
| 5. 命中检测精度: partialTick问题 | 🔴 高 | 逻辑线程同步骨骼 (ClientBoneTickManager) | Phase 1 |
| 6. 服务端配置版本热更新 | 🟡 低 | 配置带版本号 | Phase 3 |
| 7. Cube数据提取不完整 | 🟡 低 | 完善BoneModelDataExtractor | Phase 1 |

---

### 10.2 关键优化实现详解

#### 10.2.1 双重缓冲 (DoubleBufferedBoneData) - 解决线程安全问题

**问题**: 渲染线程更新骨骼矩阵，逻辑线程同时读取 → 数据竞争

**解决方案**:
```java
public class DoubleBufferedBoneData {
    private volatile ClientBoneCollection frontBuffer;  // 逻辑线程读
    private volatile ClientBoneCollection backBuffer;   // 渲染线程写
    private final Object swapLock = new Object();
    
    // 渲染线程调用: 更新后备缓冲区
    public void updateBackBuffer(Consumer<ClientBoneCollection> updater) {
        updater.accept(backBuffer);
    }
    
    // 渲染帧结束后调用: 原子性交换
    public void swapBuffers() {
        synchronized (swapLock) {
            ClientBoneCollection temp = frontBuffer;
            frontBuffer = backBuffer;
            backBuffer = temp;
        }
    }
    
    // 逻辑线程调用: 读取安全的数据
    public ClientBoneCollection getSafeData() {
        return frontBuffer;
    }
}
```

**使用位置**:
- 在 `GeoRenderer.render()` 中更新 `backBuffer`
- 渲染完成后调用 `swapBuffers()`
- 逻辑线程攻击检测时读取 `frontBuffer`

---

#### 10.2.2 逻辑线程骨骼同步 - 解决partialTick精度问题

**问题**: 渲染帧的骨骼是插值后的视觉位置，与逻辑tick的实体位置脱节

**解决方案**: 新增 `ClientBoneTickManager` 在逻辑tick中同步一次骨骼
```java
public class ClientBoneTickManager {
    
    // 每tick在逻辑线程中调用一次
    public void tickEntity(Entity entity, GeoModel<?> geoModel) {
        // 获取上一tick结束时的骨骼变换 (非插值)
        BakedGeoModel bakedModel = geoModel.getBakedModel(...);
        
        for (GeoBone geoBone : bakedModel.topLevelBones()) {
            geoBone.setTrackingMatrices(true);
            
            // 获取上一tick的骨骼位置 (partialTick=0)
            Vector3d tickPos = geoBone.getWorldPosition();
            
            // 同步到专门的逻辑线程缓冲区
            logicThreadBoneManager.updateBone(entity.getId(), geoBone.getName(), tickPos);
        }
    }
}
```

**工作流**:
```
┌─────────────────┐      ┌──────────────────┐
│  渲染线程       │      │  逻辑线程        │
│  (每16.6ms)     │      │  (每50ms)        │
├─────────────────┤      ├──────────────────┤
│  partialTick    │─→   │  tickBone()      │
│  → backBuffer   │      │  → logicBuffer   │
│  swapBuffers()  │      │  攻击检测用      │
└─────────────────┘      └──────────────────┘
```

---

#### 10.2.3 攻击Packet改进 - 解决延迟问题

**原Packet**:
```java
public class HitAttemptPacket {
    int entityId;
    String boneName;
    float damage;
}
```

**改进后Packet**:
```java
public class HitAttemptPacket {
    int entityId;
    String boneName;
    float damage;
    long attackTimestamp;           // 攻击时刻的时间戳
    int entityStateSequence;        // 目标实体的状态序列号
    Vec3 attackerEyePosition;       // 攻击者眼睛位置 (用于服务端验证)
    Vec3 rayDirection;              // 攻击射线方向 (用于服务端验证)
}
```

**服务端处理**:
1. 记录最近N个tick的实体位置历史
2. 根据 `attackTimestamp` 回滚到攻击时刻
3. 进行合理性校验 (距离、视线遮挡等)

---

#### 10.2.4 按需追踪骨骼 - 解决性能问题

**问题**: 每帧对所有实体的所有骨骼调用 `setTrackingMatrices(true)` → 性能开销

**解决方案**:
```java
public class EntityBoneManager {
    private final Map<Long, Set<String>> trackedBones = new HashMap<>();
    
    // 实体进入玩家视野时: 只追踪主要骨骼
    public void onEntityEnterView(Entity entity) {
        Set<String> importantBones = getImportantBones(entity); // head, torso等
        trackedBones.put(entity.getId(), importantBones);
    }
    
    // 实体被瞄准时: 启用所有骨骼追踪
    public void onEntityAimed(Entity entity) {
        trackedBones.put(entity.getId(), getAllBones(entity));
    }
    
    // 更新时只更新被追踪的骨骼
    public void updateBone(long entityId, String boneName, GeoBone geoBone) {
        Set<String> tracked = trackedBones.get(entityId);
        if (tracked != null && tracked.contains(boneName)) {
            geoBone.setTrackingMatrices(true);
            // 更新...
        }
    }
}
```

---

## 11. 完整流程图 (更新后)

```
渲染线程 (每16.6ms)                    逻辑线程 (每50ms)
     |                                      |
     ▼                                      ▼
GeoRenderer.render()              ClientBoneTickManager.tick()
     |                                      |
     ▼                                      ▼
backBuffer更新                         logicBuffer更新
     |                                      |
     ▼                                      ▼
swapBuffers() ──────────┐         玩家点击攻击
     |                  ▼              |
     ▼          ┌────────────────┐    ▼
HitboxDebug     │DoubleBuffered  │  raycast检测
   Renderer     │  BoneData      │    |
                │  frontBuffer   │    ▼
                └────────────────┘  得到 (entityId, boneName)
                                           |
                        ┌──────────────────┘
                        ▼
                 HitAttemptPacket
               (带时间戳 + 状态序列号)
                        |
                        ▼
                 服务端处理
               (BodyUnit属性 → BodyPart模块血量 → 基础血量)
                        |
                        ▼
                 SyncDamageResultPacket
```

---

**总结**: 核心思路是**客户端负责几何计算/渲染，服务端负责游戏逻辑，通过EntityID+boneName关联**，无需同步复杂的骨骼坐标数据。

**新增重点**:
- 双重缓冲解决线程安全
- 逻辑线程同步解决partialTick精度问题
- Packet改进解决延迟问题
- 简化BodyUnit为属性映射，血量管理在BodyPart
- 碰撞先使用Geckolib原版，服务端验证Tier4+考虑
