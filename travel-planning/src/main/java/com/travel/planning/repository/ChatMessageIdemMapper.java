package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.ChatMessageIdem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天消息幂等登记 Mapper（M4-3/P0-3）。
 */
@Mapper
public interface ChatMessageIdemMapper extends BaseMapper<ChatMessageIdem> {
}
