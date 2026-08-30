package com.hmdp.agent.task;
import com.hmdp.agent.legacy.task.TaskExecutor;
import com.hmdp.agent.legacy.task.TaskQueue;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * TaskExecutor — 子任务执行器测试（回退路径）。
 * <p>
 * 覆盖 TOOL_CALL 成功/失败/未知工具、LLM_REASON 成功。
 */
@ExtendWith(MockitoExtension.class)
class TaskExecutorTest {

    @Mock
    private ToolCallback weatherCallback;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    @Mock
    private PromptService promptService;

    private final ToolDefinition toolDef = ToolDefinition.builder()
            .name("queryWeather").description("查天气").inputSchema("{}").build();

    private AgentTracer tracer;

    @BeforeEach
    void setUp() {
        lenient().when(weatherCallback.getToolDefinition()).thenReturn(toolDef);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(responseSpec);
        lenient().when(responseSpec.content()).thenReturn("晴天 25°C");
        lenient().when(promptService.render(anyString(), any())).thenReturn("渲染后模板");
        tracer = mock(AgentTracer.class);
        lenient().when(tracer.start(any(AgentSpanSpec.class), any())).thenReturn(mock(AgentSpan.class));
    }

    @Test
    void should_execute_tool_call_successfully() {
        when(weatherCallback.call(anyString(), any(ToolContext.class))).thenReturn("sunny");
        TaskExecutor executor = new TaskExecutor(
                new ToolCallback[]{weatherCallback}, 1L, null, chatClient, 5000, tracer, promptService);

        SubTask task = SubTask.builder().id("1").type(TaskType.TOOL_CALL)
                .toolName("queryWeather").status(SubTaskStatus.PENDING).build();
        TaskQueue queue = new TaskQueue(List.of(task));
        executor.executeAll(queue);

        assertThat(task.getStatus()).as("工具调用应成功").isEqualTo(SubTaskStatus.COMPLETED);
        assertThat(task.getResult()).isEqualTo("sunny");
    }

    @Test
    void should_mark_failed_when_tool_not_found() {
        TaskExecutor executor = new TaskExecutor(
                new ToolCallback[]{weatherCallback}, 1L, null, chatClient, 5000, tracer, promptService);

        SubTask task = SubTask.builder().id("1").type(TaskType.TOOL_CALL)
                .toolName("nonExistent").status(SubTaskStatus.PENDING).build();
        TaskQueue queue = new TaskQueue(List.of(task));
        executor.executeAll(queue);

        assertThat(task.getStatus()).as("未知工具应标记 FAILED").isEqualTo(SubTaskStatus.FAILED);
        assertThat(task.getResult().toString()).contains("未知工具");
    }

    @Test
    void should_execute_llm_reason_with_context() {
        TaskExecutor executor = new TaskExecutor(
                new ToolCallback[]{weatherCallback}, 1L, null, chatClient, 5000, tracer, promptService);

        SubTask toolTask = SubTask.builder().id("1").type(TaskType.TOOL_CALL)
                .toolName("queryWeather").description("查北京天气")
                .result("晴天").status(SubTaskStatus.COMPLETED).build();
        SubTask reasonTask = SubTask.builder().id("2").type(TaskType.LLM_REASON)
                .dependsOn(List.of("1")).description("生成结论")
                .status(SubTaskStatus.PENDING).build();
        TaskQueue queue = new TaskQueue(List.of(toolTask, reasonTask));
        executor.executeAll(queue);

        assertThat(reasonTask.getStatus()).as("LLM_REASON 应成功").isEqualTo(SubTaskStatus.COMPLETED);
        verify(chatClient).prompt();
    }

    @Test
    void should_include_failed_tools_in_llm_reason() {
        TaskExecutor executor = new TaskExecutor(
                new ToolCallback[]{weatherCallback}, 1L, null, chatClient, 5000, tracer, promptService);

        SubTask failedTask = SubTask.builder().id("1").type(TaskType.TOOL_CALL)
                .toolName("queryWeather").description("查天气")
                .status(SubTaskStatus.FAILED).result("API 超时").build();
        SubTask reasonTask = SubTask.builder().id("2").type(TaskType.LLM_REASON)
                .dependsOn(List.of("1")).description("生成结论")
                .status(SubTaskStatus.PENDING).build();
        TaskQueue queue = new TaskQueue(List.of(failedTask, reasonTask));
        executor.executeAll(queue);

        assertThat(reasonTask.getStatus()).as("即使有工具失败，LLM_REASON 也应执行").isEqualTo(SubTaskStatus.COMPLETED);
    }
}
