package com.travel.planning.workflow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M4-8/P1-5：行程状态机配置。
 *
 * <p>对应 yml：{@code travel.itinerary.state-machine.*}。false 时回退旧行为
 * （不插占位、不写快照、无 resume 语义——一次性写入 GENERATED）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.itinerary.state-machine")
public class ItineraryStateMachineProperties {

    /** 是否启用状态机（GENERATING 占位 + 节点快照 + resume） */
    private boolean enabled = true;

    /** GENERATING 僵尸判定（分钟）：updated_at 超过该时长未推进视为死任务，可 resume */
    private int zombieMinutes = 10;
}
