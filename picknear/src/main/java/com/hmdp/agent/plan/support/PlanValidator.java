package com.hmdp.agent.plan.support;

import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.plan.model.ParsedPlan;
import com.hmdp.agent.plan.model.ValidationOptions;
import com.hmdp.agent.plan.intent.ToolIntentTree;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.plan.model.TaskType;
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
                    .description(taskDescription(entry, toolName, callbackIndex.get(toolName)))
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

    /**
     * 任务可读描述（面向任务语义，而非"执行工具: X"）：
     * ① 规划条目显式给了 description（含业务语义）→ 优先采用；
     * ② 否则用工具定义的可读描述（能力一句话）；
     * ③ 兜底工具名。避免技术味术语进任务清单。
     */
    private static String taskDescription(Map<String, Object> entry, String toolName, ToolCallback cb) {
        Object declared = entry.get("description");
        if (declared instanceof String s && !s.isBlank()) {
            String t = s.trim();
            if (!t.contains(toolName)) {
                return compact(t, 24);
            }
        }
        if (cb != null) {
            var def = cb.getToolDefinition();
            if (def != null) {
                String d = def.description();
                if (d != null && !d.isBlank()) {
                    return compact(d, 24);
                }
            }
        }
        return toolName;
    }

    /** 取一句可读描述：优先切在最早的句末标点/逗号（避免半句截断带引号），超长才补省略号 */
    private static String compact(String s, int max) {
        String t = s.trim();
        int cut = -1;
        String[] seps = {"。」", "」", "。", "！", "？", "；", "，", ","};
        for (String sep : seps) {
            int i = t.indexOf(sep);
            if (i >= 0 && (cut < 0 || i < cut)) cut = i;
        }
        if (cut >= 0) {
            t = t.substring(0, cut).trim();
        }
        return t.length() <= max ? t : t.substring(0, max) + "…";
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
