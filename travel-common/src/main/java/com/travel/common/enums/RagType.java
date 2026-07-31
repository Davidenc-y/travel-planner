package com.travel.common.enums;

/**
 * RAG 检索策略类型枚举
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public enum RagType {

    /** 单路 BM25 或单路向量 */
    NAIVE,

    /** BM25 + KNN + RRF 混合检索 */
    HYBRID,

    /** 自适应检索（借鉴 Self-RAG） */
    SELF_RAG,

    /** 查询重写检索（借鉴 Corrective-RAG） */
    CORRECTIVE_RAG
}
