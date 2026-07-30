package com.hmdp.utils.constants;

/**
 * RabbitMQ 常量 — 队列/交换器/路由键定义
 */
public class RabbitMqConstants {
    public static final String QUEUE_NAME = "voucher_order";
    public static final String DEAD_QUEUE_NAME = "dead_voucher_order";
    public static final String NORMAL_EXCHANGE_NAME = "voucher_order_exchange";
    public static final String DEAD_EXCHANGE_NAME = "dead_voucher_order_exchange";
    public static final String NORMAL_ROUTING_KEY = "order.normal";
    public static final String DEAD_ROUTING_KEY = "order.dead";
    public static final String ALTERNATE_EXCHANGE_NAME = "alternate_voucher_order_exchange" ;
    public static final String NORMAL_ORDER_PREFIX = "normal.order";
}
