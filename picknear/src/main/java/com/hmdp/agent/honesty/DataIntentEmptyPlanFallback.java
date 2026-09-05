package com.hmdp.agent.honesty;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据意图 × 空计划 的诚实兜底（反编造 L1）。
 * <p>
 * 场景：数据类问题被强制进入规划，但规划阶段没有可用工具产出子任务（空计划）。
 * 此时不得把 Phase1 可能的编造文本（currentResponse）作为最终答案返回，应给一段诚实的
 * 兜底文案。由 MultiRoundOrchestrator 在 tasks.isEmpty() 且带数据意图标记时调用。
 * </p>
 */
@Slf4j
@Component
public class DataIntentEmptyPlanFallback {

    /** 兜底文案：明确"未查到"，不给编造空间 */
    public String fallbackText() {
        return "抱歉，我暂时没有查询到相关数据，换个问法或稍后再试。";
    }
}
