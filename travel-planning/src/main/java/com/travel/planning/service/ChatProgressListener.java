package com.travel.planning.service;

/**
 * M6：聊天流水线进度回调（思考阶段提示 + 最终回答就绪）。
 *
 * <p>旧 JSON 路径使用 {@link #NOOP}，行为逐字等价；流式路径由
 * {@link ChatStreamService} 桥接为 SSE 事件。</p>
 */
public interface ChatProgressListener {

    ChatProgressListener NOOP = new ChatProgressListener() {
    };

    /**
     * 流水线阶段提示（thinking 事件数据源）。
     */
    default void onThinking(String stage, String message) {
    }

    /**
     * 真 token 流：直答/回顾路径的流式增量文本（逐 token 输出）。
     *
     * <p>仅流式路径调用；旧 JSON 路径使用 {@link #NOOP}，不产生事件。</p>
     */
    default void onToken(String text) {
    }

    /**
     * 最终回答已就绪（完整文本；流式传输层自行分块）。
     */
    default void onResponse(String response) {
    }
}
