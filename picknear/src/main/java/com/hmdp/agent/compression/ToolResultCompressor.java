package com.hmdp.agent.compression;

/**
 * 工具结果压缩接口。
 */
public interface ToolResultCompressor {

    String compress(String raw, String toolName, int maxLength);
}
