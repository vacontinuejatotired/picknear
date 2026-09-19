package com.hmdp.agent.access;

/**
 * 接入层传入 Agent 运行时的请求模型。
 */
public record AgentCommand(
        String content,
        String conversationId,
        Long userId
) {
}
