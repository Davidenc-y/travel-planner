package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.travel.planning.service.TurnCancellation;
import com.travel.planning.service.TurnInterruptedException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * M6-57/T9 Step2：响应式阻塞与取消协作工具（从 TravelSupervisorAgent 迁出）。
 *
 * <p>纯静态、零状态；被直答/图流执行器共用。行为与迁移前逐字节等价
 * （取消/超时/根因解包语义保留，M6-40~46 护栏不动）。</p>
 */
public final class ReactiveBlockSupport {

    private ReactiveBlockSupport() {
    }

    /**
     * M6-40：可取消订阅并阻塞等待完成（替代 blockLast）。
     *
     * <p>取消路径：TurnCancellation.cancel() → dispose 订阅（响应式取消沿 Flux 上传，
     * 终止 DashScope 流/图流）；节点边界在 onNext 前 throwIfCancelled 短路。
     * 超时/线程中断保留原语义。</p>
     */
    public static <T> void blockUntilDone(Flux<T> flux, Consumer<T> onNext,
                                          TurnCancellation cancellation,
                                          long timeoutSeconds) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        Disposable disposable = flux
                .doOnNext(out -> {
                    cancellation.throwIfCancelled();
                    onNext.accept(out);
                })
                .subscribe(
                        ignored -> { },
                        err -> {
                            errorRef.set(err);
                            latch.countDown();
                        },
                        () -> {
                            done.set(true);
                            latch.countDown();
                        });
        cancellation.setDisposable(disposable);
        if (cancellation.isCancelled()) {
            disposable.dispose();
            throw new TurnInterruptedException("轮次已中断");
        }
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                disposable.dispose();
                throw new IllegalStateException("流式执行超时（超过 " + timeoutSeconds + " 秒）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disposable.dispose();
            // M6-46：线程中断在图流/直答路径几乎总是用户取消（dispose 沿 Flux 上传
            // 到图执行器后中断等待线程）——必须按取消语义上抛，否则 ChatRoutingStep
            // 会误走"图流失败，降级阻塞"分支（M6-38 语义回归）
            throw new TurnInterruptedException("轮次已中断");
        }
        Throwable error = errorRef.get();
        if (error != null) {
            // M6-42：拦截器取消短路异常可能被图执行器包装，根因解包后原样上抛
            if (findRootCause(error) instanceof TurnInterruptedException tie) {
                throw tie;
            }
            if (error instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("流式执行失败", error);
        }
        if (!done.get()) {
            throw new IllegalStateException("流式执行未返回最终状态");
        }
    }

    /** M6-42：把轮次取消 key 写入 RunnableConfig metadata（图内拦截器短路用）。 */
    public static void addCancellationMetadata(
            RunnableConfig.Builder builder, TurnCancellation cancel) {
        String key = cancel == null ? null : cancel.clientMessageId();
        if (key != null && !key.isBlank()) {
            builder.addMetadata(TokenUsageInterceptor.TURN_CANCELLATION_KEY, key);
        }
    }

    /** M6-42：沿 cause 链收敛根因（拦截器异常可能被图执行器包装多层）。 */
    public static Throwable findRootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}
