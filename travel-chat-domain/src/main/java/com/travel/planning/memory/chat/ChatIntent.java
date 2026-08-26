package com.travel.planning.memory.chat;

/**
 * 对话意图（F85 第二步，入口意图分类）。
 *
 * <p>PLANNING/REFINE → Supervisor 全图；RECALL → 轻量回顾管线；
 * PROFILE/CHAT/FUNCTIONAL → 直答（不触发 supervisor 与知识预检索）。</p>
 */
public enum ChatIntent {
    PLANNING, REFINE, RECALL, PROFILE, CHAT, FUNCTIONAL
}
