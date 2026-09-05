package com.hmdp.agent.history;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 多轮记忆回放契约服务 — 「上下文压缩子系统」复用的读取点。
 * <p>
 * 取指定会话最近 windowTurns 轮完整消息（升序、role 已映射为 Spring AI 消息），
 * 无历史返回空列表。方法名与语义为跨会话契约，勿自由改名。
 * </p>
 */
public interface ConversationReplayService {

    List<Message> recentMessages(Long userId, String conversationId, int windowTurns);
}