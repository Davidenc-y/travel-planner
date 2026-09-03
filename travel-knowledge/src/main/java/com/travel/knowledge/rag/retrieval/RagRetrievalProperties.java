package com.travel.knowledge.rag.retrieval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M8-9e：检索层配置（对应 yml {@code travel.rag.retrieval.*}）。
 *
 * <p>步骤 1：city 从“只过滤”升级为“过滤 + 参与评分”，修复名称/描述不含查询词
 * 但城市命中文档（如故宫博物院）的 BM25 召回缺口；
 * 步骤 2：PLANNING 类查询规则化扩展检索文本（零 LLM，同时作用于 BM25 与 KNN）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.retrieval")
public class RagRetrievalProperties {

    /** city 参与评分时的底分 boost（constant 加分，不影响文本相对顺序） */
    private double cityScoreBoost = 0.5;

    /** 规则化意图扩展总开关（生产默认关，local 灰度开） */
    private boolean planningExpansionEnabled = false;

    /** 触发扩展的规划类关键词（命中任一且 city 非空、type 为空才扩展） */
    private List<String> planningTriggerKeywords = List.of(
            "规划", "安排", "行程", "攻略", "几日游", "一日游", "两日游", "三日游",
            "旅游", "路线", "推荐");

    /** 追加到检索文本的扩展词（已包含的词不重复追加） */
    private List<String> planningExpansionWords = List.of("旅游", "景点", "推荐", "攻略");
}
