package com.hmdp.agent.task;

import com.hmdp.agent.config.FeatureProperties;
import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.guard.ConfirmRequiredException;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.guard.ToolInvocationContext;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.routing.ToolRouter;
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
import com.hmdp.agent.hook.ChatContext;
import com.hmdp.agent.service.AgentHistoryService;
import io.micrometer.observation.Observation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
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
import java.util.stream.Collectors;

/**
 * 子任务规划器。
 * <p>
 * 核心循环：decompose() -> execute() -> merge()，最多 {@link #MAX_ROUNDS} 轮。
 * execute() 阶段根据 {@code feature.subagent.enabled} 走 SubTaskAgent 或原 TaskExecutor。
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
    private ToolRouter toolRouter;

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
            // 快照恢复路径 ctx=null 时无根 span，跳过 resume（Fail-Open，span 变孤儿仅 WARN）
            AgentSpan rootSpan = ctx != null ? ctx.getRootSpan() : null;
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
        if (!paused && ctx != null && ctx.getUserId() != null) {
            try {
                historyService.recordTurn(ctx.getUserId(), ctx.getConversationId(),
                        ctx.getOriginalContent(), result);
            } catch (Exception e) {
                log.error("记录 PLANNING 回合历史失败, conversationId={}", ctx.getConversationId(), e);
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

        for (int round = 0; round < MAX_ROUNDS; round++) {
            int r = round + 1;
            log.info("========== [Round {}] 1) 规划拆解 ==========", r);

            // 观测：每轮一个 round span（名称固定，semantic 区分轮次，架构文档 §5.1）
            try (AgentSpan roundSpan = agentTracer.start(AgentSpanSpec.ROUND, String.valueOf(r))) {
                try {
                    // decompose() 只返回 TOOL_CALL，不再追加 LLM_REASON
                    List<SubTask> tasks = decompose(input, currentResponse, toolCallbacks, history,
                            ctx != null ? ctx.getUserId() : null);
                    roundSpan.attribute("tool_count", String.valueOf(tasks.size()));
                    if (tasks.isEmpty()) {
                        roundSpan.attribute("plan_valid", "false");
                        log.warn("========== [Round {}] 2) 无需执行, 保持原回复 ==========", r);
                        return currentResponse;
                    }
                    roundSpan.attribute("plan_valid", "true");

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
                                    .userId(ctx != null ? ctx.getUserId() : null)
                                    .conversationId(ctx != null ? ctx.getConversationId() : null)
                                    .round(round)
                                    .build();

                            SubTaskExecution execution = SubTaskExecution.builder()
                                    .plan(plan)
                                    .callback(new SseSubAgentCallback(emitter))
                                    .properties(subTaskProperties)
                                    .startTimeMs(System.currentTimeMillis())
                                    .build();

                            SubTaskResult result = subTaskAgent.execute(execution);

                            subagentSpan.attribute("tool_count", String.valueOf(result.getRawResults() != null
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
                                ctx != null ? ctx.getUserId() : null,
                                ctx != null ? ctx.getConversationId() : null,
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
    // decompose 部分
    // ============================================================

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PLAN_RESPONSE_TRUNCATE = 200;
    private static final int RESULT_SUMMARY_LEN = 50;

    private static final String PLAN_START = "===PLAN_START===";
    private static final String PLAN_END = "===PLAN_END===";

    /**
     * 拆解子任务：优先从 AI 初次回复中解析工具调用 -> 兜底 AI 规划推理 -> Java 三层校验。
     * <p>
     * Phase1 AI 可能已直接输出 JSON 格式的工具调用规划，
     * 此时直接解析使用，避免重复问 AI 导致被"已有回复"误导。
     * 返回的列表只包含 TOOL_CALL 任务（不再追加 LLM_REASON）。
     * </p>
     */
    List<SubTask> decompose(String input, String response,
                            ToolCallback[] toolCallbacks, TaskReport history, Long userId) {
        // 观测：agent.plan（decompose 校验后结束，plan.tools[] 摘要 + 校验结论）
        try (AgentSpan planSpan = agentTracer.start(AgentSpanSpec.PLAN, null)) {
            // 1) 优先尝试从 AI 初次回复中解析工具调用（Phase1 可能已输出 JSON 规划）
            List<SubTask> fromResponse = validatePlan(response, toolCallbacks, history);
            if (!fromResponse.isEmpty()) {
                log.info("  [规划] 从 AI 初次回复中解析到 {} 个工具调用", fromResponse.size());
                planSpan.attribute("validate_result", "from_response");
                planSpan.attribute("plan.tools", fromResponse.stream()
                        .map(SubTask::getToolName).collect(Collectors.joining(",")));
                return fromResponse;
            }
            // 2) 回退：让 AI 做规划推理
            String planJson = askAiForPlan(input, response, userId, toolCallbacks, history);
            List<SubTask> tasks = validatePlan(planJson, toolCallbacks, history);
            planSpan.attribute("validate_result", tasks.isEmpty() ? "empty" : "ai_plan");
            planSpan.attribute("plan.tools", tasks.stream()
                    .map(SubTask::getToolName).collect(Collectors.joining(",")));
            return tasks;
        }
    }

    /**
     * 调 AI 做规划推理（编排：紧凑目录 → 调 LLM → 不确定则全量目录重跑一次）。
     * <p>
     * 目录构建/标签/不确定性判定全部在 router 侧，本方法只做编排；
     * __UNCERTAIN__ 在进 validatePlan 前由本层拦截（用全量重跑结果替换），
     * validatePlan 的 callbackIndex 仍保持全量，防止子集外工具被误判"工具不存在"。
     * </p>
     */
    private String askAiForPlan(String input, String response, Long userId,
                                 ToolCallback[] toolCallbacks,
                                 TaskReport history) {
        boolean compact = featureProperties.getToolRouting() != null
                && featureProperties.getToolRouting().isEnabled();
        String toolsDesc = toolRouter.buildCatalog(compact, toolCallbacks, history);
        log.debug("规划工具目录字符数={}", toolsDesc.length());
        String result = plannerCall(input, response, userId, toolsDesc, history);
        if (compact && toolRouter.isUncertain(result)) {
            log.info("[规划] 路由不确定，改用全量目录重试");
            result = plannerCall(input, response, userId,
                    toolRouter.buildCatalog(false, toolCallbacks, history), history);
        }
        return result;
    }

    /**
     * 渲染规划 prompt 并调用规划 LLM（失败降级返回 "[]"）。
     */
    private String plannerCall(String input, String response, Long userId,
                               String toolsDesc, TaskReport history) {
        List<String> completedSummary = history.getCompleted().stream()
                .map(t -> t.getToolName() + ": " + truncate(String.valueOf(t.getResult()), RESULT_SUMMARY_LEN))
                .toList();
        List<String> failedSummary = history.getFailed().stream()
                .map(t -> t.getToolName() + ": " + extractErrorType(String.valueOf(t.getResult())))
                .toList();

        Map<String, String> planVars = new LinkedHashMap<>();
        planVars.put("toolsDescription", toolsDesc);
        planVars.put("completedSummary", String.join("\n", completedSummary));
        planVars.put("failedSummary", String.join("\n", failedSummary));
        planVars.put("userInput", input);
        planVars.put("currentResponse", truncate(response, PLAN_RESPONSE_TRUNCATE));
        planVars.put("planStart", PLAN_START);
        planVars.put("planEnd", PLAN_END);

        try {
            String result = chatClient.prompt()
                    .system(promptService.render(PromptKeys.SYSTEM_PLANNER,
                            Map.of("userId", userId != null ? String.valueOf(userId) : "")))
                    .user(promptService.render(PromptKeys.PLANNER_USER, planVars))
                    .call().content();
            log.info("  [规划] AI 建议: {}", result);
            return result;
        } catch (Exception e) {
            log.warn("AI 规划请求失败", e);
            return "[]";
        }
    }

    /**
     * 从 AI 回复中提取标记之间的 JSON 内容。
     * 先找 ===PLAN_START=== / ===PLAN_END===，找不到则尝试从第一个 [ 或 { 开始提取。
     */
    private String extractPlanJson(String raw) {
        if (raw == null || raw.isBlank()) return "[]";

        // 优先从标记中提取
        int startIdx = raw.indexOf(PLAN_START);
        if (startIdx >= 0) {
            startIdx = startIdx + PLAN_START.length();
            int endIdx = raw.indexOf(PLAN_END, startIdx);
            if (endIdx >= 0) {
                return raw.substring(startIdx, endIdx).trim();
            }
            // 有开始标记但没结束标记 -> 从开始标记后取到末尾
            return raw.substring(startIdx).trim();
        }

        // 无标记时尝试找第一个 [ 或 {
        int bracket = raw.indexOf('[');
        if (bracket >= 0) {
            // 找匹配的 ]
            int close = findMatchingClose(raw, bracket, '[', ']');
            if (close >= 0) return raw.substring(bracket, close + 1);
        }
        int brace = raw.indexOf('{');
        if (brace >= 0) {
            int close = findMatchingClose(raw, brace, '{', '}');
            if (close >= 0) return raw.substring(brace, close + 1);
        }

        return "[]";
    }

    /** 找匹配的闭合符号，简单处理（不考虑嵌套） */
    private static int findMatchingClose(String s, int open, char openChar, char closeChar) {
        if (open < 0) return -1;
        for (int i = open + 1; i < s.length(); i++) {
            if (s.charAt(i) == closeChar) return i;
        }
        return -1;
    }

    /**
     * 三层校验：JSXON 语法 -> 工具存在性 -> 历史状态。
     * <p>
     * 不再追加 LLM_REASON，由调用方自行决定是否追加。
     * </p>
     */
    private List<SubTask> validatePlan(String planJson,
                                        ToolCallback[] toolCallbacks,
                                        TaskReport history) {
        // 0) 从原始文本中提取 JSON
        String json = extractPlanJson(planJson);

        // 1) JSON 语法校验
        List<Map<String, Object>> planEntries;
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray()) {
                log.warn("  [规划] 结果非 JSON 数组: {}", truncate(planJson, 100));
                return List.of();
            }
            planEntries = JSON.convertValue(root, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("规划结果 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }

        // 2) 构建工具名索引
        Map<String, ToolCallback> callbackIndex = new HashMap<>();
        if (toolCallbacks != null) {
            for (ToolCallback cb : toolCallbacks) {
                callbackIndex.put(GuardedToolCallback.rawName(cb), cb);
            }
        }

        // 3) 逐条校验
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

        return tasks;
    }

    // ============================================================
    // 工具方法
    // ============================================================

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
        snapshot.setConversationId(ctx != null ? ctx.getConversationId() : ic.getConversationId());
        snapshot.setUserId(ctx != null ? ctx.getUserId() : ic.getUserId());
        snapshot.setRootSpan(ctx != null ? ctx.getRootSpan() : null);
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
            AgentSpan rootSpan = ctx != null ? ctx.getRootSpan() : (snapshot != null ? snapshot.getRootSpan() : null);
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
        Long uid = ctx != null ? ctx.getUserId() : snapshot.getUserId();
        String cid = ctx != null ? ctx.getConversationId() : snapshot.getConversationId();
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

    /**
     * 从快照恢复执行（用户 CONFIRM 后）。
     */
    /**
     * 从快照恢复执行（旧 2 参版本已废弃，见上方 {@link #resumeFromSnapshot(TaskSnapshot, ChatContext, SseEmitter)}）。
     */
}
