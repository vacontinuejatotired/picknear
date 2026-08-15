package com.hmdp.agent.task;

import com.hmdp.agent.config.FeatureProperties;
import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.guard.ConfirmRequiredException;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.guard.ToolInvocationContext;
import com.hmdp.agent.hook.ChatContext;
import com.hmdp.agent.legacy.task.TaskExecutor;
import com.hmdp.agent.legacy.task.TaskQueue;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.plan.PlanOutcome;
import com.hmdp.agent.plan.PlanRequest;
import com.hmdp.agent.plan.PlanRouter;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.service.AgentHistoryService;
import com.hmdp.agent.service.ApprovalService;
import com.hmdp.agent.subagent.SubTaskAgent;
import com.hmdp.agent.subagent.callback.SseSubAgentCallback;
import com.hmdp.agent.subagent.model.SubTaskExecution;
import com.hmdp.agent.subagent.model.SubTaskPlan;
import com.hmdp.agent.subagent.model.SubTaskResult;
import com.hmdp.agent.subagent.prompt.SubAgentPromptBuilder;
import com.hmdp.agent.tool.ToolBeanCollector;
import com.hmdp.agent.util.SseEventConstants;
import com.hmdp.agent.util.SseUtils;
import io.micrometer.observation.Observation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 子任务规划器（编排层）。
 * <p>
 * 核心循环：decompose() -> execute() -> merge()，最多 {@link #MAX_ROUNDS} 轮。
 * 规划细节（目录构建/意图树/解析校验）全部下沉到 {@link PlanRouter} 策略，
 * 本类只做编排与观测。execute() 阶段根据 {@code feature.subagent.enabled} 走 SubTaskAgent 或原 TaskExecutor。
 * </p>
 */
@Slf4j
@Component
public class TaskPlanner {

    private static final int MAX_ROUNDS = 5;
    private static final long TASK_TIMEOUT_MS = 5_000L;

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    @Qualifier("aliibabaChatClient")
    private ChatClient chatClient;

    @Resource(name = "subtaskExecutor")
    private Executor subtaskExecutor;

    @Resource
    private SubTaskAgent subTaskAgent;

    @Resource
    private FeatureProperties featureProperties;

    @Resource
    private SubTaskProperties subTaskProperties;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private AgentHistoryService historyService;

    @Resource
    private ApprovalService approvalService;

    @Resource
    private PromptService promptService;

    @Resource
    private PlanRouter planRouter;

    // ============================================================
    // 异步段上下文解析（第 2 步收敛）：AgentContext（Propagator 传播）优先，ChatContext 兜底
    // ============================================================

    /**
     * 解析用户 ID：优先 AgentContextHolder（请求入口创建、异步边界 Propagator 传播），
     * 未设置时回退 ChatContext（直调/测试路径），再兜底 fallback（审批记录/快照）。
     */
    private static Long resolveUserId(ChatContext ctx, Long fallback) {
        AgentContext agentCtx = AgentContextHolder.get();
        if (agentCtx != null && agentCtx.userId() != null) {
            return agentCtx.userId();
        }
        if (ctx != null && ctx.getUserId() != null) {
            return ctx.getUserId();
        }
        return fallback;
    }

    /**
     * 解析会话 ID：优先级同 {@link #resolveUserId}。
     */
    private static String resolveConversationId(ChatContext ctx, String fallback) {
        AgentContext agentCtx = AgentContextHolder.get();
        if (agentCtx != null && agentCtx.conversationId() != null) {
            return agentCtx.conversationId();
        }
        if (ctx != null && ctx.getConversationId() != null) {
            return ctx.getConversationId();
        }
        return fallback;
    }

    /**
     * 解析原始输入：优先级同 {@link #resolveUserId}（快照恢复时 ctx 无原文，取 fallback）。
     */
    private static String resolveOriginalContent(ChatContext ctx, String fallback) {
        AgentContext agentCtx = AgentContextHolder.get();
        if (agentCtx != null && agentCtx.originalInput() != null) {
            return agentCtx.originalInput();
        }
        if (ctx != null && ctx.getOriginalContent() != null) {
            return ctx.getOriginalContent();
        }
        return fallback;
    }

    /**
     * 解析根 span：优先级同 {@link #resolveUserId}（explicitRootSpan 由调用方决定兜底顺序）。
     */
    private static AgentSpan resolveRootSpan(ChatContext ctx, AgentSpan fallback) {
        AgentContext agentCtx = AgentContextHolder.get();
        if (agentCtx != null && agentCtx.rootSpan() != null) {
            return agentCtx.rootSpan();
        }
        if (ctx != null && ctx.getRootSpan() != null) {
            return ctx.getRootSpan();
        }
        return fallback;
    }

    /**
     * 异步入口：在 subtaskExecutor 上执行规划，不阻塞 SSE 主线程。
     * <p>
     * 观测：入口 resume 根 span（跨线程传播，架构文档 §6.2）——
     * 之后主循环内的 round/plan/subagent 等 span 自动挂到会话树。
     * </p>
     */
    public void planAndExecuteAsync(String input, String aiResponse, ChatContext ctx,
                                    SseEmitter emitter) {
        planAndExecuteAsync(input, aiResponse, ctx, null, emitter);
    }

    /**
     * 异步入口重载：显式携带根 span（快照恢复路径 ctx=null，靠此参数恢复挂载）。
     */
    public void planAndExecuteAsync(String input, String aiResponse, ChatContext ctx,
                                    AgentSpan explicitRootSpan, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            // 根 span 优先级：AgentContext（入口创建，Propagator 传播）→ ChatContext → explicit 参数
            AgentSpan rootSpan = resolveRootSpan(ctx, explicitRootSpan);
            if (rootSpan == null && explicitRootSpan != null) {
                rootSpan = explicitRootSpan; // 断链修复（2026-08-04）：快照携带的根 span 兜底
            }
            try (Observation.Scope scope = rootSpan != null ? agentTracer.resume(rootSpan)
                    : Observation.Scope.NOOP) {
                try {
                    String result = planAndExecute(input, aiResponse, ctx, emitter);
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
     * CONFIRM 暂停路径（ctx.pendingSnapshot != null）跳过：回合未完成，
     * 不落库、不推送尾文本（确认事件已是最后一个数据），仅结束流。
     * </p>
     */
    private void completeTurn(ChatContext ctx, String result, SseEmitter emitter) {
        boolean paused = ctx != null && ctx.getPendingSnapshot() != null;
        // 上下文来源：AgentContext（Propagator 传播）优先，ChatContext 兜底
        Long userId = resolveUserId(ctx, null);
        if (!paused && userId != null) {
            try {
                historyService.recordTurn(userId, resolveConversationId(ctx, null),
                        resolveOriginalContent(ctx, null), result);
            } catch (Exception e) {
                log.error("记录 PLANNING 回合历史失败, conversationId={}",
                        resolveConversationId(ctx, null), e);
            }
        }
        if (!paused) {
            SseUtils.safeSend(emitter, SseUtils.escapeJson(result));
        }
        // 根 span 收敛由 ObservedSseEmitter 负责（complete/completeWithError/容器回调/兜底 TTL）
        emitter.complete();
    }

    /**
     * 主循环：拆解 -> 执行 -> 聚合，重复至多 MAX_ROUNDS 轮。
     */
    public String planAndExecute(String input, String aiResponse, ChatContext ctx,
                                 SseEmitter emitter) {
        return planAndExecute(input, aiResponse, ctx, new TaskReport(), emitter);
    }

    /**
     * 主循环（可携带预置历史的入口，resume 用）。
     */
    private String planAndExecute(String input, String aiResponse, ChatContext ctx,
                                  TaskReport history, SseEmitter emitter) {
        String currentResponse = aiResponse;
        var toolCallbacks = toolBeanCollector.getToolCallbacks();
        boolean useSubAgent = featureProperties.getSubagent().isEnabled();
        // 异步段上下文（第 2 步收敛）：AgentContext 优先，ChatContext 兜底——本循环内统一取值
        Long userId = resolveUserId(ctx, null);
        String conversationId = resolveConversationId(ctx, null);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            int r = round + 1;
            log.info("========== [Round {}] 1) 规划拆解 ==========", r);

            // 观测：每轮一个 round span（名称固定，semantic 区分轮次，架构文档 §5.1）
            try (AgentSpan roundSpan = agentTracer.start(AgentSpanSpec.ROUND, String.valueOf(r))) {
                try {
                    // decompose() 只返回 TOOL_CALL，不再追加 LLM_REASON
                    List<SubTask> tasks = decompose(input, currentResponse, toolCallbacks, history,
                            userId);
                    roundSpan.set(AgentField.TOOL_COUNT, String.valueOf(tasks.size()));
                    if (tasks.isEmpty()) {
                        roundSpan.set(AgentField.PLAN_VALID, "false");
                        log.warn("========== [Round {}] 2) 无需执行, 保持原回复 ==========", r);
                        return currentResponse;
                    }
                    roundSpan.set(AgentField.PLAN_VALID, "true");

                    // 推送：规划阶段
                    String planDesc = tasks.stream()
                            .map(SubTask::getDescription)
                            .collect(Collectors.joining("、"));
                    SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_PLANNING,
                            SseEventConstants.TEXT_PLANNING_PREFIX + planDesc));

                    if (useSubAgent) {
                        // ================================================
                        // 子 Agent 路径（观测：agent.subagent，整段）
                        // ================================================
                        log.info("========== [Round {}] 2) 子 Agent 执行 ==========", r);

                        try (AgentSpan subagentSpan = agentTracer.start(AgentSpanSpec.SUBAGENT, null)) {
                            SubTaskPlan plan = SubTaskPlan.builder()
                                    .userInput(input)
                                    .currentResponse(currentResponse)
                                    .tasks(tasks)
                                    .historySummary(buildHistorySummary(history))
                                    .userId(userId)
                                    .conversationId(conversationId)
                                    .round(round)
                                    .build();

                            SubTaskExecution execution = SubTaskExecution.builder()
                                    .plan(plan)
                                    .callback(new SseSubAgentCallback(emitter))
                                    .properties(subTaskProperties)
                                    .startTimeMs(System.currentTimeMillis())
                                    .build();

                            SubTaskResult result = subTaskAgent.execute(execution);

                            subagentSpan.set(AgentField.TOOL_COUNT, String.valueOf(result.getRawResults() != null
                                    ? result.getRawResults().size() : 0));
                            currentResponse = result.getSummary();
                            recordHistory(history, result);
                        }
                    } else {
                        // ================================================
                        // 回退路径：原 TaskExecutor 串行直调（工具级 span 在 TaskExecutor 内）
                        // ================================================
                        log.info("========== [Round {}] 2) 回退模式：TaskExecutor 执行 ==========", r);

                        // 手动追加 LLM_REASON（validatePlan 已不再追加）
                        List<SubTask> execTasks = new ArrayList<>(tasks);
                        execTasks.add(SubTask.builder()
                                .id(UUID.randomUUID().toString())
                                .description("基于以上数据生成最终结论")
                                .type(TaskType.LLM_REASON)
                                .dependsOn(tasks.stream().map(SubTask::getId).toList())
                                .status(SubTaskStatus.PENDING)
                                .build());

                        // 推送：逐任务 RUNNING
                        for (SubTask t : execTasks) {
                            if (t.getType() != TaskType.TOOL_CALL) continue;
                            SseUtils.safeSend(emitter, SseUtils.stepEvent(t.getToolName(), SseEventConstants.TOOL_RUNNING));
                        }

                        TaskQueue queue = new TaskQueue(execTasks);
                        TaskExecutor executor = new TaskExecutor(toolCallbacks,
                                userId,
                                conversationId,
                                chatClient, TASK_TIMEOUT_MS, agentTracer, promptService);
                        executor.executeAll(queue);

                        // 推送：逐任务完成/失败
                        for (SubTask t : queue.getAllTasks()) {
                            if (t.getToolName() == null) continue;
                            String st = t.getStatus() == SubTaskStatus.COMPLETED
                                    ? SseEventConstants.TOOL_COMPLETED
                                    : SseEventConstants.TOOL_FAILED;
                            SseUtils.safeSend(emitter, SseUtils.stepEvent(t.getToolName(), st));
                        }

                        // 记录历史
                        history.record(execTasks);

                        // 聚合
                        log.info("========== [Round {}] 3) 聚合结论 ==========", r);
                        SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_MERGING, SseEventConstants.TEXT_MERGING_FALLBACK));
                        currentResponse = merge(currentResponse, queue);
                        SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_MERGING, SseEventConstants.TEXT_MERGING_DONE));
                    }
                } catch (ConfirmRequiredException e) {
                    // CONFIRM 审批：保存快照 → 建审批记录 → 推确认事件 → 暂停本轮流
                    return handleConfirmPause(e, input, currentResponse, history, round, ctx, emitter);
                }
            }
        }

        return currentResponse;
    }

    // ============================================================
    // decompose 部分（规划细节下沉到 PlanRouter，本层只做编排 + 观测）
    // ============================================================

    /**
     * 拆解子任务：委托激活的 {@link PlanRouter} 策略产出 TOOL_CALL 任务。
     */
    List<SubTask> decompose(String input, String response,
                            ToolCallback[] toolCallbacks, TaskReport history, Long userId) {
        // 观测：agent.plan（decompose 校验后结束，plan.tools[] 摘要 + 校验结论）
        try (AgentSpan planSpan = agentTracer.start(AgentSpanSpec.PLAN, null)) {
            PlanOutcome outcome = planRouter.plan(new PlanRequest(input, response, userId, toolCallbacks, history));
            planSpan.set(AgentField.VALIDATE_RESULT, outcome.source());
            planSpan.set(AgentField.PLAN_TOOLS, outcome.tasks().stream()
                    .map(SubTask::getToolName).collect(Collectors.joining(",")));
            log.info("  [规划] 产出 {} 个工具调用 (source={})", outcome.tasks().size(), outcome.source());
            return outcome.tasks();
        }
    }

    // ============================================================
    // CONFIRM 审批：暂停 / 恢复
    // ============================================================

    /**
     * CONFIRM 暂停处理：保存快照 → 建审批记录 → 推确认事件 → 终止本轮流。
     * <p>
     * 返回 currentResponse，由 completeTurn 识别暂停态（pendingSnapshot != null）
     * 跳过尾文本推送与历史落库，保证确认事件是流中最后一个数据。
     * </p>
     */
    private String handleConfirmPause(ConfirmRequiredException e, String input, String currentResponse,
                                      TaskReport history, int round, ChatContext ctx, SseEmitter emitter) {
        ToolInvocationContext ic = e.getContext();
        TaskSnapshot snapshot = new TaskSnapshot();
        snapshot.setOriginalInput(input);
        snapshot.setPartialResponse(currentResponse);
        snapshot.setCompletedTools(history.getCompleted().stream()
                .map(SubTask::getToolName).toList());
        snapshot.setRound(round);
        snapshot.setPendingToolName(ic.getToolName());
        snapshot.setPendingToolArguments(ic.getArguments());
        // 上下文来源：AgentContext 优先，ChatContext 兜底，最后取守卫异常携带的调用上下文
        snapshot.setConversationId(resolveConversationId(ctx, ic.getConversationId()));
        snapshot.setUserId(resolveUserId(ctx, ic.getUserId()));
        snapshot.setRootSpan(resolveRootSpan(ctx, null));
        if (ctx != null) {
            ctx.setPendingSnapshot(snapshot);
        }

        String reason = e.getReason() != null ? e.getReason() : "该操作需要你的确认才能执行";
        // best-effort 持久化：DB 失败返回 null，仍推确认事件（前端只提示、无法续流，可接受降级）
        String confirmId = approvalService.createApproval(snapshot);
        SseUtils.safeSend(emitter, SseUtils.confirmEvent(
                confirmId != null ? confirmId : "",
                ic.getToolName(), reason, ic.getArguments()));
        log.info("CONFIRM 暂停规划 [tool={}, confirmId={}, round={}]", ic.getToolName(), confirmId, round);
        return currentResponse;
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
    public void resumeFromSnapshot(TaskSnapshot snapshot, ChatContext ctx, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            // 根 span 优先级：AgentContext（confirm 入口重建，Propagator 传播）→ ChatContext → 快照
            AgentSpan rootSpan = resolveRootSpan(ctx, snapshot != null ? snapshot.getRootSpan() : null);
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

    private String resumePlan(TaskSnapshot snapshot, ChatContext ctx, SseEmitter emitter) {
        // ① 先执行待审批工具
        String toolResult = executeApprovedTool(snapshot, ctx, emitter);
        // ② 预置历史
        TaskReport history = new TaskReport();
        seedCompletedHistory(history, snapshot, toolResult);
        // ③ 续接回复：暂停前的 partial + 工具结果
        String currentResponse = snapshot.getPartialResponse() != null ? snapshot.getPartialResponse() : "";
        if (toolResult != null && !toolResult.isBlank()) {
            currentResponse = currentResponse + "\n\n" + toolResult;
        }
        return planAndExecute(snapshot.getOriginalInput(), currentResponse, ctx, history, emitter);
    }

    /**
     * 执行已确认的工具（绕过守卫直调底层）。显式携带 userId / conversationId：
     * 恢复执行在异步线程、无 UserHolder，且数据权限切面从 ToolContext 取 userId。
     */
    private String executeApprovedTool(TaskSnapshot snapshot, ChatContext ctx, SseEmitter emitter) {
        String toolName = snapshot.getPendingToolName();
        if (toolName == null || toolName.isBlank()) {
            log.warn("快照缺少待审批工具名，无法恢复");
            return null;
        }
        ToolCallback cb = toolBeanCollector.getToolCallback(toolName);
        if (!(cb instanceof GuardedToolCallback guarded)) {
            log.error("待审批工具不存在或未包装: {}", toolName);
            SseUtils.safeSend(emitter, SseUtils.errorEvent("待确认工具不可用，无法继续"));
            return null;
        }
        // 上下文来源：AgentContext 优先，ChatContext 兜底，最后取快照（跨请求持久化语义）
        Long uid = resolveUserId(ctx, snapshot.getUserId());
        String cid = resolveConversationId(ctx, snapshot.getConversationId());
        Map<String, Object> toolCtxMap = new HashMap<>();
        if (uid != null) toolCtxMap.put("userId", uid);
        if (cid != null && !cid.isBlank()) toolCtxMap.put("conversationId", cid);

        SseUtils.safeSend(emitter, SseUtils.stepEvent(toolName, SseEventConstants.TOOL_RUNNING));
        String result = guarded.callBypass(snapshot.getPendingToolArguments(), new ToolContext(toolCtxMap));
        SseUtils.safeSend(emitter, SseUtils.stepEvent(toolName, SseEventConstants.TOOL_COMPLETED));
        log.info("审批工具已执行 [tool={}, userId={}]", toolName, uid);
        if (snapshot.getPendingConfirmId() != null && uid != null) {
            approvalService.markExecuted(snapshot.getPendingConfirmId(), uid);
        }
        return result;
    }

    /**
     * 预置已完成工具到 history：completedTools ∪ 待审批工具（全部 COMPLETED）。
     * 目的：resume 后 decompose 的 validatePlan 依据 isCompleted 过滤，防二次审批。
     */
    private void seedCompletedHistory(TaskReport history, TaskSnapshot snapshot, String approvedToolResult) {
        if (snapshot.getCompletedTools() != null) {
            for (String tool : snapshot.getCompletedTools()) {
                if (tool == null || tool.isBlank()) continue;
                history.record(List.of(SubTask.builder()
                        .toolName(tool)
                        .type(TaskType.TOOL_CALL)
                        .status(SubTaskStatus.COMPLETED)
                        .result("(已完成)")
                        .build()));
            }
        }
        if (snapshot.getPendingToolName() != null) {
            history.record(List.of(SubTask.builder()
                    .toolName(snapshot.getPendingToolName())
                    .type(TaskType.TOOL_CALL)
                    .status(SubTaskStatus.COMPLETED)
                    .result(approvedToolResult != null ? approvedToolResult : "(已执行)")
                    .build()));
        }
    }

    // ============================================================
    // 回退路径专用方法
    // ============================================================

    /**
     * 聚合结果（仅回退路径使用）。
     * 取 LLM_REASON 的执行结论作为最终输出。
     */
    private String merge(String currentResponse, TaskQueue queue) {
        String llmConclusion = queue.getAllTasks().stream()
                .filter(t -> t.getType() == TaskType.LLM_REASON
                        && t.getStatus() == SubTaskStatus.COMPLETED
                        && t.getResult() != null)
                .map(t -> t.getResult().toString())
                .findFirst()
                .orElse(null);
        if (llmConclusion != null) return llmConclusion;
        return currentResponse;
    }

    // ============================================================
    // 子 Agent 路径专用方法
    // ============================================================

    /**
     * 从 TaskReport 中提取历史摘要（key=toolName, value=50字摘要）。
     */
    private Map<String, String> buildHistorySummary(TaskReport history) {
        Map<String, String> summary = new LinkedHashMap<>();
        for (SubTask t : history.getCompleted()) {
            summary.put(t.getToolName(),
                    SubAgentPromptBuilder.truncateResult(String.valueOf(t.getResult())));
        }
        return summary;
    }

    /**
     * 将 SubTaskResult 记录到 TaskReport。
     * <p>
     * 构建 SubTask 列表后调用 {@link TaskReport#record(List)}，
     * 确保 finalFailed 黑名单等逻辑正确触发。
     * </p>
     */
    private void recordHistory(TaskReport history, SubTaskResult result) {
        List<SubTask> subTasks = new ArrayList<>();

        if (result.getRawResults() != null) {
            for (var entry : result.getRawResults().entrySet()) {
                subTasks.add(SubTask.builder()
                        .toolName(entry.getKey())
                        .result(entry.getValue() != null ? entry.getValue().toString() : "")
                        .type(TaskType.TOOL_CALL)
                        .status(SubTaskStatus.COMPLETED)
                        .build());
            }
        }
        if (result.getErrors() != null) {
            for (var entry : result.getErrors().entrySet()) {
                subTasks.add(SubTask.builder()
                        .toolName(entry.getKey())
                        .result(entry.getValue())
                        .type(TaskType.TOOL_CALL)
                        .status(SubTaskStatus.FAILED)
                        .retryCount(1)  // 触发 TaskReport 的 finalFailed 黑名单
                        .build());
            }
        }

        if (!subTasks.isEmpty()) {
            history.record(subTasks);
        }
    }
}
