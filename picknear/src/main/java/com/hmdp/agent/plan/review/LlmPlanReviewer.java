package com.hmdp.agent.plan.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 工具规划审查器（预留实现）
 *
 * <p>使用 LLM 审查工具选择是否合理。</p>
 * <p>TODO: 实现 LLM 审查逻辑</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.plan-reviewer-enabled", havingValue = "true")
public class LlmPlanReviewer implements PlanReviewer {

    // TODO: 注入 ChatModel

    @Override
    public ReviewResult review(List<String> selectedTools, String userInput) {
        log.warn("LlmPlanReviewer 尚未实现，直接通过");

        return ReviewResult.builder()
            .approved(true)
            .reason("审查功能预留")
            .build();
    }
}
