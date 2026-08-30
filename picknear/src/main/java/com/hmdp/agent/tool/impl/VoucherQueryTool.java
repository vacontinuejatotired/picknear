package com.hmdp.agent.tool.impl;

import java.util.List;

import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.agent.annotation.ToolMeta;
import com.hmdp.agent.plan.executionPlan.annotation.DependsOn;
import com.hmdp.dto.Result;
import com.hmdp.voucher.entity.Voucher;
import com.hmdp.voucher.entity.VoucherOrder;
import com.hmdp.voucher.order.VoucherOrderService;
import com.hmdp.voucher.service.IVoucherService;
import com.hmdp.utils.constants.SystemConstants;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 优惠券/订单查询工具 — 查店铺优惠券 / 查我的优惠券订单。
 * 供长任务串链：店铺 → 优惠券 → 我的订单。
 */
@TargetTool(active = true)
@Slf4j
public class VoucherQueryTool {

    @Resource
    private IVoucherService voucherService;

    @Resource
    private VoucherOrderService voucherOrderService;

    public record VoucherBrief(Long id, String title, String subTitle, Long payValue, Long actualValue,
                               Integer type, Integer stock) {}

    public record VoucherOrderBrief(Long id, Long voucherId, String statusLabel, String createTime) {}

    /**
     * 查询某店铺的可用优惠券（普通券 + 秒杀券，含库存）。
     */
    @Tool(description = """
            查询某家店铺的可用优惠券列表（含秒杀券库存），和「这家店有什么券」「优惠券」「领券/抢券」一起使用。
            返回券ID/标题/面值/实付/类型/库存。店铺ID来自 queryShopById 或 queryShopsByType。
            """)
    @ToolMeta(keywords = {"优惠券", "有什么券", "领券", "抢券", "券"}, intents = {"shop", "voucher"})
    @DependsOn(toolName = {"queryShopById", "queryShopsByType"})
    public List<VoucherBrief> queryVouchersByShop(
            @ToolParam(description = "店铺ID") Long shopId) {
        Result result = voucherService.queryVoucherOfShop(shopId);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) return List.of();
        if (!(result.getData() instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Voucher.class::isInstance)
                .map(Voucher.class::cast)
                .map(v -> new VoucherBrief(v.getId(), v.getTitle(), v.getSubTitle(),
                        v.getPayValue(), v.getActualValue(), v.getType(), v.getStock()))
                .toList();
    }

    /**
     * 查询当前用户自己的优惠券订单列表（按下单时间倒序，前10条）。
     */
    @Tool(description = """
            查询当前用户自己的优惠券订单列表，和「我的订单」「我买的券」「下单记录」一起使用。
            返回订单ID/券ID/订单状态/下单时间。只看当前登录用户自己的订单。
            """)
    @ToolMeta(keywords = {"我的订单", "我买的券", "下单记录", "订单"}, intents = {"voucher"})
    public List<VoucherOrderBrief> queryMyVoucherOrders(ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");
        log.info("queryMyVoucherOrders userId: {}", userId);
        List<VoucherOrder> orders = voucherOrderService.listByUserId(userId, SystemConstants.MAX_PAGE_SIZE);
        return orders.stream()
                .map(o -> new VoucherOrderBrief(o.getId(), o.getVoucherId(),
                        statusLabel(o.getStatus()), String.valueOf(o.getCreateTime())))
                .toList();
    }

    private static String statusLabel(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 1 -> "未支付";
            case 2 -> "已支付";
            case 3 -> "已核销";
            case 4 -> "已取消";
            case 5 -> "退款中";
            case 6 -> "已退款";
            default -> "未知(" + status + ")";
        };
    }
}
