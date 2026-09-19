package com.hmdp.agent.runtime.graph.definition;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.honesty.DataIntentClassifier;
import com.hmdp.agent.runtime.graph.config.GraphRuntimeProperties;
import com.hmdp.agent.runtime.graph.node.ExecuteNode;
import com.hmdp.agent.runtime.graph.node.FinalizeNode;
import com.hmdp.agent.runtime.graph.node.PlanNode;
import com.hmdp.agent.runtime.graph.node.RespondNode;
import com.hmdp.agent.runtime.graph.node.RouteNode;
import com.hmdp.agent.runtime.graph.node.VerifyNode;
import com.hmdp.agent.runtime.graph.state.GraphStateKeys;
import com.hmdp.agent.runtime.graph.state.GraphStopReasons;
import com.hmdp.agent.tool.ToolBeanCollector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentGraphFactoryTest {

    @Test
    void should_stream_respond_graph() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(
                new Generation(AssistantMessage.builder()
                        .content("模型回复")
                        .build())
        ))));
        AgentGraphFactory factory = createFactory(chatModel);

        var outputs = factory.stream(
                new AgentCommand("你好", "conv-1", 1010L),
                "系统提示",
                List.of()
        ).toStream().toList();

        assertThat(outputs).isNotEmpty();
    }

    @Test
    void should_route_data_intent_into_plan_budget() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("查询完成"));
        AgentGraphFactory factory = createFactory(chatModel);

        Map<String, Object> input = new HashMap<>();
        input.put(OverAllState.DEFAULT_INPUT_KEY, "平台一共有多少家店");
        input.put(GraphStateKeys.SYSTEM_TEXT, "系统提示");
        input.put(GraphStateKeys.HISTORY, List.of());
        RunnableConfig config = RunnableConfig.builder().threadId("conv-plan").build();

        NodeOutput last = factory.graph().stream(input, config).last().block();

        assertThat(last).isNotNull();
        assertThat(last.state().value(GraphStateKeys.NEEDS_PLANNING, Boolean.class))
                .contains(true);
        assertThat(last.state().value(GraphStateKeys.PLAN_ITERATIONS, Integer.class))
                .contains(1);
        assertThat(last.state().value(GraphStateKeys.MODEL_CALL_COUNT, Integer.class))
                .contains(1);
        assertThat(last.state().value(GraphStateKeys.STOP_REASON, String.class))
                .contains(GraphStopReasons.COMPLETED);
    }

    @Test
    void should_execute_one_tool_round_and_verify_loop() {
        ChatModel chatModel = mock(ChatModel.class);
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        ToolBeanCollector toolBeanCollector = mock(ToolBeanCollector.class);
        when(toolBeanCollector.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        ChatResponse toolCallResponse = responseWithToolCall();
        ChatResponse finalResponse = textResponse("工具结果已整理");
        when(chatModel.call(any(Prompt.class))).thenReturn(toolCallResponse, finalResponse);

        Message toolMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "queryTotalShops", "{\"total\":12}")))
                .build();
        ToolExecutionResult toolResult = mock(ToolExecutionResult.class);
        when(toolResult.conversationHistory()).thenReturn(List.of(toolMessage));
        when(toolResult.returnDirect()).thenReturn(false);
        when(toolCallingManager.executeToolCalls(any(Prompt.class), eq(toolCallResponse)))
                .thenReturn(toolResult);

        AgentGraphFactory factory = createFactory(
                chatModel,
                toolCallingManager,
                toolBeanCollector
        );

        Map<String, Object> input = new HashMap<>();
        input.put(OverAllState.DEFAULT_INPUT_KEY, "平台一共有多少家店");
        input.put(GraphStateKeys.SYSTEM_TEXT, "系统提示");
        input.put(GraphStateKeys.HISTORY, List.of());
        input.put(GraphStateKeys.USER_ID, 1010L);
        input.put(GraphStateKeys.CONVERSATION_ID, "conv-tool");
        input.put(GraphStateKeys.MESSAGES, List.of());
        input.put(GraphStateKeys.PLAN_ITERATIONS, 0);
        input.put(GraphStateKeys.TOOL_ROUNDS, 0);
        input.put(GraphStateKeys.TOOL_CALL_COUNT, 0);
        input.put(GraphStateKeys.MODEL_CALL_COUNT, 0);
        input.put(GraphStateKeys.PENDING_TOOL_CALLS, false);
        input.put(GraphStateKeys.DEADLINE_EPOCH_MILLIS, Long.MAX_VALUE);
        RunnableConfig config = RunnableConfig.builder().threadId("conv-tool").build();

        List<NodeOutput> outputs = factory.graph().stream(input, config).toStream().toList();
        NodeOutput last = outputs.get(outputs.size() - 1);

        assertThat(last).isNotNull();
        assertThat(outputs)
                .anyMatch(output -> output instanceof StreamingOutput<?> streaming
                        && "工具结果已整理".equals(streaming.chunk()));
        assertThat(last.state().value(GraphStateKeys.TOOL_ROUNDS, Integer.class))
                .contains(1);
        assertThat(last.state().value(GraphStateKeys.TOOL_CALL_COUNT, Integer.class))
                .contains(1);
        assertThat(last.state().value(GraphStateKeys.MODEL_CALL_COUNT, Integer.class))
                .contains(2);
        assertThat(last.state().value(GraphStateKeys.PENDING_TOOL_CALLS, Boolean.class))
                .contains(false);
        assertThat(last.state().value(GraphStateKeys.STOP_REASON, String.class))
                .contains(GraphStopReasons.COMPLETED);
    }

    @Test
    void should_stop_before_execute_when_tool_round_budget_is_zero() {
        ChatModel chatModel = mock(ChatModel.class);
        ToolBeanCollector toolBeanCollector = mock(ToolBeanCollector.class);
        when(toolBeanCollector.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        GraphRuntimeProperties properties = new GraphRuntimeProperties();
        properties.setMaxToolRounds(0);
        AgentGraphFactory factory = createFactory(
                chatModel,
                mock(ToolCallingManager.class),
                toolBeanCollector,
                properties
        );

        Map<String, Object> input = new HashMap<>();
        input.put(OverAllState.DEFAULT_INPUT_KEY, "平台一共有多少家店");
        input.put(GraphStateKeys.SYSTEM_TEXT, "系统提示");
        input.put(GraphStateKeys.HISTORY, List.of());
        input.put(GraphStateKeys.MESSAGES, List.of());
        input.put(GraphStateKeys.PLAN_ITERATIONS, 0);
        input.put(GraphStateKeys.TOOL_ROUNDS, 0);
        input.put(GraphStateKeys.TOOL_CALL_COUNT, 0);
        input.put(GraphStateKeys.MODEL_CALL_COUNT, 0);
        input.put(GraphStateKeys.PENDING_TOOL_CALLS, false);
        input.put(GraphStateKeys.DEADLINE_EPOCH_MILLIS, Long.MAX_VALUE);
        RunnableConfig config = RunnableConfig.builder().threadId("conv-budget").build();

        NodeOutput last = factory.graph().stream(input, config).last().block();

        assertThat(last).isNotNull();
        assertThat(last.state().value(GraphStateKeys.STOP_REASON, String.class))
                .contains(GraphStopReasons.MAX_TOOL_ROUNDS);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    private static AgentGraphFactory createFactory(ChatModel chatModel) {
        ToolBeanCollector toolBeanCollector = mock(ToolBeanCollector.class);
        when(toolBeanCollector.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        return createFactory(
                chatModel,
                mock(ToolCallingManager.class),
                toolBeanCollector
        );
    }

    private static AgentGraphFactory createFactory(
            ChatModel chatModel,
            ToolCallingManager toolCallingManager,
            ToolBeanCollector toolBeanCollector) {
        return createFactory(
                chatModel,
                toolCallingManager,
                toolBeanCollector,
                new GraphRuntimeProperties()
        );
    }

    private static AgentGraphFactory createFactory(
            ChatModel chatModel,
            ToolCallingManager toolCallingManager,
            ToolBeanCollector toolBeanCollector,
            GraphRuntimeProperties properties) {
        return new AgentGraphFactory(
                new RespondNode(chatModel, properties),
                new RouteNode(new DataIntentClassifier()),
                new PlanNode(properties),
                new ExecuteNode(
                        chatModel,
                        toolCallingManager,
                        toolBeanCollector,
                        properties
                ),
                new VerifyNode(properties),
                new FinalizeNode(),
                properties
        );
    }

    private static ChatResponse textResponse(String content) {
        return new ChatResponse(List.of(
                new Generation(AssistantMessage.builder()
                        .content(content)
                        .build())
        ));
    }

    private static ChatResponse responseWithToolCall() {
        return new ChatResponse(List.of(
                new Generation(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "queryTotalShops",
                                "{}"
                        )))
                        .build())
        ));
    }
}
