package com.travel.memory.shortterm;

import com.travel.memory.shortterm.SummaryStorePort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RK-10/D-0：SummaryStorePort 的 Redis 适配器——方法体=SummaryAssembler 原调用
 * 原样搬移（E-21 签名提取语义），lua 资源引用不动（save_summary_cas.lua 仍在
 * chat-domain classpath）。暂无消费方（D-1 接线），编译绿即门槛。
 */
@Component
public class RedisSummaryStoreAdapter implements SummaryStorePort {

    private static final DefaultRedisScript<Long> SAVE_SUMMARY_CAS = buildCasScript();

    private final StringRedisTemplate redisTemplate;

    public RedisSummaryStoreAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static DefaultRedisScript<Long> buildCasScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/save_summary_cas.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public String loadSummaryText(String sessionId) {
        return redisTemplate.opsForValue().get(summaryKey(sessionId));
    }

    @Override
    public String loadSummaryMeta(String sessionId) {
        return redisTemplate.opsForValue().get(summaryMetaKey(sessionId));
    }

    @Override
    public boolean saveSummaryCas(String sessionId, int expectedVersion, String text,
                                  String metaJson, long ttlSeconds) {
        Long r = redisTemplate.execute(SAVE_SUMMARY_CAS,
                List.of(summaryKey(sessionId), summaryMetaKey(sessionId)),
                String.valueOf(expectedVersion), text, metaJson, String.valueOf(ttlSeconds));
        return r != null && r == 1L;
    }

    @Override
    public void saveSummaryPair(String sessionId, String text, String metaJson, long ttlDays) {
        redisTemplate.opsForValue().set(summaryKey(sessionId), text, ttlDays, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(summaryMetaKey(sessionId), metaJson, ttlDays, TimeUnit.DAYS);
    }

    @Override
    public void refreshTtl(String sessionId, long ttlDays) {
        redisTemplate.expire(summaryKey(sessionId), ttlDays, TimeUnit.DAYS);
        redisTemplate.expire(summaryMetaKey(sessionId), ttlDays, TimeUnit.DAYS);
    }

    private String summaryKey(String sessionId) {
        return "session:" + sessionId + ":summary";
    }

    private String summaryMetaKey(String sessionId) {
        return "session:" + sessionId + ":summary:meta";
    }
}
