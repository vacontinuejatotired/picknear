package com.hmdp.agent.task;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.subagent.SubTaskAgent;
import com.hmdp.agent.subagent.callback.SseSubAgentCallback;
import com.hmdp.agent.subagent.model.SubTaskExecution;
import com.hmdp.agent.subagent.model.SubTaskPlan;
import com.hmdp.agent.subagent.model.SubTaskResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 子 Agent 路径执行器（从 PlanLoopExecutor 拆出）。
 * <p>
 * 执行主循环一轮中的子 Agent 分支：构建 SubTaskPlan/SubTaskExecution →
 * {@link SubTaskAgent#execute}（观测：agent.subagent 整段 span）→ 记录历史。
 * </p>
 */
@Slf4j
@Component
public class SubAgentRoundExecutor {

    @Resource
    private SubTaskAgent subTaskAgent;

    @Resource
    private SubTaskProperties subTaskProperties;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private TaskReportHelper taskReportHelper;

    /**
     * 执行一轮子 Agent 路径。
     *
     * @return 更新后的 currentResponse（子 Agent 摘要）
     */
    public String execute(String input, String currentResponse, List<SubTask> tasks,
                          TaskReport history, int round, Long userId, String conversationId,
                          SseEmitter emitter) {
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
            taskReportHelper.recordHistory(history, result);
            return result.getSummary();
        }
    }
}
