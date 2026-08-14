package com.hmdp.agent.history;

import com.hmdp.agent.service.AgentHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话历史落库器 — 最佳努力记录对话回合。
 * <p>
 * 从 AiServiceImpl 拆出（JSON/SSE 双模共用）：
 * 失败只记日志、绝不向上抛——SSE 模式下抛异常会被重试循环捕获导致重跑 LLM 浪费 token，
 * JSON 模式下会中断整个响应。历史持久化是收尾附加能力，不能影响聊天主链路。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryRecorder {

    private final AgentHistoryService historyService;

    /**
     * 最佳努力记录一个对话回合；失败静默（仅日志）。
     */
    public void recordBestEffort(Long userId, String conversationId, String userContent, String assistantContent) {
        try {
            if (userId != null && assistantContent != null && !assistantContent.isBlank()) {
                historyService.recordTurn(userId, conversationId, userContent, assistantContent);
            }
        } catch (Exception e) {
            log.error("记录会话历史失败, conversationId={}", conversationId, e);
        }
    }
}
