package com.hmdp.agent.tool.impl;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.agent.annotation.ToolMeta;
import com.hmdp.dto.Result;
import com.hmdp.shop.entity.Shop;
import com.hmdp.shop.entity.ShopType;
import com.hmdp.shop.service.IShopService;
import com.hmdp.shop.service.IShopTypeService;
import com.hmdp.utils.constants.SystemConstants;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 店铺查询工具 — 店铺类型 / 按类型查店 / 店铺详情。
 * 供长任务串链：类型 → 店铺 → 该店优惠券（VoucherQueryTool）。
 */
@TargetTool(active = true)
@Slf4j
public class ShopQueryTool {

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    public record ShopTypeBrief(Long id, String name) {}

    public record ShopBrief(Long id, String name, String area, Long avgPrice, Integer score, Integer sold) {}

    public record ShopDetailBrief(Long id, String name, String area, String address, Long avgPrice,
                                  Integer score, Integer sold, String openHours) {}

    /**
     * 查询平台所有店铺类型（首页分类 Tab），作为「按类型查店铺」的前置步骤。
     */
    @Tool(description = """
            查询平台所有店铺类型（美食/酒店/影院等分类），和「有哪些店铺类型」「店铺分类」一起使用。
            返回类型ID和名称，可作为按类型查店铺的参数。
            """)
    @ToolMeta(keywords = {"店铺类型", "分类", "有哪些类型", "类型列表", "美食", "酒店", "影院"}, intents = {"shop"})
    public List<ShopTypeBrief> queryShopTypes() {
        Result result = shopTypeService.queryList();
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) return List.of();
        if (!(result.getData() instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(ShopType.class::isInstance)
                .map(ShopType.class::cast)
                .map(t -> new ShopTypeBrief(t.getId(), t.getName()))
                .toList();
    }

    /**
     * 按店铺类型查询店铺列表（按评分降序，前10条）。
     */
    @Tool(description = """
            按店铺类型查询店铺列表，和「查某类型的店铺」「有哪些美食店/酒店」「按分类找店」一起使用。
            返回该类型下前10家店（名称/商圈/人均/评分/销量）。类型ID来自 queryShopTypes。
            """)
    @ToolMeta(keywords = {"类型的店铺", "美食店", "酒店有哪些", "按类型", "找店", "店铺列表", "有哪些店"}, intents = {"shop"})
    public List<ShopBrief> queryShopsByType(
            @ToolParam(description = "店铺类型ID（来自 queryShopTypes 的 id）") Long typeId,
            @ToolParam(description = "页码，从1开始，可选，默认1") Integer current) {
        int page = current != null && current > 0 ? current : 1;
        Page<Shop> p = shopService.query().eq("type_id", typeId)
                .orderByDesc("score").page(new Page<>(page, SystemConstants.MAX_PAGE_SIZE));
        return p.getRecords().stream()
                .map(s -> new ShopBrief(s.getId(), s.getName(), s.getArea(), s.getAvgPrice(),
                        s.getScore(), s.getSold()))
                .toList();
    }

    /**
     * 查询单个店铺详细信息（走多级缓存）。
     */
    @Tool(description = """
            查询单个店铺的详细信息（名称/商圈/地址/人均/评分/销量/营业时间），和「这家店怎么样」「店铺详情」一起使用。
            返回店铺ID来自 queryShopsByType 或 queryShopsByName。
            """)
    @ToolMeta(keywords = {"店铺详情", "这家店", "店铺怎么样", "店怎么样", "店铺信息"}, intents = {"shop"})
    public ShopDetailBrief queryShopById(
            @ToolParam(description = "店铺ID") Long shopId) {
        Result result = shopService.queryById(shopId);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())
                || !(result.getData() instanceof Shop s)) {
            return null;
        }
        return new ShopDetailBrief(s.getId(), s.getName(), s.getArea(), s.getAddress(), s.getAvgPrice(),
                s.getScore(), s.getSold(), s.getOpenHours());
    }
}
