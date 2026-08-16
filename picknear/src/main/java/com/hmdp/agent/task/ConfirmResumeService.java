package com.hmdp.agent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.entity.AgentApproval;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.stream.SseSessionFactory;
import com.hmdp.agent.stream.SseSessionFactory.ChatSseSession;
import com.hmdp.agent.task.model.TaskSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * CONFIRM 审批通过后的 SSE 续流装配（从 ChatController.confirm 下沉）。
 * <p>
 * 职责：打开续流会话（新根 span）→ 注册 emitter 回调 → 推送 conversationId →
 * 从审批记录重建 {@link TaskSnapshot} 与请求级 {@link AgentContext} → 委托
 * {@link TaskPlanner#resumeFromSnapshot} 恢复执行并继续规划。
 * </p>
 * <p>
 * 与 {@link ConfirmFlowManager} 的分工：后者管「暂停 → 审批」中间态与已批工具直调，
 * 本组件管「审批通过 → 续流恢复」的会话装配与编排入口（ConfirmFlowManager 注释中的
 * "恢复后的续跑由调用方编排"即指此处）。
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

    /**
     * 审批通过后恢复执行（SSE 续流）。
     *
     * @param approval 已 APPROVED 的审批记录（含快照所需全部字段）
     * @param userId   当前操作者（记录归属校验已由调用方完成）
     * @return SSE emitter；推送 conversationId 失败时已 completeWithError 并返回 null
     */
    public SseEmitter resume(AgentApproval approval, Long userId) {
        log.info("确认续流：confirmId={}, tool={}", approval.getConfirmId(), approval.getToolName());

        // 会话装配：新会话根 span（续流挂在 confirm SSE 的生命周期上，避免挂旧 session 变孤儿）
        ChatSseSession session = sseSessionFactory.open(approval.getConversationId(), userId);
        AgentSpan root = session.root();
        SseEmitter emitter = session.emitter();

        emitter.onCompletion(() ->
                log.info("确认续流完成, confirmId={}, thread={}", approval.getConfirmId(), Thread.currentThread().getName()));
        emitter.onTimeout(() -> log.warn("确认续流超时, confirmId={}", approval.getConfirmId()));
        emitter.onError(ex -> log.error("确认续流异常, confirmId={}", approval.getConfirmId(), ex));

        // 先推 conversationId（元事件，前端据此识别，不混入回答文本）
        try {
            sseSessionFactory.sendConversationId(emitter, approval.getConversationId());
        } catch (IOException e) {
            log.error("推送 conversationId 失败", e);
            emitter.completeWithError(e);
            return null;
        }

        TaskSnapshot snapshot = TaskSnapshot.fromApproval(approval, objectMapper);

        // 请求级 AgentContext：从审批记录重建（跨请求持久化上下文 → 请求级上下文），
        // resumeFromSnapshot 提交到 subtaskExecutor 时由 Propagator 自动携带
        AgentContext agentCtx = AgentContext.builder()
                .userId(approval.getUserId())
                .conversationId(approval.getConversationId())
                .originalInput(approval.getOriginalInput())
                .rootSpan(root)
                .build();
        AgentContextHolder.set(agentCtx);
        try {
            taskPlanner.resumeFromSnapshot(snapshot, agentCtx, emitter);
        } finally {
            AgentContextHolder.clear();
        }
        return emitter;
    }
}
