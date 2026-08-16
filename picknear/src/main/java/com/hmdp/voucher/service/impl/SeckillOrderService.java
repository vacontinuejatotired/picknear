package com.hmdp.voucher.service.impl;

import com.hmdp.common.idempotent.Idempotent;
import com.hmdp.dto.Result;
import com.hmdp.enums.SeckillOrderCode;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redis.RedisIdWorker;
import com.hmdp.voucher.entity.VoucherOrder;
import com.hmdp.voucher.mq.VoucherOrderProducer;
import com.hmdp.voucher.order.VoucherOrderService;
import com.hmdp.voucher.service.IVoucherOrderService;
import com.hmdp.voucher.stock.SeckillStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

/**
 * 秒杀订单编排服务 — 秒杀下单主链路（Redis+Lua 扣减 → DB 落单 → afterCommit 发 MQ）
 * <p>
 * 纯编排门面，无私有基础设施逻辑：
 * <ul>
 *   <li>Redis/Lua 侧 → {@link SeckillStockService}</li>
 *   <li>DB 侧 → {@link VoucherOrderService}</li>
 *   <li>MQ 侧 → {@link VoucherOrderProducer}</li>
 * </ul>
 * 四合一拆分（P1）最终形态：原 MqVoucherOrderServiceImpl 删除，职责全部收敛至此编排 + 三个组件。
 * </p>
 */
@Slf4j
@Service
@Primary
public class SeckillOrderService implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private SeckillStockService seckillStockService;
    @Resource
    private VoucherOrderProducer voucherOrderProducer;
    @Resource
    private VoucherOrderService voucherOrderService;

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
     * 门面委托（D6）：查重 + 落单，事务内不再发 MQ（H-5 修复）
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        if (voucherOrderService.existsOrder(voucherOrder.getUserId(), voucherOrder.getVoucherId())) {
            log.error("该用户已经购买过");
            return;
        }
        voucherOrderService.saveOrder(voucherOrder);
    }

    /**
     * 秒杀下单主链路（HTTP 层幂等防重：@Idempotent 窗口防双击；
     * 内层 Redis Lua 已有"库存不足/重复下单"原子判断，两层互补）
     */
    @Idempotent(key = "#voucherId + ':' + T(com.hmdp.utils.UserHolder).getUserId()", ttl = 10)
    @Override
    public Result saveOrder(Long voucherId) {
        long startTime = System.currentTimeMillis();
        Long userId = UserHolder.getUserId();
        // 1. 生成订单ID
        Long orderId = redisIdWorker.getIdFromQueue();
        // 2. 执行Lua脚本扣减库存
        Long luaResult = seckillStockService.deductStock(voucherId, userId, orderId);
        if (!luaResult.equals(SeckillOrderCode.SUCCESS.getCode())) {
            log.info("【Lua脚本执行】耗时: {} ms", System.currentTimeMillis() - startTime);
            return Result.fail(SeckillOrderCode.getDefaultMessage(luaResult));
        }

        // 3. 构建订单对象 + 4. 保存订单到数据库
        VoucherOrder voucherOrder = voucherOrderService.buildOrder(userId, voucherId, orderId);
        boolean saved = voucherOrderService.saveOrder(voucherOrder);
        if (!saved) {
            log.info("【数据库操作】耗时: {} ms", System.currentTimeMillis() - startTime);
            return Result.fail("订单创建失败，请稍后重试");
        }

        // 5. 事务提交后发送MQ消息（H-5：不在事务内发外部副作用）
        voucherOrderProducer.sendAfterCommit(voucherOrder);

        long totalTime = System.currentTimeMillis() - startTime;
        // 耗时告警
        if (totalTime > 500) {
            log.warn("【性能告警】saveOrder处理时间过长: {} ms, 用户: {}, 优惠券: {}",
                    totalTime, userId, voucherId);
        }

        return Result.ok(orderId);
    }

    /**
     * 恢复 MySQL 库存至指定值，并删除相关订单信息（测试/运维用）
     */
    @Override
    public void deleteVoucherOrders(Long voucherId, Long stock) {
        seckillStockService.restoreDbStock(voucherId, stock);
        boolean removed = voucherOrderService.removeByVoucherId(voucherId);
        log.info("删除{}", removed);
    }

    /**
     * 发送MQ消息（供 /test/** 测试端点调用，无事务语义直发）
     */
    @Override
    public void sendMqMessage(VoucherOrder voucherOrder) {
        voucherOrderProducer.sendRaw(voucherOrder);
    }
}
