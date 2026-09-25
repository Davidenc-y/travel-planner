package com.travel.common.event;

/**
 * Y-3a：事件信封（EventBusPort 的统一载荷）。
 *
 * <p>{@code type}=事件类型（如 REFINE）；{@code key}=业务键（如 sessionId，供 rabbit 通道
 * 路由/分区用，redis 通道不落盘）；{@code payloadJson}=载荷 JSON 对象（扁平 string 字段，
 * redis 通道按 type+载荷字段扁平化为 XADD 字段面）；{@code ts}=发布时间戳（epoch ms，
 * redis 通道不落盘）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public record EventEnvelope(String type, String key, String payloadJson, long ts) {
}
