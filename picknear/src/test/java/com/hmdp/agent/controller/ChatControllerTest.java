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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatController — 聊天控制器测试（纯 Mock 方式）。
 * <p>
 * 覆盖 JSON/SSE 模式切换、conversationId 生成/复用、SSE 超时。
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
    void should_return_json_when_no_accept_header() {
        when(aiService.chatReturnStringResult(anyString(), anyString())).thenReturn("AI回复");

        Object result = controller.chat("你好", "", null);

        assertThat(result).as("JSON 模式应返回 Result 信封").isInstanceOf(com.hmdp.dto.Result.class);
        com.hmdp.dto.Result r = (com.hmdp.dto.Result) result;
        assertThat(r.getData()).as("data 应包含 content 和 conversationId")
                .isInstanceOf(Map.class);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertThat(data).containsKey("content");
        assertThat(data).containsKey("conversationId");
    }

    @Test
    void should_return_sse_when_accept_is_event_stream() {
        SseSessionFactory.ChatSseSession session =
                new SseSessionFactory.ChatSseSession(mock(AgentSpan.class), mock(SseEmitter.class));
        when(sseSessionFactory.open(anyString(), anyLong())).thenReturn(session);

        Object result = controller.chat("你好", "text/event-stream", null);

        assertThat(result).as("SSE 模式应返回 SseEmitter").isInstanceOf(SseEmitter.class);
        SseEmitter emitter = (SseEmitter) result;
        verify(aiService).chatWithToolcall(eq("你好"), anyString(), eq(session.emitter()), any());
    }

    @Test
    void should_generate_new_conversation_id() {
        when(aiService.chatReturnStringResult(anyString(), anyString())).thenReturn("AI回复");

        Object result = controller.chat("你好", "", null);

        com.hmdp.dto.Result r = (com.hmdp.dto.Result) result;
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertThat(data.get("conversationId")).as("首次调用应生成新 conversationId")
                .isNotNull().asString().isNotBlank();
    }

    @Test
    void should_reuse_conversation_id() {
        when(aiService.chatReturnStringResult(eq("你好"), eq("existing-id"))).thenReturn("AI回复");

        Object result = controller.chat("你好", "", "existing-id");

        com.hmdp.dto.Result r = (com.hmdp.dto.Result) result;
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertThat(data.get("conversationId"))
                .as("传入 conversationId 应被复用").isEqualTo("existing-id");
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
