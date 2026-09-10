package com.travel.planning.memory.pipeline;

/** R7.1：管线步骤统一契约（接口先行）。阶段一不接线；阶段二经人工确认后逐步让八步实现本接口。 */
public interface ChatPipelineStep {

    /** 步骤顺序（升序执行）。 */
    int order();

    /** 步骤开关（默认启用）。 */
    default boolean enabled() { return true; }

    /** 步骤执行（签名以映射表产出的统一上下文为准，阶段二定稿）。 */
    // void execute(ChatPipelineContext ctx);
}
