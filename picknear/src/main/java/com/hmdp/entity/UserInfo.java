package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户详细信息实体 — 城市、介绍、粉丝数、关注数、性别、生日、积分、会员等级
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user_info")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键，用户id
     */
    @TableId(value = "user_id", type = IdType.AUTO)
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * 昵称（从 tb_user.nick_name 迁移至此）
     */
    private String nickName = "";

    /**
     * 人物头像（从 tb_user.icon 迁移至此）
     */
    private String icon = "";

    /**
     * 城市名称
     */
    @Schema(description = "城市")
    private String city;

    /**
     * 个人介绍，不要超过128个字符
     */
    @Schema(description = "个人简介")
    private String introduce;

    /**
     * 粉丝数量
     */
    @Schema(description = "粉丝数量")
    private Integer fans;

    /**
     * 关注的人的数量
     */
    @Schema(description = "关注数量")
    private Integer followee;

    /**
     * 性别，0：男，1：女
     */
    @Schema(description = "性别（0-未知 1-男 2-女）")
    private Boolean gender;

    /**
     * 生日
     */
    @Schema(description = "生日")
    private LocalDate birthday;

    /**
     * 积分
     */
    @Schema(description = "积分")
    private Integer credits;

    /**
     * 会员级别，0~9级,0代表未开通会员
     */
    @Schema(description = "会员等级")
    private Boolean level;

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
