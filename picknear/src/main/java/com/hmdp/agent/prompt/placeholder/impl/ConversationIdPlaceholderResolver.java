package com.hmdp.agent.prompt.placeholder.impl;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.prompt.placeholder.PlaceholderResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 会话 ID 占位符解析器。
 * <p>
 * 解析 {@code {{conversationId}}} 占位符，从 {@link AgentContext} 获取当前会话 ID。
 * </p>
 *
 * <h3>示例</h3>
 * <pre>
 * 模板：当前会话 ID：{{conversationId}}，请记录。
 * 解析后：当前会话 ID：conv_abc123，请记录。
 * </pre>
 */
@Component
public class ConversationIdPlaceholderResolver implements PlaceholderResolver {

    /** 占位符 key */
    public static final String KEY = "conversationId";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Optional<String> resolve(AgentContext context) {
        if (context == null) {
            return Optional.empty();
        }
        String conversationId = context.conversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(conversationId);
    }
}
