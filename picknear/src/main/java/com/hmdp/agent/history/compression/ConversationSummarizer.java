package com.hmdp.agent.history.compression;

import com.hmdp.agent.entity.AgentMessage;

import java.util.List;

/**
 * 对话摘要器端口（策略接口，实现可替换：LLM / 规则 / 本地）。
 */
public interface ConversationSummarizer {

    /** 把一批消息并入运行摘要，返回新摘要与关键事实点。失败抛异常（调用方不推进游标、置 dirty）。 */
    SummaryResult summarize(String currentSummary, List<AgentMessage> batch);
}