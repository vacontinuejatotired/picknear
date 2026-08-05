package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
/**
 * 秒杀订单服务接口 — 秒杀下单（Redis+Lua+Mq异步落库）
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result querySeckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherId);

    Result saveOrder(Long voucherId);

    void deleteVoucherOrders(Long voucherId,Long stock);

    void sendMqMessage(VoucherOrder voucherOrder);
}
