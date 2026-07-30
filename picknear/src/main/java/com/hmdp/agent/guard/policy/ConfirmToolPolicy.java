package com.hmdp.agent.guard.policy;

import com.hmdp.agent.config.PromptGuardProperties;
import com.hmdp.agent.guard.ToolGuardPolicy;
import com.hmdp.agent.guard.ToolInvocationContext;
import com.hmdp.agent.guard.Vote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 需确认工具名单策略
 * <p>
 * 从 {@link PromptGuardProperties#getConfirmTools()} 中读取精确匹配名单，
 * 如果工具名称在名单中，返回 {@link Vote#CONFIRM}。
 * </p>
 *
 * <pre>{@code
 * hmdp:
 *   prompt-guard:
 *     confirm-tools:
 *       - publishTestBlog
 *       - updateProfile
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmToolPolicy implements ToolGuardPolicy {

    private final PromptGuardProperties properties;

    @Override
    public Vote vote(ToolInvocationContext context) {
        if (properties.getConfirmTools().contains(context.getToolName())) {
            log.info("需确认工具匹配: tool={}", context.getToolName());
            return Vote.CONFIRM;
        }
        return Vote.ABSTAIN;
    }
}
