package com.hmdp.agent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.guard.ConfirmRequiredException;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.util.SseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 子任务执行器（已废弃，保留供回退路径使用）。
 * <p>
 * 新代码使用 {@link com.hmdp.agent.subagent.SubTaskAgent} 替代。
 * </p>
 * <p>
 * 按类型分发：
 * <ul>
 *   <li>TOOL_CALL → 复用 GuardedToolCallback（由 ToolBeanCollector 已包装）</li>
 *   <li>LLM_REASON → 调 ChatClient 做聚合推理</li>
 * </ul>
 * </p>
 */
@Deprecated
@Slf4j
public class TaskExecutor {

    private final ToolCallback[] toolCallbacks;
    private final Long userId;
    private final String conversationId;
    private final ChatClient chatClient;
    private final long timeoutMs;
    private final AgentTracer agentTracer;
    private final PromptService promptService;

    public TaskExecutor(ToolCallback[] toolCallbacks, Long userId, String conversationId,
                        ChatClient chatClient, long timeoutMs, AgentTracer agentTracer,
                        PromptService promptService) {
        this.toolCallbacks = toolCallbacks;
        this.userId = userId;
        this.conversationId = conversationId;
        this.chatClient = chatClient;
        this.timeoutMs = timeoutMs;
        this.agentTracer = agentTracer;
        this.promptService = promptService;
    }

    /**
     * 串行执行队列中所有可执行任务，直到全部完成或超时。
     */
    public void executeAll(TaskQueue queue) {
        while (!queue.isAllDone()) {
            var ready = queue.getReadyTasks();
            if (ready.isEmpty()) {
                // 无就绪任务但未全部完成 → 死锁，终止
                log.warn("TaskQueue 死锁：存在 PENDING 任务但无 READY 任务");
                break;
            }
            for (SubTask task : ready) {
                task.setStatus(SubTaskStatus.RUNNING);
                executeOne(task, queue);
            }
        }
    }

    private void executeOne(SubTask task, TaskQueue queue) {
        switch (task.getType()) {
            case TOOL_CALL -> executeTool(task, queue);
            case LLM_REASON -> executeLlmReason(task, queue);
        }
    }

    private void executeTool(SubTask task, TaskQueue queue) {
        ToolCallback callback = findTool(task.getToolName());
        if (callback == null) {
            queue.markFailed(task.getId(), "未知工具: " + task.getToolName());
            return;
        }
        // 观测：agent.tool_call.{toolName}（架构文档 §5.1，guard 决策由 GuardedToolCallback 内 agent.guard 记录）
        try (AgentSpan toolSpan = agentTracer.start(AgentSpanSpec.TOOL_CALL, task.getToolName())) {
            try {
                String jsonArgs = serializeParams(task.getParams());
                Map<String, Object> toolCtx = new java.util.HashMap<>();
                if (userId != null) {
                    toolCtx.put("userId", userId);
                }
                if (conversationId != null && !conversationId.isBlank()) {
                    toolCtx.put("conversationId", conversationId);
                }
                String result = callback.call(jsonArgs, new ToolContext(toolCtx));
                queue.markDone(task.getId(), result);
                toolSpan.status("OK");
                toolSpan.attribute("tool.result_summary", truncate(result, 200));
                log.info("    TOOL_CALL ✅ [tool={}]", task.getToolName());
            } catch (ConfirmRequiredException e) {
                // CONFIRM 审批信号：立即原样抛出（不 markFailed），冒泡到 TaskPlanner 专用 catch
                throw e;
            } catch (Exception e) {
                queue.markFailed(task.getId(), e.getMessage());
                toolSpan.status("FAILED");
                log.warn("    TOOL_CALL ❌ [tool={}, err={}]", task.getToolName(), e.getMessage());
            }
        }
    }

    private void executeLlmReason(SubTask task, TaskQueue queue) {
        // 已完成的工具结果做上下文
        String contextSummary = queue.getCompleted().stream()
                .filter(t -> t.getType() == TaskType.TOOL_CALL)
                .map(t -> "【" + t.getDescription() + "】\n" + t.getResult())
                .collect(Collectors.joining("\n\n"));

        // 失败任务注入失败摘要，不让 LLM_REASON 阻塞
        String errorNote = "";
        var failedTasks = queue.getFailed();
        if (!failedTasks.isEmpty()) {
            errorNote = "\n\n注意：以下步骤执行失败，请基于已有数据回答，并提示用户：\n"
                    + failedTasks.stream()
                        .map(t -> "❌ " + t.getDescription() + "：" + t.getResult())
                        .collect(Collectors.joining("\n"));
        }

        // 聚合模板已外置（agent.prompt.task.merge），系统提示词每次请求注入
        String prompt = promptService.render(PromptKeys.TASK_MERGE, Map.of(
                "contextSummary", contextSummary,
                "errorNote", errorNote));

        // 观测：agent.llm_reason（based_on 记录已完成的工具摘要）
        try (AgentSpan reasonSpan = agentTracer.start(AgentSpanSpec.LLM_REASON, null)) {
            reasonSpan.attribute("based_on", queue.getCompleted().stream()
                    .filter(t -> t.getType() == TaskType.TOOL_CALL)
                    .map(SubTask::getToolName).collect(Collectors.joining(",")));
            try {
                ChatModelObservationConventionConfig.mark("llm-reason");
                String conclusion;
                try {
                    conclusion = chatClient.prompt()
                            .system(promptService.render(PromptKeys.SYSTEM_MAIN,
                                    Map.of("userId", userId != null ? String.valueOf(userId) : "")))
                            .user(prompt)
                            .call().content();
                } finally {
                    ChatModelObservationConventionConfig.clear();
                }
                queue.markDone(task.getId(), conclusion);
                reasonSpan.status("OK");
                log.info("    LLM_REASON ✅");
            } catch (Exception e) {
                queue.markFailed(task.getId(), "聚合失败：" + e.getMessage());
                reasonSpan.status("FAILED");
                log.warn("LLM_REASON 失败 [err={}]", e.getMessage());
            }
        }
    }

    private ToolCallback findTool(String toolName) {
        if (toolCallbacks == null || toolName == null) return null;
        for (ToolCallback cb : toolCallbacks) {
            if (toolName.equals(GuardedToolCallback.rawName(cb))) return cb;
        }
        return null;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private String serializeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        try {
            return JSON.writeValueAsString(params);
        } catch (Exception e) {
            log.warn("参数序列化失败", e);
            return "{}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
