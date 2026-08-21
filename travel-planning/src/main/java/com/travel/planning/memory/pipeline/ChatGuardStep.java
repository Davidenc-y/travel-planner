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
public class ChatGuardStep {

    private final GuardService guardService;

    public void check(Long userId, String message) {
        var guard = guardService.check(String.valueOf(userId), message);
        if (!guard.allowed()) {
            throw new BusinessException(40302, guard.reason());
        }
    }
}
