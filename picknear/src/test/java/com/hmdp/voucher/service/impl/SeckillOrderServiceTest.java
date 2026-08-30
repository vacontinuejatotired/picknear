package com.hmdp.voucher.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.enums.SeckillOrderCode;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redis.RedisIdWorker;
import com.hmdp.voucher.entity.VoucherOrder;
import com.hmdp.voucher.mq.VoucherOrderProducer;
import com.hmdp.voucher.order.VoucherOrderService;
import com.hmdp.voucher.stock.SeckillStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SeckillOrderService — 秒杀下单主链路测试（P1 拆分后编排门面）。
 * 覆盖：Lua 扣减成功→落单→afterCommit 发 MQ；Lua 拒绝；DB 落单失败。
 */
@ExtendWith(MockitoExtension.class)
class SeckillOrderServiceTest {

    @Mock
    private RedisIdWorker redisIdWorker;

    @Mock
    private SeckillStockService seckillStockService;

    @Mock
    private VoucherOrderProducer voucherOrderProducer;

    @Mock
    private VoucherOrderService voucherOrderService;

    private SeckillOrderService service;

    @BeforeEach
    void setUp() {
        service = new SeckillOrderService();
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "seckillStockService", seckillStockService);
        ReflectionTestUtils.setField(service, "voucherOrderProducer", voucherOrderProducer);
        ReflectionTestUtils.setField(service, "voucherOrderService", voucherOrderService);
        UserHolder.saveUserId(7L);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void saveOrder_should_deduct_then_save_then_send_after_commit() {
        when(redisIdWorker.getIdFromQueue()).thenReturn(555L);
        when(seckillStockService.deductStock(9L, 7L, 555L)).thenReturn(SeckillOrderCode.SUCCESS.getCode());
        VoucherOrder order = new VoucherOrder().setId(555L).setUserId(7L).setVoucherId(9L);
        when(voucherOrderService.buildOrder(7L, 9L, 555L)).thenReturn(order);
        when(voucherOrderService.saveOrder(order)).thenReturn(true);

        Result result = service.saveOrder(9L);

        assertThat(result.getSuccess()).as("主链路应成功").isTrue();
        assertThat(result.getData()).as("应返回订单号").isEqualTo(555L);
        // H-5：MQ 发送委托 producer（afterCommit 语义在其内部）
        verify(voucherOrderProducer).sendAfterCommit(order);
    }

    @Test
    void saveOrder_should_fail_when_lua_rejects() {
        when(redisIdWorker.getIdFromQueue()).thenReturn(555L);
        when(seckillStockService.deductStock(9L, 7L, 555L))
                .thenReturn(SeckillOrderCode.INSUFFICIENT_STOCK.getCode());

        Result result = service.saveOrder(9L);

        assertThat(result.getSuccess()).as("Lua 拒绝（库存不足）应失败").isFalse();
        verify(voucherOrderService, never()).saveOrder(any());
        verify(voucherOrderProducer, never()).sendAfterCommit(any());
    }

    @Test
    void saveOrder_should_fail_when_db_save_fails() {
        when(redisIdWorker.getIdFromQueue()).thenReturn(555L);
        when(seckillStockService.deductStock(9L, 7L, 555L)).thenReturn(SeckillOrderCode.SUCCESS.getCode());
        VoucherOrder order = new VoucherOrder().setId(555L).setUserId(7L).setVoucherId(9L);
        when(voucherOrderService.buildOrder(7L, 9L, 555L)).thenReturn(order);
        when(voucherOrderService.saveOrder(order)).thenReturn(false);

        Result result = service.saveOrder(9L);

        assertThat(result.getSuccess()).as("DB 落单失败应失败").isFalse();
        // 落单失败不发 MQ（避免消息存在但订单不存在）
        verify(voucherOrderProducer, never()).sendAfterCommit(any());
    }
}
