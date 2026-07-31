package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    @Select("SELECT * FROM t_chat_session WHERE user_id = #{userId} AND status = 'ACTIVE' ORDER BY created_at DESC")
    List<ChatSession> findActiveByUserId(Long userId);

    @Select("SELECT * FROM t_chat_session WHERE session_id = #{sessionId}")
    ChatSession findBySessionId(String sessionId);
}
