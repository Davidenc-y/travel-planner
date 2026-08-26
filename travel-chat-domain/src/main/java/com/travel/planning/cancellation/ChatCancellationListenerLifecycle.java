package com.travel.planning.cancellation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M6-46：取消广播监听容器容错启动器。
 *
 * <p>修复：RedisMessageListenerContainer 作为 Lifecycle bean 在 Spring 上下文
 * 刷新时同步连接 Redis，Redis 不可用（未启动/网络不通）会直接导致应用启动失败。
 * 本启动器接管启动：容器 bean 设 {@code autoStartup=false}，由本类在后台线程
 * 尝试启动；失败仅 WARN 并按固定间隔重试，Redis 恢复后自动完成订阅。
 * 正确性不依赖广播（权威为 DB + Redis 标记），故广播暂不可用不阻塞应用。</p>
 */
@Slf4j
public class ChatCancellationListenerLifecycle implements SmartLifecycle, Runnable {

    private static final long DEFAULT_RETRY_MILLIS = 5_000L;

    private final RedisMessageListenerContainer container;
    private final long retryMillis;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chat-cancellation-pubsub");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public ChatCancellationListenerLifecycle(RedisMessageListenerContainer container) {
        this(container, DEFAULT_RETRY_MILLIS);
    }

    ChatCancellationListenerLifecycle(RedisMessageListenerContainer container, long retryMillis) {
        this.container = container;
        this.retryMillis = Math.max(500L, retryMillis);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.execute(this);
        }
    }

    @Override
    public void run() {
        if (!running.get() || connected.get()) {
            return;
        }
        try {
            container.start();
            connected.set(true);
            log.info("[CancellationPub] Redis 订阅容器已启动");
        } catch (Exception e) {
            // 不阻塞应用；复位后按固定间隔重试（container.start 抛异常时内部
            // running 已置位但订阅未建立，需 stop 复位才能安全重试）
            log.warn("[CancellationPub] Redis 订阅容器启动失败，{}ms 后重试: {}",
                    retryMillis, e.getMessage());
            try {
                container.stop();
            } catch (Exception stopErr) {
                log.warn("[CancellationPub] 停止未就绪订阅容器失败: {}", stopErr.getMessage());
            }
            scheduler.schedule(this, retryMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        if (container.isRunning()) {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("[CancellationPub] 停止订阅容器失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get() || container.isRunning();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
