package com.hmdp.agent.orchestration.round;

import com.hmdp.agent.legacy.task.TaskExecutor;
import com.hmdp.agent.legacy.task.TaskQueue;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.orchestration.support.HistoryAggregator;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.plan.model.TaskType;
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

/**
 * 回退路径执行器。
 * <p>
 * 执行主循环一轮中的回退分支：原 TaskExecutor 串行直调。
 * 仅在 {@code feature.subagent.enabled=false} 时被主循环选中。
 * </p>
 */
@Slf4j
@Component
public class FallbackRoundExecutor {

    private static final long TASK_TIMEOUT_MS = 5_000L;

    @Resource
    @Qualifier("aliibabaChatClient")
    private ChatClient chatClient;

    @Resource
    private PromptService promptService;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private HistoryAggregator historyAggregator;

    /**
     * 执行一轮回退路径。
     *
     * @return 更新后的 currentResponse（LLM_REASON 结论或原回复）
     */
    public String executeRound(String currentResponse, List<SubTask> tasks,
                               TaskReport history, Long userId, String conversationId,
                               ToolCallback[] toolCallbacks, SseEmitter emitter) {
        List<SubTask> execTasks = new ArrayList<>(tasks);
        execTasks.add(SubTask.builder()
                .id(UUID.randomUUID().toString())
                .description("基于以上数据生成最终结论")
                .type(TaskType.LLM_REASON)
                .dependsOn(tasks.stream().map(SubTask::getId).toList())
                .status(SubTaskStatus.PENDING)
                .build());

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

        for (SubTask t : queue.getAllTasks()) {
            if (t.getToolName() == null) continue;
            String st = t.getStatus() == SubTaskStatus.COMPLETED
                    ? SseEventConstants.TOOL_COMPLETED
                    : SseEventConstants.TOOL_FAILED;
            SseUtils.safeSend(emitter, SseUtils.stepEvent(t.getToolName(), st));
        }

        history.record(execTasks);

        log.info("========== [Round] 3) 聚合结论 ==========");
        SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_MERGING, SseEventConstants.TEXT_MERGING_FALLBACK));
        String merged = reportAggregator.merge(currentResponse, queue.getAllTasks());
        SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_MERGING, SseEventConstants.TEXT_MERGING_DONE));
        return merged;
    }
}
