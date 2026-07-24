package com.hmdp.agent.task;

import com.hmdp.agent.tool.ToolBeanCollector;
import com.hmdp.agent.util.SseUtils;
import com.hmdp.agent.hook.ChatContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 子任务规划器。
 * <p>
 * 核心循环：decompose() → execute() → merge()，最多 {@link #MAX_ROUNDS} 轮。
 * 每轮执行后通过 SSE 推送进度，保持连接活跃。
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

    /**
     * 异步入口：在 subtaskExecutor 上执行规划，不阻塞 SSE 主线程。
     */
    public void planAndExecuteAsync(String input, String aiResponse, ChatContext ctx,
                                    SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            try {
                String result = planAndExecute(input, aiResponse, ctx, emitter);
                SseUtils.safeSend(emitter, SseUtils.escapeJson(result));
                emitter.complete();
            } catch (Exception e) {
                log.error("TaskPlanner 执行异常", e);
                SseUtils.safeSend(emitter, SseUtils.escapeJson("处理中断：" + e.getMessage()));
                emitter.completeWithError(e);
            }
        }, subtaskExecutor);
    }

    /**
     * 主循环：拆解 → 执行 → 聚合，重复至多 MAX_ROUNDS 轮。
     */
    public String planAndExecute(String input, String aiResponse, ChatContext ctx,
                                 SseEmitter emitter) {
        String currentResponse = aiResponse;
        var toolCallbacks = toolBeanCollector.getToolCallbacks();
        TaskReport history = new TaskReport();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            int r = round + 1;
            log.info("========== [Round {}] ① 规划拆解 ==========", r);
            // decompose() 返回的已含 TOOL_CALL + 强制 LLM_REASON
            List<SubTask> tasks = decompose(input, currentResponse, toolCallbacks, history);
            if (tasks.isEmpty()) {
                log.warn("========== [Round {}] ② 无需执行, 保持原回复 ==========", r);
                return currentResponse;
            }

            // 推送：规划阶段（只描述 TOOL_CALL，跳过 LLM_REASON）
            String planDesc = tasks.stream()
                    .filter(t -> t.getType() == TaskType.TOOL_CALL)
                    .map(SubTask::getDescription)
                    .collect(java.util.stream.Collectors.joining("、"));
            SseUtils.safeSend(emitter, SseUtils.progressEvent("planning",
                    "规划完成：需要执行 " + planDesc));

            // CONFIRM 工具 → 保存快照，提示用户
            if (hasConfirmTool(tasks)) {
                TaskSnapshot snapshot = new TaskSnapshot();
                snapshot.setOriginalInput(input);
                snapshot.setPartialResponse(currentResponse);
                snapshot.setCompletedTools(history.getCompleted().stream()
                        .map(SubTask::getToolName).toList());
                snapshot.setRound(round);
                ctx.setPendingSnapshot(snapshot);

                currentResponse += "\n\n⚠️ 部分操作需要你确认后才能执行，请明确告知是否继续。";
                SseUtils.safeSend(emitter, SseUtils.confirmEvent("需要确认，暂停规划"));
                break;
            }

            // 推送：逐任务 RUNNING 状态（仅 TOOL_CALL）
            for (SubTask t : tasks) {
                if (t.getType() != TaskType.TOOL_CALL) continue;
                SseUtils.safeSend(emitter, SseUtils.stepEvent(t.getToolName(), "RUNNING"));
            }

            log.info("========== [Round {}] ② 执行子任务 ==========", r);
            // 执行本轮任务
            TaskQueue queue = new TaskQueue(tasks);
            TaskExecutor executor = new TaskExecutor(toolCallbacks, ctx.getUserId(),
                    chatClient, TASK_TIMEOUT_MS);
            executor.executeAll(queue);

            // 推送：逐任务完成/失败状态
            for (SubTask t : queue.getAllTasks()) {
                if (t.getToolName() == null) continue;
                String st = t.getStatus() == SubTaskStatus.COMPLETED ? "COMPLETED" : "FAILED";
                SseUtils.safeSend(emitter, SseUtils.stepEvent(t.getToolName(), st));
            }

            // 记录历史
            history.record(tasks);

            // 推送：聚合阶段
            log.info("========== [Round {}] ③ 聚合结论 ==========", r);
            SseUtils.safeSend(emitter, SseUtils.progressEvent("merging", "正在生成结论..."));

            // 聚合结果
            currentResponse = merge(currentResponse, queue);
            SseUtils.safeSend(emitter, SseUtils.progressEvent("merging", "结论生成完成"));
        }

        return currentResponse;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PLAN_RESPONSE_TRUNCATE = 200;
    private static final int RESULT_SUMMARY_LEN = 50;

    /**
     * 拆解子任务：AI 做规划推理 → Java 三层校验 → 强制追加 LLM_REASON。
     * <p>
     * 返回的列表已包含 TOOL_CALL（由 AI 规划）+ LLM_REASON（由 Java 强制追加）。
     * 主循环拿到后直接塞 TaskQueue 执行，不再额外处理。
     * </p>
     */
    List<SubTask> decompose(String input, String response,
                            ToolCallback[] toolCallbacks, TaskReport history) {
        String planJson = askAiForPlan(input, response, toolCallbacks, history);
        return validatePlan(planJson, toolCallbacks, history);
    }

    /**
     * 调 AI 做规划推理。
     * <p>
     * 传入的已完成工具只传 50 字摘要，不传完整 result，防止 Token 爆炸。
     * </p>
     */
    private String askAiForPlan(String input, String response,
                                 ToolCallback[] toolCallbacks,
                                 TaskReport history) {
        StringBuilder toolsDesc = new StringBuilder();
        for (ToolCallback cb : toolCallbacks) {
            String name = cb.getToolDefinition().name();
            if (history.isCompleted(name) || history.isFinalFailed(name)) continue;
            toolsDesc.append("- ").append(name)
                     .append(": ").append(cb.getToolDefinition().description()).append("\n");
        }

        List<String> completedSummary = history.getCompleted().stream()
                .map(t -> t.getToolName() + ": " + truncate(String.valueOf(t.getResult()), RESULT_SUMMARY_LEN))
                .toList();
        List<String> failedSummary = history.getFailed().stream()
                .map(t -> t.getToolName() + ": " + extractErrorType(String.valueOf(t.getResult())))
                .toList();

        String prompt = """
                你是一个任务规划器，根据用户问题和已有回复判断还需要调用哪些工具。

                可用工具：
                %s

                已完成的工具及摘要：
                %s

                失败的工具（不再重试）：
                %s

                用户问题：%s
                AI 已有回复：%s

                规则：
                - 只输出 JSON 数组，不要多余解释
                - 格式：[{"tool":"工具名","params":{"参数名":"值"}}]
                - 不需要额外工具则输出 []
                - 需要参数时从用户问题中提取，参数名与工具定义一致
                """.formatted(
                        toolsDesc,
                        String.join("\n", completedSummary),
                        String.join("\n", failedSummary),
                        input,
                        truncate(response, PLAN_RESPONSE_TRUNCATE)
                );

        try {
            String result = chatClient.prompt().user(prompt).call().content();
            log.info("  [规划] AI 建议: {}", result);
            return result;
        } catch (Exception e) {
            log.warn("AI 规划请求失败", e);
            return "[]";
        }
    }

    /**
     * 三层校验：JSON 语法 → 工具存在性 → 历史状态。
     * 校验通过后强制追加 LLM_REASON 聚合任务。
     */
    private List<SubTask> validatePlan(String planJson,
                                        ToolCallback[] toolCallbacks,
                                        TaskReport history) {
        // ① JSON 语法校验
        List<Map<String, Object>> planEntries;
        try {
            JsonNode root = JSON.readTree(planJson);
            if (!root.isArray()) {
                log.warn("  [规划] 结果非 JSON 数组: {}", planJson);
                return List.of();
            }
            planEntries = JSON.convertValue(root, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("规划结果 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }

        // ② 构建工具名索引
        Map<String, ToolCallback> callbackIndex = new HashMap<>();
        if (toolCallbacks != null) {
            for (ToolCallback cb : toolCallbacks) {
                callbackIndex.put(cb.getToolDefinition().name(), cb);
            }
        }

        // ③ 逐条校验
        List<SubTask> tasks = new ArrayList<>();
        for (Map<String, Object> entry : planEntries) {
            String toolName = entry.get("tool") instanceof String s ? s : null;
            if (toolName == null || toolName.isBlank()) {
                log.warn("  [规划] 缺少 tool 字段: {}", entry);
                continue;
            }
            if (!callbackIndex.containsKey(toolName)) {
                log.warn("  [规划] 工具不存在: {}", toolName);
                continue;
            }
            if (history.isCompleted(toolName)) continue;
            if (history.isFinalFailed(toolName)) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> params = entry.get("params") instanceof Map
                    ? (Map<String, Object>) entry.get("params") : null;

            tasks.add(SubTask.builder()
                    .id(UUID.randomUUID().toString())
                    .description("执行工具: " + toolName)
                    .type(TaskType.TOOL_CALL)
                    .toolName(toolName)
                    .params(params != null ? params : Map.of())
                    .status(SubTaskStatus.PENDING)
                    .build());
            log.info("  [规划] 需执行 [tool={}, params={}]", toolName, params);
        }

        // ★ 强制追加 LLM_REASON 聚合任务
        if (!tasks.isEmpty()) {
            tasks.add(SubTask.builder()
                    .id(UUID.randomUUID().toString())
                    .description("基于以上数据生成最终结论")
                    .type(TaskType.LLM_REASON)
                    .dependsOn(tasks.stream().map(SubTask::getId).toList())
                    .status(SubTaskStatus.PENDING)
                    .build());
            log.info("  [规划] 追加 LLM_REASON");
        }
        return tasks;
    }

    /** 截取前 N 个字符，超长加 "..." */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    /** 从异常信息中提取错误类型首行 */
    private static String extractErrorType(String error) {
        if (error == null) return "未知错误";
        String[] lines = error.split("\n");
        String first = lines[0];
        return first.length() > 80 ? first.substring(0, 80) : first;
    }

    /**
     * 检查任务列表中是否包含需要用户确认的工具。
     */
    private boolean hasConfirmTool(List<SubTask> tasks) {
        // Phase 1 简化：检查 GuardedToolCallback 的 CONFIRM 特性
        // TODO: 后续从 GuardedToolCallback 获取是否需要确认
        return false;
    }

    /**
     * 聚合结果：取 LLM_REASON 的执行结论作为最终输出，不拼接原始工具数据。
     * 若无 LLM_REASON，保持原 AI response 不变。
     */
    private String merge(String currentResponse, TaskQueue queue) {
        // 取 LLM_REASON 的结论作为最终输出
        String llmConclusion = queue.getAllTasks().stream()
                .filter(t -> t.getType() == TaskType.LLM_REASON
                        && t.getStatus() == SubTaskStatus.COMPLETED
                        && t.getResult() != null)
                .map(t -> t.getResult().toString())
                .findFirst()
                .orElse(null);
        if (llmConclusion != null) return llmConclusion;

        // 无 LLM_REASON 时保持原样
        return currentResponse;
    }

    /**
     * 从快照恢复执行（用户 CONFIRM 后）。
     */
    public void resumeFromSnapshot(TaskSnapshot snapshot, SseEmitter emitter) {
        log.info("从快照恢复执行 [round={}]", snapshot.getRound());
        // Phase 1 简化：重新执行规划
        planAndExecuteAsync(snapshot.getOriginalInput(), snapshot.getPartialResponse(),
                null, emitter);
    }
}
