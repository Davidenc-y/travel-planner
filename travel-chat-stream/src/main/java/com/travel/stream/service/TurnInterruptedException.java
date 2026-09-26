package com.travel.stream.service;

import com.travel.common.exception.ControlFlowException;

/**
 * M6-36：轮次被用户中断（执行已中断）。
 *
 * <p>由 ChatService.runStream 在路由前/落库前检查中断标记时抛出；外层按既有
 * 异常路径 failTurn（幂等保持 FAILED），不落库 assistant 回答。</p>
 *
 * <p>AA-1a：实现 {@link ControlFlowException} 标记——prepareStream 阶段逃逸到
 * GlobalExceptionHandler 时按 log.info（无堆栈）+40906 收敛，不再记 ERROR+50000
 * （M7-8 同根异径的 prepare 段补堵）。</p>
 */
public class TurnInterruptedException extends RuntimeException implements ControlFlowException {

    public TurnInterruptedException(String message) {
        super(message);
    }
}
