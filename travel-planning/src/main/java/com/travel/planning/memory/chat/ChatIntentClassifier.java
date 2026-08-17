package com.travel.planning.memory.chat;

import com.travel.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话入口意图分类器（F85 第二步）。
 *
 * <p>确定性规则优先 + LLM 兜底 + LRU 缓存，完全镜像 knowledge 侧
 * QueryUnderstandingService 已验证模式；分类失败/不确定回退 PLANNING（最重但最安全）。</p>
 */
@Slf4j
@Service
public class ChatIntentClassifier {

    private final ChatModel chatModel;
    private final ChatIntentProperties properties;
    /** 意图 LRU 缓存（access-order，容量由配置 cacheSize 控制） */
    private final Map<String, ChatIntent> cache;

    public ChatIntentClassifier(ChatModel chatModel, ChatIntentProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ChatIntent> eldest) {
                return properties.getCacheSize() > 0 && size() > properties.getCacheSize();
            }
        });
    }

    /**
     * 意图分类入口：规则优先 →（可选）LLM 兜底 → 回退 PLANNING；结果 LRU 缓存
     */
    public ChatIntent classify(String message) {
        String q = message == null ? "" : message.trim();
        if (q.isBlank()) {
            return ChatIntent.PLANNING;
        }
        if (!properties.isEnabled()) {
            return ChatIntent.PLANNING;
        }
        ChatIntent cached = cache.get(q);
        if (cached != null) {
            return cached;
        }
        ChatIntent result = ruleBased(q);
        if (result == null && properties.isLlmFallback() && chatModel != null) {
            ChatIntent llm = extractByLlm(q);
            result = llm != null ? llm : ChatIntent.PLANNING;
            log.info("[ChatIntent] LLM 判定: query={}, intent={}", q, result);
        }
        if (result == null) {
            result = ChatIntent.PLANNING;
        }
        if (properties.getCacheSize() > 0) {
            cache.put(q, result);
        }
        return result;
    }

    /**
     * 规则表（优先级从高到低）：FUNCTIONAL → PROFILE → CHAT → REFINE → RECALL。
     * 变更词（REFINE）优先于回顾词（RECALL），防止"上次行程帮我优化"误判为回顾。
     */
    static ChatIntent ruleBased(String q) {
        if (containsAny(q, "你能做什么", "有什么功能", "你是谁", "怎么用", "能干嘛", "帮助", "说明书")) {
            return ChatIntent.FUNCTIONAL;
        }
        if (containsAny(q, "画像", "我的偏好", "我的记忆", "历史行程", "我的行程",
                // 预算查询式表达（"我的预算是什么/多少"）；"按我的预算帮我规划"是 PLANNING，不能误伤
                "我的预算是什么", "我的预算是多少", "我的预算多少",
                // F66 语义迁移：偏好陈述类消息（"记住我喜欢爬山，预算8000元"）→ 直答
                "记住", "设为", "设置为")) {
            return ChatIntent.PROFILE;
        }
        if (containsAny(q, "你好", "您好", "谢谢", "再见", "在吗", "嗨")) {
            return ChatIntent.CHAT;
        }
        if (containsAny(q, "优化", "调整", "重新规划", "换成", "改成", "修改", "重新安排", "换一个")) {
            return ChatIntent.REFINE;
        }
        if (containsAny(q, "上次", "之前", "回顾", "安排了哪些", "都去了", "去过", "行程记录")) {
            return ChatIntent.RECALL;
        }
        return null;
    }

    /**
     * LLM 结构化抽取；返回 null 表示失败/非法（走回退）
     */
    private ChatIntent extractByLlm(String query) {
        try {
            String prompt = """
                    你是对话意图分类器。从用户消息中判定意图，只输出 JSON，不要任何解释或代码块标记。
                    用户消息：%s
                    输出格式：{"intent": "PLANNING|REFINE|RECALL|PROFILE|CHAT|FUNCTIONAL"}
                    定义：
                    - PLANNING：新行程规划/景点推荐/攻略（如"帮我规划北京三日游"）
                    - REFINE：修改已有行程/预算调整后重新规划（如"上次行程帮我优化一下"）
                    - RECALL：回顾本会话已有事实（如"我上次北京3日游都安排了哪些景点？"）
                    - PROFILE：画像/偏好/记忆查询或偏好陈述（如"我的旅行画像里有什么？""记住我喜欢爬山"）
                    - CHAT：寒暄闲聊（如"你好""谢谢"）
                    - FUNCTIONAL：功能咨询（如"你能做什么？"）
                    若消息混合 REFINE 与 RECALL，判 REFINE。
                    """.formatted(query);
            String response = chatModel.call(prompt);
            String json = extractJson(response);
            if (json == null) {
                return null;
            }
            IntentRaw raw = JsonUtils.fromJson(json, IntentRaw.class);
            if (raw == null || raw.intent() == null) {
                return null;
            }
            try {
                return ChatIntent.valueOf(raw.intent().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        } catch (Exception e) {
            log.warn("[ChatIntent] LLM 判定失败，回退: {}", e.getMessage());
            return null;
        }
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String t : tokens) {
            if (text.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 从 LLM 响应中提取 JSON（容忍 ```json 代码块与前后噪声） */
    private static String extractJson(String response) {
        if (response == null) {
            return null;
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return response.substring(start, end + 1);
    }

    /** LLM JSON 反序列化中间对象 */
    private record IntentRaw(String intent) {
    }
}
