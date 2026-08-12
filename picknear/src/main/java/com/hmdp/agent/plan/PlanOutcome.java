package com.hmdp.agent.plan;

import com.hmdp.agent.task.SubTask;

import java.util.List;

/**
 * 规划结果（TaskPlanner 观测用）。
 *
 * @param tasks  校验通过的工具任务（TOOL_CALL）
 * @param source 产出来源：from_response（Phase1 直解）/ ai_plan（规划 LLM）/ empty（空计划）
 */
public record PlanOutcome(List<SubTask> tasks, String source) {

    public static PlanOutcome of(List<SubTask> tasks, String source) {
        return new PlanOutcome(tasks, source);
    }
}
