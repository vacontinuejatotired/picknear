package com.hmdp.agent.access;

import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Agent 接入门面。
 *
 * <p>负责请求归一化和运行时委托，不承载规划、工具执行或状态管理。</p>
 */
@Slf4j
@Service
public class AgentAccessService {

    private final AgentRuntime runtime;

    public AgentAccessService(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * 发送一轮 Agent 请求并返回 SSE 连接。
     */
    public SseEmitter send(String content, String conversationId) {
        String resolvedConversationId = resolveConversationId(conversationId);
        AgentCommand command = new AgentCommand(
                content,
                resolvedConversationId,
                UserHolder.getUserId()
        );
        return runtime.run(command);
    }

    private String resolveConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            String generated = UUID.randomUUID().toString().replace("-", "");
            log.info("新建会话 [conversationId={}]", generated);
            return generated;
        }
        log.info("续传会话 [conversationId={}]", conversationId);
        return conversationId;
    }
}
