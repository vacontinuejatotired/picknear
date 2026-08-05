package com.hmdp.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项 — 轻量 VO，不吐 Entity
 */
@Data
@Schema(description = "AI 会话列表项")
public class ConversationVO {

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "会话标题（首条用户消息截断）")
    private String title;

    @Schema(description = "最后活跃时间")
    private LocalDateTime updatedAt;

    @Schema(description = "消息条数")
    private Long messageCount;
}
