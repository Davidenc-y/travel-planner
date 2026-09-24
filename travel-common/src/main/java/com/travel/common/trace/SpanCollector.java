package com.travel.common.trace;

import com.travel.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * S-B4：请求级 Span 树采集器（t_agent_trace.spans 列数据源；B-5/B-6 挂点接线）。
 *
 * <p>requestId 键控 {@link ConcurrentHashMap}（F27/E-48 合规：零 ThreadLocal、零共享可变
 * 状态——每个 Span 实例由发起线程独占写、刷盘线程终态读）。生命周期与
 * {@code QuotaTripwire} 同型：{@link #drainSpansJson}（刷盘取走并清理）/ {@link #clear}
 * （请求 finally）；条目超限时惰性 TTL 清理兜底泄漏。</p>
 *
 * <p>截断策略（方案 01 §S-B/B-3④）：单 span attrs 序列化 ≤2KB（超限替换为
 * {@code {truncated:true, preview}} 保 JSON 合法）；单请求 spans JSON 总量 ≤16KB
 * （超限丢弃更晚 span，尾部追加 {@code __truncated__} 标记）。</p>
 *
 * <p>S-B5a 关联方式：同线程 MDC 型上下文（{@link #bindCurrent}/{@link #unbindCurrent} +
 * {@link #start(String,String)}/{@link #end} 便捷对）——**仅在请求线程同步链内读写**，
 * 无跨线程传递（异步环节不读上下文，其 span 由调用方线程 start/end 包夹），不触碰
 * E-48"跨线程 ThreadLocal 传递"红线；spans 存储仍为 requestId 键控 map。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class SpanCollector {

    /** 单 span attrs 序列化上限（UTF-8 字节） */
    static final int MAX_SPAN_ATTRS_BYTES = 2 * 1024;
    /** 单请求 spans JSON 总上限（UTF-8 字节） */
    static final int MAX_SPANS_JSON_BYTES = 16 * 1024;
    /** 条目 TTL：轮次早已结束的残留必须失效 */
    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    /** 一个 Span（name/type/startMs 构造后不变；end 三字段由发起线程 endSpan 一次写入） */
    public static final class Span {
        private final String name;
        private final String type;
        private final long startMs;
        private volatile long endMs;
        private volatile String status;
        private volatile Map<String, Object> attrs;

        private Span(String name, String type, long startMs) {
            this.name = name;
            this.type = type;
            this.startMs = startMs;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }
    }

    /** requestId → 已完成 spans（终态追加-only）。 */
    private final ConcurrentMap<String, List<Span>> finished = new ConcurrentHashMap<>();

    /**
     * S-B5a：同线程 MDC 型关联（仅请求线程同步链读写；见类注 E-48 红线说明）。
     */
    private final ThreadLocal<String> currentRequest = new ThreadLocal<>();

    /** 入口绑定（KnowledgeTraceRecorder.aroundRag / chat 侧入口调用；须配对 unbindCurrent）。 */
    public void bindCurrent(String requestId) {
        currentRequest.set(requestId);
    }

    /** 出口解绑（finally 调用，防线程池串号）。 */
    public void unbindCurrent() {
        currentRequest.remove();
    }

    /** 当前关联 requestId（未绑定为 null——挂点空安全跳过）。 */
    public String currentRequestId() {
        return currentRequest.get();
    }

    /** 便捷对：用当前绑定 requestId 开 span（未绑定返回 null，挂点空安全）。 */
    public Span start(String name, String type) {
        String rid = currentRequest.get();
        return rid == null ? null : startSpan(rid, name, type);
    }

    /** 便捷对：用当前绑定 requestId 收 span（span 为 null 安全跳过）。 */
    public void end(Span span, String status, Map<String, Object> attrs) {
        String rid = currentRequest.get();
        if (rid != null) {
            endSpan(rid, span, status, attrs);
        }
    }

    /**
     * W-1a：错误 span 补记（catch 块用）——落一个 type/status 均 error 的 span，
     * attrs 携带 errorMsg 键（超长截断沿用 drainSpansJson 既有 2KB 预算）。
     * requestId 为 null 时空安全跳过（TraceContext 未激活等场景零行为）。
     */
    public void recordError(String requestId, String name, String errorMsg) {
        if (requestId == null) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("errorMsg", errorMsg == null ? "" : errorMsg);
        endSpan(requestId, new Span(name, "error", System.currentTimeMillis()), "error", attrs);
    }

    /** 开启一个 span（调用线程持有返回值，endSpan 时回传）。 */
    public Span startSpan(String requestId, String name, String type) {
        return new Span(name, type, System.currentTimeMillis());
    }

    /** 结束一个 span（status：ok/error/timeout/skip 等；attrs 可空）。 */
    public void endSpan(String requestId, Span span, String status, Map<String, Object> attrs) {
        if (span == null || requestId == null) {
            return;
        }
        span.endMs = System.currentTimeMillis();
        span.status = status == null ? "ok" : status;
        span.attrs = attrs;
        finished.computeIfAbsent(requestId, k -> new CopyOnWriteArrayList<>()).add(span);
        evictExpiredIfLarge();
    }

    /**
     * 刷盘取走：按 startMs 排序序列化为 JSON 数组（截断策略见类注）；
     * 无 spans 返回 null。调用后该 requestId 的 spans 即清空。
     */
    public String drainSpansJson(String requestId) {
        List<Span> list = finished.remove(requestId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        Span[] spans = list.toArray(new Span[0]);
        Arrays.sort(spans, Comparator.comparingLong(s -> s.startMs));
        List<Map<String, Object>> out = new ArrayList<>();
        int bytes = 2;
        int dropped = 0;
        for (Span s : spans) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", s.name);
            e.put("type", s.type);
            e.put("startMs", s.startMs);
            e.put("endMs", s.endMs);
            e.put("durationMs", Math.max(0L, s.endMs - s.startMs));
            e.put("status", s.status);
            if (s.attrs != null && !s.attrs.isEmpty()) {
                String aj = JsonUtils.toJson(s.attrs);
                if (aj != null && utf8Length(aj) > MAX_SPAN_ATTRS_BYTES) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("truncated", Boolean.TRUE);
                    t.put("preview", truncateUtf8(aj, MAX_SPAN_ATTRS_BYTES - 64));
                    e.put("attrs", t);
                } else {
                    e.put("attrs", s.attrs);
                }
            }
            String ej = JsonUtils.toJson(e);
            int add = utf8Length(ej) + 1;
            if (bytes + add > MAX_SPANS_JSON_BYTES) {
                dropped++;
                continue;
            }
            out.add(e);
            bytes += add;
        }
        if (dropped > 0) {
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("name", "__truncated__");
            marker.put("type", "marker");
            marker.put("droppedSpans", dropped);
            out.add(marker);
        }
        return JsonUtils.toJson(out);
    }

    /** 请求结束显式清理（与 AgentTraceCollector/QuotaTripwire clear 对称）。 */
    public void clear(String requestId) {
        if (requestId != null) {
            finished.remove(requestId);
        }
    }

    /** 当前请求已入队 span 数（观测/单测用；不改变状态）。 */
    public int finishedSpanCount(String requestId) {
        List<Span> list = finished.get(requestId);
        return list == null ? 0 : list.size();
    }

    /** 惰性清理：仅在条目较多时扫描过期项（QuotaTripwire 同型纪律）。 */
    private void evictExpiredIfLarge() {
        if (finished.size() <= 1024) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, List<Span>>> it = finished.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, List<Span>> e = it.next();
            List<Span> spans = e.getValue();
            long last = spans.isEmpty() ? 0L : spans.get(spans.size() - 1).startMs;
            if (now - last >= TTL_MILLIS) {
                it.remove();
            }
        }
    }

    /** UTF-8 字节长度 */
    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 按 UTF-8 字节预算安全截断（不产生半字符） */
    private static String truncateUtf8(String s, int maxBytes) {
        if (s == null || utf8Length(s) <= maxBytes) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int b = c < 0x80 ? 1 : (c < 0x800 ? 2 : 3); // BMP 内中文最多 3 字节；代理对按 2×3 保守计
            if (used + b > maxBytes) {
                break;
            }
            sb.append(c);
            used += b;
        }
        return sb.toString();
    }
}
