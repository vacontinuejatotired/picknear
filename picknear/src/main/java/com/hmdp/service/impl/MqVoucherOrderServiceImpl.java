package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.injector.methods.DeleteById;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.enums.SeckillOrderCode;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.constants.RabbitMqConstants;
import com.hmdp.utils.redis.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;


/**
 * 秒杀订单服务实现（MQ异步版）— Redis+Lua原子校验库存/重复 → RabbitMQ异步落库
 * 使用 @Primary 覆盖旧版同步实现
 */
@Service
@Slf4j
@Primary
public class MqVoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource(name = "seckillScript")
    private DefaultRedisScript<Long> seckillScript;
    private static final String LOCK_KET_PREFIX = "voucher:order:lock";
    private static final int MAX_RETRY = 3;

    @RabbitListener(queues = RabbitMqConstants.QUEUE_NAME)
    public void voucherOrderHandler(
            VoucherOrder voucherOrder,
            Message message,                                      // 新增：用于读取和设置 headers
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) throws IOException {

        // 从消息头中获取当前重试次数（第一次消费为 null）
        Integer retryCount = message.getMessageProperties()
                .getHeader("x-retry-count");
        if (retryCount == null) {
            retryCount = 0;
        }

        // 超过最大重试次数 → 直接进入死信队列
        if (retryCount >= MAX_RETRY) {
            log.warn("deliveryTag：{} 已超过最大重试次数 {}，放入死信队列", deliveryTag, MAX_RETRY);
            channel.basicAck(deliveryTag, false);
            rabbitTemplate.convertAndSend(
                    RabbitMqConstants.DEAD_EXCHANGE_NAME,
                    RabbitMqConstants.DEAD_ROUTING_KEY,
                    voucherOrder);
            return;
        }

        boolean success = false;
        try {
            success = seckillVoucherService.update()
                    .setSql("stock = stock -1")
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .gt("stock", 0)
                    .update();
        } catch (Exception e) {
            log.error("更新库存失败,订单id:{}", voucherOrder.getVoucherId(), e);
            // 系统异常：增加重试计数后重新入队
            rejectAndRequeueWithIncrement(channel, deliveryTag, message, retryCount + 1);
            return;
        }

        if (!success) {
            log.warn("库存不足，订单id:{}", voucherOrder.getId());
            // 业务失败也重试（根据您的要求）
            rejectAndRequeueWithIncrement(channel, deliveryTag, message, retryCount + 1);
            return;
        }

        // 成功：确认消息
        channel.basicAck(deliveryTag, false);
        log.info("订单{}扣减数据库库存成功", voucherOrder.getId());
    }

    /**
     * 拒绝消息并且重新入队，同时增加重试计数
     * @param channel
     * @param deliveryTag
     * @param message
     * @param nextRetryCount
     * @throws IOException
     */
    private void rejectAndRequeueWithIncrement(Channel channel, Long deliveryTag,
                                               Message message, int nextRetryCount) throws IOException {

        // 1. 获取原消息属性（Spring 类型）
        MessageProperties originalProps = message.getMessageProperties();

        // 2. 构建新的属性副本，并设置重试计数
        MessageProperties newProps = MessagePropertiesBuilder
                .fromClonedProperties(originalProps)
                .setHeader("x-retry-count", nextRetryCount)
                .build();

        // 3. 构建新消息
        Message newMessage = MessageBuilder
                .withBody(message.getBody())
                .andProperties(newProps)
                .build();

        // 4. 重新投递到当前队列（使用默认交换机 ""）
        Map<String, Object> headers = new HashMap<>(message.getMessageProperties().getHeaders());
        headers.put("x-retry-count", nextRetryCount);

        AMQP.BasicProperties nativeProps = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .deliveryMode(2)  // persistent
                .build();
        channel.basicPublish(
                "",                               // 默认交换机（direct to queue）
                RabbitMqConstants.QUEUE_NAME,     // 队列名作为 routing key
                false,                            // mandatory
                nativeProps,  // 注意：这里要转成 Map 或用原生方式
                newMessage.getBody()
        );

        // 5. 确认原消息（防止重复）
        channel.basicAck(deliveryTag, false);
    }

    @RabbitListener(queues = RabbitMqConstants.DEAD_QUEUE_NAME)
    public void deadQueueHandler(VoucherOrder voucherOrder, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) Long deliverTag) throws IOException {
        boolean success = false;
        log.warn("订单id：{}进入死信队列", voucherOrder.getId());
        channel.basicAck(deliverTag, false);
        log.info("order:{} has been down", voucherOrder.getId());
    }



    @Override
    public Result querySeckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUserId();
        Long orderId = redisIdWorker.getIdFromQueue();
        Long result = stringRedisTemplate.execute(seckillScript, Collections.emptyList(), voucherId.toString(), userId.toString(), orderId.toString());
        int r = result.intValue();
        //0代表才加入缓存，1代表库存不足，2代表重复下单
        //集群部署的redis，你这怎么查？
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //警示！！代理对象要变成全局使用的
        //TODO 集群下目前只会有一个会有代理对象，需要解决
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单号
        return Result.ok(orderId);
    }

    /**
     * 查询现有订单表是否存在用户已下的秒杀单
     * 创建订单,失败回滚
     * 最后在mq异步扣减库存
     * @param voucherOrder
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        int count = Math.toIntExact(query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherOrder.getVoucherId()).count());
        if (count > 0) {
            log.error("该用户已经购买过");
            return;
        }
        //发送到消息队列处理扣减库存
        rabbitTemplate.convertAndSend(RabbitMqConstants.NORMAL_EXCHANGE_NAME, RabbitMqConstants.NORMAL_ROUTING_KEY, voucherOrder);
    }

    @Override
    public Result saveOrder(Long voucherId) {
        long startTime = System.currentTimeMillis();
        Long userId = UserHolder.getUserId();
        // 1. 生成订单ID
        long orderIdGenStart = System.currentTimeMillis();
        Long orderId = redisIdWorker.getIdFromQueue();
        // 2. 执行Lua脚本扣减库存
        Long luaResult = executeSeckillLua(voucherId, userId, orderId);
        if (!luaResult .equals( SeckillOrderCode.SUCCESS.getCode())) {
            log.info("【Lua脚本执行】耗时: {} ms", System.currentTimeMillis() - startTime);
            return Result.fail(SeckillOrderCode.getDefaultMessage(luaResult));
        }

        // 3. 构建订单对象
        long buildOrderStart = System.currentTimeMillis();
        VoucherOrder voucherOrder = buildVoucherOrder(userId, voucherId, orderId);
        // 4. 保存订单到数据库（耗时操作）
        boolean saved = saveOrderToDatabase(voucherOrder);
        if (!saved) {
            log.info("【数据库操作】耗时: {} ms", System.currentTimeMillis() - startTime);
            return Result.fail("订单创建失败，请稍后重试");
        }

        // 5. 发送MQ消息（异步操作）
        sendMqMessage(voucherOrder);

        long totalTime = System.currentTimeMillis() - startTime;
        // 耗时告警
        if (totalTime > 500) {
            log.warn("【性能告警】saveOrder处理时间过长: {} ms, 用户: {}, 优惠券: {}",
                    totalTime, userId, voucherId);
        }

        return Result.ok(orderId);
    }

    /**
     *恢复MySQl库存至指定值
     * 并且删除MYSQL中相关订单信息
     * @param voucherId
     * @param stock
     */
    @Override
    public void deleteVoucherOrders(Long voucherId,Long stock) {
        seckillVoucherService.update().eq("voucher_id", voucherId).set("stock",stock).update();
        LambdaQueryWrapper<VoucherOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VoucherOrder::getVoucherId, voucherId);
        boolean removed = this.remove(wrapper);
        log.info("删除{}", removed);

    }

    /**
     * 执行秒杀Lua脚本
     */
    private Long executeSeckillLua(Long voucherId, Long userId, Long orderId) {
        long startTime = System.currentTimeMillis();

        List<String> args = Arrays.asList(
                String.valueOf(voucherId),
                String.valueOf(userId),
                String.valueOf(orderId)
        );

        try {
            Long result = stringRedisTemplate.execute(
                    seckillScript,
                    Collections.emptyList(),
                    args.toArray()
            );

            long costTime = System.currentTimeMillis() - startTime;
            // Lua脚本执行耗时告警
            if (costTime > 200) {
                log.warn("【性能告警】Lua脚本执行过慢: {} ms", costTime);
            }

            return result != null ? result : -1;
        } catch (Exception e) {
            log.error("【executeSeckillLua】执行异常", e);
            throw new RuntimeException("Lua脚本执行失败", e);
        }
    }

    /**
     * 构建订单对象
     */
    private VoucherOrder buildVoucherOrder(Long userId, Long voucherId, Long orderId) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setStatus(1);
        return voucherOrder;
    }

    /**
     * 保存订单到数据库（耗时操作）
     */
    private boolean saveOrderToDatabase(VoucherOrder voucherOrder) {
        long startTime = System.currentTimeMillis();

        try {
            boolean saved = save(voucherOrder);
            long costTime = System.currentTimeMillis() - startTime;
            // 数据库操作耗时告警
            if (costTime > 200) {
                log.warn("【性能告警】数据库保存过慢: {} ms, 订单ID: {}",
                        costTime, voucherOrder.getId());
            }

            return saved;
        } catch (Exception e) {
            long costTime = System.currentTimeMillis() - startTime;
            log.error("【saveOrderToDatabase】异常, 耗时: {} ms, 订单ID: {}",
                    costTime, voucherOrder.getId(), e);
            throw new RuntimeException("订单保存失败", e);
        }
    }


    /**
     * 发送MQ消息（异步操作）
     */
    @Override
    public void sendMqMessage(VoucherOrder voucherOrder) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConstants.NORMAL_EXCHANGE_NAME,
                    RabbitMqConstants.NORMAL_ROUTING_KEY,
                    voucherOrder
            );

        } catch (AmqpException e) {
            log.error("【sendMqMessage】发送失败,  订单ID: {}",
                     voucherOrder.getId(), e);
            throw new RuntimeException("异步更新库存失败", e);
        }
    }
}
