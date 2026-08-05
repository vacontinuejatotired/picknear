package com.hmdp.agent.hook.impl;

import com.hmdp.agent.hook.ChatContext;
import com.hmdp.agent.hook.HookResult;
import com.hmdp.agent.hook.PromptHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 注入检测 Hook（示例实现）
 * <p>
 * 检测用户输入中是否包含 Prompt 注入攻击特征。
 * 基于 {@code originalInput} 做检测，不受前置 Hook 脱敏影响。
 * </p>
 */
@Slf4j
@Component
public class InjectionDetectHook implements PromptHook {

    /** 注入特征模式（示例，生产环境应更全面） */
    private static final List<String> INJECTION_PATTERNS = List.of(
            "忽略之前的指令",
            "ignore all previous",
            "you are now",
            "忘记你之前的角色",
            "system prompt:"
    );

    @Override
    public HookResult beforePrompt(String originalInput, String currentInput, ChatContext context) {
        String lower = originalInput.toLowerCase();

        for (String pattern : INJECTION_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                log.warn("检测到 Prompt 注入 [pattern={}]", pattern);
                return HookResult.block("检测到可疑的指令注入，该内容已被安全策略拦截（命中: " + pattern + "）", hookName());
            }
        }

        return HookResult.pass();
    }
}
