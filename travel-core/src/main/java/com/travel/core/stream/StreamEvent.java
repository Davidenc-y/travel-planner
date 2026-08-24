package com.travel.core.stream;

import java.util.Map;

/**
 * M6：跨域统一流式事件。
 *
 * <p>协议纪律：事件“只增、不改、不删”；data 由各域自定义，传输层统一 JSON 序列化。</p>
 */
public record StreamEvent(
        int version,
        StreamEventType type,
        String stage,
        String message,
        Object data,
        StreamMeta meta,
        /** A-P2：可选的确定性事件 id（重放流=分块下标；普通流由传输适配器自增生成） */
        String eventId) {

    public static final int VERSION = 1;

    public static StreamEvent thinking(StreamMeta meta, String stage, String message) {
        return new StreamEvent(VERSION, StreamEventType.THINKING, stage, message, null, meta, null);
    }

    public static StreamEvent token(StreamMeta meta, String text) {
        return token(meta, text, null);
    }

    public static StreamEvent token(StreamMeta meta, String text, String eventId) {
        return new StreamEvent(VERSION, StreamEventType.TOKEN, null, null,
                Map.of("text", text == null ? "" : text), meta, eventId);
    }

    public static StreamEvent done(StreamMeta meta, Map<String, Object> data) {
        return done(meta, data, null);
    }

    public static StreamEvent done(StreamMeta meta, Map<String, Object> data, String eventId) {
        return new StreamEvent(VERSION, StreamEventType.DONE, null, null, data, meta, eventId);
    }

    public static StreamEvent error(StreamMeta meta, int code, String message) {
        return new StreamEvent(VERSION, StreamEventType.ERROR, null, message,
                Map.of("code", code, "message", message == null ? "" : message), meta, null);
    }

    public static StreamEvent ping(StreamMeta meta) {
        return new StreamEvent(VERSION, StreamEventType.PING, null, null, Map.of(), meta, null);
    }
}
