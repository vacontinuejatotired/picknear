package com.hmdp.agent.runtime.graph;

import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.access.AgentRuntime;
import com.hmdp.agent.config.properties.ReplayProperties;
import com.hmdp.agent.history.ConversationReplayService;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.stream.SseSessionFactory;
import com.hmdp.agent.stream.SseSessionFactory.ChatSseSession;
import com.hmdp.agent.stream.SseUtils;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Alibaba Graph Agent 运行时骨架。
 *
 * <p>当前支持最小流式 Phase1：读取历史和系统提示，订阅 Graph 的 StreamingOutput
 * 并逐段推送 SSE。默认不启用，只有 {@code agent.access.runtime=graph} 时才会
 * 替代 Legacy 实现。</p>
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
    private final PromptService promptService;
    private final ConversationReplayService conversationReplayService;
    private final ReplayProperties replayProperties;

    public GraphAgentRuntime(AgentGraphFactory graphFactory,
                             SseSessionFactory sseSessionFactory,
                             PromptService promptService,
                             ConversationReplayService conversationReplayService,
                             ReplayProperties replayProperties) {
        this.graphFactory = graphFactory;
        this.sseSessionFactory = sseSessionFactory;
        this.promptService = promptService;
        this.conversationReplayService = conversationReplayService;
        this.replayProperties = replayProperties;
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
            String systemText = promptService.render(
                    PromptKeys.SYSTEM_MAIN,
                    Map.of("userId", command.userId() != null
                            ? String.valueOf(command.userId())
                            : "")
            );
            List<Message> history = conversationReplayService.recentMessages(
                    command.userId(),
                    command.conversationId(),
                    replayProperties.getKeepRecentTurns()
            );
            graphFactory.stream(command, systemText, history)
                    .subscribe(
                            output -> emitChunk(emitter, output),
                            error -> {
                                log.error("Graph Runtime 流式执行失败", error);
                                SseUtils.safeSend(emitter, SseUtils.errorEvent(
                                        "Agent Graph Runtime 执行失败，请稍后再试。"));
                                emitter.complete();
                            },
                            emitter::complete
                    );
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

    private void emitChunk(SseEmitter emitter, NodeOutput output) {
        if (!(output instanceof StreamingOutput<?> streaming)) {
            return;
        }
        if (streaming.getOutputType() == OutputType.GRAPH_NODE_FINISHED) {
            return;
        }
        String chunk = streaming.chunk();
        if (chunk != null && !chunk.isEmpty()) {
            SseUtils.safeSend(emitter, SseUtils.escapeJson(chunk));
        }
    }
}
