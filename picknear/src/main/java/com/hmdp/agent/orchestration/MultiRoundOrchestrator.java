package com.hmdp.agent.orchestration;

import com.hmdp.agent.config.FeatureProperties;
import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.guard.model.ConfirmRequiredException;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.orchestration.confirm.ConfirmFlowManager;
import com.hmdp.agent.orchestration.round.FallbackRoundExecutor;
import com.hmdp.agent.orchestration.round.RoundExecutionProxy;
import com.hmdp.agent.orchestration.support.AgentContextResolver;
import com.hmdp.agent.plan.model.PlanOutcome;
import com.hmdp.agent.plan.model.PlanRequest;
import com.hmdp.agent.plan.PlanRouter;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.tool.ToolBeanCollector;
import com.hmdp.agent.stream.SseEventConstants;
import com.hmdp.agent.stream.SseUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 多轮编排器（原 PlanLoopExecutor，改名）。
 * <p>
 * 职责：decompose() -> execute() -> merge() 的循环骨架，最多 {@link #MAX_ROUNDS} 轮，
 * 每轮执行分支按 {@code feature.subagent.enabled} 委托。
 * </p>
 * <p>
 * 方法命名：
 * <ul>
 *   <li>{@link #runLoop} — 执行主循环（首次/重试）</li>
 *   <li>{@link #runLoopWithHistory} — 携带预置历史的循环（resume 用）</li>
 *   <li>{@link #decompose} — 规划拆解（包内可见，便于单测）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class MultiRoundOrchestrator {

    private static final int MAX_ROUNDS = 5;

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    private FeatureProperties featureProperties;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private PlanRouter planRouter;

    @Resource
    private ConfirmFlowManager confirmFlowManager;

    @Resource
    private RoundExecutionProxy roundExecutionProxy;

    @Resource
    private FallbackRoundExecutor fallbackRoundExecutor;

    /**
     * 执行主循环：拆解 -> 执行 -> 聚合，重复至多 MAX_ROUNDS 轮。
     */
    public String runLoop(String input, String aiResponse, AgentContext ctx,
                          SseEmitter emitter) {
        return runLoopWithHistory(input, aiResponse, ctx, new TaskReport(), emitter);
    }

    /**
     * 执行主循环（可携带预置历史，resume 用）。
     */
    public String runLoopWithHistory(String input, String aiResponse, AgentContext ctx,
                                     TaskReport history, SseEmitter emitter) {
        String currentResponse = aiResponse;
        var toolCallbacks = toolBeanCollector.getToolCallbacks();
        boolean useSubAgent = featureProperties.getSubagent().isEnabled();
        Long userId = AgentContextResolver.resolveUserId(ctx, null);
        String conversationId = AgentContextResolver.resolveConversationId(ctx, null);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            int r = round + 1;
            log.info("========== [Round {}] 1) 规划拆解 ==========", r);

            try (AgentSpan roundSpan = agentTracer.start(AgentSpanSpec.ROUND, String.valueOf(r))) {
                try {
                    List<SubTask> tasks = decompose(input, currentResponse, toolCallbacks, history, userId);
                    roundSpan.set(AgentField.TOOL_COUNT, String.valueOf(tasks.size()));
                    if (tasks.isEmpty()) {
                        roundSpan.set(AgentField.PLAN_VALID, "false");
                        log.warn("========== [Round {}] 2) 无需执行, 保持原回复 ==========", r);
                        return currentResponse;
                    }
                    roundSpan.set(AgentField.PLAN_VALID, "true");

                    String planDesc = tasks.stream()
                            .map(SubTask::getDescription)
                            .collect(Collectors.joining("、"));
                    SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_PLANNING,
                            SseEventConstants.TEXT_PLANNING_PREFIX + planDesc));

                    if (useSubAgent) {
                        log.info("========== [Round {}] 2) 子 Agent 执行 ==========", r);
                        currentResponse = roundExecutionProxy.executeRound(input, currentResponse,
                                tasks, history, round, userId, conversationId, emitter);
                    } else {
                        log.info("========== [Round {}] 2) 回退模式：TaskExecutor 执行 ==========", r);
                        currentResponse = fallbackRoundExecutor.executeRound(currentResponse, tasks,
                                history, userId, conversationId, toolCallbacks, emitter);
                    }
                } catch (ConfirmRequiredException e) {
                    return confirmFlowManager.pause(e, input, currentResponse, history, round, ctx, emitter);
                }
            }
        }

        return currentResponse;
    }

    List<SubTask> decompose(String input, String response,
                            ToolCallback[] toolCallbacks, TaskReport history, Long userId) {
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
