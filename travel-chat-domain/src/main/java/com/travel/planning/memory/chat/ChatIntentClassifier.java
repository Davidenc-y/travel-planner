package com.travel.planning.memory.chat;

import com.travel.common.util.JsonUtils;
import com.travel.planning.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.travel.common.util.AgentOutputUtils.containsAny;

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
    // M3-20：Prompt 模板外置（P1-17）
    private final PromptTemplates promptTemplates;
    /** 意图 LRU 缓存（access-order，容量由配置 cacheSize 控制） */
    private final Map<String, ChatIntent> cache;

    public ChatIntentClassifier(ChatModel chatModel, ChatIntentProperties properties,
                                PromptTemplates promptTemplates) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.promptTemplates = promptTemplates;
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
        // M6-56/T6：缓存键哈希化——避免明文原文常驻内存（隐私面收窄）；
        // SHA-256 碰撞可忽略，哈希化不影响规则/LLM 判定本身
        String cacheKey = cacheKey(q);
        ChatIntent cached = cache.get(cacheKey);
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
            cache.put(cacheKey, result);
        }
        return result;
    }

    private static String cacheKey(String q) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(q.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return q; // 防御回退（不影响正确性，仅隐私面不变）
        }
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
            String prompt = promptTemplates.intentClassify().formatted(query);
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

    /** 从 LLM 响应中提取 JSON（容忍 ```json 代码块与前后噪声） */
    private static String extractJson(String response) {
        return com.travel.common.util.AgentOutputUtils.extractJson(response);
    }

    /** LLM JSON 反序列化中间对象 */
    private record IntentRaw(String intent) {
    }
}
