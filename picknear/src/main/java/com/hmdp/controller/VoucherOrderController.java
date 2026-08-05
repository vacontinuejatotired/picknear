package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * <p>
 * 秒杀订单控制器 — 秒杀下单（Redis+Lua+Mq异步落库）
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
@Slf4j
@Tag(name = "秒杀订单模块", description = "秒杀券下单接口")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    @Operation(summary = "查询秒杀券", description = "获取秒杀券详情")
    public Result seckillVoucher(
            @Parameter(description = "秒杀券ID") @PathVariable("id") Long voucherId) {
        return voucherOrderService.querySeckillVoucher(voucherId);
    }

    @PostMapping("seckill/saveOrder/{id}")
    @Operation(summary = "秒杀下单", description = "抢购秒杀券，使用Redis+Lua保证原子性")
    public Result saveSeckillVoucherOrder(
            @Parameter(description = "秒杀券ID") @PathVariable("id") Long voucherId) {

        Result result = voucherOrderService.saveOrder(voucherId);

        return result;
    }
}