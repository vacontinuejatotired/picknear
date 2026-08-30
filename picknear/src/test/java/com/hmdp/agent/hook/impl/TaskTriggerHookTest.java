package com.hmdp.agent.hook.impl;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.hook.HookResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskTriggerHook — 触发词检测测试。
 * <p>
 * 纯逻辑，无外部依赖，直接 new 即可。
 */
class TaskTriggerHookTest {

    private final TaskTriggerHook hook = new TaskTriggerHook();

    @Test
    void should_return_planning_when_trigger_word_found() {
        HookResult result = hook.afterAi("统计一下数据", "好的，我来帮你统计数据的结果和变化趋势，请稍等一下", AgentContext.builder().build());

        assertThat(result.isPlanning())
                .as("输入含'统计'应进规划")
                .isTrue();
    }

    @Test
    void should_return_pass_when_response_too_short() {
        HookResult result = hook.afterAi("统计一下", "ok", AgentContext.builder().build());

        assertThat(result.isPass())
                .as("回复过短（<20字符）不应规划")
                .isTrue();
    }

    @Test
    void should_return_pass_when_response_contains_unable() {
        HookResult result = hook.afterAi("统计一下", "抱歉，我无法完成这个操作", AgentContext.builder().build());

        assertThat(result.isPass())
                .as("回复含'无法'不应规划")
                .isTrue();
    }

    @Test
    void should_return_pass_when_no_trigger_word() {
        HookResult result = hook.afterAi("你好", "你好！我是你的AI助手，有什么可以帮你的？", AgentContext.builder().build());

        assertThat(result.isPass())
                .as("无触发词应放行")
                .isTrue();
    }
}
