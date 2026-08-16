package com.hmdp.voucher.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.injector.methods.DeleteById;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.enums.SeckillOrderCode;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.constants.RabbitMqConstants;
import com.hmdp.utils.redis.RedisIdWorker;
import com.hmdp.voucher.entity.VoucherOrder;
import com.hmdp.voucher.mapper.VoucherOrderMapper;
import com.hmdp.voucher.service.ISeckillVoucherService;
import com.hmdp.voucher.service.IVoucherOrderService;
import com.hmdp.voucher.stock.SeckillStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
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
    private RedisIdWorker redisIdWorker;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private SeckillStockService seckillStockService;

    @Override
    public Result querySeckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUserId();
        Long orderId = redisIdWorker.getIdFromQueue();
        Long result = seckillStockService.deductStock(voucherId, userId, orderId);
        int r = result.intValue();
        //0代表才加入缓存，1代表库存不足，2代表重复下单
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
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
        Long luaResult = seckillStockService.deductStock(voucherId, userId, orderId);
        if (!luaResult.equals(SeckillOrderCode.SUCCESS.getCode())) {
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
    public void deleteVoucherOrders(Long voucherId, Long stock) {
        seckillStockService.restoreDbStock(voucherId, stock);
        LambdaQueryWrapper<VoucherOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VoucherOrder::getVoucherId, voucherId);
        boolean removed = this.remove(wrapper);
        log.info("删除{}", removed);
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
