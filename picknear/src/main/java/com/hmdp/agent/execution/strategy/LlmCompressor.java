package com.hmdp.agent.execution.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 压缩策略（预留）
 *
 * <p>使用 LLM 生成摘要压缩结果。</p>
 * <p>TODO: 实现 LLM 压缩逻辑</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.compressor", havingValue = "llm")
public class LlmCompressor implements ToolResultCompressor {

    // TODO: 注入 ChatModel

    @Override
    public String compress(String raw, String toolName, int maxLength) {
        log.warn("LlmCompressor 尚未实现，降级为截断压缩");

        // 降级为截断
        if (raw == null) return "";
        if (raw.length() <= maxLength) return raw;
        return raw.substring(0, maxLength) + "...";
    }
}
