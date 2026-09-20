package com.travel.common.web.support;

import com.travel.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RK-16：X-Internal-Token fail-closed 校验单点（原 planning 两 controller 复制收敛）。
 *
 * <p>语义=被替换的 requireInternalToken/内联校验块逐字保持：服务端未配置（null/空白）、
 * 请求头缺失（null）或不匹配 → BusinessException(40101, "内部调用凭证缺失或不匹配")。</p>
 */
@Component
public class InternalTokenSupport {

    private final String token;

    public InternalTokenSupport(@Value("${travel.internal.token:}") String token) {
        this.token = token;
    }

    public void requireValid(String headerToken) {
        if (token == null || token.isBlank()
                || headerToken == null || !token.equals(headerToken)) {
            throw new BusinessException(40101, "内部调用凭证缺失或不匹配");
        }
    }
}
