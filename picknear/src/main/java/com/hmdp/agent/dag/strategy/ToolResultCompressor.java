package com.hmdp.agent.dag.strategy;

/**
 * 工具结果压缩接口
 * 
 * <p>定义工具执行结果的压缩逻辑，用于减少上下文长度。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
public interface ToolResultCompressor {
    
    /**
     * 压缩工具执行结果
     * 
     * @param raw      原始结果
     * @param toolName 工具名称（用于日志）
     * @param maxLength 最大长度
     * @return 压缩后的结果
     */
    String compress(String raw, String toolName, int maxLength);
}
