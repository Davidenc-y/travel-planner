package com.travel.planning.service;

/**
 * M6-36：轮次被用户中断（执行已中断）。
 *
 * <p>由 ChatService.runStream 在路由前/落库前检查中断标记时抛出；外层按既有
 * 异常路径 failTurn（幂等保持 FAILED），不落库 assistant 回答。</p>
 */
public class TurnInterruptedException extends RuntimeException {

    public TurnInterruptedException(String message) {
        super(message);
    }
}
