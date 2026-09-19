package com.hmdp.agent.runtime.graph.definition;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        AgentGraphFactory factory = createFactory(chatModel);

        var outputs = factory.stream(
                new AgentCommand("你好", "conv-1", 1010L),
                "系统提示",
                List.of()
        ).toStream().toList();

        assertThat(outputs).isNotEmpty();
    }

    @Test
    void should_reach_reserved_execute_node_for_data_intent() {
        ChatModel chatModel = mock(ChatModel.class);
        AgentGraphFactory factory = createFactory(chatModel);

        Map<String, Object> input = new HashMap<>();
        input.put(OverAllState.DEFAULT_INPUT_KEY, "平台一共有多少家店");
        input.put(GraphStateKeys.SYSTEM_TEXT, "系统提示");
        input.put(GraphStateKeys.HISTORY, List.of());
        RunnableConfig config = RunnableConfig.builder().threadId("conv-plan").build();

        assertThatThrownBy(() -> factory.graph().stream(input, config).last().block())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ExecuteNode 尚未实现");
    }

    private static AgentGraphFactory createFactory(ChatModel chatModel) {
        GraphRuntimeProperties properties = new GraphRuntimeProperties();
        return new AgentGraphFactory(
                new RespondNode(chatModel, properties),
                new RouteNode(new DataIntentClassifier()),
                new PlanNode(properties),
                new ExecuteNode(),
                new VerifyNode(properties),
                new FinalizeNode(),
                properties
        );
    }

}
