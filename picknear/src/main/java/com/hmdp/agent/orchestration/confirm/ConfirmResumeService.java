package com.hmdp.agent.orchestration.confirm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.entity.AgentApproval;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.orchestration.TaskPlanner;
import com.hmdp.agent.stream.SseSessionFactory;
import com.hmdp.agent.stream.SseSessionFactory.ChatSseSession;
import com.hmdp.agent.task.model.TaskSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * CONFIRM 审批通过后的 SSE 续流装配。
 * <p>
 * 职责：打开续流会话 → 注册 emitter 回调 → 推送 conversationId →
 * 从审批记录重建 TaskSnapshot 与 AgentContext → 委托 TaskPlanner 恢复执行。
 * </p>
 */
@Slf4j
@Component
public class ConfirmResumeService {

    @Resource
    private SseSessionFactory sseSessionFactory;

    @Resource
    private TaskPlanner taskPlanner;

    @Resource
    private ObjectMapper objectMapper;

    public SseEmitter resume(AgentApproval approval, Long userId) {
        log.info("确认续流：confirmId={}, tool={}", approval.getConfirmId(), approval.getToolName());

        ChatSseSession session = sseSessionFactory.open(approval.getConversationId(), userId);
        AgentSpan root = session.root();
        SseEmitter emitter = session.emitter();

        emitter.onCompletion(() ->
                log.info("确认续流完成, confirmId={}, thread={}", approval.getConfirmId(), Thread.currentThread().getName()));
        emitter.onTimeout(() -> log.warn("确认续流超时, confirmId={}", approval.getConfirmId()));
        emitter.onError(ex -> log.error("确认续流异常, confirmId={}", approval.getConfirmId(), ex));

        try {
            sseSessionFactory.sendConversationId(emitter, approval.getConversationId());
        } catch (IOException e) {
            log.error("推送 conversationId 失败", e);
            emitter.completeWithError(e);
            return null;
        }

        TaskSnapshot snapshot = TaskSnapshot.fromApproval(approval, objectMapper);

        AgentContext agentCtx = AgentContext.builder()
                .userId(approval.getUserId())
                .conversationId(approval.getConversationId())
                .originalInput(approval.getOriginalInput())
                .rootSpan(root)
                .build();
        AgentContextHolder.set(agentCtx);
        try {
            taskPlanner.resume(snapshot, agentCtx, emitter);
        } finally {
            AgentContextHolder.clear();
        }
        return emitter;
    }
}
