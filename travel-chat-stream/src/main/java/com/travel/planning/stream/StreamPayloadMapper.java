package com.travel.planning.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.core.stream.StreamEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M6-6-R1 Step 0：StreamEvent → SSE data 扁平 payload 映射（传输无关，MVC/WebFlux 共用）。
 *
 * <p>thinking → {@code {stage,message}}；token/done/error → 事件 data 透传；
 * ping → 空对象。不把整个 StreamEvent 记录序列化到 data，避免前端解析错位。</p>
 */
@Component
@RequiredArgsConstructor
public class StreamPayloadMapper {

    private final ObjectMapper objectMapper;

    public Map<String, Object> toPayload(StreamEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (event.type()) {
            case THINKING -> {
                payload.put("stage", event.stage() == null ? "" : event.stage());
                payload.put("message", event.message() == null ? "" : event.message());
            }
            case TOKEN, DONE, ERROR -> {
                if (event.data() instanceof Map<?, ?> data) {
                    data.forEach((k, v) -> payload.put(String.valueOf(k), v));
                } else if (event.data() != null) {
                    payload.put("value", event.data());
                }
            }
            default -> {
                // PING：空对象
            }
        }
        return payload;
    }

    public String toJson(StreamEvent event) throws JsonProcessingException {
        return objectMapper.writeValueAsString(toPayload(event));
    }
}
