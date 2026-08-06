package com.hmdp.agent.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.guard.ConfirmRequiredException;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.subagent.model.SubTaskExecution;
import com.hmdp.agent.subagent.model.SubTaskResult;
import com.hmdp.agent.subagent.prompt.SubAgentPromptBuilder;
import com.hmdp.agent.subagent.prompt.SubAgentPromptTemplate;
import com.hmdp.agent.tool.ToolBeanCollector;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * 子任务执行 Agent。
 * <p>
 * 职责：接收 SubTaskExecution → 按 tasks 筛选 ToolCallback →
 * 调带工具的 ChatClient → 从回复中提取 JSON 数据快照 → 返回 SubTaskResult。
 * 执行 Prompt 与系统提示词经 {@link PromptService} 外置（Langfuse → 内置兜底）。
 * </p>
 */
@Slf4j
@Component
public class SubTaskAgent {

    @Resource
    @Qualifier("subAgentChatClient")
    private ChatClient subAgentChatClient;

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    private SubTaskProperties properties;

    @Resource
    private PromptService promptService;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 执行子任务计划，返回摘要。
     *
     * @param execution 执行上下文（含 plan、callback、配置）
     * @return 执行结果（含摘要 + 原始数据快照）
     */
    public SubTaskResult execute(SubTaskExecution execution) {
        long start = System.currentTimeMillis();
        var plan = execution.getPlan();
        var tasks = plan.getTasks();
        var callback = execution.getCallback();
        var props = execution.getProperties() != null ? execution.getProperties() : properties;

        // 提取去重后的工具名列表
        List<String> toolNames = tasks.stream()
                .map(t -> t.getToolName())
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // ═════ 入口结构化日志 ═════
        log.info("[SubAgent] 开始执行 [round={}, taskCount={}, tasks={}, userId={}]",
                plan.getRound(), tasks.size(), toolNames, plan.getUserId());

        // 1. 按 plan.tasks 筛选工具（同名工具去重，只保留一个 ToolCallback 实例）
        ToolCallback[] filteredCallbacks = filterCallbacks(toolNames);
        if (filteredCallbacks.length == 0) {
            log.warn("[SubAgent] 无可用的 ToolCallback [round={}, requested={}]",
                    plan.getRound(), toolNames);
            if (callback != null) callback.onError("无可用的工具");
            return SubTaskResult.builder()
                    .summary(plan.getCurrentResponse())
                    .allSuccess(false)
                    .errors(Map.of("subAgent", "无可用的工具"))
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 2. 推送：开始执行
        if (callback != null) callback.onExecuteStart(tasks.size());

        // 3. 构建执行 Prompt（模板外置，PromptService 渲染 {{var}}）
        String prompt = promptService.render(PromptKeys.SUBAGENT_EXECUTION,
                SubAgentPromptBuilder.buildVariables(plan));
        // 系统提示词渲染一次（缓存命中后开销≈0），重试循环不重复渲染
        String systemText = promptService.render(PromptKeys.SYSTEM_SUBAGENT,
                Map.of("userId", plan.getUserId() != null ? String.valueOf(plan.getUserId()) : ""));

        // 4. 带退避重试调用（含总超时保护），携带 userId / conversationId 作为 ToolContext
        String content = executeWithRetry(systemText, prompt, filteredCallbacks,
                props.getMaxRetries(), props.getRetryBackoff(),
                props.getTotalTimeout(), start, plan.getUserId(), plan.getConversationId());

        if (content == null) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[SubAgent] 执行失败（重试耗尽或超时） [round={}, elapsed={}ms]",
                    plan.getRound(), elapsed);
            if (callback != null) callback.onError("服务暂时不可用");
            return SubTaskResult.builder()
                    .summary("⚠️ 服务暂时不可用，请稍后重试。")
                    .allSuccess(false)
                    .errors(Map.of("subAgent", "重试耗尽"))
                    .executionTimeMs(elapsed)
                    .build();
        }

        // 5. 解析结果（从回复中提取 JSON 数据快照 + 数据截断 + 降级兜底）
        SubTaskResult result = parseResult(content, start);

        // 6. 推送：数据汇总完成
        if (callback != null) callback.onMergeStart();

        // ═════ 出口结构化日志（只打 key 列表，不打全量数据） ═════
        log.info("[SubAgent] 执行完成 [round={}, elapsed={}ms, allSuccess={}, toolResults={}]",
                plan.getRound(), result.getExecutionTimeMs(), result.isAllSuccess(),
                result.getRawResults() != null ? result.getRawResults().keySet() : "none");

