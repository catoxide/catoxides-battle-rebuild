package com.catoxide.catoxidesbattlerebuild.core.quest;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * 任务系统事件（NeoForge EVENT_BUS 广播，内容包组件/UI 订阅）。
 */
public abstract class QuestEvents {

    /** 任务已发放 */
    public static class QuestGivenEvent extends Event {
        public final QuestInstance quest;
        public final ServerPlayer player;
        public final String giverId;

        public QuestGivenEvent(QuestInstance quest, ServerPlayer player, String giverId) {
            this.quest = quest;
            this.player = player;
            this.giverId = giverId;
        }
    }

    /** 目标进度已更新（供 UI 刷新） */
    public static class QuestProgressEvent extends Event {
        public final QuestInstance quest;
        public final ServerPlayer player;

        public QuestProgressEvent(QuestInstance quest, ServerPlayer player) {
            this.quest = quest;
            this.player = player;
        }
    }

    /** 任务成功完成 */
    public static class QuestCompletedEvent extends Event {
        public final QuestInstance quest;
        public final ServerPlayer player;

        public QuestCompletedEvent(QuestInstance quest, ServerPlayer player) {
            this.quest = quest;
            this.player = player;
        }
    }

    /** 任务失败 */
    public static class QuestFailedEvent extends Event {
        public final QuestInstance quest;
        public final ServerPlayer player;
        public final String reason;

        public QuestFailedEvent(QuestInstance quest, ServerPlayer player, String reason) {
            this.quest = quest;
            this.player = player;
            this.reason = reason;
        }
    }

    /** 任务已消除（完成/失败后的清理；从系统中移除） */
    public static class QuestRemovedEvent extends Event {
        public final QuestInstance quest;
        public final ServerPlayer player;

        public QuestRemovedEvent(QuestInstance quest, ServerPlayer player) {
            this.quest = quest;
            this.player = player;
        }
    }
}
