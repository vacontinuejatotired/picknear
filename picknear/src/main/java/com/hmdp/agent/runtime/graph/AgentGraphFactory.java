package com.hmdp.agent.runtime.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.prompt.Phase1PromptAssembler;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Alibaba Graph 最小工厂。
 *
 * <p>当前提供最小 Phase1：组装系统提示、历史消息和当前输入后调用 ChatModel。
 * 规划、工具执行、审批和 checkpoint 都在后续批次扩展。</p>
 */
@Component
public class AgentGraphFactory {

    public static final String OUTPUT = "output";
    private static final String SYSTEM_TEXT = "systemText";
    private static final String HISTORY = "history";

    private final CompiledGraph graph;

    public AgentGraphFactory(ChatModel chatModel,
                             Phase1PromptAssembler promptAssembler) {
        try {
            this.graph = buildGraph(chatModel, promptAssembler);
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
    public String invoke(AgentCommand command,
                         String systemText,
                         List<Message> history) throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put(OverAllState.DEFAULT_INPUT_KEY, command.content());
        input.put(SYSTEM_TEXT, systemText);
        input.put(HISTORY, history);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(command.conversationId())
                .build();

        Optional<OverAllState> result = graph.invoke(input, config);
        return result.flatMap(state -> state.value(OUTPUT, String.class))
                .orElse("");
    }

    private CompiledGraph buildGraph(ChatModel chatModel,
                                     Phase1PromptAssembler promptAssembler)
            throws GraphStateException {
        StateGraph graph = new StateGraph(() -> Map.of(
                OverAllState.DEFAULT_INPUT_KEY, new ReplaceStrategy(),
                SYSTEM_TEXT, new ReplaceStrategy(),
                HISTORY, new ReplaceStrategy(),
                OUTPUT, new ReplaceStrategy()
        ));

        graph.addNode("phase1", node_async(state -> {
            String input = state.value(OverAllState.DEFAULT_INPUT_KEY, String.class)
                    .orElse("");
            String systemText = state.value(SYSTEM_TEXT, String.class)
                    .orElse("");
            List<Message> history = castHistory(state.value(HISTORY).orElse(List.of()));

            Prompt prompt = promptAssembler.withCurrentUser(
                    promptAssembler.assembleBase(systemText, history),
                    input
            );
            ChatResponse response = chatModel.call(prompt);
            String output = response != null
                    && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText()
                    : "";
            return Map.of(OUTPUT, output != null ? output : "");
        }));
        graph.addEdge(START, "phase1");
        graph.addEdge("phase1", END);

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
