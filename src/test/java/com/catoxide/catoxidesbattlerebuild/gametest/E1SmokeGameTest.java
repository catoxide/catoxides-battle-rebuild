package com.catoxide.catoxidesbattlerebuild.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * E1 冒烟测试（模组+内容包插拔测试验收系统，见 design/RESEARCH_2026-10-26_模组内容包测试验收系统.md）。
 *
 * <p>只验证管线本身，不做任何内容包断言（那是 E2 及之后的职责）：
 * <ol>
 *   <li>test 源集在 gameTestServer 的 MOD_CLASSES 上（build.gradle gameTestServer 块 modSource 接线）；</li>
 *   <li>@GameTestHolder("catoxidesbattlerebuild") 被 NeoForge 扫描注册
 *       （依赖 systemProperty neoforge.enabledGameTestNamespaces）；</li>
 *   <li>GameTest 能执行、能 succeed、进程能正常退出（exit code 0 = 验收信号）。</li>
 * </ol>
 *
 * <p>失败信号对照：若 test 源集没进 MOD_CLASSES，GameTestServer 会因
 * "no gametests provided" 直接崩溃（build.gradle 注释 :106）。
 */
@GameTestHolder("catoxidesbattlerebuild")
public class E1SmokeGameTest {

    /**
     * 无 template = 结构名派生为测试名（<ns>.<classlower>.<methodlower>），
     * 由 StructureTemplateManager 从 data/<ns>/structure/<name>.snbt 加载
     * （文件在 main 资源：src/main/resources/data/catoxidesbattlerebuild/structure/）。
     */
    @GameTest(timeoutTicks = 100)
    public void smokeSucceed(GameTestHelper context) {
        context.succeed();
    }


}
