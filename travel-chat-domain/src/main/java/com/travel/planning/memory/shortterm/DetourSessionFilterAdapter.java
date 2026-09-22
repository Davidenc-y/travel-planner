package com.travel.planning.memory.shortterm;

import com.travel.memory.detour.SessionDetourFilterPort;
import com.travel.planning.config.DetourIsolationProperties;
import com.travel.planning.memory.focus.DetourWordMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * U-3b 审计实施（B 案）：SessionDetourFilterPort 在 chat-domain 的实现。
 * 委托 DetourIsolationProperties/DetourWordMatcher（两者被 ChatService 双消费留驻本模块）。
 * 当 summaryFilter 关闭或 DETOUR 隔离关闭时恒 false（不过滤）。
 */
@Component
public class DetourSessionFilterAdapter implements SessionDetourFilterPort {

    private final DetourIsolationProperties props;
    private final DetourWordMatcher matcher;

    @Autowired(required = false)
    public DetourSessionFilterAdapter(DetourIsolationProperties props, DetourWordMatcher matcher) {
        this.props = props;
        this.matcher = matcher;
    }

    @Override
    public boolean shouldFilter(String summaryText) {
        if (summaryText == null || summaryText.isBlank()) {
            return false;
        }
        return props != null && matcher != null
                && props.isEnabled() && props.isSummaryFilter()
                && matcher.isLikelyDetour(summaryText);
    }
}
