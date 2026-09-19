package com.hmdp.agent.runtime.graph;

import com.hmdp.agent.access.AgentCommand;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

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
        AgentGraphFactory factory = new AgentGraphFactory(chatModel);

        var outputs = factory.stream(
                new AgentCommand("你好", "conv-1", 1010L),
                "系统提示",
                List.of()
        ).toStream().toList();

        assertThat(outputs).isNotEmpty();
    }
}
