package com.hmdp.agent.runtime.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.hmdp.agent.prompt.ConversationPromptComposer;
import com.hmdp.agent.runtime.graph.state.GraphStateKeys;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 当前轮的流式文本回复节点。
 */
@Component
public class RespondNode {

    private final ChatModel chatModel;

    public RespondNode(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String name() {
        return GraphNodeNames.RESPOND;
    }

    public AsyncNodeAction action() {
        return node_async(this::apply);
    }

    private Map<String, Object> apply(OverAllState state) {
        String input = state.value(OverAllState.DEFAULT_INPUT_KEY, String.class)
                .orElse("");
        String systemText = state.value(GraphStateKeys.SYSTEM_TEXT, String.class)
                .orElse("");
        List<Message> history = castHistory(
                state.value(GraphStateKeys.HISTORY).orElse(List.of()));

        Prompt prompt = ConversationPromptComposer.compose(
                systemText,
                history,
                input
        );
        return Map.of(GraphStateKeys.OUTPUT, chatModel.stream(prompt));
    }

    @SuppressWarnings("unchecked")
    private static List<Message> castHistory(Object history) {
        return history instanceof List<?> list
                ? (List<Message>) list
                : List.of();
    }
}
