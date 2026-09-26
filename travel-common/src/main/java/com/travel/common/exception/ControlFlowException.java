package com.travel.common.exception;

/**
 * 控制流异常标记接口（AA-1a）：预期内的流程中断，不是系统故障。
 *
 * <p>案情（M7-8 同根异径）：M7-8 已修复"SSE 响应已建立路径"的异常降噪
 * （浏览器关闭/切页触发容器异步错误分发，Content-Type 已是 text/event-stream，
 * GlobalExceptionHandler 降级 DEBUG）；但 {@code ChatService.prepareStream} 阶段
 * SSE 头尚未 flush、Content-Type 仍为 JSON，轮次中断逃逸到
 * {@code handleGeneric} 时落入 {@code log.error("系统异常") + 50000}——
 * 同根（预期控制流被当系统异常记账）异径（M7-8 只堵了 SSE 已建立的路径，
 * prepare 阶段漏堵）。</p>
 *
 * <p>实现本标记的异常在 handleGeneric 中按 {@code log.info}（无堆栈）+
 * 业务码 40906（轮次已中断）收敛；未来其他控制流异常（超时/取消类）
 * 实现同一标记即自动收编。标记接口零依赖反转：travel-common 不引 chat-stream。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public interface ControlFlowException {
}
