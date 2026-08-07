package com.hmdp.agent.config;

import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义 {@link ChatModelObservationConvention}：按调用方区分 Langfuse 里的 generation 名。
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
 */
@Configuration
public class ChatModelObservationConventionConfig {

    /** 调用方标记（ThreadLocal，随调用线程传递）：{@code subagent} 等；空 = 主代理默认名 */
    private static final ThreadLocal<String> CALLER = new ThreadLocal<>();

    /** 子代理等调用方在调模型前打标，finally 中必须调 {@link #clear()} */
    public static void mark(String caller) {
        CALLER.set(caller);
    }

    public static void clear() {
        CALLER.remove();
    }

    @Bean
    public ChatModelObservationConvention chatModelObservationConvention() {
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
        };
    }
}
