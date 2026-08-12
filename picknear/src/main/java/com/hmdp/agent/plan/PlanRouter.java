package com.hmdp.agent.plan;

/**
 * 规划策略接口。
 * <p>
 * 每个策略封装完整的"产计划"流水线（Phase1 直解 → 目录构建 → 规划 LLM 调用 → 解析校验），
 * 由 {@code feature.tool-routing.enabled} 决定激活哪个实现（DI 选择，无布尔）。
 * </p>
 */
public interface PlanRouter {

    PlanOutcome plan(PlanRequest request);
}
