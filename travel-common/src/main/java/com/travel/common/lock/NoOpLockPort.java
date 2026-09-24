package com.travel.common.lock;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * X-7a：默认锁端口——进程内 synchronized 串行（单实例语义=现状等价，E-33 默认）。
 *
 * <p>{@code travel.lock.redisson.enabled=true} 时 RedissonLockPort 以 @Primary 接管注入；
 * 本 Bean 始终存在，作为无分布式环境的兜底实现。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Component
public class NoOpLockPort implements LockPort {

    @Override
    public <T> T executeWithLock(String key, Supplier<T> action) {
        synchronized (this) {
            return action.get();
        }
    }
}
