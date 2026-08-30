package com.hmdp.agent.hook.impl;

import com.hmdp.agent.hook.HookResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensitiveWordHook — 敏感词脱敏测试。
 * <p>
 * 纯逻辑，无外部依赖，直接 new 即可。
 */
class SensitiveWordHookTest {

    private final SensitiveWordHook hook = new SensitiveWordHook();

    @Test
    void should_replace_when_sensitive_word_found() {
        HookResult result = hook.beforePrompt("我想攻击银行系统", null, null);

        assertThat(result.isReplace())
                .as("命中敏感词应返回 REPLACE")
                .isTrue();
        assertThat(result.getReplacedText())
                .as("敏感词应被替换为 *")
                .isEqualTo("我想****系统");
    }

    @Test
    void should_pass_when_no_sensitive_word() {
        HookResult result = hook.beforePrompt("今天天气不错", null, null);

        assertThat(result.isPass())
                .as("无敏感词时应放行")
                .isTrue();
    }

    @Test
    void should_replace_multiple_sensitive_words() {
        HookResult result = hook.beforePrompt("用炸弹攻击银行", null, null);

        assertThat(result.getReplacedText())
                .as("多个敏感词应全部替换")
                .isEqualTo("用******");
    }
}
