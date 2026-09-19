package com.hmdp.agent.runtime.legacy;

import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.access.AgentRuntime;
import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.service.AiService;
import com.hmdp.agent.stream.SseSessionFactory;
import com.hmdp.agent.stream.SseSessionFactory.ChatSseSession;
import com.hmdp.agent.stream.SseUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 旧 Agent 链路的运行时适配。
 *
 * <p>本类只负责把接入层请求装配到现有 AiService，不复制规划、工具循环或 DAG
 * 逻辑。后续 Graph 实现接入后，可以通过配置替换本实现。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "agent.access",
        name = "runtime",
        havingValue = "legacy",
        matchIfMissing = true
)
public class LegacyAgentRuntime implements AgentRuntime {

    @Resource
    private AiService aiService;

    @Resource
    private SseSessionFactory sseSessionFactory;

    @Resource
    private ChatMemory chatMemory;

    @Override
    public SseEmitter run(AgentCommand command) {
        String conversationId = command.conversationId();
        ChatSseSession session = sseSessionFactory.open(conversationId, command.userId());
        AgentSpan root = session.root();
        SseEmitter emitter = session.emitter();

        AgentContextHolder.set(AgentContext.builder()
                .userId(command.userId())
                .conversationId(conversationId)
                .originalInput(command.content())
                .history(chatMemory.get(conversationId))
                .rootSpan(root)
                .build());

        try {
            emitter.onCompletion(() ->
                    log.debug("SSE 流完成, thread={}", Thread.currentThread().getName()));
            emitter.onTimeout(() -> log.warn("SSE 流超时, content={}", brief(command.content())));
            emitter.onError(ex -> log.error("SSE 流异常, content={}", brief(command.content()), ex));

            try {
                sseSessionFactory.sendConversationId(emitter, conversationId);
            } catch (IOException e) {
                log.error("推送 conversationId 失败", e);
                emitter.completeWithError(e);
                return emitter;
            }

            try {
                aiService.chatWithToolcall(
                        command.content(),
                        conversationId,
                        emitter,
                        root
                );
            } catch (Exception e) {
                log.error("SSE 会话初始化异常, content={}", brief(command.content()), e);
                SseUtils.safeSend(emitter, SseUtils.errorEvent(
                        "抱歉，AI 服务暂时不可用，请稍后再试。"));
                emitter.complete();
            }
            return emitter;
        } finally {
            AgentContextHolder.clear();
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 50 ? s : s.substring(0, 50) + "...";
    }
}
