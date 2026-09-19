package com.hmdp.agent.runtime.graph;

import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.config.properties.ReplayProperties;
import com.hmdp.agent.history.ConversationReplayService;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.stream.SseSessionFactory;
import com.hmdp.agent.stream.SseSessionFactory.ChatSseSession;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphAgentRuntimeTest {

    @Mock
    private AgentGraphFactory graphFactory;

    @Mock
    private SseSessionFactory sseSessionFactory;

    @Mock
    private PromptService promptService;

    @Mock
    private ConversationReplayService conversationReplayService;

    @Mock
    private ReplayProperties replayProperties;

    @InjectMocks
    private GraphAgentRuntime runtime;

    @Test
    void should_invoke_graph_and_complete_sse() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        ChatSseSession session = new ChatSseSession(mock(AgentSpan.class), emitter);
        when(sseSessionFactory.open("conv-1", 1010L)).thenReturn(session);
        when(promptService.render(eq(PromptKeys.SYSTEM_MAIN), any())).thenReturn("系统提示");
        when(replayProperties.getKeepRecentTurns()).thenReturn(6);
        when(conversationReplayService.recentMessages(1010L, "conv-1", 6))
                .thenReturn(List.of());
        ChatResponse chunk = new ChatResponse(List.of(
                new Generation(AssistantMessage.builder()
                        .content("Graph 回复")
                        .build())
        ));
        StreamingOutput<ChatResponse> streamingOutput = new StreamingOutput<>(
                chunk,
                AgentGraphFactory.RESPOND_NODE,
                "agent",
                new OverAllState(),
                OutputType.GRAPH_NODE_STREAMING
        );
        when(graphFactory.stream(any(AgentCommand.class), anyString(), any()))
                .thenReturn(Flux.just(streamingOutput));

        SseEmitter result = runtime.run(new AgentCommand("你好", "conv-1", 1010L));

        assertThat(result).isSameAs(emitter);
        verify(graphFactory).stream(any(AgentCommand.class), eq("系统提示"), any());
        verify(emitter).complete();
    }
}
