package com.travel.knowledge.rag.support;

/**
 * RAG Agent 输出 JSON 提取工具（F42/F43 共用）。
 *
 * <p>容忍 ```json 代码块与前后噪声：优先提取数组（检索结果为 JSON 数组），
 * 其次对象。</p>
 */
public final class RagJsonExtractor {

    private RagJsonExtractor() {
    }

    /**
     * 从响应中提取首个完整 JSON 数组或对象；找不到返回 null
     */
    public static String extract(String response) {
        if (response == null) {
            return null;
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        start = response.indexOf('{');
        end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }
}
