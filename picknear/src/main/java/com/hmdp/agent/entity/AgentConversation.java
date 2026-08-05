package com.hmdp.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 对话会话元数据 — 与 {@link AgentMessage} 一对多
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("agent_conversation")
public class AgentConversation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "全局唯一会话ID（UUID）")
    private String conversationId;

    @Schema(description = "所属用户ID")
    private Long userId;

    @Schema(description = "会话标题（首条用户消息截断）")
    private String title;

    @Schema(description = "0-活跃 1-归档 2-删除")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（每回合显式刷新，活跃排序依据）")
    private LocalDateTime updatedAt;
}
