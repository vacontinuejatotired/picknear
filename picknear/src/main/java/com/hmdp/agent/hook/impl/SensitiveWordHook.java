package com.hmdp.agent.hook.impl;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.hook.HookResult;
import com.hmdp.agent.hook.PromptHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 敏感词脱敏 Hook（默认关闭）。
 * <p>
 * 基于 {@code originalInput} 做敏感词检测，若命中则返回 {@link HookResult#REPLACE} 替换为脱敏文本。
 * 安全检测基于原始输入，不受前置 Hook 修改影响。
 * </p>
 * <p>
 * 默认关闭（{@code agent.hook.sensitive-word.enabled=false}）：
 * 多轮历史回放上线后，Hook 只查当条输入、不扫历史，而落库存的是替换前原文，回放会把原文重新喂给后续模型
 * （等于绕过一次脱敏）。为避免该语义争议，暂时停用。
 * TODO（多轮脱敏）：重新启用时需同时解决"历史原文 vs hook 后文本"的落库语义，再决定开关默认值。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent.hook.sensitive-word", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class SensitiveWordHook implements PromptHook {

    /** 示例敏感词表（生产环境应从配置/数据库加载） */
    private static final List<String> SENSITIVE_WORDS = List.of(
            "攻击银行", "爆破", "炸弹"
    );

    @Override
    public HookResult beforePrompt(String originalInput, String currentInput, AgentContext context) {
        String replaced = originalInput;
        boolean hit = false;

        for (String word : SENSITIVE_WORDS) {
            if (replaced.contains(word)) {
                String masked = word.replaceAll(".", "*");
                replaced = replaced.replace(word, masked);
                hit = true;
                log.info("敏感词命中 [{}] -> [{}]", word, masked);
            }
        }

        if (hit) {
            return HookResult.replace(replaced, hookName());
        }
        return HookResult.pass();
    }
}
