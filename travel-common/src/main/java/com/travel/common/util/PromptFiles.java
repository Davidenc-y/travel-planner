package com.travel.common.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M18-2：classpath prompts/*.st 轻量加载器（common 层，knowledge/chat-domain 共用）。
 *
 * <p>chat-domain 既有 PromptTemplates（枚举式）保持不动；本工具服务于后补外置的
 * 散点 prompt（评审 26 项清单 H13/H14/H16），Phase 3 模块拆分时再评估与
 * PromptTemplates 合并。懒加载 + 缓存 + 缺失快速失败（与 PromptTemplates 同哲学）。</p>
 */
public final class PromptFiles {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptFiles() {
    }

    /** 读取 prompts/{name}.st 全文；缺失/读失败快速失败。 */
    public static String get(String name) {
        return CACHE.computeIfAbsent(name, k -> {
            String path = "/prompts/" + k + ".st";
            try (InputStream in = PromptFiles.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("Prompt 模板缺失: classpath:" + path);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Prompt 模板读取失败: " + path + " - " + e.getMessage(), e);
            }
        });
    }
}