        return result;
    }

    /**
     * 根据任务规划中的 toolName 筛选 ToolCallback。
     * 防止子 Agent 调用本轮未规划的工具（如写操作工具）。
     */
    private ToolCallback[] filterCallbacks(List<String> toolNames) {
        ToolCallback[] all = toolBeanCollector.getToolCallbacks();
        if (all == null) return new ToolCallback[0];
        Set<String> allowed = new HashSet<>(toolNames);
        return Arrays.stream(all)
                .filter(cb -> allowed.contains(cb.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 带指数退避和总超时控制的重试调用。
     * retryBackoff 为基础间隔，每次翻倍：1s → 2s → 4s
     * totalTimeout 为整个 execute() 的总超时（含重试），超时直接终止。
     */
    private String executeWithRetry(String systemText, String prompt, ToolCallback[] callbacks,
                                     int maxRetries, Duration retryBackoff,
                                     Duration totalTimeout, long roundStartMs,
                                     Long userId, String conversationId) {
        Exception lastError = null;
        String currentPrompt = prompt;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // 总超时检查
            if (System.currentTimeMillis() - roundStartMs > totalTimeout.toMillis()) {
                log.warn("[SubAgent] 执行总超时 [attempt={}/{}, elapsed>{}ms]",
                        attempt, maxRetries, totalTimeout.toMillis());
                break;
            }

            try {
                var promptBuilder = subAgentChatClient.prompt()
                        .system(systemText)
                        .user(currentPrompt)
                        .toolCallbacks(callbacks);
                // 将 userId / conversationId 以 ToolContext 传递，Guard 层才能获取到
                Map<String, Object> toolCtx = new HashMap<>();
                if (userId != null) {
                    toolCtx.put("userId", userId);
                }
                if (conversationId != null && !conversationId.isBlank()) {
                    toolCtx.put("conversationId", conversationId);
                }
                if (!toolCtx.isEmpty()) {
                    promptBuilder.toolContext(toolCtx);
                }
                String content = promptBuilder.call().content();
                log.info("[SubAgent] 调用成功 [attempt={}/{}]", attempt, maxRetries);
                return content;
            } catch (ConfirmRequiredException e) {
                // CONFIRM 审批信号：立即原样抛出（不重试、不把确认提示注入下次 prompt），
                // 一路冒泡到 TaskPlanner 的专用 catch 生成审批记录并暂停规划
                throw e;
            } catch (Exception e) {
                lastError = e;
                log.warn("[SubAgent] 调用失败 [attempt={}/{}], err={}",
                        attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    // 指数退避
                    long backoffMs = retryBackoff.toMillis() * (long) Math.pow(2, attempt - 1);
                    log.info("[SubAgent] {}ms 后重试...", backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // 标准化错误注入格式
                    currentPrompt = prompt + "\n\n[系统提示] 上一次调用失败，原因：" + e.getMessage() + "。请重试。";
                }
            }
        }
        log.error("[SubAgent] 重试耗尽 [maxRetries={}]", maxRetries, lastError);
        return null;
    }

    /**
     * 从 LLM 回复中提取 JSON 数据快照。
     * <p>
     * 子 Agent 的 Prompt 强制要求回复末尾附加：
     * ===DATA_SNAPSHOT===
     * { "toolName1": {"status":"ok","data":...} }
     * ===DATA_SNAPSHOT_END===
     * </p>
     *
     * 降级策略：
     * - LLM 未附加 JSON 快照 → rawResults={}，summary 取完整 content
     * - JSON 解析失败 → rawResults={}，摘要不变，日志记录警告
     * - data 字段超长（>RAW_DATA_MAX_LENGTH）→ 截断
     */
    private SubTaskResult parseResult(String content, long start) {
        long elapsed = System.currentTimeMillis() - start;

        String snapshotStr = extractSnapshot(content);
        Map<String, Object> rawResults = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        boolean allSuccess = true;
        List<String> executedTools = new ArrayList<>();

        if (snapshotStr != null) {
            try {
                Map<String, Object> snapshot = JSON.readValue(snapshotStr,
                        new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                    executedTools.add(entry.getKey());
                    if (entry.getValue() instanceof Map<?, ?> detail) {
                        String status = Objects.toString(detail.get("status"), "");
                        if ("error".equals(status)) {
                            allSuccess = false;
                            errors.put(entry.getKey(),
                                    Objects.toString(detail.get("message"), "未知错误"));
                        }
                        // 对 data 字段做 RAW_DATA_MAX_LENGTH 字符截断，防 Token 爆炸
                        Object data = detail.get("data");
                        if (data instanceof String s && s.length() > SubAgentPromptTemplate.RAW_DATA_MAX_LENGTH) {
                            data = s.substring(0, SubAgentPromptTemplate.RAW_DATA_MAX_LENGTH) + "...(截断)";
                        }
                        rawResults.put(entry.getKey(), data != null ? data : detail);
                    } else {
                        rawResults.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception e) {
                log.warn("[SubAgent] JSON 快照解析失败, 将使用完整回复作为摘要 [err={}]", e.getMessage());
            }
        } else {
            log.warn("[SubAgent] 未检测到 JSON 快照标记, rawResults 将为空");
        }

        // 摘要 = 去除 JSON 快照部分后的纯文本；无快照时全文作为摘要
        String summary = snapshotStr != null
                ? content.substring(0, content.indexOf(SubAgentPromptTemplate.SNAPSHOT_BEGIN)).trim()
                : content;

        return SubTaskResult.builder()
                .summary(summary)
                .rawResults(rawResults.isEmpty() ? null : rawResults)
                .errors(errors.isEmpty() ? null : errors)
                .allSuccess(allSuccess)
                .executedTools(executedTools)
                .executionTimeMs(elapsed)
                .build();
    }

    /** 提取 ===DATA_SNAPSHOT=== ... ===DATA_SNAPSHOT_END=== 之间的 JSON */
    private String extractSnapshot(String content) {
        if (content == null) return null;
        int startIdx = content.indexOf(SubAgentPromptTemplate.SNAPSHOT_BEGIN);
        if (startIdx < 0) return null;
        startIdx += SubAgentPromptTemplate.SNAPSHOT_BEGIN.length();
        int endIdx = content.indexOf(SubAgentPromptTemplate.SNAPSHOT_END, startIdx);
        if (endIdx < 0) return null;
        return content.substring(startIdx, endIdx).trim();
    }
}
