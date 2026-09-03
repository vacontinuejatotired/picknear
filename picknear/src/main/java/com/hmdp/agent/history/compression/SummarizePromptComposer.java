package com.hmdp.agent.history.compression;

import com.hmdp.agent.entity.AgentMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 摘要 Prompt 组装器 — 压缩 prompt / JSON schema 外置，摘要器不沾模板字符串。
 */
@Component
public class SummarizePromptComposer {

    public String system() {
        return "把以下对话轮次压缩成一段要点摘要。要求："
                + "必须保留全部关键数字、ID、价格、数量、百分比、日期、专名等事实，不得篡改数值，不得遗漏用户明确陈述的事实；"
                + "只输出 JSON（无任何其他文字）："
                + "{\"summary\":\"...\",\"keyData\":[\"关键事实点...\"],\"truncated\":false}。"
                + "summary 为 ≤ 段落长度的要点总结；keyData 列出必须保留的关键事实点（数字/日期/名称）原文；"
                + "若输入过长无法全部保留，置 truncated=true 并优先保留最新且最重要的事实。";
    }

    /** 拼接：已有运行摘要 + 分批轮次（user/assistant 交替，升序）。 */
    public String user(String currentSummary, List<AgentMessage> batch) {
        StringBuilder sb = new StringBuilder();
        if (currentSummary != null && !currentSummary.isBlank()) {
            sb.append("已有摘要：\n").append(currentSummary).append("\n\n");
        }
        sb.append("以下为需要并入摘要的新对话轮次：\n");
        for (AgentMessage m : batch) {
            sb.append("### ").append(m.getRole()).append(" ###\n")
                    .append(m.getContent() == null ? "" : m.getContent()).append('\n');
        }
        return sb.toString();
    }
}