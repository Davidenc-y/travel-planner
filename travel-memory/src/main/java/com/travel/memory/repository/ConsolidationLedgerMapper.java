package com.travel.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * distill 台账 Mapper（t_consolidation_ledger，AP-B1）——SQL 统一在
 * resources/mapper/ConsolidationLedgerMapper.xml（AD-1f 仓库约定）。
 *
 * <p>insert 走 MyBatis-Plus BaseMapper 通用件；自定义两方法=启动恢复与批扫清理
 * （R513~515 挂点：库失败 fail-open=P0㉘，双写不阻断内存态）。</p>
 */
@Mapper
public interface ConsolidationLedgerMapper extends BaseMapper<ConsolidationLedger> {

    /** 启动恢复：最近 limit 条（按 id 倒序=写入序；LEDGER_CAP=2000 上限由调用方给定） */
    List<ConsolidationLedger> findRecent(@Param("limit") int limit);

    /** 批扫清理幂等：按会话清空该会话台账行（重复删除返回 0 无害） */
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
