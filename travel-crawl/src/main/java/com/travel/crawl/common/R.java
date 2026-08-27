package com.travel.crawl.common;

/**
 * 轻量统一响应（travel-crawl 保持不依赖 travel-common，镜像 common.result.R 契约）。
 *
 * <p>M6-56/T7：本类为<b>有意与 travel-common/result/R 分离</b>——crawl 只依赖
 * travel-core（零框架内核），若引入 travel-common 会连带 MyBatis-Plus/MinIO 等
 * 重依赖，破坏爬虫模块的轻量可独立运行属性。字段/语义与 common.R 保持镜像
 * （code/message/data/timestamp），改动一侧时须同步另一侧。</p>
 */
public record R<T>(int code, String message, T data, long timestamp) {

    public static <T> R<T> ok(T data) {
        return new R<>(200, "success", data, System.currentTimeMillis());
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null, System.currentTimeMillis());
    }
}
