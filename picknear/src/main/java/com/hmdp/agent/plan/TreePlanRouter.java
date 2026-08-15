package com.hmdp.agent.plan;

import com.hmdp.agent.config.FeatureProperties;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.routing.ToolIntentTree;
import com.hmdp.agent.routing.TreeCatalogBuilder;
import com.hmdp.agent.task.SubTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 意图→工具组两级路由策略（默认激活）。
 * <p>
 * 单次 LLM 调用内两段式：第一段输出意图路径 → 第二段只从声明路径对应工具输出计划。
 * Phase1 直解同样套意图树校验；树目录无命中节点时跳过规划调用（不回归全量）。
 * 规划 prompt 注入真实 userId（修复 LLM 编造 "self" 占位符）。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "feature.tool-routing.enabled", havingValue = "true", matchIfMissing = true)
public class TreePlanRouter implements PlanRouter {

    private final PlanSupport support;
    private final TreeCatalogBuilder treeCatalogBuilder;
    private final FeatureProperties featureProperties;
    private final ToolIntentTree intentTree;

    public TreePlanRouter(PlanSupport support, TreeCatalogBuilder treeCatalogBuilder,
                          FeatureProperties featureProperties, ToolIntentTree intentTree) {
        this.support = support;
        this.treeCatalogBuilder = treeCatalogBuilder;
        this.featureProperties = featureProperties;
        this.intentTree = intentTree;
    }

    @Override
    public PlanOutcome plan(PlanRequest req) {
        Set<String> matched = intentTree.matchNodes(req.userInput());
        // Phase1 直解（同样套树校验，堵住主回复解析绕过组路由的洞）
        List<SubTask> fromResponse = support.parseAndValidate(req, req.aiResponse(),
                ValidationOptions.tree(matched, req.userId()));
        if (!fromResponse.isEmpty()) {
            return PlanOutcome.of(fromResponse, "from_response");
        }
        // 树目录（剪枝到命中节点）
        String catalog = treeCatalogBuilder.build(req.toolCallbacks(), req.history(), maxTagLength(), req.userInput());
        log.debug("规划工具目录字符数={}（两级路由）", catalog.length());
        if (catalog.isBlank()) {
            log.info("[规划] 无命中意图组，跳过规划调用（不回归全量）");
            return PlanOutcome.of(List.of(), "empty");
        }
        String raw = support.plannerCall(req, catalog, PromptKeys.PLANNER_USER_V2);
        List<SubTask> tasks = support.parseAndValidate(req, raw, ValidationOptions.tree(matched, req.userId()));
        return PlanOutcome.of(tasks, tasks.isEmpty() ? "empty" : "ai_plan");
    }

    private int maxTagLength() {
        if (featureProperties == null || featureProperties.getToolRouting() == null) {
            return 60;
        }
        return featureProperties.getToolRouting().getMaxTagLength();
    }
}
