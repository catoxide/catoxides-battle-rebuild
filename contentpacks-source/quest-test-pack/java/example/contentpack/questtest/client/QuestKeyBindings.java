package example.contentpack.questtest.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 任务界面按键（默认 O，原版"成就"键 L 同款）。
 * <p><b>M3 迁移</b>：由 core 移入本包。原 core 版用 {@code @EventBusSubscriber}——
 * 包类由 ChildFirstClassLoader 加载，FML 注解扫描不到（反模式 A7），故改为
 * {@link #register(IEventBus)} 显式注册，由 {@code QuestTestPack.register()} 调用。
 * <p>KeyMapping 用懒初始化：避免 dedicated server 上 register() 触发类初始化时
 * 在服务器端实例化客户端 KeyMapping。
 */
public final class QuestKeyBindings {

    private static KeyMapping openQuest;

    private QuestKeyBindings() {
    }

    /** 懒初始化（仅客户端事件触发时执行） */
    public static KeyMapping getOpenQuest() {
        if (openQuest == null) {
            openQuest = new KeyMapping(
                    "key.catoxides.quest",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_O,
                    "key.categories.catoxides");
        }
        return openQuest;
    }

    /** 显式注册按键（mod 总线 RegisterKeyMappingsEvent，客户端才会触发） */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterKeyMappingsEvent.class,
                e -> e.register(getOpenQuest()));
    }

    /** 客户端 tick 处理（game 总线 ClientTickEvent.Post，由包 register() 显式挂上） */
    public static void onClientTick() {
        if (getOpenQuest().consumeClick() && Minecraft.getInstance().screen == null
                && Minecraft.getInstance().player != null) {
            Minecraft.getInstance().setScreen(new QuestScreen());
        }
    }
}
