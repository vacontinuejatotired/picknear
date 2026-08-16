package com.hmdp.voucher.service;

import com.hmdp.dto.Result;
import com.hmdp.voucher.entity.VoucherOrder;

/**
 * 秒杀订单服务接口 — 秒杀下单（Redis+Lua 原子校验 → DB 落单 → MQ 异步扣 DB 库存）
 * <p>
 * 编排门面：实现类只做职责编排（见 {@code SeckillOrderService}），
 * DB/Redis/MQ 三侧分别收敛在 voucher.order / voucher.stock / voucher.mq。
 * </p>
 */
public interface IVoucherOrderService {

    /** 秒杀状态查询（Lua 原子扣减 + 重复校验） */
    Result querySeckillVoucher(Long voucherId);

    /** 门面委托：查重 + 落单（保留兼容，无外部调用方） */
    void createVoucherOrder(VoucherOrder voucherOrder);

    /** 秒杀下单主链路 */
    Result saveOrder(Long voucherId);

    /** 恢复库存并删除订单（测试/运维用） */
    void deleteVoucherOrders(Long voucherId, Long stock);

    /** 直发 MQ（测试端点用） */
    void sendMqMessage(VoucherOrder voucherOrder);
}
