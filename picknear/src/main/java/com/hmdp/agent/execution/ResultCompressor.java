package com.hmdp.agent.execution;

import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.observability.model.CallerType;
import com.hmdp.agent.util.TextUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 结果压缩器。
 * <p>
 * 把工具返回的原始结果压成 ≤ 上限字数的要点摘要。
 * </p>
 */
@Slf4j
@Component
public class ResultCompressor {

    @Resource
    @Qualifier("subAgentChatClient")
    private ChatClient compressChatClient;

    private static final String SYSTEM_TEMPLATE = """
            你是工具结果压缩器。把下面的工具返回结果压缩成不超过 %d 字的要点摘要，
            保留关键数字、名称、状态、错误信息，供后续步骤使用。只输出摘要，不要解释。
            """;

    public String compress(String raw, String toolName, int maxLength) {
        int limit = Math.max(1, maxLength);
        if (raw == null) return "（空结果）";
        if (raw.length() <= limit) return raw;
        ChatModelObservationConventionConfig.mark(CallerType.SUBAGENT_COMPRESS, toolName);
        try {
            String content = compressChatClient.prompt()
                    .system(SYSTEM_TEMPLATE.formatted(limit))
                    .user("工具 %s 返回：%n%s".formatted(toolName, raw))
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                return TextUtils.truncate(raw, limit);
            }
            return TextUtils.truncate(content.trim(), limit);
        } catch (Exception e) {
            log.warn("[Compressor] 压缩失败, 回退截断 [tool={}, err={}]", toolName, e.getMessage());
            return TextUtils.truncate(raw, limit);
        } finally {
            ChatModelObservationConventionConfig.clear();
        }
    }
}
