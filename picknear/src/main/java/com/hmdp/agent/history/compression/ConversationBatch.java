package com.hmdp.agent.history.compression;

import com.hmdp.agent.entity.AgentMessage;

import java.util.List;

/**
 * 一批待压消息值对象：{@code uptoId} 为批次末条消息 id（压缩成功后游标推进到此，期间新写入行 id 更大、留批，不重不漏）。
 */
public record ConversationBatch(long uptoId, List<AgentMessage> messages) {
}