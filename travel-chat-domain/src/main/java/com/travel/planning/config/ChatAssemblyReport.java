package com.travel.planning.config;

import com.travel.planning.agent.support.ChatWeatherContextPort;
import com.travel.planning.agent.support.ItineraryConflictPort;
import com.travel.planning.agent.support.ItineraryVersionPort;
import com.travel.planning.guard.GuardProperties;
import com.travel.stream.ChatStreamProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * M16-4：聊天域装配清单（启动期打印）。
 *
 * <p>双进程（planning 8081 / webflux 8083）加载同一 chat-domain，但可选 Port
 * （版本回写/冲突观测/天气）与灰度开关的生效情况此前只能靠读代码推导。本组件在
 * {@link ApplicationReadyEvent} 打印一份装配清单：流式开关、Supervisor 灰度开关、
 * 三个中立 Port 的装配与否、guard 词表规模——使「本进程启用了什么」一目了然
 * （评审 A1/任务 4 方向七的最小落地）。</p>
 */
@Slf4j
@Component
public class ChatAssemblyReport implements ApplicationListener<ApplicationReadyEvent> {

    private final ChatStreamProperties chatStreamProps;
    private final GuardProperties guardProperties;
    private final ObjectProvider<ItineraryVersionPort> versionPort;
    private final ObjectProvider<ItineraryConflictPort> conflictPort;
    private final ObjectProvider<ChatWeatherContextPort> weatherPort;
    private final boolean conflictObserveEnabled;
    private final boolean writebackEnabled;
    private final boolean dispatchDedupEnabled;

    public ChatAssemblyReport(ChatStreamProperties chatStreamProps,
                              GuardProperties guardProperties,
                              ObjectProvider<ItineraryVersionPort> versionPort,
                              ObjectProvider<ItineraryConflictPort> conflictPort,
                              ObjectProvider<ChatWeatherContextPort> weatherPort,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${travel.chat.supervisor.conflict-observe.enabled:true}") boolean conflictObserveEnabled,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${travel.chat.supervisor.itinerary-writeback.enabled:true}") boolean writebackEnabled,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${travel.chat.supervisor.dispatch-dedup.enabled:false}") boolean dispatchDedupEnabled) {
        this.chatStreamProps = chatStreamProps;
        this.guardProperties = guardProperties;
        this.versionPort = versionPort;
        this.conflictPort = conflictPort;
        this.weatherPort = weatherPort;
        this.conflictObserveEnabled = conflictObserveEnabled;
        this.writebackEnabled = writebackEnabled;
        this.dispatchDedupEnabled = dispatchDedupEnabled;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("[ChatAssembly] {}", buildReport(
                chatStreamProps.isEnabled(),
                chatStreamProps.isPlanningGraphStreamEnabled(),
                writebackEnabled,
                conflictObserveEnabled,
                dispatchDedupEnabled,
                versionPort.getIfAvailable() != null,
                conflictPort.getIfAvailable() != null,
                weatherPort.getIfAvailable() != null,
                guardProperties.isEnabled(),
                guardProperties.getPromptInjection().getBlockKeywords() == null
                        ? 0 : guardProperties.getPromptInjection().getBlockKeywords().size()));
    }

    /** 纯函数化组装（便于单测）；present=true 表示该 Port 在本进程有实现 Bean。 */
    static String buildReport(boolean streamEnabled, boolean graphStreamEnabled,
                              boolean writebackEnabled, boolean conflictObserveEnabled,
                              boolean dispatchDedupEnabled,
                              boolean versionPortPresent, boolean conflictPortPresent,
                              boolean weatherPortPresent,
                              boolean guardEnabled, int guardBlockKeywords) {
        return ("stream=" + on(streamEnabled)
                + " graphStream=" + on(graphStreamEnabled)
                + " writeback=" + on(writebackEnabled) + "(port=" + present(versionPortPresent) + ")"
                + " conflictObserve=" + on(conflictObserveEnabled) + "(port=" + present(conflictPortPresent) + ")"
                + " dispatchDedup=" + on(dispatchDedupEnabled)
                + " weatherPort=" + present(weatherPortPresent)
                + " guard=" + on(guardEnabled) + "(blockKeywords=" + guardBlockKeywords + ")");
    }

    private static String on(boolean b) {
        return b ? "on" : "off";
    }

    private static String present(boolean b) {
        return b ? "bean" : "absent";
    }
}
