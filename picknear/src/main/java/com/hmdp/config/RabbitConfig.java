package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.voucher.entity.VoucherOrder;
import com.hmdp.utils.constants.RabbitMqConstants;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置 — 正常队列/死信队列/备用交换器声明，可靠投递回调（C 组卫生清理，2026-08）
 * <p>
 * 清理：缩进修正、删除注释死代码（connectionFactory/myRabbitTemplate）、
 * messageConverter 注入复用（消除重复创建）、未用 Map 删除、bean 命名规范化。
 * </p>
 */
@Configuration
public class RabbitConfig {

    @Bean
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        // 复用 Spring 统一管理的 ObjectMapper（含 JavaTimeModule 等配置）
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        // 设置类型映射（重要！）：消息头类型信息 → Java 类型
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(DefaultJackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
        Map<String, Class<?>> idClassMapping = new HashMap<>();
        idClassMapping.put("voucherOrder", VoucherOrder.class);
        typeMapper.setIdClassMapping(idClassMapping);
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);  // 不可路由时触发 ReturnCallback
        return template;
    }

    @Bean
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }

    /** 备用交换器（不可路由消息落点；无队列绑定则丢弃） */
    @Bean
    public Exchange alternateExchange() {
        return ExchangeBuilder.fanoutExchange(RabbitMqConstants.ALTERNATE_EXCHANGE_NAME).durable(true).build();
    }

    @Bean
    public Exchange deadExchange() {
        return ExchangeBuilder.topicExchange(RabbitMqConstants.DEAD_EXCHANGE_NAME).durable(true).build();
    }

    @Bean
    public Exchange normalExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("alternate-exchange", RabbitMqConstants.ALTERNATE_EXCHANGE_NAME);
        return new TopicExchange(RabbitMqConstants.NORMAL_EXCHANGE_NAME, true, false, args);
    }

    /**
     * 正常队列：死信转发 + 消息 TTL + 长度上限（x-queue-type 实际为 classic，非仲裁）
     */
    @Bean
    public Queue voucherOrderQueue() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-dead-letter-exchange", RabbitMqConstants.DEAD_EXCHANGE_NAME);
        arguments.put("x-dead-letter-routing-key", RabbitMqConstants.DEAD_ROUTING_KEY);
        arguments.put("x-message-ttl", 60000);
        arguments.put("x-max-length", 100000);
        arguments.put("x-queue-type", "classic");
        return QueueBuilder.durable(RabbitMqConstants.QUEUE_NAME).withArguments(arguments).build();
    }

    /** 死信队列：TTL + 长度上限 + 溢出丢弃头部 + 投递上限（防毒消息无限循环） */
    @Bean
    public Queue deadQueue() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-message-ttl", 60000);
        arguments.put("x-max-length", 10000);
        arguments.put("x-queue-type", "classic");
        arguments.put("x-overflow", "drop-head");
        arguments.put("x-delivery-limit", 20);
        return QueueBuilder.durable(RabbitMqConstants.DEAD_QUEUE_NAME).build();
    }

    @Bean
    public Binding voucherOrderBinding() {
        return BindingBuilder
                .bind(voucherOrderQueue())
                .to(normalExchange())
                .with(RabbitMqConstants.NORMAL_ROUTING_KEY).noargs();
    }

    @Bean
    public Binding deadQueueBinding() {
        return BindingBuilder
                .bind(deadQueue())
                .to(deadExchange())
                .with(RabbitMqConstants.DEAD_ROUTING_KEY).noargs();
    }
}
