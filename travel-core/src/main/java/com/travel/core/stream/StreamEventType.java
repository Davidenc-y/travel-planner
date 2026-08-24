package com.travel.core.stream;

/**
 * M6：流式事件类型（跨域统一协议）。
 */
public enum StreamEventType {
    /** 思考/阶段提示 */
    THINKING,
    /** 最终回答分块 */
    TOKEN,
    /** 流结束（含 meta） */
    DONE,
    /** 业务级错误 */
    ERROR,
    /** 保活 */
    PING
}
