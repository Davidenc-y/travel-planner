package com.travel.common.config;

/**
 * 灰度开关键单源（R5.3）。仅收敛常量，不改读取机制。
 *
 * <p>R5.3 收敛范围注记：{@code travel-crawl} 的 2 处 {@code "X-Internal-Token"}
 * 字面量（CrawlImageUploader、LocalPipelinePublisher）经主控裁决豁免——该模块
 * pom 刻意不依赖 travel-common（保持轻量与解耦），字面量保留、记录在案。</p>
 */
public final class GrayFlags {

    private GrayFlags() {
    }

    /** 服务间内调共享密钥的请求头名（planning/knowledge/webflux 桥 fail-closed 凭证）。 */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** webflux 灰度传输开关的配置键（yml 侧同名键的单源引用）。 */
    public static final String CFG_STREAM_WEBFLUX_ENABLED = "gray.stream.webflux-enabled";
}
