package com.hmdp.dto;

import lombok.Data;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 滚动分页结果 — 用于 Feed流 滚动加载（基于 Redis ZSet）
 */
@Data
public class ScrollResult {
    @Schema(description = "数据列表")
    private List<?> list;
    @Schema(description = "最小时间戳（用于下次滚动查询）")
    private Long minTime;
    @Schema(description = "偏移量（用于下次滚动查询）")
    private Integer offset;
}
