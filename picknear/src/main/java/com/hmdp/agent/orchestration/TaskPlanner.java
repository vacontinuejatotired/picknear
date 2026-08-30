package com.hmdp.agent.orchestration;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.orchestration.confirm.ConfirmFlowManager;
import com.hmdp.agent.orchestration.support.AgentContextResolver;
import com.hmdp.agent.orchestration.support.HistoryAggregator;
import com.hmdp.agent.service.AgentHistoryService;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.task.model.TaskSnapshot;
import com.hmdp.agent.stream.SseUtils;
import io.micrometer.observation.Observation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 子任务规划器（编排门面）。
 * <p>
 * 只负责异步入口、根 span 挂载与收尾；主循环拆到 {@link MultiRoundOrchestrator}，
 * CONFIRM 中间态拆到 {@link ConfirmFlowManager}。
 * </p>
 * <p>
 * 方法命名：
 * <ul>
 *   <li>{@link #submit} — 异步提交新会话的规划执行</li>
 *   <li>{@link #resume} — 异步恢复 CONFIRM 暂停后的执行</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class TaskPlanner {

    @Resource(name = "subtaskExecutor")
    private Executor subtaskExecutor;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private AgentHistoryService historyService;

    @Resource
    private ConfirmFlowManager confirmFlowManager;

    @Resource
    private HistoryAggregator historyAggregator;

    @Resource
    private MultiRoundOrchestrator multiRoundOrchestrator;

    /**
     * 异步提交新会话的规划执行。
     */
    public void submit(String input, String aiResponse, AgentContext ctx,
                       SseEmitter emitter) {
        submit(input, aiResponse, ctx, null, emitter);
    }

    /**
     * 异步提交（显式携带根 span）。
     */
    public void submit(String input, String aiResponse, AgentContext ctx,
                       AgentSpan explicitRootSpan, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            AgentSpan rootSpan = AgentContextResolver.resolveRootSpan(ctx, explicitRootSpan);
            if (rootSpan == null && explicitRootSpan != null) {
                rootSpan = explicitRootSpan;
            }
            try (Observation.Scope scope = rootSpan != null ? agentTracer.resume(rootSpan)
                    : Observation.Scope.NOOP) {
                try {
                    String result = multiRoundOrchestrator.runLoop(input, aiResponse, ctx, emitter);
                    completeTurn(ctx, result, emitter);
                } catch (Exception e) {
                    log.error("TaskPlanner 执行异常", e);
                    SseUtils.safeSend(emitter, SseUtils.escapeJson("处理中断：" + e.getMessage()));
                    emitter.completeWithError(e);
                }
            }
        }, subtaskExecutor);
    }

    private void completeTurn(AgentContext ctx, String result, SseEmitter emitter) {
        boolean paused = ctx != null && ctx.attribute(AgentContext.ATTR_PENDING_SNAPSHOT) != null;
        Long userId = AgentContextResolver.resolveUserId(ctx, null);
        if (!paused && userId != null) {
            try {
                historyService.recordTurn(userId, AgentContextResolver.resolveConversationId(ctx, null),
                        AgentContextResolver.resolveOriginalContent(ctx, null), result);
            } catch (Exception e) {
                log.error("记录 PLANNING 回合历史失败, conversationId={}",
                        AgentContextResolver.resolveConversationId(ctx, null), e);
            }
        }
        if (!paused) {
            SseUtils.safeSend(emitter, SseUtils.escapeJson(result));
        }
        emitter.complete();
    }

    /**
     * 异步恢复 CONFIRM 暂停后的执行。
     */
    public void resume(TaskSnapshot snapshot, AgentContext ctx, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            AgentSpan rootSpan = AgentContextResolver.resolveRootSpan(ctx, snapshot != null ? snapshot.getRootSpan() : null);
            try (Observation.Scope scope = rootSpan != null ? agentTracer.resume(rootSpan)
                    : Observation.Scope.NOOP) {
                try {
                    String result = resumePlan(snapshot, ctx, emitter);
                    completeTurn(ctx, result, emitter);
                } catch (Exception ex) {
                    log.error("从快照恢复执行异常", ex);
                    SseUtils.safeSend(emitter, SseUtils.escapeJson("恢复执行失败：" + ex.getMessage()));
                    emitter.completeWithError(ex);
                }
            }
        }, subtaskExecutor);
    }

    private String resumePlan(TaskSnapshot snapshot, AgentContext ctx, SseEmitter emitter) {
        String toolResult = confirmFlowManager.executeApprovedTool(snapshot, ctx, emitter);
        TaskReport history = new TaskReport();
        confirmFlowManager.seedCompletedHistory(history, snapshot, toolResult);
        String currentResponse = snapshot.getPartialResponse() != null ? snapshot.getPartialResponse() : "";
        if (toolResult != null && !toolResult.isBlank()) {
            currentResponse = currentResponse + "\n\n" + toolResult;
        }
        return multiRoundOrchestrator.runLoopWithHistory(snapshot.getOriginalInput(), currentResponse, ctx, history, emitter);
    }
}
