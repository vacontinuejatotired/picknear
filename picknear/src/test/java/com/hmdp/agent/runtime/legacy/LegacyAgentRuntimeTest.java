package com.hmdp.agent.runtime.legacy;

import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.service.AiService;
import com.hmdp.agent.stream.SseSessionFactory;
import com.hmdp.agent.stream.SseSessionFactory.ChatSseSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyAgentRuntimeTest {

    @Mock
    private AiService aiService;

    @Mock
    private SseSessionFactory sseSessionFactory;

    @Mock
    private ChatMemory chatMemory;

    @InjectMocks
    private LegacyAgentRuntime runtime;

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void should_assemble_session_and_delegate_to_ai_service() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        ChatSseSession session = new ChatSseSession(mock(AgentSpan.class), emitter);
        when(sseSessionFactory.open("conv-1", 1010L)).thenReturn(session);
        when(chatMemory.get("conv-1")).thenReturn(List.of());

        SseEmitter result = runtime.run(new AgentCommand("你好", "conv-1", 1010L));

        assertThat(result).isSameAs(emitter);
        verify(sseSessionFactory).sendConversationId(emitter, "conv-1");
        verify(aiService).chatWithToolcall("你好", "conv-1", emitter, session.root());
        assertThat(AgentContextHolder.get()).isNull();
    }
}
