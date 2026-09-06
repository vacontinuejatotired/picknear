package com.hmdp.agent.execution.loop.argument;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.agent.execution.loop.ToolResultStore;
import com.hmdp.agent.plan.executionPlan.binding.ToolParameterBinding;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把上游工具结果合并进下游工具调用参数。
 *
 * <p>策略选择方案 A：在调用 {@code ToolCallback.call(payload, ctx)} 前，把原始 LLM
 * arguments 与已绑定参数的上游结果合并成完整 JSON。Spring AI 1.1.2 的
 * {@code MethodToolCallback} 会把 JSON object 按形参名映射到方法参数，因此这里
 * 不依赖框架侧的“结果自动注入”能力。</p>
 */
@Component
public class ToolCallArgumentInjector {

    private final ToolResultStore toolResultStore;
    private final ObjectMapper objectMapper;

    public ToolCallArgumentInjector(ToolResultStore toolResultStore, ObjectMapper objectMapper) {
        this.toolResultStore = toolResultStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 合并参数并返回新的 payload。
     *
     * @param targetToolName 下游工具名
     * @param llmArguments LLM 原始参数 JSON
     * @param bindings 该工具已消歧的参数绑定
     * @return 可直接传给 ToolCallback.call 的 JSON
     */
    public String inject(
            String targetToolName,
            String llmArguments,
            List<ToolParameterBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return llmArguments;
        }

        String rawArguments = llmArguments == null || llmArguments.isBlank()
            ? "{}"
            : llmArguments;

        JsonNode argumentsNode;
        try {
            argumentsNode = objectMapper.readTree(rawArguments);
        } catch (JsonProcessingException e) {
            throw new ToolCallArgumentInjectionException(
                "工具 [" + targetToolName + "] 的 LLM 参数不是合法 JSON，无法执行依赖注入: "
                    + e.getOriginalMessage(),
                e);
        }

        if (!(argumentsNode instanceof ObjectNode arguments)) {
            throw new ToolCallArgumentInjectionException(
                "工具 [" + targetToolName + "] 的参数必须是 JSON object，实际类型为 "
                    + argumentsNode.getNodeType());
        }

        for (ToolParameterBinding binding : bindings) {
            JsonNode sourceValue = readSourceValue(targetToolName, binding);
            // 文本节点直接 set 会被二次 JSON 转义（如 "abc" → "\"abc\""）
            if (sourceValue != null && sourceValue.isTextual()) {
                arguments.put(binding.parameterName(), sourceValue.textValue());
            } else {
                arguments.set(binding.parameterName(), sourceValue);
            }
        }

        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            throw new ToolCallArgumentInjectionException(
                "工具 [" + targetToolName + "] 注入参数后序列化失败: " + e.getOriginalMessage(),
                e);
        }
    }

    private JsonNode readSourceValue(String targetToolName, ToolParameterBinding binding) {
        Object raw = toolResultStore.getRawResult(binding.sourceToolName());
        if (raw == null) {
            return NullNode.instance;
        }
        if (raw instanceof String rawJson) {
            try {
                return objectMapper.readTree(rawJson);
            } catch (JsonProcessingException e) {
                throw new ToolCallArgumentInjectionException(
                    "工具 [" + targetToolName + "] 参数 [" + binding.parameterName()
                        + "] 依赖来源 [" + binding.sourceToolName() + "] 的结果不是合法 JSON: "
                        + e.getOriginalMessage(),
                    e);
            }
        }
        if (raw instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        return objectMapper.valueToTree(raw);
    }
}
