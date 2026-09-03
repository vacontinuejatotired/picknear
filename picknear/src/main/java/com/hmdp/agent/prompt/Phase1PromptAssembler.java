package com.hmdp.agent.prompt;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase1 对话 Prompt 组装器 — 只管消息顺序与意图，不接触流式/重试。
 * <p>
 * 解耦意图：把「[System] + 回放历史 + 当前 User」的拼装从流式重试循环里拆出，
 * 便于独立单测；重试时 base 固定、仅当前 User 随错误回喂变化。
 * </p>
 */
@Component
public class Phase1PromptAssembler {

    /**
     * 组装重试循环内固定不变的消息底座：[SystemMessage(systemText), ...historyMessages]。
     * 循环外构建一次，保证重试时历史固定。
     */
    public List<Message> assembleBase(String systemText, List<Message> historyMessages) {
        List<Message> base = new ArrayList<>(historyMessages.size() + 1);
        base.add(new SystemMessage(systemText));
        base.addAll(historyMessages);
        return base;
    }

    /**
     * 在底座后追加当前用户消息并生成 Prompt（每轮 attempt 用变化的 currentUserContent）。
     */
    public Prompt withCurrentUser(List<Message> baseMessages, String currentUserContent) {
        List<Message> messages = new ArrayList<>(baseMessages.size() + 1);
        messages.addAll(baseMessages);
        messages.add(new UserMessage(currentUserContent));
        return new Prompt(messages);
    }
}