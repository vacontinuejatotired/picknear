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
 * 商铺实体 — 包含名称、位置（经纬度）、评分、营业时间等
 * distance 为非数据库字段，仅在按距离排序时填充
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "商铺ID")
    private Long id;

    /**
     * 商铺名称
     */
    @Schema(description = "商铺名称")
    private String name;

    /**
     * 商铺类型的id
     */
    @Schema(description = "商铺类型ID")
    private Long typeId;

    /**
     * 商铺图片，多个图片以','隔开
     */
    @Schema(description = "商铺图片")
    private String images;

    /**
     * 商圈，例如陆家嘴
     */
    @Schema(description = "商圈区域")
    private String area;

    /**
     * 地址
     */
    @Schema(description = "地址")
    private String address;

    /**
     * 经度
     */
    private Double x;

    /**
     * 维度
     */
    private Double y;

    /**
     * 均价，取整数
     */
    @Schema(description = "人均价格")
    private Long avgPrice;

    /**
     * 销量
     */
    @Schema(description = "销量")
    private Integer sold;

    /**
     * 评论数量
     */
    @Schema(description = "评论数")
    private Integer comments;

    /**
     * 评分，1~5分，乘10保存，避免小数
     */
    @Schema(description = "评分")
    private Integer score;

    /**
     * 营业时间，例如 10:00-22:00
     */
    @Schema(description = "营业时间")
    private String openHours;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


    @TableField(exist = false)
    @Schema(description = "距离（米）")
    private Double distance;
}
