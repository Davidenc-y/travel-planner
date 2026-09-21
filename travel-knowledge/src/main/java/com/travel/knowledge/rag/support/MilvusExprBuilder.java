package com.travel.knowledge.rag.support;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * S-E0：Milvus expr 参数化构建器（C-08-01 注入清偿；方案 01 §S-E/E-1）。
 *
 * <p>全部 expr 值经本类净化后拼入引号表达式，禁止业务代码直接字符串拼接：</p>
 * <ul>
 *   <li>白名单快径 {@code [A-Za-z0-9:-]}（sessionId/seq 常规形态零开销直通）；</li>
 *   <li>白名单外（含 CJK 城市名等合法值）→ 引号转义（反斜杠/双引号）+控制字符剥离；</li>
 *   <li>非法输入（null/空/含控制字符）→ {@link IllegalArgumentException} + 静态计数
 *       {@link #illegalCount()}；</li>
 *   <li>长度上限 128 截断。</li>
 * </ul>
 *
 * <p>裁决留痕（2026-09-21 自审）：方案"白名单字符集"落地为<b>快径+转义兜底</b>而非硬门禁——
 * 硬门禁会 IAE 误伤含 {@code _}/CJK 的合法值（城市名"北京"必经此路），误伤比注入面更伤；
 * 注入面已由转义+控制字符剥离+IAE 覆盖。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public final class MilvusExprBuilder {

    /** 值长度上限 */
    public static final int MAX_VALUE_LENGTH = 128;

    /** 白名单快径字符集（sessionId/seq 常规形态） */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9:\\-]+");

    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f]");

    private static final AtomicLong ILLEGAL_COUNT = new AtomicLong();

    private MilvusExprBuilder() {
    }

    /** 非法输入累计（观测口；方案"非法输入计数"） */
    public static long illegalCount() {
        return ILLEGAL_COUNT.get();
    }

    /** {@code field == "value"} 形式（city/type/sessionId 等等值过滤）。 */
    public static String eq(String field, String raw) {
        return field + " == \"" + sanitize(field, raw) + "\"";
    }

    /** {@code field == "value" and seq like "value%"} 形式（会话按前缀删除）。 */
    public static String sessionSeqPrefixExpr(String sessionId, String seqPrefix) {
        return eq("sessionId", sessionId) + " and seq like \"" + sanitize("seqPrefix", seqPrefix) + "%\"";
    }

    /** 值净化：白名单快径直通；否则控制字符剥离+反斜杠/双引号转义+长度截断。 */
    public static String sanitize(String field, String raw) {
        if (raw == null) {
            ILLEGAL_COUNT.incrementAndGet();
            throw new IllegalArgumentException(field + " must not be null");
        }
        String v = raw.strip();
        if (v.isEmpty()) {
            ILLEGAL_COUNT.incrementAndGet();
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (CONTROL.matcher(v).find()) {
            ILLEGAL_COUNT.incrementAndGet();
            throw new IllegalArgumentException(field + " contains control characters");
        }
        if (v.length() > MAX_VALUE_LENGTH) {
            v = v.substring(0, MAX_VALUE_LENGTH);
        }
        if (SAFE.matcher(v).matches()) {
            return v;
        }
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
