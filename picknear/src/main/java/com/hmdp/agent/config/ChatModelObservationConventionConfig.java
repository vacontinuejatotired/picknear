package com.hmdp.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.observability.support.TraceProperties;
import io.micrometer.common.KeyValues;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义 {@link ChatModelObservationConvention}：按调用方区分 Langfuse 里的 generation 名，
 * 并补发 LLM 请求/回复全文（默认 convention 不发，Langfuse input/output 因此为 null）。
 * <p>
 * 背景（Langfuse 云接入说明 §5.6）：Langfuse 云版 OTLP 路径下<b>自定义 span attributes 不展示</b>，
 * 关键业务语义必须编码进 span 名才可见。默认 ChatModel 观察的 span 名是
 * {@code chat <model>}（如 {@code chat qwen-plus}），主代理与子代理共用同一
 * {@code OpenAiChatModel} Bean，Langfuse 里全部显示为同一个 AI 名字，无法区分。
 * </p>
 * <p>
 * 本约定在 {@link #mark(String)} 标记的子代理调用前，给 span 名加前缀：
 * 子代理 → {@code subagent-chat qwen-plus}，主代理（未标记）→ {@code chat qwen-plus}。
 * observation 名（{@code gen_ai.client.operation}）不变，白名单与统计不受影响。
 * </p>
 * <p>
 * content 补发：Spring AI 1.1.2 的 {@link DefaultChatModelObservationConvention} 只发
 * usage/参数/finish_reason，不产生 {@code gen_ai.request.content}/{@code gen_ai.response.content}。
 * 这里在 {@link #getHighCardinalityKeyValues} 手动补上，Langfuse 据此渲染 input/output。
 * 内容先经 {@link AttributeSanitizer} 脱敏（手机号/邮箱/身份证 + 截断），开关
 * {@code hmdp.ai-observability.chat-observation.include-content}（默认 true）。
 * </p>
 */
@Slf4j
@Configuration
public class ChatModelObservationConventionConfig {

    /** 调用方标记（ThreadLocal，随调用线程传递）：{@code subagent} 等；空 = 主代理默认名 */
    private static final ThreadLocal<String> CALLER = new ThreadLocal<>();

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 子代理等调用方在调模型前打标，finally 中必须调 {@link #clear()} */
    public static void mark(String caller) {
        CALLER.set(caller);
    }

    public static void clear() {
        CALLER.remove();
    }

    @Bean
    public ChatModelObservationConvention chatModelObservationConvention(TraceProperties traceProperties,
                                                                         AttributeSanitizer sanitizer) {
        log.info("[obs-convention] 已加载自定义 ChatModelObservationConvention（含 content 补发），includeContent={}",
                traceProperties.getChatObservation().isIncludeContent());
        return new DefaultChatModelObservationConvention() {
            @Override
            public String getContextualName(ChatModelObservationContext context) {
                String base = super.getContextualName(context); // 默认 "chat <model>"
                String caller = CALLER.get();
                if (caller == null || caller.isBlank()) {
                    return base;
                }
                return caller + "-" + base; // "subagent-chat <model>"
            }

            @Override
            public KeyValues getHighCardinalityKeyValues(ChatModelObservationContext context) {
                KeyValues keyValues = super.getHighCardinalityKeyValues(context);
                if (!traceProperties.getChatObservation().isIncludeContent()) {
                    log.info("[obs-convention] includeContent=false，跳过 content 补发");
                    return keyValues;
                }
                String requestContent = toRequestContentJson(context.getRequest(), sanitizer);
                if (requestContent != null) {
                    keyValues = keyValues.and("gen_ai.request.content", requestContent);
                    log.info("[obs-convention] 写入 gen_ai.request.content，len={}", requestContent.length());
                } else {
                    log.info("[obs-convention] 无 request content（request 为 null 或无消息）");
                }
                String responseContent = toResponseContent(context.getResponse(), sanitizer);
                if (responseContent != null) {
                    keyValues = keyValues.and("gen_ai.response.content", responseContent);
                    log.info("[obs-convention] 写入 gen_ai.response.content，len={}", responseContent.length());
                } else {
                    log.info("[obs-convention] 无 response content（response 为 null 或无文本）");
                }
                return keyValues;
            }
        };
    }

    /**
     * 请求消息 → {@code [{"role":...,"content":...}, ...]} JSON 数组（Langfuse 按角色渲染输入）。
     * tool 消息在工具循环里即"工具结果回灌"段，随轮次累积——正好可视化上下文膨胀。
     * 注意 tool 消息的文本在 {@code getResponses()} 而非 getText()，须单独提取，
     * 否则 Langfuse 里工具结果全是空串（本次观测发现的坑）。
     */
    private static String toRequestContentJson(Prompt request, AttributeSanitizer sanitizer) {
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
            return JSON.writeValueAsString(arr);
        } catch (Exception e) {
            return null;
        }
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

    /** 回复消息文本（多代以换行连接）；无有效输出返回 null */
    private static String toResponseContent(ChatResponse response, AttributeSanitizer sanitizer) {
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
}
