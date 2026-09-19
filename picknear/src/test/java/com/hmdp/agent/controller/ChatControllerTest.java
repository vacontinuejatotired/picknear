package com.hmdp.agent.controller;

import com.hmdp.agent.access.AgentAccessService;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.orchestration.confirm.ConfirmResumeService;
import com.hmdp.agent.service.ApprovalService;
import com.hmdp.agent.stream.ObservedSseEmitter;
import com.hmdp.agent.stream.SseSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatController 只负责旧入口兼容，实际聊天编排由 AgentAccessService 承担。
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private AgentAccessService agentAccessService;

    @Mock
    private ApprovalService approvalService;

    @Mock
    private ConfirmResumeService confirmResumeService;

    @Mock
    private AgentTracer agentTracer;

    @InjectMocks
    private ChatController controller;

    @Test
    void should_delegate_chat_to_agent_access_service() {
        SseEmitter emitter = mock(SseEmitter.class);
        when(agentAccessService.send("你好", null)).thenReturn(emitter);

        SseEmitter result = controller.chat("你好", null);

        assertThat(result).isSameAs(emitter);
        verify(agentAccessService).send("你好", null);
    }

    @Test
    void should_use_sse_timeout() {
        try (MockedConstruction<ObservedSseEmitter> mocked = mockConstruction(
                ObservedSseEmitter.class,
                (mock, context) -> {
                    long timeout = (long) context.arguments().get(0);
                    assertThat(timeout).as("SSE 超时应为 30 分钟（1800000ms）")
                            .isEqualTo(30 * 60 * 1000L);
                })) {
            SseSessionFactory factory = new SseSessionFactory();
            ReflectionTestUtils.setField(factory, "agentTracer", agentTracer);
            ReflectionTestUtils.setField(factory, "taskScheduler", mock(TaskScheduler.class));

            factory.open("conv-1", 1010L);

            assertThat(mocked.constructed()).as("SSE emitter 构造了一次").hasSize(1);
        }
    }
}
