package com.travel.planning.memory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AP-B2（GP-5）：city-counts 缓存失效广播订阅者。
 *
 * <p>收到 {@code {"type":"cityCounts"}} 即调用 {@link CityCorpusCache#invalidate()}
 * 置空快照（下一轮 get 重载）——扩容导入后 600s TTL 滞后窗消除；无关 type 仅 debug
 * 忽略（同一频道多事件类型的向前兼容面）；非法 payload WARN 不抛（fail-open，
 * {@code TurnCancellationSubscriber} 同款纪律）。</p>
 *
 * <p>P0㉗ 契约冻结：频道与 type 逐字见 {@link CityCorpusInvalidationPubSubConfig}
 * 常量（乙侧独立声明+契约注释，禁从 knowledge/common 引用——审计窗合线做两侧一致
 * 静态断言）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CityCorpusInvalidationSubscriber implements MessageListener {

    private final CityCorpusCache cache;
    private final ObjectMapper objectMapper;

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null) {
            return;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(message.getBody(), Map.class);
            Object type = payload == null ? null : payload.get("type");
            if (CityCorpusInvalidationPubSubConfig.TYPE_CITY_COUNTS.equals(type)) {
                cache.invalidate();
                log.info("[CacheInvalidationSub] city-counts 失效广播已消费（快照置空）");
            } else {
                log.debug("[CacheInvalidationSub] 无关 type={} 忽略", type);
            }
        } catch (Exception e) {
            log.warn("[CacheInvalidationSub] 忽略非法失效广播: {}", e.getMessage());
        }
    }
}
