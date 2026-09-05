package com.hmdp.agent.history.compression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.config.ContextCompressionProperties;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.observability.model.CallerType;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 对话摘要器 — 压缩小模型（compressChatClient，独立可配）一次返回 JSON {summary,keyData,truncated}。
 * 解析失败抛异常 → 调用方不推进游标（由"下一次写回合"自然重试）；压缩旁路失败绝不阻断请求主链。
 */
@Slf4j
@Component
public class LlmConversationSummarizer implements ConversationSummarizer {

    @Resource(name = "compressChatClient")
    private ChatClient compressChatClient;

    @Resource
    private SummarizePromptComposer composer;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ContextCompressionProperties properties;

    @Override
    public SummaryResult summarize(String currentSummary, List<AgentMessage> batch) {
        String content;
        try {
            ChatModelObservationConventionConfig.mark(CallerType.SUBAGENT_COMPRESS, "conv");
            content = compressChatClient.prompt()
                    .system(composer.system(properties.getSummary().getMaxTokens()))
                    .user(composer.user(currentSummary, batch))
                    .call()
                    .content();
        } finally {
            ChatModelObservationConventionConfig.clear();
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("压缩模型返回空内容");
        }
        return parse(content);
    }

    private SummaryResult parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            String summary = root.path("summary").asText(null);
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("压缩模型 JSON 缺少 summary");
            }
            List<String> keyData = new ArrayList<>();
            if (root.has("keyData") && root.path("keyData").isArray()) {
                root.path("keyData").forEach(n -> keyData.add(n.asText()));
            }
            boolean truncated = root.path("truncated").asBoolean(false);
            return new SummaryResult(summary, keyData, truncated);
        } catch (Exception e) {
            throw new IllegalStateException("压缩模型未返回合法 JSON：content=" + truncate(content), e);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}