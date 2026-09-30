package com.travel.common.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AK-1a：事件消费者注册中心（G3 统一观测面）。
 *
 * <p>三处既有事件消费者（writeback Redis 消费 / writeback Rabbit 消费 / 取消广播消费）
 * 启动时经 {@link #register} 注册，启停经 {@link #start}/{@link #stop} 协调；
 * {@link #status()} 输出统一观测面（channel/key/gray 键/运行态/最近消费时间）。
 * 挂接层改造=行为等价：各消费者自身触发逻辑逐字不动（P0⑲）。</p>
 *
 * <p>并发结构仅键控 {@link ConcurrentHashMap}（E-48）：consumerKey 唯一键控；
 * 消费时间戳为 volatile 字段，仅供观测面读取。</p>
 *
 * <p>AK-3d（观测面闭环）：per-consumer 通道计数（consume/reject，AtomicLong 零依赖形态——
 * travel-common 无 micrometer 依赖，AK-3b 同款预检）；经 {@link #recordConsumed}/
 * {@link #recordRejected} 打点，status() 行携带计数=审计窗 R399 注册中心实弹的读取面。
 * 消费者触发逻辑零触碰（打点钩子由挂接层/审计窗显式调用=P0⑲ 边界内）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class EventConsumerRegistry {

    private final Map<String, ConsumerEntry> consumers = new ConcurrentHashMap<>();

    /** 单消费者注册记录（consumerKey 唯一键控）。 */
    public static final class ConsumerEntry {
        private final String channel;
        private final String consumerKey;
        private final String grayKey;
        private final Runnable startAction;
        private final Runnable stopAction;
        private volatile boolean running;
        private volatile long lastConsumeAt;
        /** AK-3d：per-consumer 通道计数（消费/拒绝） */
        private final java.util.concurrent.atomic.AtomicLong consumeCount = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong rejectCount = new java.util.concurrent.atomic.AtomicLong();

        ConsumerEntry(String channel, String consumerKey, String grayKey,
                      Runnable startAction, Runnable stopAction) {
            this.channel = channel;
            this.consumerKey = consumerKey;
            this.grayKey = grayKey;
            this.startAction = startAction;
            this.stopAction = stopAction;
        }

        public String getChannel() { return channel; }

        public String getConsumerKey() { return consumerKey; }

        public String getGrayKey() { return grayKey; }

        public boolean isRunning() { return running; }

        public long getLastConsumeAt() { return lastConsumeAt; }

        public long getConsumeCount() { return consumeCount.get(); }

        public long getRejectCount() { return rejectCount.get(); }
    }

    /** status() 统一观测行（channel/key/gray 键/运行态/最近消费时间/通道计数）。 */
    public static final class ConsumerStatus {
        private final String channel;
        private final String consumerKey;
        private final String grayKey;
        private final boolean running;
        private final long lastConsumeAt;
        private final long consumeCount;
        private final long rejectCount;

        ConsumerStatus(ConsumerEntry entry) {
            this.channel = entry.getChannel();
            this.consumerKey = entry.getConsumerKey();
            this.grayKey = entry.getGrayKey();
            this.running = entry.isRunning();
            this.lastConsumeAt = entry.getLastConsumeAt();
            this.consumeCount = entry.getConsumeCount();
            this.rejectCount = entry.getRejectCount();
        }

        public String getChannel() { return channel; }

        public String getConsumerKey() { return consumerKey; }

        public String getGrayKey() { return grayKey; }

        public boolean isRunning() { return running; }

        public long getLastConsumeAt() { return lastConsumeAt; }

        public long getConsumeCount() { return consumeCount; }

        public long getRejectCount() { return rejectCount; }
    }

    /**
     * 注册消费者（channel+consumerKey+gray 键名+启停动作）。
     * consumerKey 重复=编程错误，抛 {@link IllegalStateException}（注册期失败早暴露）。
     */
    public ConsumerEntry register(String channel, String consumerKey, String grayKey,
                                  Runnable startAction, Runnable stopAction) {
        ConsumerEntry entry = new ConsumerEntry(channel, consumerKey, grayKey, startAction, stopAction);
        ConsumerEntry prev = consumers.putIfAbsent(consumerKey, entry);
        if (prev != null) {
            throw new IllegalStateException(
                    "EventConsumerRegistry: duplicate consumerKey registration: " + consumerKey);
        }
        log.info("[AK-1a] event consumer registered: channel={}, key={}, grayKey={}",
                channel, consumerKey, grayKey);
        return entry;
    }

    /** 启动消费者（执行注册的 start 动作并置运行态）。未知 key 返回 false 不抛出。 */
    public boolean start(String consumerKey) {
        ConsumerEntry entry = consumers.get(consumerKey);
        if (entry == null) {
            log.warn("[AK-1a] start on unknown consumer: {}", consumerKey);
            return false;
        }
        entry.startAction.run();
        entry.running = true;
        log.info("[AK-1a] event consumer started: key={}", consumerKey);
        return true;
    }

    /** 停止消费者（执行注册的 stop 动作并清运行态）。未知 key 返回 false 不抛出。 */
    public boolean stop(String consumerKey) {
        ConsumerEntry entry = consumers.get(consumerKey);
        if (entry == null) {
            log.warn("[AK-1a] stop on unknown consumer: {}", consumerKey);
            return false;
        }
        entry.stopAction.run();
        entry.running = false;
        log.info("[AK-1a] event consumer stopped: key={}", consumerKey);
        return true;
    }

    /** 消费路径打点：更新最近消费时间+消费计数（观测面专用，不影响消费语义）。 */
    public void recordConsumed(String consumerKey) {
        ConsumerEntry entry = consumers.get(consumerKey);
        if (entry == null) {
            return;
        }
        entry.lastConsumeAt = System.currentTimeMillis();
        entry.consumeCount.incrementAndGet();
    }

    /** AK-3d：拒绝路径打点（观测面专用；未知 key 空安全跳过）。 */
    public void recordRejected(String consumerKey) {
        ConsumerEntry entry = consumers.get(consumerKey);
        if (entry == null) {
            return;
        }
        entry.rejectCount.incrementAndGet();
    }

    /** 统一观测面：全部注册消费者的 status 行。 */
    public List<ConsumerStatus> status() {
        List<ConsumerStatus> rows = new ArrayList<>();
        for (ConsumerEntry entry : consumers.values()) {
            rows.add(new ConsumerStatus(entry));
        }
        return rows;
    }
}
