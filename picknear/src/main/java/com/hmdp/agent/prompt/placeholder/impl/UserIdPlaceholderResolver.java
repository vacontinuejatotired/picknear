package com.hmdp.agent.prompt.placeholder.impl;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.prompt.placeholder.PlaceholderResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 用户 ID 占位符解析器。
 * <p>
 * 解析 {@code {{userId}}} 占位符，从 {@link AgentContext} 获取当前登录用户 ID。
 * </p>
 *
 * <h3>示例</h3>
 * <pre>
 * 模板：你的用户 ID 是 {{userId}}，请妥善保管。
 * 解析后：你的用户 ID 是 12345，请妥善保管。
 * </pre>
 */
@Component
public class UserIdPlaceholderResolver implements PlaceholderResolver {

    /** 占位符 key */
    public static final String KEY = "userId";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Optional<String> resolve(AgentContext context) {
        if (context == null) {
            return Optional.empty();
        }
        Long userId = context.userId();
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(userId));
    }
}
