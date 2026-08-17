package com.hmdp.agent.stream;

import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.observability.model.CallerType;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.util.SseUtils;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 流式 LLM 调用器 — ChatModel 层流式直调 + SSE 逐 token 推送 + 重试循环。
 * <p>
 * 从 AiServiceImpl 异步段拆出：原逻辑（3 次重试、错误回喂重试、phase1 观测、
 * 首 chunk content=null 转空串、断链修复的 observation context 写入）整体搬迁，行为不变。
 * 成功返回 {@link StreamOutcome#fullResponse()}，重试耗尽返回
 * {@link StreamOutcome#failed()} + 最后一次异常（调用方给用户友好提示）。
 * </p>
 */
@Slf4j
@Component
public class StreamingChatInvoker {

    private static final int MAX_ATTEMPTS = 3;

    private final ChatModel chatModel;
    private final AgentTracer agentTracer;
    private final ObservationRegistry observationRegistry;

    public StreamingChatInvoker(ChatModel chatModel, AgentTracer agentTracer,
                                ObservationRegistry observationRegistry) {
        this.chatModel = chatModel;
        this.agentTracer = agentTracer;
        this.observationRegistry = observationRegistry;
    }

    /**
     * 流式调用 LLM 并逐 token 推送，内部最多 {@link #MAX_ATTEMPTS} 次重试。
     * <p>
     * 调用方需已 resume 根 span（跨线程挂载），本方法内创建的 phase1 观测会自动挂树。
     * </p>
     *
     * @param systemText     系统提示词（调用方渲染一次，重试循环不重复渲染）
     * @param initialContent 用户输入（重试时自动附加失败原因回喂 LLM）
     * @param emitter        SSE 输出（逐 token 推送原文）
     */
    public StreamOutcome streamWithRetry(String systemText, String initialContent, SseEmitter emitter) {
        Exception lastError = null;
        String currentContent = initialContent;

        // 观测：Phase1 整段（含重试循环），属性 attempt 标记成功轮次
        try (AgentSpan phase1 = agentTracer.start(AgentSpanSpec.PHASE1, null)) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    phase1.set(AgentField.ATTEMPT, String.valueOf(attempt));
                    // 流式断链修复（2026-08-03）：绕开 ChatClient 层，直接调 ChatModel 层流式。
                    // 原因（字节码+实测三层验证）：ChatClient.stream() 内部有 ChatClient 层观察对象
                    // （spring.ai.chat.client），被 handler 链上 meter 优先的 composite 匹配、
                    // 不产生 tracing span；model 层观察对象从 Reactor context 读到它作为父，
                    // TracingContext 缺失 → 断链（新 traceId）。绕开后 context 里只有下面
                    // 写入的当前栈顶 observation（phase1），model 层观察对象直接挂上。
                    // 观察标记：流式 observation 在订阅时 start()，标记需覆盖 stream 创建到消费结束
                    ChatModelObservationConventionConfig.mark(CallerType.PHASE1);
                    String fullResponse;
                    try {
                        Observation streamParent = observationRegistry.getCurrentObservation();
                        Prompt streamPrompt = new Prompt(List.of(
                                new SystemMessage(systemText),
                                new UserMessage(currentContent)));
                        Flux<ChatResponse> stream = chatModel.stream(streamPrompt);
                        if (streamParent != null) {
                            stream = stream.contextWrite(rctx -> rctx.put("micrometer.observation", streamParent));
                        }
                        StringBuilder buffer = new StringBuilder();
                        // OpenAI 兼容流式首 chunk 只有 role、content=null，getText() 为 null；
                        // Flux.map 不允许 mapper 返回 null（会抛 NPE），需转空串再按空串过滤
                        for (String token : stream.map(r -> {
                                String text = r.getResult().getOutput().getText();
                                return text != null ? text : "";
                            }).toIterable()) {
                            if (!token.isEmpty()) {
                                buffer.append(token);
                                SseUtils.safeSend(emitter, SseUtils.escapeJson(token));
                            }
                        }
                        fullResponse = buffer.toString();
                    } finally {
                        ChatModelObservationConventionConfig.clear();
                    }

                    log.info("[Phase1] AI 流式回复完成, length={}", fullResponse.length());
                    phase1.set(AgentField.STREAM_LEN, String.valueOf(fullResponse.length()));
                    return new StreamOutcome(fullResponse, null);
                } catch (Exception e) {
                    lastError = e;
                    log.warn("AI 流式调用失败 [attempt={}/{}]", attempt, MAX_ATTEMPTS, e);
                    if (attempt < MAX_ATTEMPTS) {
                        // 把错误喂给 AI，让 AI 重试生成回复
                        currentContent = initialContent + "\n\n[系统提示] 上一步调用因以下异常失败，请重试："
                                + e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                }
            }
            phase1.set(AgentField.STATUS, "FAILED");
        }
        return new StreamOutcome(null, lastError);
    }

    /**
     * 流式调用结果：成功时 fullResponse 非空；重试耗尽时 fullResponse 为 null、
     * lastError 为最后一次异常（供调用方生成用户友好提示）。
     */
    public record StreamOutcome(String fullResponse, Throwable lastError) {

        public boolean failed() {
            return fullResponse == null;
        }
    }
}
