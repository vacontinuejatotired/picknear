package com.hmdp.agent.dag.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 截断压缩策略（默认）
 * 
 * <p>简单截断超出长度的结果。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.compressor", havingValue = "truncate", matchIfMissing = true)
public class TruncateCompressor implements ToolResultCompressor {
    
    @Override
    public String compress(String raw, String toolName, int maxLength) {
        if (raw == null) return "";
        if (raw.length() <= maxLength) return raw;
        
        log.debug("工具 {} 结果截断: {} -> {} 字符", toolName, raw.length(), maxLength);
        return raw.substring(0, maxLength) + "...";
    }
}
