package com.hmdp.agent.runtime.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.prompt.ConversationPromptComposer;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Alibaba Graph 最小工厂。
 *
 * <p>当前提供最小 respond 节点：组装系统提示、历史消息和当前输入后调用
 * ChatModel，并以 Flux 形式交给 Graph 流式输出。
 * 规划、工具执行、审批和 checkpoint 都在后续批次扩展。</p>
 */
@Component
public class AgentGraphFactory {

    public static final String OUTPUT = "output";
    public static final String RESPOND_NODE = "respond";
    private static final String SYSTEM_TEXT = "systemText";
    private static final String HISTORY = "history";

    private final CompiledGraph graph;

    public AgentGraphFactory(ChatModel chatModel) {
        try {
            this.graph = buildGraph(chatModel);
        } catch (GraphStateException e) {
            throw new IllegalStateException("Agent Graph 初始化失败", e);
        }
    }

    public CompiledGraph graph() {
        return graph;
    }

    /**
     * 执行当前最小图，返回输出节点写入的状态。
     */
    public Flux<com.alibaba.cloud.ai.graph.NodeOutput> stream(
            AgentCommand command,
            String systemText,
            List<Message> history) {
        Map<String, Object> input = new HashMap<>();
        input.put(OverAllState.DEFAULT_INPUT_KEY, command.content());
        input.put(SYSTEM_TEXT, systemText);
        input.put(HISTORY, history);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(command.conversationId())
                .build();

        return graph.stream(input, config);
    }

    private CompiledGraph buildGraph(ChatModel chatModel)
            throws GraphStateException {
        StateGraph graph = new StateGraph(() -> Map.of(
                OverAllState.DEFAULT_INPUT_KEY, new ReplaceStrategy(),
                SYSTEM_TEXT, new ReplaceStrategy(),
                HISTORY, new ReplaceStrategy(),
                OUTPUT, new ReplaceStrategy()
        ));

        graph.addNode(RESPOND_NODE, node_async(state -> {
            String input = state.value(OverAllState.DEFAULT_INPUT_KEY, String.class)
                    .orElse("");
            String systemText = state.value(SYSTEM_TEXT, String.class)
                    .orElse("");
            List<Message> history = castHistory(state.value(HISTORY).orElse(List.of()));

            Prompt prompt = ConversationPromptComposer.compose(
                    systemText,
                    history,
                    input
            );
            return Map.of(OUTPUT, chatModel.stream(prompt));
        }));
        graph.addEdge(START, RESPOND_NODE);
        graph.addEdge(RESPOND_NODE, END);

        return graph.compile(CompileConfig.builder()
                .recursionLimit(10)
                .build());
    }

    @SuppressWarnings("unchecked")
    private static List<Message> castHistory(Object history) {
        return history instanceof List<?> list
                ? (List<Message>) list
                : List.of();
    }
}
