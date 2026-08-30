package com.hmdp.voucher.mq;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.voucher.entity.SeckillVoucher;
import com.hmdp.voucher.entity.VoucherOrder;
import com.hmdp.voucher.service.ISeckillVoucherService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VoucherOrderConsumer — 秒杀订单消费者测试（S-1 收尾后）。
 * 覆盖：幂等标记跳过重放、扣减成功置标记、库存不足终态不重试。
 */
@ExtendWith(MockitoExtension.class)
class VoucherOrderConsumerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private Message message;

    @Mock
    private Channel channel;

    private VoucherOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new VoucherOrderConsumer(rabbitTemplate, seckillVoucherService, stringRedisTemplate);
    }

    private VoucherOrder order() {
        return new VoucherOrder().setId(1001L).setVoucherId(9L).setUserId(7L);
    }

    @SuppressWarnings("unchecked")
    private UpdateChainWrapper<SeckillVoucher> mockStockUpdate(boolean success) {
        UpdateChainWrapper<SeckillVoucher> chain = mock(UpdateChainWrapper.class);
        when(seckillVoucherService.update()).thenReturn(chain);
        when(chain.setSql(anyString())).thenReturn(chain);
        when(chain.eq(anyString(), any())).thenReturn(chain);
        when(chain.gt(anyString(), any())).thenReturn(chain);
        when(chain.update()).thenReturn(success);
        return chain;
    }

    @Test
    void doConsume_should_skip_replayed_message_when_marked() throws Exception {
        // 已处理标记存在 → 重放直接 ack 跳过，不再扣库存
        when(stringRedisTemplate.hasKey("seckill:order:processed:1001")).thenReturn(true);

        boolean result = consumer.doConsume(order(), message, channel, 1L);

        assertThat(result).as("重放消息应视为成功跳过").isTrue();
        verify(seckillVoucherService, never()).update();
    }

    @Test
    void doConsume_should_mark_and_succeed_when_stock_deducted() throws Exception {
        when(stringRedisTemplate.hasKey("seckill:order:processed:1001")).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        mockStockUpdate(true);

        boolean result = consumer.doConsume(order(), message, channel, 1L);

        assertThat(result).as("扣减成功应返回 true").isTrue();
        verify(valueOps).set(eq("seckill:order:processed:1001"), eq("1"), eq(7L), eq(TimeUnit.DAYS));
    }

    @Test
    void doConsume_should_return_false_when_stock_insufficient() throws Exception {
        when(stringRedisTemplate.hasKey("seckill:order:processed:1001")).thenReturn(false);
        mockStockUpdate(false);

        boolean result = consumer.doConsume(order(), message, channel, 1L);

        assertThat(result).as("库存不足是业务终态，返回 false（ack 不重试）").isFalse();
        // 终态失败不置幂等标记（否则重放会误判已处理）
        verify(stringRedisTemplate, never()).opsForValue();
    }
}
