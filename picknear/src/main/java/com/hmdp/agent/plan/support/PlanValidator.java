package com.hmdp.agent.plan.support;

import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.plan.model.ParsedPlan;
import com.hmdp.agent.plan.model.ValidationOptions;
import com.hmdp.agent.routing.ToolIntentTree;
import com.hmdp.agent.task.model.SubTask;
import com.hmdp.agent.task.model.SubTaskStatus;
import com.hmdp.agent.task.model.TaskReport;
import com.hmdp.agent.task.model.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 计划校验器：ParsedPlan → 校验 → List&lt;SubTask&gt;（TOOL_CALL）。
 * <p>
 * 校验顺序：工具存在（callbackIndex 全量构建，防子集外工具被误判不存在）→ 历史状态 →
 * 意图树归属（enforceTree 时；声明意图优先，空则退关键词命中节点）→ userId 占位符解析。
 * 不匹配的条目丢弃 + WARN，不拒绝整单。
 * </p>
 */
@Slf4j
@Component
public class PlanValidator {

    private final ToolIntentTree intentTree;

    public PlanValidator(ToolIntentTree intentTree) {
        this.intentTree = intentTree;
    }

    /**
     * 校验并构建任务。
     *
     * @param parsed    解析后的计划
     * @param callbacks 全量工具回调（内部建全量索引）
     * @param history   历史报告（已完成/终失败工具跳过）
     * @param opts      校验选项（legacy 不套树；tree 套归属校验）
     */
    public List<SubTask> validate(ParsedPlan parsed, ToolCallback[] callbacks,
                                  TaskReport history, ValidationOptions opts) {
        Map<String, ToolCallback> callbackIndex = buildIndex(callbacks);
        Set<String> allowed = resolveAllowed(parsed, opts);

        List<SubTask> tasks = new ArrayList<>();
        for (Map<String, Object> entry : parsed.entries()) {
            String toolName = entry.get("tool") instanceof String s ? s : null;
            if (toolName == null || toolName.isBlank()) {
                log.warn("  [规划] 缺少 tool 字段: {}", entry);
                continue;
            }
            if (!callbackIndex.containsKey(toolName)) {
                log.warn("  [规划] 工具不存在: {}", toolName);
                continue;
            }
            if (history.isCompleted(toolName)) {
                continue;
            }
            if (history.isFinalFailed(toolName)) {
                continue;
            }
            if (opts.enforceTree() && !intentTree.toolIn(toolName, allowed)) {
                log.warn("  [规划] 工具 {} 不属于声明的意图节点 {}, 丢弃", toolName, allowed);
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> params = entry.get("params") instanceof Map
                    ? (Map<String, Object>) entry.get("params") : null;
            Map<String, Object> safeParams = params != null ? new HashMap<>(params) : new HashMap<>();
            UserIdPlaceholderResolver.resolveParams(safeParams, toolName, opts.userId());

            tasks.add(SubTask.builder()
                    .id(UUID.randomUUID().toString())
                    .description("执行工具: " + toolName)
                    .type(TaskType.TOOL_CALL)
                    .toolName(toolName)
                    .params(safeParams)
                    .status(SubTaskStatus.PENDING)
                    .build());
            log.info("  [规划] 需执行 [tool={}, params={}]", toolName, safeParams);
        }
        return tasks;
    }

    /** 声明意图优先，空则退关键词命中节点（仅 enforceTree 生效） */
    private Set<String> resolveAllowed(ParsedPlan parsed, ValidationOptions opts) {
        if (!opts.enforceTree()) {
            return Set.of();
        }
        Set<String> declared = intentTree.resolveIntents(parsed.declaredIntents());
        return declared.isEmpty() ? opts.fallbackNodes() : declared;
    }

    private static Map<String, ToolCallback> buildIndex(ToolCallback[] callbacks) {
        Map<String, ToolCallback> index = new HashMap<>();
        if (callbacks != null) {
            for (ToolCallback cb : callbacks) {
                index.put(GuardedToolCallback.rawName(cb), cb);
            }
        }
        return index;
    }
}
