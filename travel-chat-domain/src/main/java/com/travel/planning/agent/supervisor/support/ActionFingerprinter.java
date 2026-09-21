package com.travel.planning.agent.supervisor.support;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * S-C0：动作指纹器（方案 01 §S-C/C-1；图流振荡四维检测的第一维数据源）。
 *
 * <p>指纹口径：{@code sha256(subtask|agent|tool|normalize(params))[:16]}（十六进制前 16 位）；
 * params 归一化=键排序后 {@code k=trim(v)} 以逗号拼接（确定性：同语义参数同指纹）。</p>
 *
 * <p>requestId 键控滑动窗口（F27/E-48 合规：ConcurrentHashMap + 按键串行化，零 ThreadLocal）；
 * 窗口 10（方案定值），超限淘汰最旧。生命周期与 QuotaTripwire/SpanCollector 同型：
 * {@link #clear}（请求结束）+ 超限惰性 TTL 清理兜底泄漏。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Component
public class ActionFingerprinter {

    /** 方案定值：滑动窗口 10 */
    static final int WINDOW = 10;
    /** 条目 TTL：轮次早已结束的残留必须失效 */
    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private static final class Entry {
        final String fingerprint;
        final long atMs;

        Entry(String fingerprint, long atMs) {
            this.fingerprint = fingerprint;
            this.atMs = atMs;
        }
    }

    /** requestId → 指纹滑动窗口（尾=最新）。 */
    private final ConcurrentMap<String, ConcurrentLinkedDeque<Entry>> windows = new ConcurrentHashMap<>();

    /**
     * 计算动作指纹：sha256(subtask|agent|tool|normalize(params)) 前 16 位十六进制。
     * 任一入参可为 null（归一为空串）。
     */
    public String fingerprint(String subtask, String agent, String tool, Map<String, Object> params) {
        String raw = nullSafe(subtask) + '|' + nullSafe(agent) + '|' + nullSafe(tool) + '|' + normalize(params);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 16);
        } catch (Exception e) { // SHA-256 必然存在；防御性兜底（不抛出阻断图流）
            return "0000000000000000";
        }
    }

    /** 记录一次动作指纹（入窗尾），返回当前窗口内该指纹出现次数（含本次）。 */
    public int record(String requestId, String fingerprint) {
        if (requestId == null || fingerprint == null) {
            return 0;
        }
        ConcurrentLinkedDeque<Entry> deque = windows.computeIfAbsent(requestId, k -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            deque.addLast(new Entry(fingerprint, System.currentTimeMillis()));
            while (deque.size() > WINDOW) {
                deque.pollFirst();
            }
            int count = 0;
            for (Entry e : deque) {
                if (e.fingerprint.equals(fingerprint)) {
                    count++;
                }
            }
            evictExpiredIfLarge();
            return count;
        }
    }

    /** 当前窗口内该指纹出现次数（不改变窗口）。 */
    public int countInWindow(String requestId, String fingerprint) {
        ConcurrentLinkedDeque<Entry> deque = windows.get(requestId);
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            int count = 0;
            for (Entry e : deque) {
                if (e.fingerprint.equals(fingerprint)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** 当前窗口长度（观测/单测用）。 */
    public int windowSize(String requestId) {
        ConcurrentLinkedDeque<Entry> deque = windows.get(requestId);
        return deque == null ? 0 : deque.size();
    }

    /** 请求结束显式清理（与 QuotaTripwire/SpanCollector clear 对称）。 */
    public void clear(String requestId) {
        if (requestId != null) {
            windows.remove(requestId);
        }
    }

    /** params 归一化：键排序，k=trim(String(v))，逗号拼接；null/空 → 空串。 */
    static String normalize(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new LinkedHashMap<>();
        params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sorted.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()).trim()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s.trim();
    }

    /** 惰性清理：仅条目较多时扫描过期项（QuotaTripwire 同型纪律）。 */
    private void evictExpiredIfLarge() {
        if (windows.size() <= 1024) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ConcurrentLinkedDeque<Entry>>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConcurrentLinkedDeque<Entry>> e = it.next();
            ConcurrentLinkedDeque<Entry> deque = e.getValue();
            synchronized (deque) {
                Entry last = deque.peekLast();
                if (last == null || now - last.atMs >= TTL_MILLIS) {
                    it.remove();
                }
            }
        }
    }
}
