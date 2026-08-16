package com.hmdp.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 博客发布请求体 — 只收业务字段（P3-B1，H-1 修复）
 * <p>
 * 替代直接用 {@link com.hmdp.content.entity.Blog} 当请求体：
 * 杜绝前端注入 isLike/name/icon/liked/comments 等非库字段或计算字段。
 * </p>
 */
@Data
public class BlogFormDTO {

    @Schema(description = "关联商铺ID（可选）")
    private Long shopId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "内容")
    private String content;
}
