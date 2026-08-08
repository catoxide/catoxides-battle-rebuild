package com.catoxide.catoxidesbattlerebuild.core.contentpack;

/**
 * 模组层面 DLC 大包接口
 * <p>一个 ContentPack 可以一次性注册生物+方块+物品+机制，整块打包发布。
 * 通过 {@link ContentPackLoader} 从 {@code contentpacks/} 目录扫描加载。
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>官方扩展包：通过实现此接口，在主 mod 内注册新内容</li>
 *   <li>第三方 DLC jar：外部 jar 依赖本 mod，实现此接口，通过 MANIFEST.MF 入口加载</li>
 *   <li>数据包+补充 jar：JSON 定义生物，jar 补充复杂逻辑（AI/Combat）</li>
 * </ul>
 *
 * <h3>JAR 结构要求</h3>
 * <pre>
 * my-pack.jar
 * ├── META-INF/MANIFEST.MF
 * │   └── Module-Type: contentpack
 * │       Module-Name: my-pack
 * │       Module-Version: 1.0.0
 * │       Module-Requires: catoxidesbattlerebuild&gt;=1.0.0
 * │       Module-Entry: com.example.MyPack
 * ├── spark_models/entity/&lt;ns&gt;/&lt;entity&gt;.json
 * ├── spark_animations/entity/&lt;ns&gt;/&lt;entity&gt;/*.json
 * └── assets/&lt;ns&gt;/...
 * </pre>
 *
 * <h3>已实现</h3>
 * <ul>
 *   <li>{@code ContentPackLoader}：扫描目录、解析 manifest、兼容验证、ClassLoader 加载</li>
 *   <li>{@code ContentPackRegistry}：管理已加载的 ContentPack</li>
 *   <li>{@code ContentPackContext}：提供 EntityType/SoundEvent 注册 API</li>
 *   <li>ModularZombie3：第一个 ContentPack example</li>
 * </ul>
 *
 * <h3>TODO</h3>
 * <ol>
 *   <li>编辑器支持：可视化编辑器，生成 ContentPack 所需的 JSON + 骨架代码</li>
 *   <li>ContentPack 间依赖声明</li>
 *   <li>热加载支持</li>
 * </ol>
 */
public interface ContentPack {

    /**
     * ContentPack 唯一标识
     */
    String getId();

    /**
     * 显示名
     */
    String getDisplayName();

    /**
     * 版本号
     */
    String getVersion();

    /**
     * 注册所有内容（生物、方块、物品、机制等）
     * <p>在 mod 加载阶段调用，ContentPack 实现应在此注册所有内容。
     *
     * @param context 注册上下文，提供注册器访问
     */
    void register(ContentPackContext context);

    /**
     * 初始化阶段（在注册之后调用）
     * <p>用于事件监听器注册、网络包注册等需要 modEventBus 的操作。
     */
    default void init() {}

    /**
     * 客户端渲染器注册（Dist.CLIENT 下由主 mod 在 RegisterRenderers 事件中调用）
     * <p>pack 在此通过 {@link ClientRenderRegistrar} 注册自己实体的渲染器。
     * 主 mod 不依赖任何 pack 的具体类（渲染器注册不再使用 instanceof）。
     */
    default void registerClientRenderers(ClientRenderRegistrar registrar) {}
}
