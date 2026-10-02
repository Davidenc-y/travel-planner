package com.travel.planning.memory.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AP-B2：city-counts 失效广播监听容器容错启动器——
 * {@code ChatCancellationListenerLifecycle} 生命周期同款（异步启动+固定间隔重试+
 * 优雅停止，Redis 不可用不阻塞应用启动；正确性不依赖广播：权威=cache TTL 旧语义，
 * 广播缺席仅退化为 600s 滞后窗，与 M6-46 同判例）。
 */
@Slf4j
public class CityCorpusInvalidationListenerLifecycle implements SmartLifecycle, Runnable {

    private static final long DEFAULT_RETRY_MILLIS = 5_000L;

    private final RedisMessageListenerContainer container;
    private final long retryMillis;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "city-corpus-invalidation-pubsub");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public CityCorpusInvalidationListenerLifecycle(RedisMessageListenerContainer container) {
        this(container, DEFAULT_RETRY_MILLIS);
    }

    CityCorpusInvalidationListenerLifecycle(RedisMessageListenerContainer container, long retryMillis) {
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
            log.info("[CacheInvalidationPub] Redis 订阅容器已启动（频道 {}）",
                    CityCorpusInvalidationPubSubConfig.CHANNEL_CACHE_INVALIDATE);
        } catch (Exception e) {
            // 不阻塞应用；复位后按固定间隔重试（container.start 抛异常时内部
            // running 已置位但订阅未建立，需 stop 复位才能安全重试）
            log.warn("[CacheInvalidationPub] Redis 订阅容器启动失败，{}ms 后重试: {}",
                    retryMillis, e.getMessage());
            try {
                container.stop();
            } catch (Exception stopErr) {
                log.warn("[CacheInvalidationPub] 停止未就绪订阅容器失败: {}", stopErr.getMessage());
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
                log.warn("[CacheInvalidationPub] 停止订阅容器失败: {}", e.getMessage());
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
