package com.travel.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL 语句统一在 resources/mapper/ChatSessionMapper.xml（AD-1f 注解→XML，方法签名与语义逐字段不变；
 * findArchivedWithoutFinal 的 &lt; 比较符以 XML 转义等价表达）。
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * M6-49：活跃会话按最后一条消息时间倒序（最近消息置顶）；无消息会话
     * （last_message_at 为 NULL）排最后，同时间按创建时间倒序。
     */
    List<ChatSession> findActiveByUserId(Long userId);

    ChatSession findBySessionId(String sessionId);

    /** M4-4：条件状态迁移（乐观：from 不匹配返回 0，防并发双关） */
    int updateStatusConditional(@Param("sessionId") String sessionId,
                                @Param("from") String from, @Param("to") String to);

    /** M4-4：收口摘要持久化（幂等：仅首写，summary_final IS NULL 才更新） */
    int updateSummaryFinal(@Param("sessionId") String sessionId, @Param("text") String text);

    /** M5-1：更新会话标题（手动编辑） */
    int updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);

    /** M5-1：首条消息标题联动——仅当标题为空或仍为默认值时更新，避免覆盖手动标题 */
    int updateTitleIfDefault(@Param("sessionId") String sessionId,
                             @Param("title") String title,
                             @Param("defaultTitle") String defaultTitle);

    /** M4-4：启动补偿扫描——已归档但收口未完成（排除刚 close 在途的 updatedAt 下限） */
    List<ChatSession> findArchivedWithoutFinal(@Param("updatedBefore") java.time.LocalDateTime updatedBefore,
                                               @Param("limit") int limit);
}
