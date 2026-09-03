package com.hmdp.agent.observability.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatContentSerializer} 单测（纯静态，无 Spring 上下文）。
 * 覆盖评测取数链路依赖的两个序列化路径 + TOOL_CALLS 兜底（2026-09-02）。
 */
class ChatContentSerializerTest {

    private final AttributeSanitizer sanitizer = new AttributeSanitizer();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toRequestContentJson_returnsMessageArray() {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("你是助手"),
                new UserMessage("查天气")));

        String json = ChatContentSerializer.toRequestContentJson(prompt, sanitizer, mapper);

        assertThat(json).contains("\"role\":\"system\"", "\"content\":\"你是助手\"",
                "\"role\":\"user\"", "\"content\":\"查天气\"");
    }

    @Test
    void toRequestContentJson_nullRequest_returnsNull() {
        assertThat(ChatContentSerializer.toRequestContentJson(null, sanitizer, mapper)).isNull();
    }

    @Test
    void toResponseContent_plainText_returnsText() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(AssistantMessage.builder().content("长沙晴天").build())));

        assertThat(ChatContentSerializer.toResponseContent(response, sanitizer))
                .isEqualTo("长沙晴天");
    }

    @Test
    void toResponseContent_toolCallsWithoutText_serializesToolCalls() {
        AssistantMessage toolCallMsg = AssistantMessage.builder()
                .toolCalls(List.of(
                        new ToolCall("call-1", "function", "queryWeather", "{\"city\":\"长沙\"}"),
                        new ToolCall("call-2", "function", "queryPublishedBlogs", "{}")))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(toolCallMsg)));

        assertThat(ChatContentSerializer.toResponseContent(response, sanitizer))
                .isEqualTo("[调用工具] queryWeather({\"city\":\"长沙\"})\n[调用工具] queryPublishedBlogs({})");
    }

    @Test
    void toResponseContent_nullResponse_returnsNull() {
        assertThat(ChatContentSerializer.toResponseContent(null, sanitizer)).isNull();
        assertThat(ChatContentSerializer.toResponseContent(
                new ChatResponse(List.of()), sanitizer)).isNull();
    }
}
