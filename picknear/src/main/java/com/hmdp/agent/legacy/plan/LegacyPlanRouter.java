package com.hmdp.agent.legacy.plan;

import com.hmdp.agent.legacy.plan.ToolRouter;
import com.hmdp.agent.plan.model.PlanOutcome;
import com.hmdp.agent.plan.model.PlanRequest;
import com.hmdp.agent.plan.PlanRouter;
import com.hmdp.agent.plan.PlanSupport;
import com.hmdp.agent.plan.model.ValidationOptions;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.plan.model.SubTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * legacy 规划策略（紧凑目录 + __UNCERTAIN__ 全量重跑）。
 * <p>
 * 与现状零行为差异：Phase1 直解不套树校验；紧凑目录按工具级 TRIGGER_KEYWORDS 过滤；
 * 识别不出时用全量目录重跑一次。由 {@code feature.tool-routing.enabled=false} 激活。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "feature.tool-routing.enabled", havingValue = "false")
public class LegacyPlanRouter implements PlanRouter {

    private final PlanSupport support;
    private final ToolRouter toolRouter;

    public LegacyPlanRouter(PlanSupport support, ToolRouter toolRouter) {
        this.support = support;
        this.toolRouter = toolRouter;
    }

    @Override
    public PlanOutcome plan(PlanRequest req) {
        // Phase1 直解（legacy 不套树校验）
        List<SubTask> fromResponse = support.parseAndValidate(req, req.aiResponse(),
                ValidationOptions.legacy(req.userId()));
        if (!fromResponse.isEmpty()) {
            return PlanOutcome.of(fromResponse, "from_response");
        }
        // 紧凑目录 → 规划 LLM
        String catalog = toolRouter.buildCatalog(true, req.toolCallbacks(), req.history(), req.userInput());
        log.debug("规划工具目录字符数={}（legacy 紧凑目录）", catalog.length());
        String raw = support.plannerCall(req, catalog, PromptKeys.PLANNER_USER);
        if (toolRouter.isUncertain(raw)) {
            log.info("[规划] 路由不确定，改用全量目录重试");
            raw = support.plannerCall(req,
                    toolRouter.buildCatalog(false, req.toolCallbacks(), req.history(), req.userInput()),
                    PromptKeys.PLANNER_USER);
        }
        List<SubTask> tasks = support.parseAndValidate(req, raw, ValidationOptions.legacy(req.userId()));
        return PlanOutcome.of(tasks, tasks.isEmpty() ? "empty" : "ai_plan");
    }
}
