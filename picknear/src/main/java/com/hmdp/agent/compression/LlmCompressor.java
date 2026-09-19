package com.hmdp.agent.compression;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 压缩策略（预留）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.compressor", havingValue = "llm")
public class LlmCompressor implements ToolResultCompressor {

    @Override
    public String compress(String raw, String toolName, int maxLength) {
        log.warn("LlmCompressor 尚未实现，降级为截断压缩");
        if (raw == null) return "";
        if (raw.length() <= maxLength) return raw;
        return raw.substring(0, maxLength) + "...";
    }
}
