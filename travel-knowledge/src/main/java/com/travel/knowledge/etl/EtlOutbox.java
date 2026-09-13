package com.travel.knowledge.etl;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DG-3a：ETL 变更事件 outbox 实体（t_etl_outbox；表 DDL 见 scripts/sql/dg3_outbox.sql，
 * 由人工/审计执行——E-13）。
 *
 * <p>DG-3b 起 Import/Enrich/人工补录三更新点在 setIndexed(0) 旁同事务写入；
 * DG-3c 消费组 travel:etl:changes 消费成功后置 consumed=1（失败不 ACK 留待重投）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@TableName("t_etl_outbox")
public class EtlOutbox {

    /** 事件 id（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 变更景点 id */
    private Long attractionId;

    /** 变更类型 */
    private String changeType;

    /** 事件创建时间（DB 侧 DEFAULT CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;

    /** 消费标记（0 未消费 / 1 已消费） */
    private Integer consumed;
}
