package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 优惠券控制器 — 普通券/秒杀券CRUD、按商铺查询优惠券列表
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher")
@Tag(name = "优惠券模块", description = "普通券、秒杀券查询与创建接口")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    @PostMapping
    @Operation(summary = "新增普通优惠券", description = "创建普通优惠券")
    public Result addVoucher(
            @Parameter(description = "优惠券信息") @RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    @PostMapping("seckill")
    @Operation(summary = "新增秒杀优惠券", description = "创建秒杀优惠券")
    public Result addSeckillVoucher(
            @Parameter(description = "秒杀券信息") @RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    @GetMapping("/list/{shopId}")
    @Operation(summary = "查询店铺优惠券", description = "查询指定店铺的所有优惠券列表")
    public Result queryVoucherOfShop(
            @Parameter(description = "店铺ID") @PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}