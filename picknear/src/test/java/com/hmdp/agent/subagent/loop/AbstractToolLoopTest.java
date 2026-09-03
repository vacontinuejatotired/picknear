package com.hmdp.agent.subagent.loop;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.execution.ToolExecutionRecorder;
import com.hmdp.agent.execution.model.ExecutionInput;
import com.hmdp.agent.guard.model.ConfirmRequiredException;
import com.hmdp.agent.guard.model.ToolInvocationContext;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskType;
import com.hmdp.agent.prompt.PromptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AbstractToolLoop 统一工具执行点与执行生命周期测试（评测数据补齐 §6.1）。
 * <p>
 * 守护点：invokeToolAndRecord 三分支（成功 COMPLETED / 失败 FAILED / CONFIRM 不记录），
 * execute 生命周期（入口 reset 绑定 span、轮末 finally flush、出口 reset(null) 解绑，
 * 异常冒泡路径 flush 仍执行）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AbstractToolLoopTest {

    /** 测试子类：executeRound 走统一工具执行点 invokeToolAndRecord */
    static class TestLoop extends AbstractToolLoop {
        private ToolCallback callback;

        void setCallback(ToolCallback callback) {
            this.callback = callback;
        }

        @Override
        public String toolCallRule() {
            return "test-rule";
        }

        @Override
        protected ToolResponseMessage executeRound(AssistantMessage out, ToolLoopContext ctx,
                Map<String, String> doneSummary, List<SubTask> remaining,
                AtomicInteger callCounter, AtomicInteger dupCounter, AtomicReference<String> lastCallKey) {
            ToolCall tc = out.getToolCalls().get(0);
            String raw = invokeToolAndRecord(tc.name(),
                    () -> callback.call(tc.arguments(), new ToolContext(Map.of())));
            doneSummary.put(tc.name(), raw);
            callCounter.incrementAndGet();
            return ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponse(tc.id(), tc.name(), raw)))
                    .build();
        }
    }

    @Mock
    private ToolExecutionRecorder recorder;
    @Mock
    private ChatModel chatModel;
    @Mock
    private ToolCallback callback;
    @Mock
    private PromptService promptService;

    @InjectMocks
    private TestLoop loop;

    private ToolLoopContext ctx(AgentSpan span) {
        return new ToolLoopContext(
                List.of(callback), "systemText", "initialPrompt",
                ExecutionInput.builder()
                        .tasks(List.of(SubTask.builder().toolName("queryWeather").type(TaskType.TOOL_CALL).build()))
                        .build(),
                promptService, Map.of(), new SubTaskProperties(), span);
    }

    private AssistantMessage toolCallMessage() {
        return AssistantMessage.builder()
                .content("调用工具")
                .toolCalls(List.of(new ToolCall("1", "function", "queryWeather", "{}")))
                .build();
    }

    private ChatResponse responseWithToolCall() {
        return new ChatResponse(List.of(new Generation(toolCallMessage())));
    }

    private ChatResponse responseText(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }

    // ═══════════════════════════════════════════════════════
    // invokeToolAndRecord 三分支
    // ═══════════════════════════════════════════════════════

    @Test
    void invokeToolAndRecord_success_should_record_completed_and_return_value() {
        when(callback.call(anyString(), any())).thenReturn("工具数据");

        String result = loop.invokeToolAndRecord("queryWeather",
                () -> callback.call("{}", new ToolContext(Map.of())));

        assertThat(result).as("返回值应透传").isEqualTo("工具数据");
        verify(recorder).record("queryWeather", SubTaskStatus.COMPLETED);
    }

    @Test
    void invokeToolAndRecord_failure_should_record_failed_and_propagate() {
        when(callback.call(anyString(), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> loop.invokeToolAndRecord("queryWeather",
                () -> callback.call("{}", new ToolContext(Map.of()))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        verify(recorder).record("queryWeather", SubTaskStatus.FAILED);
    }

    @Test
    void invokeToolAndRecord_confirm_should_not_record_and_propagate() {
        when(callback.call(anyString(), any())).thenThrow(
                new ConfirmRequiredException(
                        ToolInvocationContext.builder().toolName("queryWeather").build(),
                        "需要确认", "ConfirmToolPolicy"));

        assertThatThrownBy(() -> loop.invokeToolAndRecord("queryWeather",
                () -> callback.call("{}", new ToolContext(Map.of()))))
                .isInstanceOf(ConfirmRequiredException.class);

        verify(recorder, never()).record(anyString(), any());
    }

    // ═══════════════════════════════════════════════════════
    // execute 生命周期：入口 reset / 轮末 flush / 出口 reset(null)
    // ═══════════════════════════════════════════════════════

    @Test
    void execute_should_reset_bind_flush_and_unbind() {
        AgentSpan span = mock(AgentSpan.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(responseWithToolCall(), responseText("完成"));
        when(callback.call(anyString(), any())).thenReturn("工具数据");
        when(promptService.render(any(), any())).thenReturn("重渲染文本");

        String result = loop.execute(ctx(span));

        assertThat(result).as("无工具轮应返回最终文本").isEqualTo("完成");
        verify(recorder).reset(span);              // 入口绑定 subagent span
        verify(recorder, atLeastOnce()).flush();   // 轮末 + 出口
        verify(recorder).reset(null);              // 出口解绑防串用
    }

    @Test
    void execute_exception_path_should_still_flush_and_unbind() {
        AgentSpan span = mock(AgentSpan.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseWithToolCall());
        when(callback.call(anyString(), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> loop.execute(ctx(span))).isInstanceOf(RuntimeException.class);

        verify(recorder).record("queryWeather", SubTaskStatus.FAILED);
        verify(recorder, atLeastOnce()).flush();   // 异常冒泡路径 finally 仍 flush
        verify(recorder).reset(null);
    }

    @Test
    void execute_no_tool_call_round_should_return_text_without_flush_cycle() {
        when(chatModel.call(any(Prompt.class))).thenReturn(responseText("直接回答"));

        String result = loop.execute(ctx(null));

        assertThat(result).as("无工具调用应直接返回 Phase 文本").isEqualTo("直接回答");
        verify(recorder, times(2)).reset(null);    // 入口 reset(null) + 出口 reset(null)
        verify(recorder, times(1)).flush();        // 仅出口兜底一次
    }
}
