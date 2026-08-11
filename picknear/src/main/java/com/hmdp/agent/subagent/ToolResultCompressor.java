package com.hmdp.agent.subagent;

import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.util.TextUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 工具结果压缩器。
 * <p>
 * 把工具返回的原始结果压成 ≤ 上限字数的要点摘要，供后续工具循环步骤使用。
 * 手动工具循环（{@link ToolCallLoopExecutor}）只把压缩摘要存入上下文、不存原文，
 * 每轮上下文增量 ≈ 压缩摘要长度，杜绝滚雪球（见设计文档 §4.4）。
 * </p>
 */
@Slf4j
@Component
public class ToolResultCompressor {

    @Resource
    @Qualifier("subAgentChatClient")
    private ChatClient compressChatClient;

    private static final String SYSTEM_TEMPLATE = """
            你是工具结果压缩器。把下面的工具返回结果压缩成不超过 %d 字的要点摘要，
            保留关键数字、名称、状态、错误信息，供后续步骤使用。只输出摘要，不要解释。
            """;

    /**
     * 把工具原始结果压成 ≤ maxLength 字的要点摘要。
     * <p>
     * 结果本身已短于上限直接返回（省一次 LLM 调用）；LLM 压缩失败/为空回退截断，不阻断流程。
     * </p>
     */
    public String compress(String raw, String toolName, int maxLength) {
        int limit = Math.max(1, maxLength);
        if (raw == null) return "（空结果）";
        if (raw.length() <= limit) return raw;
        // 打 subagent-compress 标记：Langfuse generation 名 = subagent-compress-chat <model>
        ChatModelObservationConventionConfig.mark("subagent-compress");
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
