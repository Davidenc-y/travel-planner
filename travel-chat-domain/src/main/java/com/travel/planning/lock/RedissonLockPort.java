package com.travel.planning.lock;

import com.travel.common.lock.LockPort;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * X-7a：Redisson 分布式锁适配器（RLock 可重入；leaseTime=-1 → watchdog 默认 30s 续期）。
 *
 * <p>获取语义=方案字面：tryLock 失败=按现状语义执行不阻塞——waitTime=0 立即尝试一次，
 * 未获取到（他节点持锁）时不等待、直接执行 action；正确性由业务层兜底
 * （TravelProfileService 画像写入已有 DB 乐观锁+重试耗尽日志）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public class RedissonLockPort implements LockPort {

    /** 锁键命名空间（Redis 侧可辨识归属）。 */
    static final String KEY_PREFIX = "travel:lock:";

    private final RedissonClient redissonClient;

    public RedissonLockPort(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T executeWithLock(String key, Supplier<T> action) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + key);
        boolean locked = false;
        try {
            // waitTime=0=立即尝试；leaseTime=-1=watchdog 接管（默认 30s 自动续期）
            locked = lock.tryLock(0, -1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            // tryLock 失败=按现状语义执行不阻塞（方案字面；兜底=业务层乐观锁）
            return action.get();
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
