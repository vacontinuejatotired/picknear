package com.hmdp.agent.orchestration.round;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.honesty.gate.EvidenceAnchor;
import com.hmdp.agent.honesty.gate.EvidenceAssertionGate;
import com.hmdp.agent.honesty.gate.HonorAction;
import com.hmdp.agent.honesty.gate.HonorConfig;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.orchestration.support.HistoryAggregator;
import com.hmdp.agent.execution.ToolExecutionFacade;
import com.hmdp.agent.subagent.callback.SseSubAgentCallback;
import com.hmdp.agent.execution.model.ExecutionInput;
import com.hmdp.agent.execution.model.ExecutionOutput;
import com.hmdp.agent.execution.model.ExecutionSession;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.TaskReport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 执行代理（原 SubAgentRoundExecutor，改名）。
 * <p>
 * 职责：观测挂载 + 数据转换 + 历史记录。
 * </p>
 */
@Slf4j
@Component
public class RoundExecutionProxy {

    @Resource
    private ToolExecutionFacade toolExecutionFacade;

    @Resource
    private SubTaskProperties subTaskProperties;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private HistoryAggregator historyAggregator;

    @Resource
    private EvidenceAssertionGate evidenceAssertionGate;

    /** 断言闸处置档位（默认 OBSERVE 只观测；命中率校准后按需开 APPEND_DISCLAIMER） */
    @Value("${agent.honesty.assertion-gate.action:OBSERVE}")
    private String assertionGateAction;

    /**
     * 执行一轮子 Agent 路径。
     *
     * @return 更新后的 currentResponse（子 Agent 摘要）
     */
    public String executeRound(String input, String currentResponse, List<SubTask> tasks,
                               TaskReport history, int round, Long userId, String conversationId,
                               SseEmitter emitter) {
        try (AgentSpan subagentSpan = agentTracer.start(AgentSpanSpec.SUBAGENT, null)) {
            ExecutionInput plan = ExecutionInput.builder()
                    .userInput(input)
                    .currentResponse(currentResponse)
                    .tasks(tasks)
                    .historySummary(historyAggregator.buildHistorySummary(history))
                    .userId(userId)
                    .conversationId(conversationId)
                    .round(round)
                    .build();

            ExecutionSession session = ExecutionSession.builder()
                    .input(plan)
                    .callback(new SseSubAgentCallback(emitter))
                    .properties(subTaskProperties)
                    .startTimeMs(System.currentTimeMillis())
                    .subagentSpan(subagentSpan)
                    .build();

            ExecutionOutput result = toolExecutionFacade.execute(session);

            // 反编造 L3（J4）：summary 离开子 Agent 前的确定性断言（Detector A，默认 OBSERVE）。
            // 处置重写须先于 recordHistory，保证聚合/落库/返回值一致用处置后文本。
            String gated = evidenceAssertionGate.gate(
                    result.getSummary(),
                    EvidenceAnchor.of(result.getToolEvidence(), input),
                    new HonorConfig(resolveAction()));
            if (gated != null && !gated.equals(result.getSummary())) {
                result.setSummary(gated);
            }

            subagentSpan.set(AgentField.TOOL_COUNT, String.valueOf(result.getRawResults() != null
                    ? result.getRawResults().size() : 0));
            historyAggregator.recordHistory(history, result);
            return result.getSummary();
        }
    }

    private HonorAction resolveAction() {
        try {
            if (assertionGateAction == null || assertionGateAction.isBlank()) {
                return HonorAction.OBSERVE;
            }
            return HonorAction.valueOf(assertionGateAction.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[AssertionGate] 未知处置档位 [{}]，回退 OBSERVE", assertionGateAction);
            return HonorAction.OBSERVE;
        }
    }
}
