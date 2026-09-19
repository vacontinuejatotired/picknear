package com.hmdp.agent.access;

import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAccessServiceTest {

    @Mock
    private AgentRuntime agentRuntime;

    @InjectMocks
    private AgentAccessService service;

    @BeforeEach
    void setUp() {
        UserHolder.saveUserId(1010L);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void should_generate_conversation_id_and_delegate_to_runtime() {
        SseEmitter emitter = new SseEmitter();
        when(agentRuntime.run(any())).thenReturn(emitter);

        SseEmitter result = service.send("你好", null);

        ArgumentCaptor<AgentCommand> captor = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(result).isSameAs(emitter);
        assertThat(captor.getValue().content()).isEqualTo("你好");
        assertThat(captor.getValue().conversationId()).isNotBlank();
        assertThat(captor.getValue().userId()).isEqualTo(1010L);
    }

    @Test
    void should_reuse_conversation_id() {
        SseEmitter emitter = new SseEmitter();
        when(agentRuntime.run(any())).thenReturn(emitter);

        service.send("你好", "existing-id");

        ArgumentCaptor<AgentCommand> captor = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().conversationId()).isEqualTo("existing-id");
    }
}
