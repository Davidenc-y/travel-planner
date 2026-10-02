package com.travel.memory.repository;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * distill 台账实体（t_consolidation_ledger，AP-B1/GP-6——重启丢工作转持久化）。
 *
 * <p>DDL 单源=方案 docs/zcode/20261002_review/01-AP系列调度短路定位与Tavily配额补齐实施方案.md
 * §二乙线（逐字）；scripts/init_sql/incremental_20261002_memory_ledger.sql 与
 * scripts/init_mysql.sql 追加块同源（三件套对账锚，六列与字段逐一对齐）。</p>
 *
 * <p><b>独立形态（不继承 BaseEntity）</b>：BaseEntity 携带 updatedAt
 * （{@code FieldFill.INSERT_UPDATE}，MybatisPlusConfig.insertFill 会填充）而本表 DDL
 * 无 updated_at 列——继承即插入报未知列；故本实体自带 id/createdAt 与 DDL 对齐。</p>
 *
 * <p>消费方=travel-chat-domain MemoryConsolidationServiceImpl 台账双写（R513~515：
 * recordChunk 落库/启动恢复 LEDGER_CAP=2000/批扫清理幂等；库失败 fail-open=P0㉘）。</p>
 */
@Data
@TableName("t_consolidation_ledger")
public class ConsolidationLedger implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 id（DDL: session_id VARCHAR(64) NOT NULL） */
    private String sessionId;

    /** chunk 类型（DDL: chunk_type VARCHAR(32) NOT NULL） */
    private String chunkType;

    /** 序列指纹（DDL: seq_hash VARCHAR(64) NOT NULL） */
    private String seqHash;

    /** 内容指纹 12 位（DDL: content_hash CHAR(12) NOT NULL；与 SessionKnowledgeWriter chunkId 同口径） */
    private String contentHash;

    /** 创建时刻（DDL: created_at DATETIME NOT NULL） */
    private LocalDateTime createdAt;
}
