package com.hmdp.agent.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentV2ControllerTest {

    @Mock
    private AgentAccessService agentAccessService;

    @InjectMocks
    private AgentV2Controller controller;

    @Test
    void should_delegate_to_agent_access_service() {
        SseEmitter emitter = new SseEmitter();
        when(agentAccessService.send("你好", "conv-1")).thenReturn(emitter);

        SseEmitter result = controller.chat("你好", "conv-1");

        assertThat(result).isSameAs(emitter);
        verify(agentAccessService).send("你好", "conv-1");
    }
}
