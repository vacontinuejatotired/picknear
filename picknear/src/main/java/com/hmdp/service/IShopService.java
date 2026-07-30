package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;

import java.util.List;

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
 * 商铺服务接口 — 商铺CRUD、按类型/距离/名称查询、多级缓存管理
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result updateShop(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y, String sortBy);

    List<Shop> getHotShop(Double x, Double y, Integer typeId);
}
