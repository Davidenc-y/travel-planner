package com.travel.core.stream;

import java.util.ArrayList;
import java.util.List;

/**
 * M6：通用分块器——优先按句子结束符/换行切分，最后定长兜底；
 * 保证 {@code String.join("", chunk(text)) 与原文逐字一致}。
 */
public final class StreamingChunker {

    private StreamingChunker() {
    }

    public static List<String> chunk(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxChars);
        List<String> result = new ArrayList<>();
        int start = 0;
        int len = text.length();
        while (start < len) {
            int end = Math.min(start + limit, len);
            int boundary = lastBoundary(text, start, end);
            if (boundary > start) {
                end = boundary;
            }
            // 避免把代理对（emoji 等）从中间切断
            if (end < len && end > start
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end += 1;
            }
            result.add(text.substring(start, end));
            start = end;
        }
        return result;
    }

    private static int lastBoundary(String text, int from, int to) {
        for (int i = to - 1; i > from; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？'
                    || c == '；' || c == '!' || c == '?' || c == ';') {
                return i + 1;
            }
        }
        return -1;
    }
}
