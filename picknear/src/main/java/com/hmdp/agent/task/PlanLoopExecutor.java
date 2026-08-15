package com.hmdp.agent.task;

import com.hmdp.agent.config.FeatureProperties;
import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.guard.ConfirmRequiredException;
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
import com.hmdp.agent.subagent.SubTaskAgent;
import com.hmdp.agent.subagent.callback.SseSubAgentCallback;
import com.hmdp.agent.subagent.model.SubTaskExecution;
import com.hmdp.agent.subagent.model.SubTaskPlan;
import com.hmdp.agent.subagent.model.SubTaskResult;
import com.hmdp.agent.tool.ToolBeanCollector;
import com.hmdp.agent.util.SseEventConstants;
import com.hmdp.agent.util.SseUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 规划主循环执行器（从 TaskPlanner 拆出）。
 * <p>
 * 职责：decompose() -> execute() -> merge() 的循环编排，最多 {@link #MAX_ROUNDS} 轮，
 * execute() 阶段根据 {@code feature.subagent.enabled} 走 SubTaskAgent 或原 TaskExecutor（回退路径）。
 * 规划细节（目录构建/意图树/解析校验）全部下沉到 {@link PlanRouter} 策略，本类只做循环编排与观测。
 * </p>
 * <p>
 * 与编排门面（TaskPlanner）的分工：本类只跑主循环（同步方法），
 * 异步入口、根 span 挂载、收尾、CONFIRM 恢复编排由 TaskPlanner 负责。
 * </p>
 */
@Slf4j
@Component
public class PlanLoopExecutor {

    private static final int MAX_ROUNDS = 5;
    private static final long TASK_TIMEOUT_MS = 5_000L;

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    @Qualifier("aliibabaChatClient")
    private ChatClient chatClient;

    @Resource
    private SubTaskAgent subTaskAgent;

    @Resource
    private FeatureProperties featureProperties;

    @Resource
    private SubTaskProperties subTaskProperties;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private PromptService promptService;

    @Resource
    private PlanRouter planRouter;

    @Resource
    private TaskReportHelper taskReportHelper;

    @Resource
    private ConfirmFlowManager confirmFlowManager;

    /**
     * 主循环：拆解 -> 执行 -> 聚合，重复至多 MAX_ROUNDS 轮。
     */
    public String planAndExecute(String input, String aiResponse, AgentContext ctx,
                                 SseEmitter emitter) {
        return planAndExecute(input, aiResponse, ctx, new TaskReport(), emitter);
    }

    /**
     * 主循环（可携带预置历史的入口，resume 用）。
     */
    public String planAndExecute(String input, String aiResponse, AgentContext ctx,
                                 TaskReport history, SseEmitter emitter) {
        String currentResponse = aiResponse;
        var toolCallbacks = toolBeanCollector.getToolCallbacks();
        boolean useSubAgent = featureProperties.getSubagent().isEnabled();
        // 异步段上下文（第 2 步收敛）：AgentContext 优先，参数 ctx 兜底——本循环内统一取值
        Long userId = AgentContextResolver.resolveUserId(ctx, null);
        String conversationId = AgentContextResolver.resolveConversationId(ctx, null);

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
                                    .historySummary(taskReportHelper.buildHistorySummary(history))
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
                            taskReportHelper.recordHistory(history, result);
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
                        currentResponse = taskReportHelper.merge(currentResponse, queue);
                        SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_MERGING, SseEventConstants.TEXT_MERGING_DONE));
                    }
                } catch (ConfirmRequiredException e) {
                    // CONFIRM 审批：保存快照 → 建审批记录 → 推确认事件 → 暂停本轮流
                    return confirmFlowManager.pause(e, input, currentResponse, history, round, ctx, emitter);
                }
            }
        }

        return currentResponse;
    }

    /**
     * 拆解子任务：委托激活的 {@link PlanRouter} 策略产出 TOOL_CALL 任务。
     * 包私有：同包测试直接验证委托行为。
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
}
