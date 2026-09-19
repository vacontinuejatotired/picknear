package com.hmdp.agent.runtime.graph.node;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Graph 节点输出构造工具。
 */
final class GraphNodeOutputs {

    private GraphNodeOutputs() {
    }

    static Flux<ChatResponse> text(String content) {
        return Flux.just(new ChatResponse(List.of(
                new Generation(new AssistantMessage(content))
        )));
    }
}
