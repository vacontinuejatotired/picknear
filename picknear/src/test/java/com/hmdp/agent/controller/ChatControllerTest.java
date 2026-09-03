package com.hmdp.agent.controller;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.service.AiService;
import com.hmdp.agent.stream.ObservedSseEmitter;
import com.hmdp.agent.stream.SseSessionFactory;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatController — 聊天控制器测试（纯 Mock 方式）。
 * <p>
 * 对话已废弃 JSON 模式：/string/send 固定 SSE（conversationId 生成/复用、SSE emitter 装配）。
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private AiService aiService;

    @Mock
    private com.hmdp.agent.observability.api.AgentTracer agentTracer;

    @Mock
    private com.hmdp.agent.service.ApprovalService approvalService;

    @Mock
    private com.hmdp.agent.orchestration.confirm.ConfirmResumeService confirmResumeService;

    @Mock
    private org.springframework.ai.chat.memory.ChatMemory chatMemory;

    @Mock
    private com.hmdp.agent.stream.SseSessionFactory sseSessionFactory;

    @InjectMocks
    private ChatController controller;

    @BeforeEach
    void setUp() {
        UserHolder.saveUserId(1010L);
        lenient().when(chatMemory.get(anyString())).thenReturn(java.util.List.of());
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void should_return_sse_emitter() {
        SseSessionFactory.ChatSseSession session =
                new SseSessionFactory.ChatSseSession(mock(AgentSpan.class), mock(SseEmitter.class));
        when(sseSessionFactory.open(anyString(), anyLong())).thenReturn(session);

        SseEmitter emitter = controller.chat("你好", null);

        assertThat(emitter).as("对话固定 SSE，应返回 SseEmitter").isInstanceOf(SseEmitter.class);
        verify(aiService).chatWithToolcall(eq("你好"), anyString(), eq(session.emitter()), any());
    }

    @Test
    void should_generate_new_conversation_id() {
        SseSessionFactory.ChatSseSession session =
                new SseSessionFactory.ChatSseSession(mock(AgentSpan.class), mock(SseEmitter.class));
        when(sseSessionFactory.open(anyString(), anyLong())).thenReturn(session);

        controller.chat("你好", null);

        ArgumentCaptor<String> cid = ArgumentCaptor.forClass(String.class);
        verify(sseSessionFactory).open(cid.capture(), anyLong());
        assertThat(cid.getValue()).as("首次调用应生成新 conversationId").isNotBlank();
    }

    @Test
    void should_reuse_conversation_id() {
        SseSessionFactory.ChatSseSession session =
                new SseSessionFactory.ChatSseSession(mock(AgentSpan.class), mock(SseEmitter.class));
        when(sseSessionFactory.open(anyString(), anyLong())).thenReturn(session);

        controller.chat("你好", "existing-id");

        verify(sseSessionFactory).open(eq("existing-id"), anyLong());
    }

    @Test
    void should_use_sse_timeout() {
        // 超时设置已收敛到 SseSessionFactory：ObservedSseEmitter 首参 = 30 分钟
        try (MockedConstruction<ObservedSseEmitter> mocked = mockConstruction(ObservedSseEmitter.class,
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
