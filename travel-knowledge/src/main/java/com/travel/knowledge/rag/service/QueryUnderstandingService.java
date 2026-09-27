package com.travel.knowledge.rag.service;

import com.travel.knowledge.rag.model.QueryIntent;

/**
 * QueryUnderstandingService 公共契约（AD-2c 接口化）。
 *
 * <p>实现见 {@link QueryUnderstandingServiceImpl}（@Service 在 Impl）；公有方法签名
 * 逐字提取（零语义变更）。包私有注入点 setSpanCollector 留守实现类。</p>
 */
public interface QueryUnderstandingService {

    /** 查询意图理解（LLM light 模型解析 query）。 */
    QueryIntent understand(String query);
}
