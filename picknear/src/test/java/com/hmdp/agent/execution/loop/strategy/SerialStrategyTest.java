package com.hmdp.agent.execution.loop.strategy;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.execution.ResultCompressor;
import com.hmdp.agent.execution.ToolExecutionRecorder;
import com.hmdp.agent.execution.model.ExecutionInput;
import com.hmdp.agent.guard.model.ConfirmRequiredException;
import com.hmdp.agent.guard.model.ToolInvocationContext;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskType;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.subagent.loop.ToolLoopContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SerialStrategy 工具执行点接入测试（评测数据补齐 §6.1）。
 * <p>
 * 守护点：三策略的 cb.call 已统一走 invokeToolAndRecord——成功记 COMPLETED、
 * 失败记 FAILED、CONFIRM 原样上抛不记录。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SerialStrategyTest {

    @Mock
    private ToolCallback callback;
    @Mock
    private ToolExecutionRecorder recorder;
    @Mock
    private ResultCompressor compressor;
    @Mock
    private PromptService promptService;

    @InjectMocks
    private SerialStrategy strategy;

    @BeforeEach
    void setUp() {
        ToolDefinition def = ToolDefinition.builder()
                .name("queryWeather").description("查天气").inputSchema("{}").build();
        lenient().when(callback.getToolDefinition()).thenReturn(def);
    }

    private ToolLoopContext ctx() {
        return new ToolLoopContext(
                List.of(callback), "systemText", "initialPrompt",
                ExecutionInput.builder()
                        .tasks(List.of(SubTask.builder().toolName("queryWeather").type(TaskType.TOOL_CALL).build()))
                        .build(),
                promptService, Map.of(), new SubTaskProperties(), null, null);
    }

    private AssistantMessage toolCallMessage() {
        return AssistantMessage.builder()
                .content("调用工具")
                .toolCalls(List.of(new ToolCall("1", "function", "queryWeather", "{}")))
                .build();
    }

    private ToolResponseMessage executeRound() {
        return strategy.executeRound(toolCallMessage(), ctx(),
                new LinkedHashMap<>(), new ArrayList<>(),
                new AtomicInteger(), new AtomicInteger(), new AtomicReference<>());
    }

    @Test
    void executeRound_success_should_record_completed() {
        when(callback.call(anyString(), any())).thenReturn("晴天 25°C");
        when(compressor.compress(anyString(), anyString(), anyInt())).thenReturn("晴天 25°C");

        ToolResponseMessage resp = executeRound();

        verify(recorder).record("queryWeather", SubTaskStatus.COMPLETED);
        assertThat(resp.getResponses()).as("应产出 1 条工具响应").hasSize(1);
        assertThat(resp.getResponses().get(0).responseData()).isEqualTo("晴天 25°C");
    }

    @Test
    void executeRound_failure_should_record_failed() {
        when(callback.call(anyString(), any())).thenThrow(new RuntimeException("boom"));

        ToolResponseMessage resp = executeRound();

        verify(recorder).record("queryWeather", SubTaskStatus.FAILED);
        assertThat(resp.getResponses().get(0).responseData()).as("失败应组错误响应").contains("错误");
    }

    @Test
    void executeRound_confirm_should_rethrow_without_recording() {
        when(callback.call(anyString(), any())).thenThrow(
                new ConfirmRequiredException(
                        ToolInvocationContext.builder().toolName("queryWeather").build(),
                        "需要确认", "ConfirmToolPolicy"));

        assertThatThrownBy(this::executeRound).isInstanceOf(ConfirmRequiredException.class);

        verify(recorder, never()).record(anyString(), any());
    }
}
