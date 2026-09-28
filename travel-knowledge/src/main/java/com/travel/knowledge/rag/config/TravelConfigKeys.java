package com.travel.knowledge.rag.config;

/**
 * AF-3b：knowledge 模块高频配置键中心常量（G4 可维护性治理）。
 *
 * <p>收录口径=普查（docs/zcode/20260928_review/配置键全景清单.md）knowledge 模块内
 * 引用 ≥2 处的键（方案预期 ≤12 为上限，实际 2 键）；替换仅对 @Value 全键字符串形态
 * 机械执行（"@{" + 常量 + ":default}" 恒等拼接），@ConditionalOnProperty prefix+name
 * 形态（rerank.type）改写涉及注解结构与白名单约束——常量先入本类，替换留待后续批。</p>
 *
 * <p>跨模块高频键（travel.internal.token 等）不在本类——chat-domain 零 knowledge 依赖
 * （pom 实证），作用域必须限定本模块。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public final class TravelConfigKeys {

    /** S-B2 同族键（键路径断裂事故面）：hedge 并行补采开关（@Value 默认 false=关闭态逐字节等价，E-33） */
    public static final String RAG_HEDGE_ENABLED = "travel.rag.hedge-enabled";

    /** rerank 实现类型（@Cond prefix 形态 ×2，登记未替换——见类注释） */
    public static final String RAG_RERANK_TYPE = "travel.rag.rerank.type";

    private TravelConfigKeys() {
    }
}
