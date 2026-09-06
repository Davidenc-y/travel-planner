package com.travel.knowledge.security;

import com.travel.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * M21-3（SEC-02-04/05/07/08 止血）：knowledge 管理面端点的服务间共享密钥拦截器。
 *
 * <p>覆盖 /api/v1/etl/**（知识库重建/任意路径导入入口）、/api/v1/memory/**
 * （跨用户会话知识读/删）、/api/v1/files/images（匿名上传面）；
 * attractions 公开读与 files 代理/签名 URL 不在拦截范围（保持既有公开语义）。</p>
 *
 * <p>fail-closed：{@code travel.internal.token} 未配置（空）时拒绝一切命中请求——
 * 与 planning 侧 chat-writeback 桥（M21-2）同一共享密钥与环境变量
 * {@code TRAVEL_INTERNAL_TOKEN}。</p>
 */
@Slf4j
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    private final String internalToken;

    public InternalTokenInterceptor(@Value("${travel.internal.token:}") String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("X-Internal-Token");
        if (internalToken == null || internalToken.isBlank()
                || header == null || !internalToken.equals(header)) {
            log.warn("[InternalAuth] 拒绝管理面调用: uri={}, tokenPresent={}",
                    request.getRequestURI(), header != null && !header.isBlank());
            throw new BusinessException(40101, "内部调用凭证缺失或不匹配");
        }
        return true;
    }
}
