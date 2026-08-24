package com.travel.core.stream;

/**
 * M6：流式同步门禁结果。
 *
 * <p>ok=false 时由 Controller 按 {@link StreamErrorMapper} 映射 HTTP 状态；
 * context 承载门禁产物（如幂等 gate），供 {@link StreamingPipeline#stream} 复用，
 * 避免门禁重复执行。</p>
 */
public record StreamPreflight(boolean ok, Integer code, String message, Object context) {

    public static StreamPreflight ok(Object context) {
        return new StreamPreflight(true, null, null, context);
    }

    public static StreamPreflight fail(int code, String message) {
        return new StreamPreflight(false, code, message, null);
    }
}
