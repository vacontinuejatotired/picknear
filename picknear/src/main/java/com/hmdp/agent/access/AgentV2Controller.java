package com.hmdp.agent.access;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent V2 接入入口。
 *
 * <p>当前先暴露聊天发送接口；确认、拒绝等审批入口后续接入同一套接入层。</p>
 */
@RestController
@RequestMapping("/agent/v2")
@Tag(name = "Agent V2", description = "可替换运行时的 Agent 接入层")
public class AgentV2Controller {

    @Resource
    private AgentAccessService agentAccessService;

    @PostMapping("/string/send")
    @Operation(summary = "发送聊天消息（V2 SSE 流式）", description =
            "通过 AgentRuntime 接口委托运行时，当前默认使用 legacy 实现")
    public SseEmitter chat(
            @Parameter(description = "聊天内容") @RequestParam String content,
            @Parameter(description = "会话 ID（首次不传，后端自动生成并返回）")
            @RequestParam(required = false) String conversationId) {
        return agentAccessService.send(content, conversationId);
    }
}
