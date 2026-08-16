package com.hmdp.voucher.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.voucher.entity.VoucherOrder;
import com.hmdp.voucher.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀订单 DB 服务 — 订单构建/落库/查重/删除
 * <p>
 * 无状态、无 MQ/Redis 依赖（纯 DB 操作）；MQ 依赖收敛在 {@code voucher.mq} 子包。
 * </p>
 */
@Slf4j
@Component
public class VoucherOrderService extends ServiceImpl<VoucherOrderMapper, VoucherOrder> {

    /**
     * 构建订单对象（订单 ID 由调用方传入，来自 RedisIdWorker）
     */
    public VoucherOrder buildOrder(Long userId, Long voucherId, Long orderId) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setStatus(1);
        return voucherOrder;
    }

    /**
     * 保存订单到数据库，含耗时告警
     *
     * @return true=保存成功
     */
    public boolean saveOrder(VoucherOrder voucherOrder) {
        long startTime = System.currentTimeMillis();
        try {
            boolean saved = save(voucherOrder);
            long costTime = System.currentTimeMillis() - startTime;
            if (costTime > 200) {
                log.warn("【性能告警】数据库保存过慢: {} ms, 订单ID: {}",
                        costTime, voucherOrder.getId());
            }
            return saved;
        } catch (Exception e) {
            long costTime = System.currentTimeMillis() - startTime;
            log.error("【saveOrder】异常, 耗时: {} ms, 订单ID: {}",
                    costTime, voucherOrder.getId(), e);
            throw new RuntimeException("订单保存失败", e);
        }
    }

    /**
     * 查询用户是否已对某优惠券下单（查重）
     */
    public boolean existsOrder(Long userId, Long voucherId) {
        return count(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId)) > 0;
    }

    /**
     * 查询某用户的订单列表（按下单时间倒序，供 agent 工具/业务查询使用）
     */
    public List<VoucherOrder> listByUserId(Long userId, int limit) {
        return list(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, userId)
                .orderByDesc(VoucherOrder::getCreateTime)
                .last("LIMIT " + limit));
    }

    /**
     * 删除某优惠券的全部订单（测试/运维用）
     */
    public boolean removeByVoucherId(Long voucherId) {
        return remove(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getVoucherId, voucherId));
    }
}
