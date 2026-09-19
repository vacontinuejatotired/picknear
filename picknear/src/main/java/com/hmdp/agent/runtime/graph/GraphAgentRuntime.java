package com.hmdp.agent.runtime.graph;

import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.access.AgentRuntime;
import com.hmdp.agent.stream.SseSessionFactory;
import com.hmdp.agent.stream.SseSessionFactory.ChatSseSession;
import com.hmdp.agent.stream.SseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * Alibaba Graph Agent 运行时骨架。
 *
 * <p>当前仅用于验证 Graph Runtime 接线和配置切换，不承载完整 Agent 行为。默认
 * 不启用，只有 {@code agent.access.runtime=graph} 时才会替代 Legacy 实现。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "agent.access",
        name = "runtime",
        havingValue = "graph"
)
public class GraphAgentRuntime implements AgentRuntime {

    private final AgentGraphFactory graphFactory;
    private final SseSessionFactory sseSessionFactory;

    public GraphAgentRuntime(AgentGraphFactory graphFactory,
                             SseSessionFactory sseSessionFactory) {
        this.graphFactory = graphFactory;
        this.sseSessionFactory = sseSessionFactory;
    }

    @Override
    public SseEmitter run(AgentCommand command) {
        ChatSseSession session = sseSessionFactory.open(
                command.conversationId(),
                command.userId()
        );
        SseEmitter emitter = session.emitter();

        try {
            sseSessionFactory.sendConversationId(emitter, command.conversationId());
            String output = graphFactory.invoke(command);
            SseUtils.safeSend(emitter, SseUtils.escapeJson(output));
            emitter.complete();
        } catch (IOException e) {
            log.error("Graph Runtime 推送 conversationId 失败", e);
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.error("Graph Runtime 执行失败", e);
            SseUtils.safeSend(emitter, SseUtils.errorEvent(
                    "Agent Graph Runtime 执行失败，请稍后再试。"));
            emitter.complete();
        }
        return emitter;
    }
}
