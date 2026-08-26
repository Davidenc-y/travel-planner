package com.travel.planning.guard;

/**
 * 防护规则 SPI（F90）。
 *
 * <p>新规则只需实现本接口并在 yml 注册/组件装配即可；未来拆 travel-guard
 * 模块时本接口即模块边界。</p>
 */
public interface GuardRule {

    /** 规则唯一名 */
    String name();

    /** 校验输入；allowed=false 时携带拒绝原因 */
    GuardResult check(String userId, String input);
}
