package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * M6-49：活跃会话按最后一条消息时间倒序（最近消息置顶）；无消息会话
     * （last_message_at 为 NULL）排最后，同时间按创建时间倒序。
     */
    @Select("SELECT s.*, "
            + "(SELECT MAX(m.created_at) FROM t_chat_message m WHERE m.session_id = s.session_id) AS last_message_at "
            + "FROM t_chat_session s "
            + "WHERE s.user_id = #{userId} AND s.status = 'ACTIVE' "
            + "ORDER BY last_message_at DESC, s.created_at DESC")
    List<ChatSession> findActiveByUserId(Long userId);

    @Select("SELECT * FROM t_chat_session WHERE session_id = #{sessionId}")
    ChatSession findBySessionId(String sessionId);

    /** M4-4：条件状态迁移（乐观：from 不匹配返回 0，防并发双关） */
    @Update("UPDATE t_chat_session SET status = #{to}, updated_at = NOW() WHERE session_id = #{sessionId} AND status = #{from}")
    int updateStatusConditional(@Param("sessionId") String sessionId,
                                @Param("from") String from, @Param("to") String to);

    /** M4-4：收口摘要持久化（幂等：仅首写，summary_final IS NULL 才更新） */
    @Update("UPDATE t_chat_session SET summary_final = #{text}, updated_at = NOW() WHERE session_id = #{sessionId} AND summary_final IS NULL")
    int updateSummaryFinal(@Param("sessionId") String sessionId, @Param("text") String text);

    /** M5-1：更新会话标题（手动编辑） */
    @Update("UPDATE t_chat_session SET title = #{title}, updated_at = NOW() WHERE session_id = #{sessionId}")
    int updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);

    /** M5-1：首条消息标题联动——仅当标题为空或仍为默认值时更新，避免覆盖手动标题 */
    @Update("UPDATE t_chat_session SET title = #{title}, updated_at = NOW() "
            + "WHERE session_id = #{sessionId} AND (title IS NULL OR title = #{defaultTitle})")
    int updateTitleIfDefault(@Param("sessionId") String sessionId,
                             @Param("title") String title,
                             @Param("defaultTitle") String defaultTitle);

    /** M4-4：启动补偿扫描——已归档但收口未完成（排除刚 close 在途的 updatedAt 下限） */
    @Select("SELECT * FROM t_chat_session WHERE status = 'ARCHIVED' AND summary_final IS NULL "
            + "AND updated_at < #{updatedBefore} ORDER BY updated_at ASC LIMIT #{limit}")
    List<ChatSession> findArchivedWithoutFinal(@Param("updatedBefore") java.time.LocalDateTime updatedBefore,
                                               @Param("limit") int limit);
}
