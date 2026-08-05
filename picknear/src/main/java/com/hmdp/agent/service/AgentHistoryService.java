package com.hmdp.agent.service;

import com.hmdp.agent.dto.ConversationVO;
import com.hmdp.agent.dto.MessageVO;

import java.util.List;

/**
 * Agent 历史会话服务 — 会话/消息落库与查询。
 * <p>
 * 写入语义：只在一次"成功回合"结束时调用（AI 失败 / Hook BLOCK 不落库）；
 * 用户消息 + AI 回复各一条，规划中间过程（tool_call / thought / 子任务）不落库。
 * </p>
 */
public interface AgentHistoryService {

    /**
     * 记录一个成功回合：会话不存在则创建（标题=首条用户消息截断），已存在则续传并刷新活跃时间。
     *
     * @param userId           当前用户（来自会话，调用方负责从主线程捕获）
     * @param conversationId   会话 ID
     * @param userContent      用户原始消息（非 Hook 替换后文本）
     * @param assistantContent AI 最终回复（PASS=完整回复 / REPLACE=替换文本 / PLANNING=最终合并答案）
     */
    void recordTurn(Long userId, String conversationId, String userContent, String assistantContent);

    /** 当前用户会话列表（按 updated_at DESC，含 message_count） */
    List<ConversationVO> listConversations(Long userId);

    /** 某会话消息列表（按 created_at ASC）；会话不属于当前用户时返回空列表 */
    List<MessageVO> listMessages(Long userId, String conversationId);
}
