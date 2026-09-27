package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * SQL 语句统一在 resources/mapper/ChatMessageMapper.xml（AD-1d 注解→XML，方法签名与语义逐字段不变）。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    List<ChatMessage> findBySessionId(String sessionId);
}
