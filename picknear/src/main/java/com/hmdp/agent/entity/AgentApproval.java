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
 * 工具调用审批记录 — CONFIRM 真暂停的持久化载体。
 * <p>
 * 守卫投出 CONFIRM 时创建一条 pending 记录；用户确认/拒绝/超时分别流转到
 * approved / rejected / expired。审批通过后 resume 执行工具并记 {@code executedAt}，
 * 防止重复执行。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("agent_approval")
public class AgentApproval implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_EXPIRED = "expired";

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "全局唯一确认ID（cfm_xxx）")
    private String confirmId;

    @Schema(description = "所属会话ID")
    private String conversationId;

    @Schema(description = "需要确认的用户ID")
    private Long userId;

    @Schema(description = "待确认的工具名")
    private String toolName;

    @Schema(description = "工具参数（JSON）")
    private String toolArguments;

    @Schema(description = "pending/approved/rejected/expired")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "确认/拒绝时间")
    private LocalDateTime decidedAt;

    @Schema(description = "过期时间（创建后 TTL 秒）")
    private LocalDateTime expiredAt;

    @Schema(description = "原始用户输入（resume 用）")
    private String originalInput;

    @Schema(description = "暂停时已收集的回复（resume 用）")
    private String partialResponse;

    @Schema(description = "已完成工具名列表（JSON 数组，resume 用）")
    private String completedTools;

    @Schema(description = "暂停轮次（resume 用）")
    private Integer round;

    @Schema(description = "审批通过后实际执行时间（防重复执行）")
    private LocalDateTime executedAt;
}
