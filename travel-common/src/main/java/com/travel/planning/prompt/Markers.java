package com.travel.planning.prompt;

/**
 * 上下文段落 marker 契约常量（M16-2 契约常量化）。
 *
 * <p>这些字符串是跨类（甚至跨模块：chat-domain 生产、planning 消费）的<strong>隐式协议</strong>——
 * composed 上下文的分段标记。修改任何值必须同步核对全部消费方，编译器无法保护，
 * 故收敛为公共常量并在 {@code MarkersContractTest} 中锁定取值。</p>
 *
 * <p>生产/消费关系（M16-2 实证）：</p>
 * <ul>
 *   <li>{@link #CURRENT_QUESTION}：ContextComposer 生产（composed 末段）；
 *       PlanningHeuristics / DirectAnswerExecutor / ItineraryVersionPortImpl 消费
 *       （截取其后内容即用户原始输入）</li>
 *   <li>{@link #SESSION_SUMMARY} / {@link #SESSION_KNOWLEDGE} / {@link #ATTRACTION_CANDIDATES}：
 *       ContextComposer 生产</li>
 *   <li>{@link #USER_PROFILE}：ProfileContextAssembler 生产（聊天链与行程链注入）</li>
 *   <li>{@link #WEATHER_REFERENCE}：WeatherContextBuilder 生产（planning 侧）</li>
 *   <li>{@link #LEGACY_USER_ID_SUFFIX}：历史遗留防御契约（早期版本曾把
 *       {@code ", userId=N"} 拼在问题尾部，当前主链路无生产方，ItineraryVersionPortImpl
 *       解析时仍防御性截取）</li>
 * </ul>
 */
public final class Markers {

    private Markers() {
    }

    /** 用户当前问题段（composed 上下文最后一段；截取其后内容即用户原始输入）。 */
    public static final String CURRENT_QUESTION = "【当前问题】";

    /** 会话滚动摘要段。 */
    public static final String SESSION_SUMMARY = "【会话摘要】";

    /**
     * 当前日期行（ChatRoutingStep 生产，拼在【当前问题】段之后）。
     * M28-11：ItineraryVersionPortImpl 消费——从【当前问题】尾段截取用户原始
     * 输入时以此为右边界（用户消息本身不含此行）。
     */
    public static final String CURRENT_DATE = "【当前日期】";

    /** 会话知识参考段。 */
    public static final String SESSION_KNOWLEDGE = "【会话知识参考】";
    /** M23（E1）：锚定行程段——生产方 SessionAnchorStore.renderSection，消费方各 Agent prompt。 */
    public static final String ANCHORED_ITINERARIES = "【锚定行程】";
    /** M23b（E4）：本轮偏好约束段——生产方 PreferenceSectionRenderer，消费方各 Agent prompt。 */
    public static final String PREFERENCE_TAGS = "【本轮偏好约束】";

    /** 知识库检索候选景点段。 */
    public static final String ATTRACTION_CANDIDATES = "【知识库检索候选景点】";

    /** 用户画像段。 */
    public static final String USER_PROFILE = "【用户画像】";

    /** 出行天气参考段。 */
    public static final String WEATHER_REFERENCE = "【出行天气参考】";

    /** 历史遗留：问题尾部可能残留的 userId 后缀（防御性截取，当前无生产方）。 */
    public static final String LEGACY_USER_ID_SUFFIX = ", userId=";
}
