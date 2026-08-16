package com.hmdp.agent.task;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.service.AgentHistoryService;
import com.hmdp.agent.task.model.TaskSnapshot;
import com.hmdp.agent.util.SseUtils;
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
 * 只负责异步入口、根 span 挂载与收尾；主循环拆到 {@link PlanLoopExecutor}，
 * CONFIRM 中间态拆到 {@link ConfirmFlowManager}，历史聚合拆到 {@link TaskReportHelper}，
 * 上下文解析拆到 {@link AgentContextResolver}。本类不承载业务实现，仅编排委托。
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
    private TaskReportHelper taskReportHelper;

    @Resource
    private PlanLoopExecutor planLoopExecutor;

    /**
     * 异步入口：在 subtaskExecutor 上执行规划，不阻塞 SSE 主线程。
     * <p>
     * 观测：入口 resume 根 span（跨线程传播，架构文档 §6.2）——
     * 之后主循环内的 round/plan/subagent 等 span 自动挂到会话树。
     * </p>
     */
    public void planAndExecuteAsync(String input, String aiResponse, AgentContext ctx,
                                    SseEmitter emitter) {
        planAndExecuteAsync(input, aiResponse, ctx, null, emitter);
    }

    /**
     * 异步入口重载：显式携带根 span（快照恢复路径 ctx=null，靠此参数恢复挂载）。
     */
    public void planAndExecuteAsync(String input, String aiResponse, AgentContext ctx,
                                    AgentSpan explicitRootSpan, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            // 根 span 优先级：AgentContext（入口创建，Propagator 传播）→ 参数 ctx → explicit
            AgentSpan rootSpan = AgentContextResolver.resolveRootSpan(ctx, explicitRootSpan);
            if (rootSpan == null && explicitRootSpan != null) {
                rootSpan = explicitRootSpan; // 断链修复（2026-08-04）：快照携带的根 span 兜底
            }
            try (Observation.Scope scope = rootSpan != null ? agentTracer.resume(rootSpan)
                    : Observation.Scope.NOOP) {
                try {
                    String result = planLoopExecutor.planAndExecute(input, aiResponse, ctx, emitter);
                    completeTurn(ctx, result, emitter);
                } catch (Exception e) {
                    log.error("TaskPlanner 执行异常", e);
                    SseUtils.safeSend(emitter, SseUtils.escapeJson("处理中断：" + e.getMessage()));
                    emitter.completeWithError(e);
                }
            }
        }, subtaskExecutor);
    }

    /**
     * 正常收尾：记录历史回合（仅完整回合）+ 推送最终回复 + 结束 SSE。
     * <p>
     * CONFIRM 暂停路径（pendingSnapshot != null）跳过：回合未完成，
     * 不落库、不推送尾文本（确认事件已是最后一个数据），仅结束流。
     * </p>
     */
    private void completeTurn(AgentContext ctx, String result, SseEmitter emitter) {
        boolean paused = ctx != null && ctx.attribute(AgentContext.ATTR_PENDING_SNAPSHOT) != null;
        // 上下文来源：AgentContext（Propagator 传播）优先，参数 ctx 兜底
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
        // 根 span 收敛由 ObservedSseEmitter 负责（complete/completeWithError/容器回调/兜底 TTL）
        emitter.complete();
    }

    /**
     * 从快照恢复执行（用户确认通过后由 /agent/confirm SSE 续流调用）。
     * <p>
     * 顺序决定成败（三轮审查定稿）：
     * ① callBypass 先执行待审批工具（守卫已批准，不再二次投票）；
     * ② 用 completedTools ∪ 待审批工具 预置 history（全部 COMPLETED）——
     *    待审批工具从未完成，不显式加入会被重新规划 → 再次 CONFIRM → 无限循环；
     * ③ 然后才进入正常规划循环，decompose 的 validatePlan 会过滤已完成工具。
     * </p>
     */
    public void resumeFromSnapshot(TaskSnapshot snapshot, AgentContext ctx, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            // 根 span 优先级：AgentContext（confirm 入口重建，Propagator 传播）→ 参数 ctx → 快照
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

    /**
     * 恢复编排：① 执行待审批工具 → ② 预置已完成历史 → ③ 续跑主循环。
     * <p>
     * ③ 委托 {@link PlanLoopExecutor}（本类唯一的主循环调用点，避免恢复侧持有循环组件引用造成循环依赖）。
     * </p>
     */
    private String resumePlan(TaskSnapshot snapshot, AgentContext ctx, SseEmitter emitter) {
        // ① 先执行待审批工具
        String toolResult = confirmFlowManager.executeApprovedTool(snapshot, ctx, emitter);
        // ② 预置历史
        TaskReport history = new TaskReport();
        confirmFlowManager.seedCompletedHistory(history, snapshot, toolResult);
        // ③ 续接回复：暂停前的 partial + 工具结果
        String currentResponse = snapshot.getPartialResponse() != null ? snapshot.getPartialResponse() : "";
        if (toolResult != null && !toolResult.isBlank()) {
            currentResponse = currentResponse + "\n\n" + toolResult;
        }
        return planLoopExecutor.planAndExecute(snapshot.getOriginalInput(), currentResponse, ctx, history, emitter);
    }
}
