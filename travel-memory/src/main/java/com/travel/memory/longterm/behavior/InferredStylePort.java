package com.travel.memory.longterm.behavior;

/**
 * AC-2b：推断风格回写端口（中立端口范式，同 {@link TripFactsPort}/{@code ProfileSlotPort}）：
 * memory 定义、chat-domain（TravelProfileService，持有 TravelProfileMapper 写路径）实现；
 * BehaviorProfileService 经 optional ObjectProvider 调用，不引入模块反向依赖。
 *
 * <p><b>E-33</b>：{@code travel.profile.style-inference-enabled=false} 默认关=调用方守卫
 * 不触发=零回写；端口装配仅 optional 注入，不触碰 DB。</p>
 */
public interface InferredStylePort {

    /**
     * 推断风格回写（实现方义务：updated_source="update" 的用户显式值保护、
     * 值域防御、乐观锁同 update 路径、updated_source="inferred"、失败不外抛）。
     *
     * @param travelStyle  推断值（AC-2a 输出值域=ECONOMY/COMFORT/LUXURY；null=不更新该列）
     * @param consumeLevel 推断值（AC-2a 输出值域=ECONOMICAL/STANDARD/COMFORT；null=不更新该列）
     */
    void applyInferredStyle(Long userId, String travelStyle, String consumeLevel);
}
