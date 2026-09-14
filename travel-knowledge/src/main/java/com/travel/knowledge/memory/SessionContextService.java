package com.travel.knowledge.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 会话级知识服务（Phase C/F78，C2）。
 *
 * <p>接收 planning 侧提交的结构化切片：规范化 → Embedding → 双写
 * Milvus {@code session_context}（向量，id=chunkId）+ ES {@code session_context}（BM25）。
 * 检索：sessionId 过滤 + Hybrid RRF 融合，按类型优先级与 seq/时间排序，命中服从 topK。</p>
 *
 * <p>MM-4 起为分域薄壳（MM-4.2 完成收敛）：检索读取面 → {@link ContextRetriever}（MM-4.1）、
 * 写入/初始化域 → {@link ContextAssembler}（均构造注入，方法体零变更迁移）；本类仅保留
 * 四个公共方法委托，签名零变更（E-21 冻结）。</p>
 */
@Slf4j
@Service
public class SessionContextService {

    // MM-4：两域协作件（检索/写入）
    private final ContextRetriever contextRetriever;
    private final ContextAssembler contextAssembler;

    public SessionContextService(ContextRetriever contextRetriever,
                                 ContextAssembler contextAssembler) {
        this.contextRetriever = contextRetriever;
        this.contextAssembler = contextAssembler;
    }

    /**
     * 写入一条会话知识切片（规范化 + 向量化 + Milvus/ES 双写）。
     */
    public void write(SessionContextChunk chunk) {
        contextAssembler.write(chunk);
    }

    /**
     * 检索会话知识：ES BM25 + Milvus KNN → RRF 融合 → 类型优先级/seq 排序
     * （MM-4.1 起委托 {@link ContextRetriever#search}，公共签名零变更）。
     *
     * @return 切片列表（Map：chunkId/sessionId/type/seq/content/role/sourceNode/createdAt）
     */
    public List<Map<String, Object>> search(String sessionId, String query, int topK) {
        return contextRetriever.search(sessionId, query, topK);
    }

    /**
     * M4-5b：按 seq 前缀取回会话切片（二次取父）。
     *
     * <p>ES 按 sessionId(term，与 {@link #search} 同口径隔离) + seq 前缀过滤，按 seq 升序
     * 取 limit 条；content 完整返回（与写入一致的原文），供 planning 侧拼出 itinerary_day
     * 完整父视图。任何失败降级空列表（调用方保留原命中，回归零风险）。</p>
     *
     * @param sessionId 会话 id（隔离键）
     * @param seqPrefix seq 前缀（如 {@code "itin:123:"}）
     * @param limit     返回条数上限（1~100 夹逼，默认调用方传 30）
     * @return 按 seq 升序的切片列表（结构同 {@link #search} 的返回，不含 score）
     */
    public List<Map<String, Object>> findBySeqPrefix(String sessionId, String seqPrefix, int limit) {
        return contextRetriever.findBySeqPrefix(sessionId, seqPrefix, limit);
    }

    /**
     * M8-9：按 seq 前缀删除会话切片（REFINE/重生成时覆盖旧版本，避免新旧版本混叠）。
     *
     * @return ES 侧删除数（Milvus 删除数不返回）
     */
    public int deleteBySeqPrefix(String sessionId, String seqPrefix) {
        return contextRetriever.deleteBySeqPrefix(sessionId, seqPrefix);
    }
}
