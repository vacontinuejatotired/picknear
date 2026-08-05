package com.hmdp.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息项 — 轻量 VO，不吐 Entity
 */
@Data
@Schema(description = "AI 会话消息项")
public class MessageVO {

    @Schema(description = "user / assistant")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "消息时间")
    private LocalDateTime createdAt;
}
