package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 优惠券 Mapper — MyBatis-Plus 基础 CRUD + 优惠券+秒杀券关联查询
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
