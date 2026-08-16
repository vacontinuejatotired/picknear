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
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 秒杀订单 MQ 消费者 — 扣减 DB 库存、死信兜底
 * <p>
 * 重试/死信/ack 逻辑继承 {@link AbstractMqConsumer} 模板：
 * 库存不足属业务终态 → ack 不重试（D5 已拍板）；系统异常 → 计数重试，超限进死信。
 * </p>
 */
@Slf4j
@Component
public class VoucherOrderConsumer extends AbstractMqConsumer<VoucherOrder> {

    private final ISeckillVoucherService seckillVoucherService;

    public VoucherOrderConsumer(RabbitTemplate rabbitTemplate,
                                ISeckillVoucherService seckillVoucherService) {
        super(rabbitTemplate);
        this.seckillVoucherService = seckillVoucherService;
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
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (success) {
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
