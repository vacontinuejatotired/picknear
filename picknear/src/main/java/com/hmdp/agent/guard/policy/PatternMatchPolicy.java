package com.hmdp.agent.guard.policy;

import com.hmdp.agent.config.PromptGuardProperties.PatternRule;
import com.hmdp.agent.config.PromptGuardProperties;
import com.hmdp.agent.guard.ToolGuardPolicy;
import com.hmdp.agent.guard.ToolInvocationContext;
import com.hmdp.agent.guard.Vote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 正则匹配策略
 * <p>
 * 从配置中读取正则模式，匹配工具名称或参数 JSON。
 * 支持两种匹配结果：{@link Vote#BLOCK}（命中 {@code blockPatterns}）
 * 和 {@link Vote#CONFIRM}（命中 {@code confirmPatterns}）。
 * </p>
 *
 * <pre>{@code
 * hmdp:
 *   prompt-guard:
 *     block-patterns:
 *       - toolName: ".*[Dd]elete.*"
 *       - arguments: "\"role\":\"admin\""
 *     confirm-patterns:
 *       - toolName: ".*[Pp]ublish.*"
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatternMatchPolicy implements ToolGuardPolicy {

    private final PromptGuardProperties properties;

    @Override
    public Vote vote(ToolInvocationContext context) {
        // 检查拦截模式
        if (matchesAny(context, properties.getBlockPatterns())) {
            log.info("正则拦截匹配: tool={}", context.getToolName());
            return Vote.BLOCK;
        }

        // 检查确认模式
        if (matchesAny(context, properties.getConfirmPatterns())) {
            log.info("正则确认匹配: tool={}", context.getToolName());
            return Vote.CONFIRM;
        }

        return Vote.ABSTAIN;
    }

    private boolean matchesAny(ToolInvocationContext context, java.util.List<PatternRule> rules) {
        for (PatternRule rule : rules) {
            if (rule.getToolName() != null && !rule.getToolName().isEmpty()
                    && context.getToolName() != null
                    && context.getToolName().matches(rule.getToolName())) {
                return true;
            }
            if (rule.getArguments() != null && !rule.getArguments().isEmpty()
                    && context.getArguments() != null
                    && context.getArguments().matches(rule.getArguments())) {
                return true;
            }
        }
        return false;
    }
}
