package com.travel.common.util;

import java.util.Collection;
import java.util.Optional;

/**
 * M3-5：Agent 输出处理工具收敛（toText/stripCodeFence/extractJson/containsAny），
 * 消除 SessionMemoryServiceImpl/
 * PreferenceSaveService/ChatIntentClassifier 等处的重复实现。
 */
public final class AgentOutputUtils {

    private AgentOutputUtils() {
    }

    /** 安全提取输出文本：递归解包 Optional；String/其他 toString */
    public static String toText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Optional<?> opt) {
            return toText(opt.orElse(null));
        }
        if (value instanceof String s) {
            return s;
        }
        return value.toString();
    }

    /** 去除 Markdown 代码围栏（```json ... ```） */
    public static String stripCodeFence(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastIdx = t.lastIndexOf("```");
            if (firstNl > 0 && lastIdx > firstNl) {
                t = t.substring(firstNl + 1, lastIdx).trim();
            }
        }
        return t;
    }

    /** 截取首个 JSON 对象片段（indexOf('{') / lastIndexOf('}')） */
    public static String extractJson(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** 文本是否包含任一目标串 */
    public static boolean containsAny(String text, String... targets) {
        if (text == null || targets == null || targets.length == 0) {
            return false;
        }
        for (String t : targets) {
            if (t != null && text.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 集合中是否包含任一目标串 */
    public static boolean containsAny(Collection<String> source, String... targets) {
        if (source == null || source.isEmpty() || targets == null || targets.length == 0) {
            return false;
        }
        for (String s : source) {
            if (s == null) {
                continue;
            }
            for (String t : targets) {
                if (t != null && s.contains(t)) {
                    return true;
                }
            }
        }
        return false;
    }
}
