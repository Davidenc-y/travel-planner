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
        StreamMeta meta) {

    public static final int VERSION = 1;

    public static StreamEvent thinking(StreamMeta meta, String stage, String message) {
        return new StreamEvent(VERSION, StreamEventType.THINKING, stage, message, null, meta);
    }

    public static StreamEvent token(StreamMeta meta, String text) {
        return new StreamEvent(VERSION, StreamEventType.TOKEN, null, null,
                Map.of("text", text == null ? "" : text), meta);
    }

    public static StreamEvent done(StreamMeta meta, Map<String, Object> data) {
        return new StreamEvent(VERSION, StreamEventType.DONE, null, null, data, meta);
    }

    public static StreamEvent error(StreamMeta meta, int code, String message) {
        return new StreamEvent(VERSION, StreamEventType.ERROR, null, message,
                Map.of("code", code, "message", message == null ? "" : message), meta);
    }

    public static StreamEvent ping(StreamMeta meta) {
        return new StreamEvent(VERSION, StreamEventType.PING, null, null, Map.of(), meta);
    }
}
