package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 探店笔记实体 — 用户发布的探店图文内容，含作者信息、点赞数、评论数
 * icon/name/isLike 为查询时动态填充的非数据库字段
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_blog")
public class Blog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "博客ID")
    private Long id;
    /**
     * 商户id
     */
    @Schema(description = "关联商铺ID")
    private Long shopId;
    /**
     * 用户id
     */
    @Schema(description = "发布用户ID")
    private Long userId;
    /**
     * 用户图标
     */
    @TableField(exist = false)
    private String icon;
    /**
     * 用户姓名
     */
    @TableField(exist = false)
    private String name;
    /**
     * 是否点赞过了
     */
    @TableField(exist = false)
    private Boolean isLike;

    /**
     * 标题
     */
    @Schema(description = "标题")
    private String title;

    /**
     * 探店的照片，最多9张，多张以","隔开
     */
    @Schema(description = "图片列表（逗号分隔）")
    private String images;

    /**
     * 探店的文字描述
     */
    @Schema(description = "内容")
    private String content;

    /**
     * 点赞数量
     */
    @Schema(description = "点赞数量")
    private Integer liked;

    /**
     * 评论数量
     */
    @Schema(description = "评论数量")
    private Integer comments;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;


}
