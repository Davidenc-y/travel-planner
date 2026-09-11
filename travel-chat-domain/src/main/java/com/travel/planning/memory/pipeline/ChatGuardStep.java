package com.travel.planning.memory.pipeline;

import com.travel.common.exception.BusinessException;
import com.travel.planning.guard.GuardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * M3-10：MessagePipeline 第三步切片——步骤 1「安全防护」。
 * 将 Guard 检查从 ChatService 抽出为独立可测步骤。
 */
@Component
@RequiredArgsConstructor
public class ChatGuardStep implements ChatPipelineStep {

    /** B3.2：步骤顺序——F90 步骤 1「安全防护」（依据 R7-pipeline-mapping 现发送链步骤序 1，ChatService :213）。 */
    static final int STEP_ORDER = 1;

    @Override
    public int order() {
        return STEP_ORDER;
    }

    private final GuardService guardService;

    public void check(Long userId, String message) {
        var guard = guardService.check(String.valueOf(userId), message);
        if (!guard.allowed()) {
            throw new BusinessException(40302, guard.reason());
        }
    }
}
