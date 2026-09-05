package com.hmdp.agent.honesty;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.hook.HookResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataAssertionHook — 数据意图强制规划兜底单测。
 * <p>
 * 覆盖：带数据意图标记 → PLANNING 且写 seed 覆盖；无标记 → PASS；context 为空 → PASS。
 * </p>
 */
class DataAssertionHookTest {

    private final DataAssertionHook hook = new DataAssertionHook();

    @Test
    void should_force_planning_when_data_intent_marked() {
        AgentContext ctx = AgentContext.builder().build();
        ctx.putAttribute(HonestyKeys.ATTR_DATA_INTENT, DataIntent.PLATFORM_STATS);

        HookResult result = hook.afterAi("平台有多少家店", "大约 200 家吧", ctx);

        assertThat(result.isPlanning()).isTrue();
        assertThat(ctx.attribute(HonestyKeys.ATTR_PLAN_SEED_OVERRIDE))
                .isEqualTo(HonestyKeys.PLAN_SEED_TEXT);
    }

    @Test
    void should_pass_without_data_intent() {
        AgentContext ctx = AgentContext.builder().build();

        HookResult result = hook.afterAi("你好", "你好，有什么可以帮你？", ctx);

        assertThat(result.isPass()).isTrue();
        assertThat(ctx.attribute(HonestyKeys.ATTR_PLAN_SEED_OVERRIDE)).isNull();
    }

    @Test
    void should_pass_when_context_null() {
        assertThat(hook.afterAi("多少家店", "回复", null).isPass()).isTrue();
    }
}
