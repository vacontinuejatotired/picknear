package com.hmdp.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.observability.backend.TraceBackendAssembler;
import com.hmdp.agent.observability.backend.TraceBackendCapabilities;
import com.hmdp.agent.observability.model.CallerType;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.observability.support.ChatContentSerializer;
import com.hmdp.agent.observability.support.TraceProperties;
import com.hmdp.agent.observability.support.TriState;
import io.micrometer.common.KeyValues;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * 自定义 {@link ChatModelObservationConvention}：按调用方区分 generation 名（能力驱动），
 * 并补发 LLM 请求/回复全文（默认 convention 不发，观测后端 input/output 因此为 null）。
 * <p>
 * 观测后端解耦改造（2026-08-17，方案 S3）：
 * <ul>
 *   <li><b>打标收敛</b>：业务类通过 {@link #mark(CallerType[, task])} 打标（引用枚举而非
 *       魔法字符串，评审 13.3.1）；<b>打标调用保留、是否拼前缀由本类按后端能力决定</b>
 *       （能力开关关闭时忽略标记，条件不进业务代码——铁律）。</li>
 *   <li><b>能力驱动</b>：后端不展示自定义属性时（Langfuse）generation 名拼
 *       {@code caller[-task]-chat <model>}（控制台免点击直读）；支持属性的后端（Jaeger 等）
 *       保持 {@code chat <model>}（语义已在 attributes）。content 补发 =
 *       {@code capabilities.contentSupplementRequired AND 用户开关 include-content}。</li>
 * </ul>
 * 背景（Langfuse 云接入说明 §5.6）：Langfuse 云版 OTLP 路径下自定义 span attributes 不展示，
 * 默认 ChatModel 观察的 span 名是 {@code chat <model>}，主代理与子代理共用同一
 * {@code OpenAiChatModel} Bean 时无法区分调用方——故前缀编码是 Langfuse 特有需求的兜底。
 * </p>
 * <p>
 * <b>补发 key 约定（2026-09-01 评测链路修复定稿）</b>：写 {@code langfuse.observation.input} /
 * {@code langfuse.observation.output}（Langfuse SDK 协议，OTLP 转译提取瀑布 Step 1 最高
 * 优先级）——observation 主字段即有值；旧 key {@code gen_ai.request/response.content}
 * 不在 Langfuse 提取器的识别列表，转译后只进 metadata、主字段恒 null，导致 LLM-as-a-judge
 * 评估器（从主字段取数）取空。详见 {@code md/agent/Agent评测功能交接文档.md} §三。
 * </p>
 */
@Slf4j
@Configuration
public class ChatModelObservationConventionConfig {

    /** 调用方标记（ThreadLocal，随调用线程传递）：{@code CallerType} 枚举；null = 主代理默认名 */
    private static final ThreadLocal<CallerType> CALLER = new ThreadLocal<>();

    /** 任务/工具标记（ThreadLocal）：当前调用对应的任务标识（如 subagent-exec 驱动执行的工具清单、compress 的工具名）；空 = 无 */
    private static final ThreadLocal<String> TASK = new ThreadLocal<>();

    /** 调用方在调模型前打标，finally 中必须调 {@link #clear()} */
    public static void mark(CallerType caller) {
        CALLER.set(caller);
    }

    /**
     * 打标 + 携带任务/工具标识（编码进 generation 名，控制台免点击直读"这是哪个任务"）。
     * task 为空时等价于 {@link #mark(CallerType)}。
     */
    public static void mark(CallerType caller, String task) {
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
                                                                         ObjectMapper objectMapper,
                                                                         TraceBackendAssembler backendAssembler) {
        TraceBackendCapabilities capabilities = backendAssembler.assemble().capabilities();
        // 语义命名编码 = 后端不展示自定义属性（单一事实源推导，评审 13.2.2），
        // 可被 span-naming.semantic-encoding 覆盖（与 SpanNamingStrategy 同源，S5）
        TriState semanticEncoding = traceProperties.getSpanNaming().semanticEncodingMode();
        boolean encodeCaller = semanticEncoding.resolve(!capabilities.supportsSpanAttributes());
        // content 补发 = include-content（auto 跟后端能力）解析
        TriState includeContentMode = traceProperties.getChatObservation().includeContentMode();
        boolean supplementContent = includeContentMode.resolve(capabilities.contentSupplementRequired());
        log.info("[obs-convention] 已加载自定义 ChatModelObservationConvention "
                        + "(后端能力: supportsSpanAttributes={}, contentSupplement={}; "
                        + "span-naming.semantic-encoding={}, include-content={})",
                capabilities.supportsSpanAttributes(), capabilities.contentSupplementRequired(),
                traceProperties.getSpanNaming().getSemanticEncoding(),
                traceProperties.getChatObservation().getIncludeContent());
        return new DefaultChatModelObservationConvention() {
            @Override
            public String getContextualName(ChatModelObservationContext context) {
                String base = super.getContextualName(context); // 默认 "chat <model>"
                // 铁律（评审 13.3.1）：条件在本类内部判断，业务代码的 mark() 调用无条件保留
                if (!encodeCaller) {
                    return base; // 支持属性的后端：语义经 attributes 展示，不加前缀
                }
                CallerType caller = CALLER.get();
                if (caller == null) {
                    return base;
                }
                String task = TASK.get();
                if (task == null || task.isBlank()) {
                    return caller.id() + "-" + base; // "subagent-chat <model>"
                }
                return caller.id() + "-" + task + "-" + base; // "subagent-exec-query-weather-chat <model>"
            }

            @Override
            public KeyValues getHighCardinalityKeyValues(ChatModelObservationContext context) {
                KeyValues keyValues = super.getHighCardinalityKeyValues(context);
                if (!supplementContent) {
                    log.info("[obs-convention] content 补发关闭（能力或用户开关），跳过");
                    return keyValues;
                }
                String requestContent = ChatContentSerializer.toRequestContentJson(
                        context.getRequest(), sanitizer, objectMapper);
                if (requestContent != null) {
                    keyValues = keyValues.and("langfuse.observation.input", requestContent);
                    log.info("[obs-convention] 写入 langfuse.observation.input，len={}", requestContent.length());
                } else {
                    log.info("[obs-convention] 无 request content（request 为 null 或无消息）");
                }
                String responseContent = ChatContentSerializer.toResponseContent(
                        context.getResponse(), sanitizer);
                if (responseContent != null) {
                    keyValues = keyValues.and("langfuse.observation.output", responseContent);
                    log.info("[obs-convention] 写入 langfuse.observation.output，len={}", responseContent.length());
                } else {
                    log.info("[obs-convention] 无 response content（response 为 null 或无文本）");
                }
                return keyValues;
            }
        };
    }
}