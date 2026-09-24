package com.travel.planning.client;

import com.travel.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * KnowledgeClient 降级工厂（X-2b：fallbackFactory 挂接）
 *
 * <p>语义红线（P0⑦）：降级=显式失败——每方法返回 {@code R.fail(50301, "knowledge 服务不可用")}
 * 并记录 cause 日志，<b>禁 R.ok 伪装成功数据</b>（空列表伪装会让调用方把降级当正常空结果，
 * 调用方降级分支 KnowledgeRetrievalService:55 的 markDegraded 观测面随之失真）。</p>
 *
 * <p>E-33：默认态 {@code spring.cloud.openfeign.circuitbreaker.enabled=false}，本工厂仅
 * 挂接不接管（异常直抛=现状零行为）；开启归审计实弹配置。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class KnowledgeClientFallbackFactory implements FallbackFactory<KnowledgeClient> {

    static final int KNOWLEDGE_UNAVAILABLE_CODE = 50301;
    static final String KNOWLEDGE_UNAVAILABLE_MESSAGE = "knowledge 服务不可用";

    @Override
    public KnowledgeClient create(Throwable cause) {
        log.warn("[KnowledgeFallback] knowledge 调用降级: {}", cause.toString());
        return new KnowledgeClient() {
            @Override
            public R<List<Map<String, Object>>> search(String ragType, String query, int topK) {
                return R.fail(KNOWLEDGE_UNAVAILABLE_CODE, KNOWLEDGE_UNAVAILABLE_MESSAGE);
            }

            @Override
            public R<Object> writeSessionContext(Map<String, Object> chunk) {
                return R.fail(KNOWLEDGE_UNAVAILABLE_CODE, KNOWLEDGE_UNAVAILABLE_MESSAGE);
            }

            @Override
            public R<List<Map<String, Object>>> searchSessionContext(String sessionId, String query, int topK) {
                return R.fail(KNOWLEDGE_UNAVAILABLE_CODE, KNOWLEDGE_UNAVAILABLE_MESSAGE);
            }

            @Override
            public R<List<Map<String, Object>>> findSessionContextByPrefix(String sessionId, String seqPrefix, int limit) {
                return R.fail(KNOWLEDGE_UNAVAILABLE_CODE, KNOWLEDGE_UNAVAILABLE_MESSAGE);
            }

            @Override
            public R<Integer> deleteSessionContextByPrefix(String sessionId, String seqPrefix) {
                return R.fail(KNOWLEDGE_UNAVAILABLE_CODE, KNOWLEDGE_UNAVAILABLE_MESSAGE);
            }
        };
    }
}
