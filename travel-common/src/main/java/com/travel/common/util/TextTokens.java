package com.travel.common.util;

/**
 * M3-5：token 估算与截断工具（HAN=1 / 其他≈0.25 启发式，与既有口径一致）。
 */
public final class TextTokens {

    private TextTokens() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                tokens += 1;
            } else {
                tokens += 0.25;
            }
        }
        return (int) Math.ceil(tokens);
    }

    /** 按 token 预算语义截断（尽量不切断 CJK 字符） */
    public static String truncateByTokens(String text, int maxTokens) {
        if (text == null || estimate(text) <= maxTokens) {
            return text == null ? "" : text;
        }
        StringBuilder sb = new StringBuilder();
        int tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int cost = isCjk(c) ? 1 : 0;
            tokens += cost;
            if (tokens > maxTokens) {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
}
