package com.hmdp.agent.runtime.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.hmdp.agent.prompt.ConversationPromptComposer;
import com.hmdp.agent.runtime.graph.config.GraphRuntimeProperties;
import com.hmdp.agent.runtime.graph.state.GraphStateKeys;
import com.hmdp.agent.runtime.graph.state.GraphStopReasons;
import com.hmdp.agent.tool.ToolBeanCollector;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Graph 自有工具执行节点。
 *
 * <p>每次只执行一个模型回合和一个工具批次；是否需要下一轮由
 * {@link VerifyNode} 决定。工具调用委托 Spring AI {@link ToolCallingManager}
 * 执行，不复用旧 DAG 或 ToolExecutionFacade。</p>
 */
@Component
public class ExecuteNode {

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final ToolBeanCollector toolBeanCollector;
    private final GraphRuntimeProperties properties;

    public ExecuteNode(ChatModel chatModel,
                       ToolCallingManager toolCallingManager,
                       ToolBeanCollector toolBeanCollector,
                       GraphRuntimeProperties properties) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolBeanCollector = toolBeanCollector;
        this.properties = properties;
    }

    public String name() {
        return GraphNodeNames.EXECUTE;
    }

    public AsyncNodeAction action() {
        return node_async(this::apply);
    }

    private Map<String, Object> apply(OverAllState state) {
        long deadline = state.value(
                GraphStateKeys.DEADLINE_EPOCH_MILLIS, Long.class).orElse(Long.MAX_VALUE);
        if (System.currentTimeMillis() >= deadline) {
            return budgetStop(
                    GraphStopReasons.TIMEOUT,
                    "本轮处理已超时，暂时无法继续处理。"
            );
        }

        int toolRounds = intValue(state, GraphStateKeys.TOOL_ROUNDS);
        if (toolRounds >= properties.getMaxToolRounds()) {
            return budgetStop(
                    GraphStopReasons.MAX_TOOL_ROUNDS,
                    "工具轮次已达到本轮上限，暂时无法继续处理。"
            );
        }

        int modelCallCount = intValue(state, GraphStateKeys.MODEL_CALL_COUNT);
        if (modelCallCount >= properties.getMaxModelCalls()) {
            return budgetStop(
                    GraphStopReasons.MAX_MODEL_CALLS,
                    "模型调用已达到本轮上限，暂时无法继续处理。"
            );
        }

        List<Message> messages = conversation(state);
        Prompt prompt = prompt(state, messages);
        ChatResponse response = chatModel.call(prompt);
        AssistantMessage assistant = response.getResult().getOutput();

        List<Message> nextMessages = new ArrayList<>(messages);
        nextMessages.add(assistant);

        int nextModelCallCount = modelCallCount + 1;
        if (!response.hasToolCalls()) {
            return Map.of(
                    GraphStateKeys.OUTPUT, Flux.just(response),
                    GraphStateKeys.MESSAGES, nextMessages,
                    GraphStateKeys.MODEL_CALL_COUNT, nextModelCallCount,
                    GraphStateKeys.PENDING_TOOL_CALLS, false,
                    GraphStateKeys.STOP_REASON, GraphStopReasons.COMPLETED
            );
        }

        int callsThisRound = assistant.getToolCalls().size();
        int currentToolCalls = intValue(state, GraphStateKeys.TOOL_CALL_COUNT);
        if (currentToolCalls + callsThisRound > properties.getMaxTotalToolCalls()) {
            return budgetStopWithMessages(
                    GraphStopReasons.MAX_TOTAL_TOOL_CALLS,
                    "工具调用已达到本轮上限，暂时无法继续处理。",
                    nextMessages,
                    nextModelCallCount
            );
        }

        ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);
        int nextToolRounds = toolRounds + 1;
        Map<String, Object> updates = new HashMap<>();
        updates.put(GraphStateKeys.MESSAGES, toolResult.conversationHistory());
        updates.put(GraphStateKeys.MODEL_CALL_COUNT, nextModelCallCount);
        updates.put(GraphStateKeys.TOOL_ROUNDS, nextToolRounds);
        updates.put(GraphStateKeys.TOOL_CALL_COUNT, currentToolCalls + callsThisRound);

        if (toolResult.returnDirect()) {
            updates.put(GraphStateKeys.OUTPUT, GraphNodeOutputs.text(
                    lastToolResponse(toolResult.conversationHistory())));
            updates.put(GraphStateKeys.PENDING_TOOL_CALLS, false);
            updates.put(GraphStateKeys.STOP_REASON, GraphStopReasons.COMPLETED);
            return updates;
        }

        updates.put(GraphStateKeys.PENDING_TOOL_CALLS, true);
        updates.put(GraphStateKeys.STOP_REASON, GraphStopReasons.TOOL_CONTINUE);
        return updates;
    }

    private Prompt prompt(OverAllState state, List<Message> messages) {
        Map<String, Object> toolContext = new HashMap<>();
        Long userId = state.value(GraphStateKeys.USER_ID, Long.class).orElse(null);
        String conversationId = state.value(
                GraphStateKeys.CONVERSATION_ID, String.class).orElse(null);
        if (userId != null) {
            toolContext.put("userId", userId);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            toolContext.put("conversationId", conversationId);
        }

        ToolCallback[] callbacks = toolBeanCollector.getToolCallbacks();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(List.of(callbacks))
                .internalToolExecutionEnabled(false)
                .toolContext(toolContext)
                .build();
        return new Prompt(messages, options);
    }

    private static List<Message> conversation(OverAllState state) {
        Object stored = state.value(GraphStateKeys.MESSAGES).orElse(null);
        if (stored instanceof List<?> list && !list.isEmpty()) {
            return castMessages(list);
        }
        String systemText = state.value(GraphStateKeys.SYSTEM_TEXT, String.class)
                .orElse("");
        String input = state.value(OverAllState.DEFAULT_INPUT_KEY, String.class)
                .orElse("");
        Object historyValue = state.value(GraphStateKeys.HISTORY).orElse(List.of());
        Prompt prompt = ConversationPromptComposer.compose(
                systemText,
                castMessages(historyValue),
                input
        );
        return prompt.getInstructions();
    }

    private static Map<String, Object> budgetStop(String reason, String message) {
        return Map.of(
                GraphStateKeys.OUTPUT, GraphNodeOutputs.text(message),
                GraphStateKeys.PENDING_TOOL_CALLS, false,
                GraphStateKeys.STOP_REASON, reason
        );
    }

    private static Map<String, Object> budgetStopWithMessages(
            String reason,
            String message,
            List<Message> messages,
            int modelCallCount) {
        return Map.of(
                GraphStateKeys.OUTPUT, GraphNodeOutputs.text(message),
                GraphStateKeys.MESSAGES, messages,
                GraphStateKeys.MODEL_CALL_COUNT, modelCallCount,
                GraphStateKeys.PENDING_TOOL_CALLS, false,
                GraphStateKeys.STOP_REASON, reason
        );
    }

    private static String lastToolResponse(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage toolResponse) {
                return toolResponse.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .filter(value -> value != null && !value.isBlank())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
            }
        }
        return "";
    }

    private static int intValue(OverAllState state, String key) {
        return state.value(key, Integer.class).orElse(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Message> castMessages(Object messages) {
        return messages instanceof List<?> list
                ? (List<Message>) list
                : List.of();
    }
}
