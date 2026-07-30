package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.ITestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 测试/工具控制器 — 压测接口（空接口耗时、Token生成、秒杀重置、MQ批量下单等）
 */
@RestController
@RequestMapping("/test")
@Tag(name = "测试工具模块", description = "压测、性能测试、Token批量生成等工具接口")
public class TestCostTimeController {

    @Resource
    private ITestService testService;

    @GetMapping("/costTime")
    @Operation(summary = "测试接口耗时", description = "模拟接口耗时，用于AOP切面耗时统计测试")
    public String testCostTime() {
        long startTime = System.currentTimeMillis();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long endTime = System.currentTimeMillis();
        long costTime = endTime - startTime;
        return "操作耗时: " + costTime + " ms";
    }

    @GetMapping("/void")
    @Operation(summary = "void方法测试", description = "测试AOP统计void类型方法的耗时")
    public String testVoid() {
        return "void方法测试成功";
    }

    @PostMapping("/restart/{num}/{voucherId}")
    @Operation(summary = "秒杀库存重置", description = "重置秒杀券库存，用于压测前恢复数据")
    public Result testRestart(
            @Parameter(description = "库存数量") @PathVariable Long num,
            @Parameter(description = "秒杀券ID") @PathVariable Long voucherId) {
        return testService.restart(num, voucherId);
    }

    @PostMapping("/generateToken/{num}")
    @Operation(summary = "批量生成测试Token", description = "生成指定数量的测试Token并写入文件")
    public Result testGenerateTestToken(
            @Parameter(description = "生成数量") @PathVariable Long num,
            @Parameter(description = "输出文件名") @RequestParam String fileName) {
        return testService.generateTestToken(num, fileName);
    }

    @GetMapping("/checkSnowFlake/{num}")
    @Operation(summary = "测试雪花ID", description = "生成指定数量的雪花ID并检查是否重复")
    public Result testCheckSnowFlake(
            @Parameter(description = "生成数量") @PathVariable int num) {
        return testService.checkSnowFlake(num);
    }

    @GetMapping("/mq/order/save/{voucherId}/{orderNum}")
    @Operation(summary = "MQ批量下单", description = "使用消息队列批量创建秒杀订单用于压力测试")
    public Result testMq(
            @Parameter(description = "秒杀券ID") @PathVariable Long voucherId,
            @Parameter(description = "订单数量") @PathVariable Long orderNum) {
        return testService.testSaveOrder(voucherId, orderNum);
    }
}