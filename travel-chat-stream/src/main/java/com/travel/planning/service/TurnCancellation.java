package com.travel.planning.service;

import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * M6-40：单轮次取消令牌（协作式取消）。
 *
 * <p>取消路径：SSE 断开 / 用户停止 / 新消息终止 → {@link #cancel()}（置位并
 * dispose 已注册的响应式订阅）；执行侧在节点边界调用 {@link #throwIfCancelled()}。
 * {@link #NOOP} 用于无取消语义的调用点（如测试/非聊天链路）。</p>
 */
public class TurnCancellation {

    /** 永不取消的空实现（无取消语义调用点/测试用） */
    public static final TurnCancellation NOOP = new TurnCancellation(null) {
        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void setDisposable(Disposable d) {
            // no-op
        }

        @Override
        public void attachExternalCancelCheck(Supplier<Boolean> check) {
            // no-op
        }

        @Override
        public void throwIfCancelled() {
            // no-op
        }
    };

    /** M6-42：所属轮次的 clientMessageId（可空；用于 TokenUsageInterceptor 取消短路） */
    private final String clientMessageId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Disposable> disposable = new AtomicReference<>();
    /**
     * M6-44：外部权威取消检查（跨实例 Redis 标记兜底），由 ChatService.runStream
     * 注册时挂载；null 表示无外部检查（测试/非聊天链路）。Redis 抖动时保守放行。
     */
    private volatile Supplier<Boolean> externalCancelCheck;

    public TurnCancellation() {
        this(null);
    }

    public TurnCancellation(String clientMessageId) {
        this.clientMessageId = clientMessageId;
    }

    public String clientMessageId() {
        return clientMessageId;
    }

    /** 首次调用返回 true；同时 dispose 已注册订阅（响应式取消沿 Flux 上传） */
    public boolean cancel() {
        boolean first = cancelled.compareAndSet(false, true);
        Disposable d = disposable.get();
        if (d != null) {
            d.dispose();
        }
        return first;
    }

    public boolean isCancelled() {
        if (cancelled.get()) {
            return true;
        }
        Supplier<Boolean> check = externalCancelCheck;
        if (check == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(check.get());
        } catch (Exception e) {
            // 权威检查（Redis GET）抖动时保守放行：本地取消已覆盖同实例，
            // 误停正常轮次比在途单节点跑完更糟
            return false;
        }
    }

    /**
     * M6-44：挂载外部权威取消检查（通常为 Redis chat:interrupt:* 标记查询）。
     * 幂等可重复挂载；已取消时无需处理（下一次检查立即命中）。
     */
    public void attachExternalCancelCheck(Supplier<Boolean> check) {
        this.externalCancelCheck = check;
    }

    /** 注册可取消订阅；若取消已发生则立即 dispose（竞态安全） */
    public void setDisposable(Disposable d) {
        if (d == null) {
            return;
        }
        disposable.set(d);
        if (cancelled.get()) {
            d.dispose();
        }
    }

    /** 节点边界检查：命中取消抛 TurnInterruptedException */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new TurnInterruptedException("轮次已中断");
        }
    }
}
