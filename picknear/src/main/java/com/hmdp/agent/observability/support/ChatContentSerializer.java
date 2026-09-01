package com.hmdp.agent.observability.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat 消息内容 → Langfuse 渲染格式的序列化器（从 ChatModelObservationConventionConfig 抽出，
 * 纯静态无状态，可独立单测）。
 * <p>
 * 用途：Langfuse 云版 OTLP 路径下自定义 span attributes 不展示，content 经
 * {@code langfuse.observation.input}/{@code langfuse.observation.output} 补发
 * （Langfuse 转译后落 observation 主字段；2026-09-01 由 gen_ai.request/response.content
 * 改为 SDK 协议 key，修复主字段恒 null——旧 key 不被 Langfuse 提取器识别）。
 * 所有文本先经 {@link AttributeSanitizer} 脱敏（手机号/邮箱/身份证 + 截断）。
 * </p>
 */
public final class ChatContentSerializer {

    private ChatContentSerializer() {
    }

    /**
     * 请求消息 → {@code [{"role":...,"content":...}, ...]} JSON 数组（Langfuse 按角色渲染输入）。
     * tool 消息在工具循环里即"工具结果回灌"段，随轮次累积——正好可视化上下文膨胀。
     * 注意 tool 消息的文本在 {@code getResponses()} 而非 getText()，须单独提取，
     * 否则 Langfuse 里工具结果全是空串（观测发现的坑）。
     */
    public static String toRequestContentJson(Prompt request, AttributeSanitizer sanitizer, ObjectMapper json) {
        if (request == null) {
            return null;
        }
        List<Message> messages = request.getInstructions();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> arr = new ArrayList<>();
        for (Message message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.getMessageType().getValue());
            item.put("content", messageContent(message, sanitizer));
            arr.add(item);
        }
        try {
            return json.writeValueAsString(arr);
        } catch (Exception e) {
            return null;
        }
    }

    /** 回复消息文本（多代以换行连接）；无有效输出返回 null */
    public static String toResponseContent(ChatResponse response, AttributeSanitizer sanitizer) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Generation generation : response.getResults()) {
            String text = generation.getOutput() != null
                    ? generation.getOutput().getText() : null;
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(sanitizer.sanitizeDiagnostic(text));
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** 提取单条消息的可见文本：tool 结果、工具调用、普通文本；脱敏 + 截断后返回 */
    private static String messageContent(Message message, AttributeSanitizer sanitizer) {
        String text;
        if (message instanceof ToolResponseMessage toolMsg && toolMsg.getResponses() != null) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse tr : toolMsg.getResponses()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("工具 ").append(tr.name()).append(" 返回：")
                        .append(tr.responseData() != null ? tr.responseData() : "");
            }
            text = sb.toString();
        } else if (message instanceof AssistantMessage am && am.hasToolCalls()) {
            StringBuilder sb = new StringBuilder();
            String body = am.getText();
            if (body != null && !body.isBlank()) {
                sb.append(body);
            }
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("[调用工具] ").append(tc.name())
                        .append("(").append(tc.arguments()).append(")");
            }
            text = sb.toString();
        } else {
            text = message.getText();
        }
        return text == null || text.isEmpty() ? "" : sanitizer.sanitizeDiagnostic(text);
    }
}
