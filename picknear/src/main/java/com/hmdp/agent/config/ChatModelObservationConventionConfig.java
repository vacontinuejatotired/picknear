package com.hmdp.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.observability.support.ChatContentSerializer;
import com.hmdp.agent.observability.support.TraceProperties;
import io.micrometer.common.KeyValues;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

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
 * 本约定在 {@link #mark(String)} / {@link #mark(String, String)} 标记的子代理调用前，给 span 名加前缀：
 * 子代理 → {@code subagent-chat qwen-plus}，携带任务标识 → {@code subagent-exec-query-weather-chat qwen-plus}
 * （任务标识把"这次调用在驱动哪个任务"编码进名，Langfuse 控制台免点击直读），
 * 主代理（未标记）→ {@code chat qwen-plus}。
 * observation 名（{@code gen_ai.client.operation}）不变，白名单与统计不受影响。
 * </p>
 * <p>
 * content 补发：Spring AI 1.1.2 的 {@link DefaultChatModelObservationConvention} 只发
 * usage/参数/finish_reason，不产生 {@code gen_ai.request.content}/{@code gen_ai.response.content}。
 * 这里在 {@link #getHighCardinalityKeyValues} 手动补上，Langfuse 据此渲染 input/output。
 * 序列化/脱敏逻辑在 {@link ChatContentSerializer}（纯静态，可独立单测），开关
 * {@code hmdp.ai-observability.chat-observation.include-content}（默认 true）。
 * </p>
 */
@Slf4j
@Configuration
public class ChatModelObservationConventionConfig {

    /** 调用方标记（ThreadLocal，随调用线程传递）：{@code subagent} 等；空 = 主代理默认名 */
    private static final ThreadLocal<String> CALLER = new ThreadLocal<>();

    /** 任务/工具标记（ThreadLocal）：当前调用对应的任务标识（如 subagent-exec 驱动执行的工具清单、compress 的工具名）；空 = 无 */
    private static final ThreadLocal<String> TASK = new ThreadLocal<>();

    /** 子代理等调用方在调模型前打标，finally 中必须调 {@link #clear()} */
    public static void mark(String caller) {
        CALLER.set(caller);
    }

    /**
     * 打标 + 携带任务/工具标识（编码进 generation 名，Langfuse 控制台免点击直读"这是哪个任务"）。
     * task 为空时等价于 {@link #mark(String)}。
     */
    public static void mark(String caller, String task) {
        CALLER.set(caller);
        TASK.set(task);
    }

    public static void clear() {
        CALLER.remove();
        TASK.remove();
    }

    @Bean
    public ChatModelObservationConvention chatModelObservationConvention(TraceProperties traceProperties,
                                                                         AttributeSanitizer sanitizer,
                                                                         ObjectMapper objectMapper) {
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
                String task = TASK.get();
                if (task == null || task.isBlank()) {
                    return caller + "-" + base; // "subagent-chat <model>"
                }
                return caller + "-" + task + "-" + base; // "subagent-exec-query-weather-chat <model>"
            }

            @Override
            public KeyValues getHighCardinalityKeyValues(ChatModelObservationContext context) {
                KeyValues keyValues = super.getHighCardinalityKeyValues(context);
                if (!traceProperties.getChatObservation().isIncludeContent()) {
                    log.info("[obs-convention] includeContent=false，跳过 content 补发");
                    return keyValues;
                }
                String requestContent = ChatContentSerializer.toRequestContentJson(
                        context.getRequest(), sanitizer, objectMapper);
                if (requestContent != null) {
                    keyValues = keyValues.and("gen_ai.request.content", requestContent);
                    log.info("[obs-convention] 写入 gen_ai.request.content，len={}", requestContent.length());
                } else {
                    log.info("[obs-convention] 无 request content（request 为 null 或无消息）");
                }
                String responseContent = ChatContentSerializer.toResponseContent(
                        context.getResponse(), sanitizer);
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
}
