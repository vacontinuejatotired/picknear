package com.hmdp.agent.prompt;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话 Prompt 组合器。
 *
 * <p>与 Agent 编排方式无关，只负责稳定地组装
 * {@code [System, ...history, currentUser]} 消息顺序。旧链路和新 Graph 都可以
 * 复用，避免把 Phase1 命名带入新运行时。</p>
 */
public final class ConversationPromptComposer {

    private ConversationPromptComposer() {
    }

    public static List<Message> composeBase(String systemText,
                                            List<Message> historyMessages) {
        List<Message> base = new ArrayList<>(historyMessages.size() + 1);
        base.add(new SystemMessage(systemText));
        base.addAll(historyMessages);
        return base;
    }

    public static Prompt compose(String systemText,
                                 List<Message> historyMessages,
                                 String currentUserContent) {
        List<Message> messages = new ArrayList<>(historyMessages.size() + 2);
        messages.addAll(composeBase(systemText, historyMessages));
        messages.add(new UserMessage(currentUserContent));
        return new Prompt(messages);
    }
}
