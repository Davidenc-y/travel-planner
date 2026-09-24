package com.travel.common.lock;

import java.util.function.Supplier;

/**
 * X-7a：分布式锁端口（端口/适配器分离——业务依赖接口，实现可替换）。
 *
 * <p>获取失败时的行为由适配器定义并留档：NoOp=进程内 synchronized 串行（单实例现状）；
 * Redisson=tryLock 失败按现状语义执行不阻塞（正确性由业务层乐观锁兜底）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public interface LockPort {

    /**
     * 在 key 对应锁的保护下执行 action 并返回其结果（异常原样上抛由实现保证语义一致）。
     *
     * @param key    锁业务键（适配器可加命名空间前缀，如 travel:lock:）
     * @param action 受保护动作
     * @param <T>    action 返回类型
     * @return action 的返回值
     */
    <T> T executeWithLock(String key, Supplier<T> action);
}
