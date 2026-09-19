package com.hmdp.agent.runtime.graph;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.honesty.DataIntentClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        AgentGraphFactory factory = new AgentGraphFactory(
                chatModel,
                new DataIntentClassifier(),
                new GraphRuntimeProperties()
        );

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
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(
                new Generation(AssistantMessage.builder()
                        .content("我来查询")
                        .build())
        ))));
        AgentGraphFactory factory = new AgentGraphFactory(
                chatModel,
                new DataIntentClassifier(),
                new GraphRuntimeProperties()
        );

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
    }
}
