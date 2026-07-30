package com.hmdp.agent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ChatClient chatClient;
    private final long timeoutMs;

    public TaskExecutor(ToolCallback[] toolCallbacks, Long userId,
                        ChatClient chatClient, long timeoutMs) {
        this.toolCallbacks = toolCallbacks;
        this.userId = userId;
        this.chatClient = chatClient;
        this.timeoutMs = timeoutMs;
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
        try {
            String jsonArgs = serializeParams(task.getParams());
            String result = callback.call(jsonArgs,
                    new ToolContext(Map.of("userId", userId)));
            queue.markDone(task.getId(), result);
            log.info("    TOOL_CALL ✅ [tool={}]", task.getToolName());
        } catch (Exception e) {
            queue.markFailed(task.getId(), e.getMessage());
            log.warn("    TOOL_CALL ❌ [tool={}, err={}]", task.getToolName(), e.getMessage());
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

        String prompt = "基于以下数据，用中文给用户一个完整的回答：\n\n"
                + contextSummary + errorNote;

        try {
            String conclusion = chatClient.prompt().user(prompt).call().content();
            queue.markDone(task.getId(), conclusion);
            log.info("    LLM_REASON ✅");
        } catch (Exception e) {
            queue.markFailed(task.getId(), "聚合失败：" + e.getMessage());
            log.warn("LLM_REASON 失败 [err={}]", e.getMessage());
        }
    }

    private ToolCallback findTool(String toolName) {
        if (toolCallbacks == null || toolName == null) return null;
        for (ToolCallback cb : toolCallbacks) {
            if (toolName.equals(cb.getToolDefinition().name())) return cb;
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
}
