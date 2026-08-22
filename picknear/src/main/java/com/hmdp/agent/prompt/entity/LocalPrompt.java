package com.hmdp.agent.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地提示词备份 — Langfuse 不可用时的兜底存储
 */
@Data
@TableName("local_prompt")
public class LocalPrompt {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提示词键名（如 agent.tool.queryShop） */
    private String promptKey;

    /** 提示词内容 */
    private String content;

    /** 标签（如 production） */
    private String label;

    /** 来源（langfuse/manual） */
    private String source;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
