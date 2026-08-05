package com.hmdp.agent.controller;

import com.hmdp.agent.service.AgentHistoryService;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史会话查询接口 — 会话列表 + 消息列表（归属校验）。
 * <p>
 * 挂在 /agent 下，由登录拦截器保护；未登录访问 401。
 * </p>
 */
@RestController
@RequestMapping("/agent")
@Tag(name = "聊天模块", description = "聊天功能接口")
public class HistoryController {

    @Resource
    private AgentHistoryService historyService;

    /** 当前用户会话列表 */
    @GetMapping("/conversations")
    @Operation(summary = "当前用户会话列表")
    public Result listConversations() {
        return Result.ok(historyService.listConversations(UserHolder.getUserId()));
    }

    /** 某会话消息列表（不属于当前用户时返回空列表） */
    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "会话消息列表")
    public Result listMessages(@PathVariable String conversationId) {
        return Result.ok(historyService.listMessages(UserHolder.getUserId(), conversationId));
    }
}
