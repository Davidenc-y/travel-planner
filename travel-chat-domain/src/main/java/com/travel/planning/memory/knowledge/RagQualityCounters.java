package com.travel.planning.memory.knowledge;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * RK-13/D-4：RAG 线上质量 Redis 日计数器（拒答/低置信/降级，G4，零 DDL）。
 *
 * <p>键=travel:rag:metrics:{yyyy-MM-dd}:{metric}（日分片，近 7 日聚合由 planning
 * rag-quality 只读端点消费，D-5 挂点）；首次写入当日键时设 TTL 90 天；
 * 指标失败 fail-open 不阻断业务。</p>
 */
@Component
public class RagQualityCounters {

    private final StringRedisTemplate redis;
    /** 键前缀公开供 planning rag-quality 端点同口径拼接（唯一权威源，禁复制字面量） */
    public static final String KEY_PREFIX = "travel:rag:metrics:";
    private static final long KEY_TTL_DAYS = 90;

    public RagQualityCounters(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void recordAbstain() {
        incr("abstain");
    }

    public void recordLowConfidenceDropped(int n) {
        incrBy("lowconf", n);
    }

    public void recordDegraded() {
        incr("degraded");
    }

    /** S-D2：grounding 未命中标注次数（D-1 annotate 行级标注计数；端点 metric=hallucflag） */
    public void recordHallucinationFlagged(int n) {
        incrBy("hallucflag", n);
    }

    private void incr(String metric) {
        incrBy(metric, 1);
    }

    private void incrBy(String metric, long n) {
        try {
            String key = KEY_PREFIX + LocalDate.now() + ":" + metric;
            Long value = redis.opsForValue().increment(key, n);
            if (value != null && value == n) {
                // 首次写入当日键（增量=初值）→ 设置 TTL 90 天
                redis.expire(key, KEY_TTL_DAYS, TimeUnit.DAYS);
            }
        } catch (Exception e) {
            // 指标失败不阻断业务（fail-open）
        }
    }
}
