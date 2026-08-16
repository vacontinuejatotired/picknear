package com.hmdp.voucher.mq.support;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * MQ 消费者模板方法 — 统一封装"重试计数 / 死信投递 / ack"三件套
 * <p>
 * 子类只需实现 {@link #doConsume} 表达业务处理：
 * <ul>
 *   <li>返回 {@code true} → 成功，直接 ack；</li>
 *   <li>返回 {@code false} → 业务终态失败（如库存不足），ack 不重试；</li>
 *   <li>抛异常 → 系统异常，按 {@code x-retry-count} 计数重试，超限进死信。</li>
 * </ul>
 * 对齐 agent 模块的模板方法风格（{@code AbstractToolLoop} 同类用法）。
 * </p>
 */
@Slf4j
public abstract class AbstractMqConsumer<T> {

    /** 消息头中携带的重试次数键 */
    private static final String RETRY_COUNT_HEADER = "x-retry-count";
    private static final int MAX_RETRY = 3;

    protected final RabbitTemplate rabbitTemplate;

    protected AbstractMqConsumer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 消费入口（@RabbitListener 注解由子类方法携带并调用本方法）。
     *
     * @param payload     反序列化后的消息体
     * @param message     Spring AMQP 消息
     * @param channel     RabbitMQ Channel
     * @param deliveryTag 投递标签
     * @throws IOException 重试/死信投递失败时抛出
     */
    protected void handle(T payload, Message message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) throws IOException {
        Integer retryCount = message.getMessageProperties().getHeader(RETRY_COUNT_HEADER);
        if (retryCount == null) {
            retryCount = 0;
        }

        // 超过最大重试次数 → 直接进死信队列
        if (retryCount >= MAX_RETRY) {
            log.warn("deliveryTag：{} 已超过最大重试次数 {}，放入死信队列", deliveryTag, MAX_RETRY);
            channel.basicAck(deliveryTag, false);
            rabbitTemplate.convertAndSend(
                    getDeadExchange(),
                    getDeadRoutingKey(),
                    payload);
            return;
        }

        try {
            boolean success = doConsume(payload, message, channel, deliveryTag);
            if (success) {
                // 成功：确认消息
                channel.basicAck(deliveryTag, false);
            } else {
                // 业务终态失败（如库存不足）：ack 不重试，避免死循环
                log.warn("业务终态失败，ack 不重试 deliveryTag={}, payload={}", deliveryTag, payload);
                channel.basicAck(deliveryTag, false);
            }
        } catch (Exception e) {
            log.error("消费异常，重试入队 deliveryTag={}, retryCount={}", deliveryTag, retryCount, e);
            rejectAndRequeueWithIncrement(channel, deliveryTag, message, retryCount + 1);
        }
    }

    /**
     * 拒绝消息并重新入队，同时增加重试计数。
     */
    private void rejectAndRequeueWithIncrement(Channel channel, Long deliveryTag,
                                               Message message, int nextRetryCount) throws IOException {
        // 1. 获取原消息属性（Spring 类型）
        MessageProperties originalProps = message.getMessageProperties();

        // 2. 构建新的属性副本，并设置重试计数
        MessageProperties newProps = MessagePropertiesBuilder
                .fromClonedProperties(originalProps)
                .setHeader(RETRY_COUNT_HEADER, nextRetryCount)
                .build();

        // 3. 构建新消息
        Message newMessage = MessageBuilder
                .withBody(message.getBody())
                .andProperties(newProps)
                .build();

        // 4. 重新投递到当前队列（使用默认交换机 ""）
        Map<String, Object> headers = new HashMap<>(message.getMessageProperties().getHeaders());
        headers.put(RETRY_COUNT_HEADER, nextRetryCount);

        com.rabbitmq.client.AMQP.BasicProperties nativeProps = new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                .headers(headers)
                .deliveryMode(2)  // persistent
                .build();
        channel.basicPublish(
                "",                               // 默认交换机（direct to queue）
                getQueueName(),                   // 队列名作为 routing key
                false,                            // mandatory
                nativeProps,
                newMessage.getBody()
        );

        // 5. 确认原消息（防止重复）
        channel.basicAck(deliveryTag, false);
    }

    /** 消费队列名（routing key = 队列名，默认交换机投递） */
    protected abstract String getQueueName();

    /** 死信交换机 */
    protected abstract String getDeadExchange();

    /** 死信路由键 */
    protected abstract String getDeadRoutingKey();

    /**
     * 业务处理。
     *
     * @return true=成功；false=业务终态失败（ack 不重试）；抛异常=系统异常（重试）
     */
    protected abstract boolean doConsume(T payload, Message message, Channel channel, Long deliveryTag)
            throws Exception;
}
