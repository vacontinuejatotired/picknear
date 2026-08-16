package com.hmdp.voucher.mq;

import com.hmdp.utils.constants.RabbitMqConstants;
import com.hmdp.voucher.entity.VoucherOrder;
import com.hmdp.voucher.mq.support.AbstractMqConsumer;
import com.hmdp.voucher.service.ISeckillVoucherService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单 MQ 消费者 — 扣减 DB 库存、死信兜底
 * <p>
 * 重试/死信/ack 逻辑继承 {@link AbstractMqConsumer} 模板：
 * 库存不足属业务终态 → ack 不重试（D5 已拍板）；系统异常 → 计数重试，超限进死信。
 * </p>
 * <p>
 * 消费幂等（S-1 收尾，2026-08）：Redis 标记 {@code seckill:order:processed:{orderId}}
 * 防消息重放重复扣减 DB 库存；与订单唯一索引（DB 层防重复建单）互补。
 * </p>
 */
@Slf4j
@Component
public class VoucherOrderConsumer extends AbstractMqConsumer<VoucherOrder> {

    /** 已处理标记 TTL：覆盖订单生命周期（下单→核销/退款） */
    private static final long PROCESSED_TTL_DAYS = 7;

    private final ISeckillVoucherService seckillVoucherService;
    private final StringRedisTemplate stringRedisTemplate;

    public VoucherOrderConsumer(RabbitTemplate rabbitTemplate,
                                ISeckillVoucherService seckillVoucherService,
                                StringRedisTemplate stringRedisTemplate) {
        super(rabbitTemplate);
        this.seckillVoucherService = seckillVoucherService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @RabbitListener(queues = RabbitMqConstants.QUEUE_NAME)
    public void onVoucherOrder(VoucherOrder voucherOrder,
                               Message message,
                               Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) throws IOException {
        handle(voucherOrder, message, channel, deliveryTag);
    }

    @RabbitListener(queues = RabbitMqConstants.DEAD_QUEUE_NAME)
    public void onDeadVoucherOrder(VoucherOrder voucherOrder,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) throws IOException {
        log.warn("订单id：{}进入死信队列", voucherOrder.getId());
        channel.basicAck(deliveryTag, false);
        log.info("order:{} has been down", voucherOrder.getId());
    }

    @Override
    protected boolean doConsume(VoucherOrder voucherOrder, Message message, Channel channel, Long deliveryTag) {
        // 幂等检查：已处理标记存在 → 重放消息直接 ack 跳过（防重复扣库存）
        String processedKey = "seckill:order:processed:" + voucherOrder.getId();
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(processedKey))) {
            log.info("订单 {} 已处理过，幂等跳过", voucherOrder.getId());
            return true;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (success) {
            // 扣减成功后置标记（TTL 覆盖订单生命周期）。
            // 极端窗口：标记设置失败（Redis 抖动）时重放可能重复扣减，
            // 由订单唯一索引（uk_user_voucher）+ 订单表存在性查询兜底。
            stringRedisTemplate.opsForValue().set(processedKey, "1", PROCESSED_TTL_DAYS, TimeUnit.DAYS);
            log.info("订单{}扣减数据库库存成功", voucherOrder.getId());
            return true;
        }
        log.warn("库存不足，订单id:{}", voucherOrder.getId());
        return false;  // 业务终态：ack 不重试
    }

    @Override
    protected String getQueueName() {
        return RabbitMqConstants.QUEUE_NAME;
    }

    @Override
    protected String getDeadExchange() {
        return RabbitMqConstants.DEAD_EXCHANGE_NAME;
    }

    @Override
    protected String getDeadRoutingKey() {
        return RabbitMqConstants.DEAD_ROUTING_KEY;
    }
}
