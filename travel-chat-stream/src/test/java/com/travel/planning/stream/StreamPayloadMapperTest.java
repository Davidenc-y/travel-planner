package com.travel.planning.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.core.stream.StreamEvent;
import com.travel.core.stream.StreamMeta;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6：SSE data 载荷协议单测（由 SseStreamAdapterTest 迁移，锁前端扁平协议）。
 */
class StreamPayloadMapperTest {

    private final StreamPayloadMapper mapper = new StreamPayloadMapper(new ObjectMapper());

    private static final StreamMeta META =
            new StreamMeta("r1", "s1", "k1", "chat", null, false);

    @Test
    void toPayload_thinking_containsStageAndMessage() {
        Map<String, Object> data = mapper.toPayload(
                StreamEvent.thinking(META, "preference", "正在分析您的偏好…"));

        assertThat(data).containsEntry("stage", "preference")
                .containsEntry("message", "正在分析您的偏好…");
    }

    @Test
    void toPayload_token_passesThroughText() {
        Map<String, Object> data = mapper.toPayload(
                StreamEvent.token(META, "你好"));

        assertThat(data).containsEntry("text", "你好");
    }

    @Test
    void toPayload_done_passesThroughMetaFields() {
        Map<String, Object> data = mapper.toPayload(
                StreamEvent.done(META, Map.of(
                        "sessionId", "s1", "messageId", 11L,
                        "tokens", 30, "replayed", true)));

        assertThat(data).containsEntry("sessionId", "s1")
                .containsEntry("messageId", 11L)
                .containsEntry("tokens", 30)
                .containsEntry("replayed", true);
    }

    @Test
    void toPayload_error_containsCodeAndMessage() {
        Map<String, Object> data = mapper.toPayload(
                StreamEvent.error(META, 50000, "流式处理失败"));

        assertThat(data).containsEntry("code", 50000)
                .containsEntry("message", "流式处理失败");
    }

    @Test
    void toPayload_ping_empty() {
        assertThat(mapper.toPayload(StreamEvent.ping(META))).isEmpty();
    }

    @Test
    void toJson_serializesToExpectedJson() throws Exception {
        String json = mapper.toJson(StreamEvent.token(META, "北京"));

        assertThat(json).contains("\"text\":\"北京\"");
    }
}
