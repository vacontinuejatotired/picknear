package com.hmdp.voucher.mq;

import com.hmdp.utils.constants.RabbitMqConstants;
import com.hmdp.voucher.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;

/**
 * 秒杀订单 MQ 生产者 — 事务提交后发送（修复 H-5 事务内发消息）
 * <p>
 * <strong>afterCommit 语义</strong>：若当前线程处于事务中，注册
 * {@link TransactionSynchronization#afterCommit()} 在事务提交成功后发送；
 * 无事务环境（如 /test/** 端点）直接发送。
 * 避免"DB 已落单、MQ 发送失败 → Redis 已扣、DB 未扣"的不一致窗口。
 * </p>
 */
@Slf4j
@Component
public class VoucherOrderProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 事务提交后发送秒杀订单消息。
     *
     * @param voucherOrder 订单
     * @throws RuntimeException 发送失败（afterCommit 中抛出，业务方按需补偿/记录）
     */
    public void sendAfterCommit(VoucherOrder voucherOrder) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(voucherOrder);
                }
            });
        } else {
            doSend(voucherOrder);
        }
    }

    /**
     * 无事务语义直发（供 /test/** 测试端点使用，与现状 sendMqMessage 行为一致）
     */
    public void sendRaw(VoucherOrder voucherOrder) {
        doSend(voucherOrder);
    }

    private void doSend(VoucherOrder voucherOrder) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConstants.NORMAL_EXCHANGE_NAME,
                    RabbitMqConstants.NORMAL_ROUTING_KEY,
                    voucherOrder
            );
            log.debug("秒杀订单消息已发送, orderId={}", voucherOrder.getId());
        } catch (AmqpException e) {
            log.error("【发送MQ】失败, 订单ID: {}", voucherOrder.getId(), e);
            throw new RuntimeException("异步更新库存失败", e);
        }
    }
}
