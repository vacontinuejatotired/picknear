package com.hmdp.agent.hook.impl;

import com.hmdp.agent.hook.ChatContext;
import com.hmdp.agent.hook.HookResult;
import com.hmdp.agent.hook.PromptHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 敏感词脱敏 Hook（示例实现）
 * <p>
 * 基于 {@code originalInput} 做敏感词检测，若命中则返回 {@link HookResult#REPLACE} 替换为脱敏文本。
 * 安全检测基于原始输入，不受前置 Hook 修改影响。
 * </p>
 */
@Slf4j
@Component
public class SensitiveWordHook implements PromptHook {

    /** 示例敏感词表（生产环境应从配置/数据库加载） */
    private static final List<String> SENSITIVE_WORDS = List.of(
            "攻击银行", "爆破", "炸弹"
    );

    @Override
    public HookResult beforePrompt(String originalInput, String currentInput, ChatContext context) {
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
